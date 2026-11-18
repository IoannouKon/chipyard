#!/usr/bin/env python3
"""Build b=2,4,8 / RF=4,8 and report exact correctness for 180 cases."""
import argparse
import collections
import csv
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time

GEOMETRIES = [(b, rf) for b in (2, 4, 8) for rf in (4, 8)]
SHAPES = [(1, k, k) for k in (4, 8, 16, 32, 64, 128, 256, 512)] + [(1, 64, 16), (1, 128, 32)]
CASES = [(r, k, m, a, w) for r, k, m in SHAPES for a in (16,) for w in (2, 4, 8)]
CYCLE_FIELDS = {'hw_cycles': 'total', 'hw_load_x_cycles': 'load_x',
                'hw_generate_brams_cycles': 'generate_brams', 'hw_load_w_cycles': 'load_w',
                'hw_select_accumulate_cycles': 'select_accumulate', 'hw_store_o_cycles': 'store_o',
                'hw_load_w_and_select_cycles': 'load_w_and_select'}
FIELDS = ['b', 'RF', 'n', 'RIN', 'CIN', 'COUT', 'IN_BITS', 'W_BITS', 'status', 'seconds', 'exact_matches', *CYCLE_FIELDS, 'exit_code', 'log']
ACTIVE = None

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def stop(signum, frame):
    raise KeyboardInterrupt

def command(argv, cwd, env, log):
    global ACTIVE
    start = time.monotonic()
    with log.open('w') as output:
        ACTIVE = subprocess.Popen(argv, cwd=cwd, env=env, stdout=output,
                                  stderr=subprocess.STDOUT, start_new_session=True)
        try:
            return ACTIVE.wait(), time.monotonic() - start
        finally:
            if ACTIVE.poll() is None:
                os.killpg(ACTIVE.pid, signal.SIGTERM)
                try:
                    ACTIVE.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(ACTIVE.pid, signal.SIGKILL)
                    ACTIVE.wait()
            ACTIVE = None

def geometry_config(original, b, rf):
    text = original
    for key, value in [('x_slice', b), ('Mem_row_factor', rf)]:
        text, count = re.subn(r'(?m)^(\s*' + key + r'\s*=\s*)\d+',
                              lambda match: match[1] + str(value), text)
        if count != 1:
            raise ValueError(f'Expected one {key} assignment, got {count}')
    return text

def hardware_cycles(content):
    match = re.search(r'^HW_CYCLES\s+([^\n]+)', content, re.MULTILINE)
    values = dict(re.findall(r'([a-z_]+)=(\d+)', match[1])) if match else {}
    return {field: int(values[key]) if key in values else '' for field, key in CYCLE_FIELDS.items()}

def write_summary(folder, records, reference):
    with (folder / 'total_results.csv').open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(records)
    lines = ['# LUMIXED configuration sweep', '', 'Normal Verilator; exact integer output comparisons; no waveforms.', '',
             '| b | RF | n | PASS | FAIL | TIMEOUT | BUILD_FAIL | ERROR |',
             '|---:|---:|---:|---:|---:|---:|---:|---:|']
    for b, rf in GEOMETRIES:
        counts = collections.Counter(row['status'] for row in records if int(row['b']) == b and int(row['RF']) == rf)
        lines.append(f'| [{b}](b{b}_rf{rf}/results.md) | {rf} | {64*rf} | ' + ' | '.join(str(counts[s]) for s in ['PASS', 'FAIL', 'TIMEOUT', 'BUILD_FAIL', 'ERROR']) + ' |')
    counts = collections.Counter(row['status'] for row in records)
    lines += ['', f'Completed {len(records)}/180 tests. Counts: {dict(counts)}.', '',
              'Each geometry tests 1×K times K×K for K=4,8,16,32,64,128,256,512,',
              'plus 1×64 times 64×16 and 1×128 times 128×32.',
              'Every shape uses input16 and weight2/weight4/weight8; output32.',
              '', 'See total_results.csv for individual results and log paths.',
              'Hardware cycle counters are saved in each test log and the hw_* columns in total_results.csv.',
              'hw_cycles = Load X + Generate BRAMs + combined Load W/Select + Store O hardware counters.',
              'Load W and Select counters describe overlapping activity; do not add them again to hw_cycles.',
              'Hardware counters exclude CPU reference calculation and host simulation time; seconds is wall-clock runner time.',
              'FAIL means an output mismatch or simulator failure. TIMEOUT is inconclusive.',
              'BUILD_FAIL means the simulator could not build; ERROR means another runner error.',
              'The default memory model is used. ' + (
                  'Python prepares the inputs, packed weights, and exact integer reference on the host.'
                  if reference == 'python' else
                  'The RISC-V CPU generates inputs and computes the pure-C reference.')]
    (folder / 'total_results.md').write_text('\n'.join(lines) + '\n')
    for b, rf in GEOMETRIES:
        group = folder / f'b{b}_rf{rf}'
        group.mkdir(exist_ok=True)
        rows = [row for row in records if int(row['b']) == b and int(row['RF']) == rf]
        with (group / 'results.csv').open('w', newline='') as stream:
            writer = csv.DictWriter(stream, fieldnames=FIELDS)
            writer.writeheader()
            writer.writerows(rows)
        counts = collections.Counter(row['status'] for row in rows)
        report = [f'# Configuration b={b}, RF={rf}', '', f'Completed {len(rows)}/30: {dict(counts)}.', '',
                  '[All results](../total_results.md) · [CSV](results.csv)', '',
                  '| X | W | A bits | W bits | Result | Exact matches | HW cycles | Log |',
                  '|---|---|---:|---:|---|---|---:|---|']
        for row in rows:
            log = Path(os.path.relpath(row['log'], group))
            report.append(f"| {row['RIN']}×{row['CIN']} | {row['CIN']}×{row['COUT']} | {row['IN_BITS']} | {row['W_BITS']} | {row['status']} | {row['exact_matches']} | {row['hw_cycles']} | [log]({log}) |")
        (group / 'results.md').write_text('\n'.join(report) + '\n')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, help='New result directory (defaults to timestamped Log directory)')
    parser.add_argument('--resume', action='store_true', help='Reuse matching builds and previously passing tests in --output')
    parser.add_argument('--reference', choices=('riscv', 'python'), default='riscv',
                        help='Reference calculation: riscv (default) or faster host Python; both check every output')
    parser.add_argument('--keep-design', action='store_true',
                        help='Keep generated-src RTL and build artifacts (removed by default; simulator and logs are kept)')
    args = parser.parse_args()
    script = Path(__file__).resolve().parent
    cy = script.parents[4]
    scala = cy / 'generators/LUMIXED/src/main/scala/Config.scala'
    original = scala.read_text()
    if not re.search(r'(?m)^\s*y_slice\s*=\s*1\s*,', original):
        parser.error('This sweep requires y_slice=1.')
    lock = (script / '.config_sweep.lock').open('w')
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        parser.error('Another configuration sweep is running.')
    folder = (args.output or script / 'Log' / time.strftime('run_%Y%m%d_%H%M%S')).resolve()
    if folder.exists() and any(folder.iterdir()) and not args.resume:
        parser.error('Output directory is nonempty; choose a new directory or --resume.')
    folder.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env.update(CONFIG='LUMIXEDROcketConfig', REBUILD='0',
               FAST_VECTORS='1' if args.reference == 'python' else '0',
               REQUIRE_RISCV_REFERENCE='0' if args.reference == 'python' else '1', QUIET_TEST='1', DRAMSIM='0', LUMIXED_LOG_FLAT='1',
               TEST_CFLAGS='-O2', TEST_SEED=env.get('TEST_SEED', '1'), TEST_PATTERN=env.get('TEST_PATTERN', '0'),
               TIMEOUT_SECONDS=env.get('TIMEOUT_SECONDS', '3600'), TIMEOUT_CYCLES=env.get('TIMEOUT_CYCLES', '1000000000'))
    manifest = {'geometries': GEOMETRIES, 'cases': CASES, 'base_config': original,
                'flags': {key: env[key] for key in ['CONFIG', 'REQUIRE_RISCV_REFERENCE', 'FAST_VECTORS', 'QUIET_TEST', 'DRAMSIM', 'TEST_CFLAGS', 'TEST_SEED', 'TEST_PATTERN', 'TIMEOUT_SECONDS', 'TIMEOUT_CYCLES']},
                'sources': {str(p.relative_to(cy)): digest(p) for p in
                            sorted((cy / 'generators/LUMIXED/src/main/scala').glob('*.scala')) if p != scala}}
    for p in [cy/'build.sbt', cy/'generators/chipyard/src/main/scala/config/LUMIXEDConfigs.scala',
              script/'Linear-sw.c', script/'rocc.h', script/'compiler.h', script/'build.sh',
              script/'run_param_test.sh', script/'prepare_test_vectors.py', Path(__file__)]:
        manifest['sources'][str(p.relative_to(cy))] = digest(p)
    artifacts = folder / 'artifacts'
    artifacts.mkdir(exist_ok=True)
    manifest_path = artifacts / 'manifest.json'
    if args.resume and (not manifest_path.exists() or json.loads(manifest_path.read_text()) != json.loads(json.dumps(manifest))):
        parser.error('Source/configuration/test flags differ from saved manifest; start a new sweep.')
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
    records = []
    prior = {}
    if args.resume and (folder/'total_results.csv').exists():
        with (folder/'total_results.csv').open() as stream:
            for row in csv.DictReader(stream):
                prior[tuple(int(row[k]) for k in ['b', 'RF', 'RIN', 'CIN', 'COUT', 'IN_BITS', 'W_BITS'])] = row
    backup = artifacts / ('backup_' + time.strftime('%Y%m%d_%H%M%S'))
    backup.mkdir()
    saved = [scala] + [script / name for name in ['Linear-sw.c', 'Linear-sw.o', 'Linear-sw.riscv', 'lumixed_test_vectors.h']]
    for path in saved:
        if path.exists(): shutil.copy2(path, backup / path.name)
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    print(f'Results: {folder} | 6 configurations × 30 cases = 180 tests', flush=True)
    print(f'Reference: {args.reference}; generated design: {"kept" if args.keep_design else "removed after build"}', flush=True)
    try:
        for b, rf in GEOMETRIES:
            group = folder / f'b{b}_rf{rf}'
            group.mkdir(exist_ok=True)
            scala.write_text(geometry_config(original, b, rf))
            design = group / 'design'
            design.mkdir(exist_ok=True)
            shutil.copy2(scala, design / 'Config.scala')
            sim = design / 'simulator-LUMIXEDROcketConfig'
            build_ok = False
            marker = design / 'simulator.sha256'
            if args.resume and sim.exists() and marker.exists() and digest(sim) == marker.read_text().strip():
                build_ok = True
            else:
                print(f'Building b={b}, RF={rf}, n={64*rf} ...', flush=True)
                result, elapsed = command(['make', '-j' + env.get('BUILD_JOBS', '8'), 'CONFIG=LUMIXEDROcketConfig',
                                           'sim=' + str(sim), 'gen_dir=' + str(design / 'generated-src')],
                                          cy/'sims/verilator', env, design/'build.log')
                build_ok = result == 0 and sim.is_file()
                print(f'Build b={b}, RF={rf}: {"OK" if build_ok else "FAILED"} ({elapsed:.0f}s)', flush=True)
                if build_ok: marker.write_text(digest(sim) + '\n')
            generated = design / 'generated-src'
            if not args.keep_design and generated.exists():
                shutil.rmtree(generated)
            for r, k, m, a, w in CASES:
                key = (b, rf, r, k, m, a, w)
                row = dict(zip(['b', 'RF', 'n', 'RIN', 'CIN', 'COUT', 'IN_BITS', 'W_BITS'], [b, rf, 64*rf, r, k, m, a, w]))
                row.update(dict.fromkeys(CYCLE_FIELDS, ''))
                old = prior.get(key)
                if build_ok and old and old['status'] == 'PASS' and Path(old['log']).exists() and 'TEST [PASS]' in Path(old['log']).read_text():
                    row = old
                elif not build_ok:
                    row.update(status='BUILD_FAIL', seconds='0', exact_matches='', exit_code='2', log=str(design/'build.log'))
                else:
                    env.update(SIM_BIN=str(sim), LUMIXED_LOG_DIR=str(group/'tests'))
                    command_log = artifacts / f'b{b}_rf{rf}_X{r}x{k}_W{k}x{m}_A{a}_W{w}.log'
                    result, elapsed = command(['bash', str(script/'run_param_test.sh'), *map(str, (r, k, m, a, w))], script, env, command_log)
                    case_log = group/'tests'/f'X{r}x{k}_W{k}x{m}_A{a}_W{w}.log'
                    content = case_log.read_text() if case_log.exists() else command_log.read_text()
                    exact = re.search(r'Exact matches:\s*(\d+/\d+)', content)
                    row.update(hardware_cycles(content))
                    status = 'PASS' if result == 0 and 'TEST [PASS]' in content and exact and exact[1] == f'{r*m}/{r*m}' else 'FAIL'
                    if 'simulator_exit=124' in content or 'simulator_exit=137' in content or 'timeout' in content.lower(): status = 'TIMEOUT'
                    elif 'TEST [FAIL]' not in content and status != 'PASS': status = 'ERROR'
                    if status == 'PASS' and any(row[field] == '' for field in CYCLE_FIELDS):
                        status = 'ERROR'
                        print(f'Missing hardware counters: {case_log}', flush=True)
                    row.update(status=status, seconds=f'{elapsed:.2f}', exact_matches=exact[1] if exact else '', exit_code=result, log=str(case_log if case_log.exists() else command_log))
                records.append(row)
                write_summary(folder, records, args.reference)
                print(f'[{len(records)}/180] b={b} RF={rf} X={r}×{k} W={k}×{m} A{a}/W{w}: {row["status"]} | HW={row["hw_cycles"] if row["hw_cycles"] != "" else "n/a"} cycles ({row["seconds"]}s)', flush=True)
    finally:
        for path in saved:
            snapshot = backup/path.name
            if snapshot.exists(): shutil.copyfile(snapshot, path)
            elif path.exists(): path.unlink()
        if not args.keep_design:
            for b, rf in GEOMETRIES:
                design = folder / f'b{b}_rf{rf}' / 'design' / 'generated-src'
                if design.exists(): shutil.rmtree(design)
        write_summary(folder, records, args.reference)
        print('Original Scala configuration and C test files restored.', flush=True)
    counts = collections.Counter(row['status'] for row in records)
    print(f'Finished: {dict(counts)}. Report: {folder / "total_results.md"}', flush=True)
    return 0 if len(records) == 180 and counts['PASS'] == 180 else 1

if __name__ == '__main__':
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print('Sweep interrupted; completed results retained.', flush=True)
        sys.exit(130)
