#!/usr/bin/env python3
"""Replay captured Qwen3 linear matmuls in isolated LUMIXED Verilator builds."""
from __future__ import annotations
import argparse
import datetime
import fcntl
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import time
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
CY = HERE.parents[3]
SRC = HERE.parent / "tests" / "src"
SHAPES = (
    ("Q", 1024, 2048, 28),
    ("K_V", 1024, 1024, 56),
    ("O", 2048, 1024, 28),
    ("GATE_UP", 1024, 3072, 56),
    ("DOWN", 3072, 1024, 28),
)
FORMULA = "28*C_Q + 56*C_K_V + 28*C_O + 56*C_GATE_UP + 28*C_DOWN"
STAGES = ("load_x", "generate_brams", "load_w_and_select", "store_o")
OPS = (("Query", "Q", 28), ("Key", "K_V", 28), ("Value", "K_V", 28),
       ("Attention output", "O", 28), ("MLP gate", "GATE_UP", 28),
       ("MLP up", "GATE_UP", 28), ("MLP down", "DOWN", 28))


def inside(path: Path) -> Path:
    path = path.resolve()
    if not path.is_relative_to(HERE):
        raise ValueError(f"Refusing write outside QWEN_VERIFY: {path}")
    return path


def save(path: Path, value):
    path = inside(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")


def sha(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def command(args, cwd, env, log):
    inside(log).parent.mkdir(parents=True, exist_ok=True)
    with inside(log).open("wb") as out:
        result = subprocess.run([str(a) for a in args], cwd=cwd, env=env,
                                stdout=out, stderr=subprocess.STDOUT)
    if result.returncode:
        tail = log.read_bytes()[-4000:].decode(errors="replace")
        raise RuntimeError(f"Command exit {result.returncode}; see {log}\n{tail}")


def environment(cache):
    cache = inside(cache)
    for name in ("tmp", "java-home", "xdg", "coursier"):
        (cache / name).mkdir(parents=True, exist_ok=True)
    conda = CY / ".conda-env"
    env = os.environ.copy()
    env.pop("USE_CHISEL7", None)
    env.update(
        PATH=f"{conda}/bin:{conda}/riscv-tools/bin:" + env.get("PATH", ""),
        RISCV=str(conda / "riscv-tools"),
        LD_LIBRARY_PATH=str(conda / "lib") + ":" + env.get("LD_LIBRARY_PATH", ""),
        TMPDIR=str(cache / "tmp"), TMP=str(cache / "tmp"), TEMP=str(cache / "tmp"),
        XDG_CACHE_HOME=str(cache / "xdg"), COURSIER_CACHE=str(cache / "coursier"),
        PYTHONDONTWRITEBYTECODE="1",
        JAVA_TOOL_OPTIONS=f"-Xmx3G -Xss8M -XX:ActiveProcessorCount=2 -Djava.io.tmpdir={cache}/tmp -Duser.home={cache}/java-home",
        MAKEFLAGS="-j1",
    )
    for name, suffix in (("CC", "gcc"), ("CXX", "c++")):
        compiler = conda / "bin" / f"x86_64-conda-linux-gnu-{suffix}"
        if compiler.exists():
            env[name] = str(compiler)
    return env


def capture_path(args, bits, k, n):
    return args.captures / f"Q{bits}_0" / f"R4_K{k}_N{n}"


def build_plan(args):
    cases, excluded = [], []
    for bits in args.weights:
        shapes = [x for x in SHAPES if x[0] in args.cases]
        if not args.include_head:
            head_source = capture_path(args, bits, 1024, 151936)
            if (head_source / "metadata.json").exists():
                head = json.loads((head_source / "metadata.json").read_text())
                excluded.append(dict(bits=bits, case="HEAD",
                    reason=head.get("reason", "Vocabulary head excluded from the default 196-projection estimate"),
                    native_weight_format=head["weight_format"]))
        if args.include_head:
            shapes += [("HEAD", 1024, 151936, 1)]
        for name, k, n, count in shapes:
            source = capture_path(args, bits, k, n)
            meta = json.loads((source / "metadata.json").read_text())
            if not meta.get("lumixed_compatible", (source / "weights.lumixed.sint.bin").exists()):
                excluded.append(dict(bits=bits, case=name, reason=meta.get("reason", "Unsupported actual tensor format"),
                                     native_weight_format=meta["weight_format"]))
                continue
            if meta["weight_bits"] != bits or meta["R"] < args.r or meta["group_size"] not in (32, 64, 128):
                raise ValueError(f"Capture does not match requested precision/R/group: {source}")
            if args.smoke:
                k, n = 32, 4
            cases.append(dict(bits=bits, case=name, R=args.r, K=k, N=n, occurrences=count,
                              group_size=meta["group_size"], source=str(source), metadata=meta))
    return dict(B=args.b, RF=args.rf, YS=args.ys, DMA_ID=args.dma_ids, R=args.r, FP_BITS=32,
                scale_dtype="FP16", group_mode="deferred", cases=cases, excluded=excluded,
                formula=FORMULA, smoke=args.smoke,
                estimate_scope="196 transformer weight projections; representative-cycle weighted estimate",
                vocabulary_head="Excluded by default; Q4_0/Q2_0 heads are Q6_K and cannot use the simple interface")


def compiler_jars():
    base = CY.parent / "cache" / "coursier"
    result = []
    for name in ("scala-compiler", "scala-library", "scala-reflect"):
        matches = list(base.glob(f"**/{name}/2.13.16/{name}-2.13.16.jar"))
        if len(matches) != 1:
            raise RuntimeError(f"Need the installed Scala 2.13.16 {name} jar")
        result.append(matches[0])
    return result


def simulator(args, plan, env):
    base_jar = CY / ".classpath_cache" / "chipyard.jar"
    tapeout = CY / ".classpath_cache" / "tapeout.jar"
    if not base_jar.exists() or not tapeout.exists():
        raise RuntimeError("Existing Chipyard assembly jars are required; this suite never runs SBT outside its folder")
    # The normal transformer build also serves the cropped smoke checks.
    maxk = max(3072, max(c["K"] for c in plan["cases"]))
    maxn = max(3072, max(c["N"] for c in plan["cases"]))
    params = dict(B=args.b, RF=args.rf, YS=args.ys, DMA_ID=args.dma_ids, R=max(args.r, args.ys),
                  K=maxk, N=maxn, base_jar_sha256=sha(base_jar),
                  rtl_sha256={p.name: sha(p) for p in sorted((CY / "generators/LUMIXED/src/main/scala").glob("*.scala"))})
    key = hashlib.sha256(json.dumps(params, sort_keys=True).encode()).hexdigest()[:12]
    build = inside(HERE / "build" / f"B{args.b}_RF{args.rf}_YS{args.ys}_ID{args.dma_ids}_{key}")
    build.mkdir(parents=True, exist_ok=True)
    sim = build / "sim" / "simulator-chipyard.harness-QwenVerifyRocketConfig"
    if (build / "build-passed.json").exists() and sim.exists():
        return sim, build
    save(build / "parameters.json", params)
    classes = build / "classes"
    classes.mkdir(exist_ok=True)
    scala = build / "QwenVerifyRocketConfig.scala"
    scala.write_text(f"""package qwenverify
import org.chipsalliance.cde.config.Config
class QwenVerifyRocketConfig extends Config(
  new Config((site, here, up) => {{
    case LUMIXED_PACKAGE.LUMIXEDParamsKey =>
      LUMIXED_PACKAGE.LUMIXED_Config.LUMIXED_Config.copy(
        Rin={params['R']}, Cin={maxk}, Cout={maxn},
        banks_per_row={args.b}, table_capacity_factor={args.rf}, parallel_rows={args.ys},
        FP_ACTIVATIONS=true, FP_BITS=32, WBitWidth=8, OutBitWidth=32,
        DMA_Bytes=8, Dma_Ids={args.dma_ids}, W_BUFFS=2,
        SCALE=false, BRAM_OUTPUTS=true, GROUP_SCALE_ALIGNED=false,
        GROUP_SCALE_DEFERRED=true, DEBUG=true, SKIP_ZERO_WEIGHTS=false,
        FLEXIBLE_BANK_GROUPING=false, WEIGHT_DMA_BURSTS=false,
        EARLY_WEIGHT_BUFFER_RELEASE=false)
  }}) ++ new chipyard.LUMIXEDRocketConfig)
""")
    java = shutil.which("java", path=env["PATH"])
    if not java:
        raise RuntimeError("Java not found")
    command([java, "-cp", ":".join(map(str, compiler_jars())), "scala.tools.nsc.Main",
             "-classpath", base_jar, "-d", classes, scala], build, env, build / "scala.log")
    jar = build / "generator.jar"
    shutil.copy2(base_jar, jar)
    with zipfile.ZipFile(jar, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        for p in classes.rglob("*.class"):
            archive.write(p, str(p.relative_to(classes)))
    dram_src = CY / "tools" / "DRAMSim2"
    dram = build / "dramsim"
    dram.mkdir(exist_ok=True)
    for p in dram_src.iterdir():
        if p.is_file() and (p.suffix in (".h", ".a", ".so") or ".so." in p.name):
            shutil.copy2(p, dram / p.name)
    if not (dram / "libdramsim.a").exists():
        raise RuntimeError("Prebuilt DRAMSim2 library is required; refusing an outside-folder build")
    sim.parent.mkdir(exist_ok=True)
    cmd = ["make", "-f", str(CY / "sims/verilator/Makefile"), "-j1",
           f"base_dir={CY}", f"sim_dir={sim.parent}",
           "CONFIG=QwenVerifyRocketConfig", "CONFIG_PACKAGE=qwenverify",
           f"GENERATOR_CLASSPATH={jar}", f"TAPEOUT_CLASSPATH={tapeout}",
           f"dramsim_dir={dram}", f"dramsim_lib={dram}/libdramsim.a",
           "SBT=/bin/false", "CHIPYARD_SCALA_SOURCES=", "CHIPYARD_VLOG_SOURCES=",
           "TAPEOUT_SCALA_SOURCES=", "TAPEOUT_VLOG_SOURCES=", "SCALA_BUILDTOOL_DEPS=",
           "SBT_SOURCES=", f"JAVA_TMP_DIR={env['TMPDIR']}",
           "-o", str(tapeout), "default"]
    save(build / "build-command.json", cmd)
    print(f"Building isolated simulator: {build}", flush=True)
    command(cmd, sim.parent, env, build / "build.log")
    if not sim.exists():
        raise RuntimeError("Build ended without simulator")
    save(build / "build-passed.json", dict(simulator_sha256=sha(sim)))
    return sim, build


_REFERENCE = None


def reference_module():
    global _REFERENCE
    if _REFERENCE is not None:
        return _REFERENCE
    spec = importlib.util.spec_from_file_location("qwen_exact_reference", SRC / "prepare_test_vectors.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    _REFERENCE = module
    return module


def prepare(case, directory):
    directory = inside(directory)
    directory.mkdir(parents=True, exist_ok=True)
    source = Path(case["source"])
    r, k, n, bits, group = (case[x] for x in ("R", "K", "N", "bits", "group_size"))
    meta = case["metadata"]
    oldk, oldn = meta["K"], meta["N"]
    xraw = (source / "activations.f32.bin").read_bytes()
    x = b"".join(xraw[i*oldk*4:i*oldk*4+k*4] for i in range(r))
    (directory / "input.bin").write_bytes(x)
    weights = bytearray()
    with (source / "weights.lumixed.sint.bin").open("rb") as stream:
        for col in range(n):
            stream.seek(col * oldk * bits // 8)
            weights += stream.read(k * bits // 8)
    if len(weights) != k*n*bits//8:
        raise ValueError("Packed weights have wrong length")
    (directory / "weights.bin").write_bytes(weights)
    rawsc = (source / "scales.fp16.bin").read_bytes()
    scales = b"".join(rawsc[g*oldn*2:g*oldn*2+n*2] for g in range((k+group-1)//group))
    (directory / "scales.bin").write_bytes(scales + b"\0" * (-len(scales) % 8))
    native = None
    if k == oldk:
        original = (source / "output.llamacpp.f32.bin").read_bytes()
        native = b"".join(original[i*oldn*4:i*oldn*4+n*4] for i in range(r))
        (directory / "output.llamacpp.f32.bin").write_bytes(native)
    ref = reference_module()
    acts = [[ref.fp_fixed(v[0], 32) for v in struct.iter_unpack("<I", x[i*k*4:(i+1)*k*4])] for i in range(r)]
    scale_bits = [v[0] for v in struct.iter_unpack("<H", scales)]
    expected = [0] * (r*n)
    per_byte, mask = 8//bits, (1 << bits)-1
    for col in range(n):
        totals = [0] * r
        row = weights[col*k*bits//8:(col+1)*k*bits//8]
        for g, begin in enumerate(range(0, k, group)):
            end = min(begin+group, k)
            qw = []
            for kk in range(begin, end):
                v = (row[kk//per_byte] >> ((kk%per_byte)*bits)) & mask
                qw.append(v-(1<<bits) if v & (1<<(bits-1)) else v)
            scale = ref.fp16_fixed24(scale_bits[g*n+col])
            for rr in range(r):
                totals[rr] += sum(acts[rr][begin+j]*v for j, v in enumerate(qw)) * scale
        for rr in range(r):
            expected[rr*n+col] = ref.fixed_to_fp32_bits(totals[rr], 173)
    raw = struct.pack("<" + "I"*len(expected), *expected)
    (directory / "output.exact.f32.bin").write_bytes(raw)
    save(directory / "case.json", case)
    return raw, native


def compile_case(case, directory, args, env):
    r, k, n, bits, group = (case[x] for x in ("R", "K", "N", "bits", "group_size"))
    text = (SRC / "Linear-sw.c").read_text()
    for name, value in dict(IN_BITS=32, W_BITS=bits, RIN_MAX=r, CIN_MAX=k, COUT_MAX=n,
                            XS=args.b, YS=args.ys, Mem_row_factor=args.rf).items():
        text, count = re.subn(r"^#define\s+"+name+r"\s+.*$", f"#define {name} {value}", text, flags=re.M)
        if count != 1:
            raise RuntimeError(f"Cannot set local C adapter macro {name}")
    (directory / "Linear-sw.snapshot.c").write_text(text)
    for name in ("compiler.h", "rocc.h"):
        shutil.copy2(SRC / name, directory / name)
    (directory / "lumixed_test_vectors.h").write_text(f"""#define LUMIXED_VECTOR_GROUP_SIZE {group}
extern const input_t lumixed_vectors_input[{r*k}];
extern const weight_t lumixed_vectors_weights[{k*n*bits//8}];
extern const output_t lumixed_vectors_reference[{r*n}];
extern const uint16_t lumixed_vectors_scales[{((k+group-1)//group*n+3)//4*4}];
""")
    assembly = [".section .rodata.qwen_vectors,\"a\",@progbits"]
    for label, name in (("input", "input.bin"), ("weights", "weights.bin"),
                        ("reference", "output.exact.f32.bin"), ("scales", "scales.bin")):
        assembly += [".balign 8", f".global lumixed_vectors_{label}",
                     f"lumixed_vectors_{label}:", f'.incbin "{name}"']
    assembly += ['.section .note.GNU-stack,"",@progbits']
    (directory / "vectors.S").write_text("\n".join(assembly)+"\n")
    harness = (HERE / "harness.c").read_text()
    (directory / "harness.c").write_text(harness)
    shutil.copy2(CY / "tests/atexit-workaround.c", directory / "atexit-workaround.c")
    gcc = Path(env["RISCV"]) / "bin/riscv64-unknown-elf-gcc"
    runtime = Path(env["RISCV"]) / "riscv64-unknown-elf/lib/rv64imac_zicsr_zifencei/lp64"
    if not runtime.exists():
        raise RuntimeError("Matching LP64 soft-float HTIF runtime is missing")
    cmd = [gcc, "-O2", "-march=rv64imac_zicsr_zifencei", "-mabi=lp64",
           "-ffunction-sections", "-fdata-sections", "-fno-builtin-printf",
           "-DLUMIXED_PREGENERATED_TEST=1", "-DLUMIXED_FP_ACTIVATIONS=1",
           "-DLUMIXED_FP_BITS=32", "-DLUMIXED_GROUP_SCALE_MODE=2",
           "-DLUMIXED_QUIET_TEST=0", "-specs=htif_nano.specs", f"-L{runtime}",
           "harness.c", "vectors.S", "atexit-workaround.c", "-static",
           "-Wl,--gc-sections", "-Wl,--wrap=atexit", "-o", "verify.riscv"]
    save(directory / "compile-command.json", list(map(str, cmd)))
    command(cmd, directory, env, directory / "compile.log")
    return directory / "verify.riscv"


def extract_payload(raw, count):
    marker = f"QWEN_VERIFY_BINARY {count*4}\n".encode()
    begin = raw.find(marker)
    if begin < 0:
        raise RuntimeError("No accelerator result payload in simulator output")
    start = begin+len(marker)
    payload = raw[start:start+count*4]
    if len(payload) != count*4 or raw[start+count*4:start+count*4+len(b"\nQWEN_VERIFY_END\n")] != b"\nQWEN_VERIFY_END\n":
        raise RuntimeError("Truncated or corrupted accelerator payload")
    counters = re.search(rb"QWEN_VERIFY_CYCLES total=(\d+) load_x=(\d+) generate_brams=(\d+) load_w=(\d+) select_accumulate=(\d+) store_o=(\d+) load_w_and_select=(\d+)", raw)
    if not counters or b"QWEN_VERIFY_GUARDS PASS" not in raw:
        raise RuntimeError("Missing counters or output guard failure")
    keys = ("total", "load_x", "generate_brams", "load_w", "select_accumulate", "store_o", "load_w_and_select")
    return payload, dict(zip(keys, map(int, counters.groups())))


def compare(got, expected, atol=0.0, rtol=0.0):
    if len(got) != len(expected):
        raise ValueError("Output lengths differ")
    bits = list(struct.iter_unpack("<I", got))
    refbits = list(struct.iter_unpack("<I", expected))
    values = list(struct.iter_unpack("<f", got))
    refs = list(struct.iter_unpack("<f", expected))
    mismatch = tolerated = nonfinite = 0
    max_abs = max_rel = square = 0.0
    max_ulp = 0
    for a, b, av, bv in zip(bits, refbits, values, refs):
        mismatch += a != b
        x, y = av[0], bv[0]
        if not math.isfinite(x) or not math.isfinite(y):
            nonfinite += 1
            continue
        diff = abs(x-y)
        max_abs = max(max_abs, diff)
        max_rel = max(max_rel, diff/max(abs(y), 1e-30))
        square += diff*diff
        tolerated += diff <= atol + rtol*abs(y)
        def ordered(u):
            return (~u & 0xffffffff) if u & 0x80000000 else u | 0x80000000
        max_ulp = max(max_ulp, abs(ordered(a[0])-ordered(b[0])))
    return dict(elements=len(bits), bit_mismatches=mismatch, bit_exact=mismatch==0,
                within_tolerance=tolerated==len(bits), atol=atol, rtol=rtol,
                max_abs_error=max_abs, max_relative_error=max_rel,
                rmse=math.sqrt(square/max(1, len(bits))), max_ulp_error=max_ulp,
                nonfinite_pairs=nonfinite)


def report(run, plan, records):
    estimates = []
    for bits in sorted({c["bits"] for c in plan["cases"]}, reverse=True):
        rows = {r["case"]: r for r in records if r["bits"] == bits and r.get("rtl_exact_pass")}
        complete = not plan["smoke"] and all(name in rows for name, *_ in SHAPES)
        entry = dict(bits=bits, complete=complete, formula=FORMULA,
                     projection_calls=196, cycles=None, operations=[])
        if complete:
            entry["cycles"] = sum(count*rows[name]["cycles"]["total"] for name, _, _, count in SHAPES)
            entry["stages"] = {stage: sum(count*rows[name]["cycles"][stage] for name, _, _, count in SHAPES) for stage in STAGES}
        if not plan["smoke"]:
            for title, name, count in OPS:
                if name not in rows:
                    continue
                c = rows[name]
                entry["operations"].append(dict(operation=title, occurrences=count, R=c["R"], K=c["K"], N=c["N"],
                                                cycles_per_matmul=c["cycles"]["total"],
                                                estimated_all_occurrences=count*c["cycles"]["total"]))
        head = rows.get("HEAD")
        entry["head_cycles"] = head["cycles"]["total"] if head else None
        entry["including_head_cycles"] = entry["cycles"]+entry["head_cycles"] if complete and head else None
        estimates.append(entry)
    result = dict(plan=plan, records=records, projection_estimates=estimates,
                  note="Measured controller-active cycles, weighted by projection counts. Not end-to-end model latency.")
    save(run / "report.json", result)
    lines = ["# QWEN_VERIFY", "", f"B={plan['B']}, RF={plan['RF']}, YS={plan['YS']}, DMA_ID={plan.get('DMA_ID',2)}, R={plan['R']}. FP32 activations / FP16 scales / deferred groups.",
             "", "## Measured matmuls", "",
             "| W | Case | R x K x N | G | Active cycles | RTL vs exact | RTL vs llama.cpp | Max abs error |",
             "|---:|---|---|---:|---:|---|---|---:|"]
    for row in records:
        native = row.get("native_comparison")
        lines.append(f"| {row['bits']} | {row['case']} | {row['R']} x {row['K']} x {row['N']} | {row['group_size']} | {row.get('cycles',{}).get('total','-')} | {'PASS' if row.get('rtl_exact_pass') else row.get('error','FAIL')} | {('BIT EXACT' if native['bit_exact'] else 'DIFFERENT') if native else 'not applicable (cropped smoke)'} | {native['max_abs_error'] if native else '-'} |")
    lines += ["", "## Estimated cycles across 28 layers", "", FORMULA, "",
              "Per-operation rows use passed full shapes. The 196-projection aggregate is withheld until all five shapes pass.",
              "", "| W | Operation | Shape R x K x N | Occurrences | Measured cycles per matmul | Estimated cycles for all occurrences |",
              "|---:|---|---|---:|---:|---:|"]
    for estimate in estimates:
        for op in estimate["operations"]:
            lines.append(f"| {estimate['bits']} | {op['operation']} | {op['R']} x {op['K']} x {op['N']} | {op['occurrences']} | {op['cycles_per_matmul']} | {op['estimated_all_occurrences']} |")
        lines += ["", f"W{estimate['bits']} estimated 196-projection total: {estimate['cycles'] if estimate['complete'] else 'WITHHELD: full suite incomplete'}.",
                  f"Vocabulary head: {estimate['head_cycles'] if estimate['head_cycles'] is not None else 'not measured / unsupported'}."]
    lines += ["", "## Limits", "",
              "Native outputs are unchanged captured llama.cpp outputs. A native difference is not silently accepted as an exact match.",
              "RTL vs exact uses arbitrary-precision fixed-point sums with one final FP32 RNE rounding.",
              "Estimated cycles reuse layer-0 shape costs for all layers and same-shape Q/K/V/gate/up operations.",
              "Total active cycles = load_x + generate_brams + load_w_and_select + store_o. The overlapped counters are not double-counted.",
              "Attention QK/AV, normalization, RoPE, softmax, SiLU, tokenization, CPU control overhead and build time are excluded.",
              "A complete model-total estimate is withheld unless the vocabulary head was also measured.",
              "Q4_0/Q2_0 heads are Q6_K and are not converted to signed W4/W2.", "",
              "Excluded cases: " + json.dumps(plan["excluded"])]
    inside(run / "report.md").write_text("\n".join(lines)+"\n")


def parse():
    argv = sys.argv[1:]
    modes = ("run", "plan", "build", "smoke")
    if not argv or argv[0] not in modes:
        argv.insert(0, "run")
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("mode", choices=modes)
    p.add_argument("b", type=int, nargs="?")
    p.add_argument("rf", type=int, nargs="?")
    p.add_argument("ys", type=int, nargs="?")
    p.add_argument("--b", dest="b_flag", type=int)
    p.add_argument("--rf", dest="rf_flag", type=int)
    p.add_argument("--ys", dest="ys_flag", type=int)
    p.add_argument("--dma-ids", "--dma-id", dest="dma_ids", type=int, default=2)
    p.add_argument("--r", type=int, default=4)
    p.add_argument("--weights", "--w", type=int, nargs="+", choices=(8,4,2), default=[8,4,2])
    p.add_argument("--cases", nargs="+", choices=[s[0] for s in SHAPES], default=[s[0] for s in SHAPES])
    p.add_argument("--captures", type=Path, default=Path("/mnt/e/Storage/Qwen3MatmulCaptures"))
    p.add_argument("--include-head", action="store_true")
    p.add_argument("--jobs", type=int, choices=(1,2), default=1)
    p.add_argument("--resume-from", type=Path)
    p.add_argument("--timeout", type=int, default=7200)
    p.add_argument("--max-cycles", type=int, default=1000000000)
    p.add_argument("--atol", type=float, default=0.0)
    p.add_argument("--rtol", type=float, default=0.0)
    args = p.parse_args(argv)
    for name in ("b","rf","ys"):
        positional = getattr(args,name)
        flagged = getattr(args,name+"_flag")
        if positional is not None and flagged is not None and positional != flagged:
            p.error(f"Conflicting positional and --{name} values")
        value = flagged if flagged is not None else positional
        if value is None:
            p.error(f"Provide --{name} or its positional value")
        setattr(args,name,value)
    if args.dma_ids < 1 or args.dma_ids > 64:
        p.error("DMA_ID must be in 1..64")
    if min(args.b,args.rf,args.ys,args.r) < 1 or args.r > 4:
        p.error("Positive B/RF/YS required; captured R supports 1..4 only")
    if args.rf & (args.rf-1):
        p.error("RF must be a power of two")
    if args.b*args.rf*64 > 65535:
        p.error("B*RF*64 exceeds the existing 16-bit RoCC slice field")
    if min(args.atol,args.rtol) < 0 or not all(math.isfinite(x) for x in (args.atol,args.rtol)):
        p.error("Tolerances must be finite and nonnegative")
    args.weights = list(dict.fromkeys(args.weights))
    args.smoke = args.mode == "smoke"
    if args.smoke:
        args.cases = ["K_V"]
        args.include_head = False
    return args


def execute_case(case, run, args, env, sim):
    name = f"W{case['bits']}_{case['case']}"
    directory = run / name
    row = {k:case[k] for k in ("bits","case","R","K","N","group_size","occurrences")}
    print(f"{name}: {case['R']}x{case['K']} * {case['K']}x{case['N']}", flush=True)
    try:
        exact, native = prepare(case, directory)
        elf = compile_case(case, directory, args, env)
        cmd = [str(sim), "+permissive", f"+max-cycles={args.max_cycles}",
               f"+loadmem={elf}", "+permissive-off", str(elf)]
        save(directory / "simulation-command.json", cmd)
        started = time.monotonic()
        with (directory / "verilator.raw.log").open("wb") as out:
            result = subprocess.run(cmd, cwd=directory, env=env, stdout=out,
                                    stderr=subprocess.STDOUT, timeout=args.timeout)
        row["simulator_exit"] = result.returncode
        row["elapsed_seconds"] = time.monotonic()-started
        if result.returncode:
            raise RuntimeError(f"Simulator exit {result.returncode}")
        raw = (directory / "verilator.raw.log").read_bytes()
        got, counters = extract_payload(raw, case["R"]*case["N"])
        (directory / "output.lumixed.f32.bin").write_bytes(got)
        row["cycles"] = counters
        if counters["total"] != sum(counters[k] for k in STAGES):
            raise RuntimeError("Active-cycle counters do not sum consistently")
        row["exact_comparison"] = compare(got, exact)
        row["rtl_exact_pass"] = row["exact_comparison"]["bit_exact"]
        row["native_comparison"] = compare(got, native, args.atol, args.rtol) if native is not None else None
        print(f"  cycles={counters['total']}, exact={'PASS' if row['rtl_exact_pass'] else 'FAIL'}, native="+
              ("not applicable" if native is None else "BIT EXACT" if row["native_comparison"]["bit_exact"] else "DIFFERENT"), flush=True)
    except Exception as error:
        row["error"] = str(error)
        row["rtl_exact_pass"] = False
        print(f"  ERROR: {error}", flush=True)
    return row


def main():
    args = parse()
    plan = build_plan(args)
    if args.mode == "plan":
        print(json.dumps(plan, indent=2))
        return 0
    cache = inside(HERE / "cache")
    cache.mkdir(exist_ok=True)
    env = environment(cache)
    with (cache / "suite.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        sim, build = simulator(args, plan, env)
        if args.mode == "build":
            print(sim)
            return 0
        stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        run = inside(HERE / "runs" / f"{stamp}_B{args.b}_RF{args.rf}_YS{args.ys}_ID{args.dma_ids}_R{args.r}")
        run.mkdir(parents=True)
        save(run / "plan.json", plan)
        save(run / "provenance.json", dict(simulator=str(sim), simulator_sha256=sha(sim),
             build=str(build), adapter_sha256=sha(SRC / "Linear-sw.c"),
             harness_sha256=sha(HERE / "harness.c"), runner_sha256=sha(Path(__file__))))
        records = []
        remaining = list(plan["cases"])
        if args.resume_from:
            previous = inside(args.resume_from)
            old = json.loads((previous / "report.json").read_text())
            provenance = json.loads((previous / "provenance.json").read_text())
            for key in ("B","RF","YS","DMA_ID","R"):
                if old["plan"].get(key,2 if key=="DMA_ID" else None) != plan[key]:
                    raise ValueError(f"Resume configuration differs: {key}")
            if old["plan"]["smoke"] or plan["smoke"]:
                raise ValueError("Only full-shape runs can be resumed")
            if provenance["simulator_sha256"] != sha(sim) or provenance["harness_sha256"] != sha(HERE / "harness.c") or provenance["adapter_sha256"] != sha(SRC / "Linear-sw.c"):
                raise ValueError("Resume simulator or C adapter differs")
            for case in list(remaining):
                key = (case["bits"],case["case"])
                prior = next((r for r in old["records"] if (r["bits"],r["case"]) == key and r.get("rtl_exact_pass") and r.get("simulator_exit")==0),None)
                if prior is None:
                    continue
                olddir = previous / f"W{case['bits']}_{case['case']}"
                if json.loads((olddir / "case.json").read_text()) != case or case["R"] != case["metadata"]["R"]:
                    continue
                source = Path(case["source"])
                originals = (("input.bin","activations.f32.bin"),("weights.bin","weights.lumixed.sint.bin"),
                             ("scales.bin","scales.fp16.bin"),("output.llamacpp.f32.bin","output.llamacpp.f32.bin"))
                if not all(sha(olddir/a)==sha(source/b) for a,b in originals):
                    raise ValueError("Resume capture input bytes changed")
                got = (olddir / "output.lumixed.f32.bin").read_bytes()
                expected = (olddir / "output.exact.f32.bin").read_bytes()
                if not compare(got,expected)["bit_exact"]:
                    raise ValueError("Resume exact output validation failed")
                shutil.copytree(olddir, run / olddir.name)
                record = dict(prior)
                record["reused_from"] = str(previous)
                records.append(record)
                remaining.remove(case)
                print(f"Reused verified measurement W{case['bits']}_{case['case']}",flush=True)
        reference_module()
        order = {(c["bits"],c["case"]):i for i,c in enumerate(plan["cases"])}
        records.sort(key=lambda r:order[(r["bits"],r["case"])])
        report(run,plan,records)
        with ThreadPoolExecutor(max_workers=args.jobs) as pool:
            futures = [pool.submit(execute_case,case,run,args,env,sim) for case in remaining]
            for future in as_completed(futures):
                records.append(future.result())
                records.sort(key=lambda r:order[(r["bits"],r["case"])])
                report(run,plan,records)
        print(f"Report: {run/'report.md'}", flush=True)
        summary_tool = HERE / "summarize.py"
        if summary_tool.exists():
            command([sys.executable, summary_tool, run], run, env, run / "summary.log")
            print(f"Summary: {run/'summary.md'}", flush=True)
        if any(not row.get("rtl_exact_pass") for row in records):
            return 1
        if any(row.get("native_comparison") and not row["native_comparison"]["bit_exact"] for row in records):
            print("Hardware matches the exact contract, but native llama.cpp bits differ. See numerical errors in the report.")
            return 2
        return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except BlockingIOError:
        raise SystemExit("Another QWEN_VERIFY run holds the local lock")
    except (ValueError, RuntimeError, FileNotFoundError) as error:
        raise SystemExit(str(error))
