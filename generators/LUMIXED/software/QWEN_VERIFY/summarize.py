#!/usr/bin/env python3
"""Add native-relative numerical error and readable cycle tables to a completed run."""
from pathlib import Path
import argparse
import csv
import json
import math
import struct


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    run = args.run.resolve()
    if not run.is_relative_to(root):
        raise SystemExit("Reports must remain inside QWEN_VERIFY")
    report = json.loads((run / "report.json").read_text())
    rows = []
    for row in report["records"]:
        enriched = dict(row)
        case = run / f"W{row['bits']}_{row['case']}"
        if (case / "output.lumixed.f32.bin").exists() and (case / "output.llamacpp.f32.bin").exists():
            hw = [v[0] for v in struct.iter_unpack("<f", (case / "output.lumixed.f32.bin").read_bytes())]
            native = [v[0] for v in struct.iter_unpack("<f", (case / "output.llamacpp.f32.bin").read_bytes())]
            if len(hw) != len(native):
                raise ValueError("Saved output lengths differ")
            n = len(hw)
            err = [x-y for x,y in zip(hw,native)]
            square_error = math.fsum(v*v for v in err)
            square_native = math.fsum(v*v for v in native)
            square_hw = math.fsum(v*v for v in hw)
            enriched["native_relative_metrics"] = dict(
                normalized_rmse_percent=100*math.sqrt(square_error/square_native) if square_native else None,
                native_output_rms=math.sqrt(square_native/n),
                mean_absolute_error=math.fsum(abs(v) for v in err)/n,
                cosine_similarity=math.fsum(x*y for x,y in zip(hw,native))/math.sqrt(square_hw*square_native) if square_hw and square_native else None)
        rows.append(enriched)
    result = dict(configuration={k:report["plan"][k] for k in ("B","RF","YS","DMA_ID","R")},
                  rows=rows, projection_estimates=report["projection_estimates"],
                  all_exact_rtl_pass=len(rows)==len(report["plan"]["cases"]) and all(r.get("rtl_exact_pass") for r in rows),
                  completed=len(rows), planned=len(report["plan"]["cases"]),
                  excluded=report["plan"]["excluded"])
    (run / "summary.json").write_text(json.dumps(result,indent=2,allow_nan=False)+"\n")
    lines = [f"# R={result['configuration']['R']} real Qwen3 matmul results", "", str(result["configuration"]), "",
             "| W | Shape R x K x N | G | Active cycles | Exact reference | Native bit differences | Native NRMSE % | Native max abs error |",
             "|---:|---|---:|---:|---|---:|---:|---:|"]
    fields = ["w_bits","R","K","N","group_size","active_cycles","rtl_exact_pass",
              "native_bit_mismatches","native_nrmse_percent","native_max_abs_error"]
    with (run / "summary.csv").open("w",newline="") as stream:
        writer = csv.DictWriter(stream,fieldnames=fields)
        writer.writeheader()
        for r in rows:
            native = r.get("native_comparison") or {}
            rel = r.get("native_relative_metrics") or {}
            value = dict(w_bits=r["bits"],R=r["R"],K=r["K"],N=r["N"],group_size=r["group_size"],
                         active_cycles=r.get("cycles",{}).get("total"),rtl_exact_pass=r.get("rtl_exact_pass"),
                         native_bit_mismatches=native.get("bit_mismatches"),
                         native_nrmse_percent=rel.get("normalized_rmse_percent"),
                         native_max_abs_error=native.get("max_abs_error"))
            writer.writerow(value)
            error = rel.get("normalized_rmse_percent")
            error_text = f"{error:.6f}" if error is not None else "-"
            lines.append(f"| {r['bits']} | {r['R']} x {r['K']} x {r['N']} | {r['group_size']} | {value['active_cycles']} | {'PASS' if r.get('rtl_exact_pass') else 'FAIL'} | {native.get('bit_mismatches','-')} | {error_text} | {native.get('max_abs_error','-')} |")
    lines += ["", "## Estimated cycles for all 196 transformer projections", "",
              "28*C_Query + 56*C_KeyValue + 28*C_AttentionOutput + 56*C_GateUp + 28*C_Down", "",
              "| W | Completed full-shape RTL checks | Estimated 196-projection cycles |",
              "|---:|---|---:|"]
    for e in result["projection_estimates"]:
        checked = sum(r["bits"]==e["bits"] and r["case"] != "HEAD" and r.get("rtl_exact_pass",False) for r in rows)
        lines.append(f"| {e['bits']} | {checked}/5 | {e['cycles'] if e['complete'] else 'WITHHELD'} |")
    lines += ["", "The estimate reuses representative layer-0 shape costs across 28 layers.",
              "It excludes the vocabulary head, attention QK/AV, normalization, RoPE, SiLU, softmax and CPU overhead.",
              "NRMSE is error RMS divided by the native output RMS, not a model accuracy score.",
              "A native bit difference is reported honestly even when RTL matches its exact arithmetic contract.",
              "See report.json for stage counters and separate 28-occurrence operation estimates.",
              "", "Q4_0 and Q2_0 vocabulary heads are Q6_K and were not requantized."]
    (run / "summary.md").write_text("\n".join(lines)+"\n")
    print(json.dumps(result,indent=2))


if __name__ == "__main__":
    main()
