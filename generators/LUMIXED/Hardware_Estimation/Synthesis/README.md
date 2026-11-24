> **Historical pre-fix baseline.** Updated scaled ID8 synthesis results with the mixed-width multiplier fix are in [Synthesis_Scale_Fix](../Synthesis_Scale_Fix/README.md).

# Vivado post-synthesis resources — 18 designs

All **18/18** completed synthesis using **Vivado 2023.2**, common part
`xcu250-figd2104-2-e` (Alveo U250). These are **post-synthesis utilization counts**,
not placement/routing or timing-closure results. No operating frequency, power,
FPGA-board feasibility or 50-MHz timing pass is claimed by this snapshot.

## Files

- `resources.csv`: all 18 resource rows, tool/part, stage, and report/checkpoint hashes.
- `reports.tar.gz`: original utilization reports and synthesis logs for all 18,
  exact configuration table, per-design provenance, source hashes, generated
  accelerator RTL and the evaluation flow. Large DCPs/assembly JARs remain local.

| Design | Scale | IDs | LUTs | FFs | DSPs | BRAM36 | BRAM18 | URAM |
|---|---|---|---|---|---|---|---|---|
| B4/RF4/YS4 | ON | 8 | 104,551 | 15,176 | 1,298 | 169 | 0 | 40 |
| B4/RF4/YS4 | ON | 16 | 101,431 | 15,641 | 1,298 | 169 | 0 | 40 |
| B8/RF4/YS4 | ON | 8 | 181,582 | 23,950 | 1,298 | 209 | 0 | 40 |
| B8/RF4/YS4 | ON | 16 | 174,217 | 24,462 | 1,298 | 209 | 0 | 40 |
| B8/RF8/YS2 | ON | 8 | 91,630 | 13,385 | 650 | 177 | 0 | 20 |
| B8/RF8/YS2 | ON | 16 | 91,689 | 13,926 | 650 | 177 | 0 | 20 |
| B16/RF4/YS2 | ON | 8 | 181,859 | 22,358 | 650 | 225 | 0 | 20 |
| B16/RF4/YS2 | ON | 16 | 173,791 | 22,856 | 650 | 225 | 0 | 20 |
| B4/RF4/YS4 | OFF | 8 | 62,366 | 7,364 | 0 | 40 | 0 | 40 |
| B4/RF4/YS4 | OFF | 16 | 61,363 | 7,858 | 0 | 40 | 0 | 40 |
| B8/RF4/YS4 | OFF | 8 | 115,568 | 11,313 | 0 | 80 | 0 | 40 |
| B8/RF4/YS4 | OFF | 16 | 121,383 | 11,815 | 0 | 80 | 0 | 40 |
| B8/RF8/YS2 | OFF | 8 | 61,393 | 6,973 | 0 | 48 | 0 | 20 |
| B8/RF8/YS2 | OFF | 16 | 61,168 | 7,504 | 0 | 48 | 0 | 20 |
| B16/RF4/YS2 | OFF | 8 | 128,701 | 11,120 | 0 | 96 | 0 | 20 |
| B16/RF4/YS2 | OFF | 16 | 128,916 | 11,626 | 0 | 96 | 0 | 20 |
| Edited Gemmini 4x4 | OFF | 16 | 56,331 | 27,130 | 170 | 0 | 64 | 0 |
| Edited Gemmini 8x8 | OFF | 16 | 133,323 | 46,738 | 225 | 0 | 0 | 0 |

## Scope and interpretation

Actual accelerator OOC tops are `LUMIXEDExample` and `Gemmini`. All original data
interfaces and internal DMA logic are retained; Rocket, SoC interconnect, external
memory, simulation models, board shell and pads are excluded. No black boxes were
found. These counts precede the implementation-only global clock-buffer insertion.

LUT totals include distributed RAM and shift registers; `resources.csv` separates
logic LUTs, memory LUTs, distributed RAM and SRLs. BRAM18, BRAM36 and UltraRAM are
separate physical resources. `mapped_ram_mibits` is allocated BRAM/URAM capacity,
not silicon area or exact logical memory usage. Gemmini 8x8 uses 7,808 LUTs as
distributed RAM and therefore reports zero block RAM here.

LUMIXED: FP32 activations, FP32 output, runtime signed W8/W4/W2, capacities
R=4/K=N=3072, two weight buffers, 8-byte DMA, BRAM outputs and performance counters.
Each geometry is synthesized once with deferred FP16 group scaling ON and once
with both group-scaling flags OFF. Other optional experiments are OFF.

Edited Gemmini: genuine FP32 x signed W8, FP32 output, 4x4 or 8x8 WS array,
32-KiB scratchpad, 4-KiB accumulator, 64-bit DMA, maximum 64-byte transactions,
and 16 outstanding slots in each independent read/write pool. LUMIXED IDs are
shared, so equal ID labels do not mean identical memory interfaces. Exact
configuration fields are included in `configurations.csv` inside the archive.

## Important scaling-multiplier finding

The scaled synthesis snapshot includes the existing oversized multiplier mapping.
The intended 297-bit exact partial times a 12-bit scale significand is emitted with
309-bit sign-extended operands; Vivado reports 309x309 multipliers. Synthesis logs
show **324 DSPs per parallel row**, plus two shared control multipliers: 650 DSPs
for YS2 and 1,298 for YS4. This is measured mapping overhead, not a necessary cost
of an ordinary FP32xFP16 multiplier. No width fix is included in these results.

## Provenance and reproduction

Benchmark hardware revision: Chipyard `6bffd7742b6e5e12ab08503215801ce5a31da324`.
Edited Gemmini uses submodule base `8c3f9923a44a2fe2c7930587be297d6d4f8c09ca`
with the verified port archived under `generators/LUMIXED/Gemmini_Edited/`.
Private Scala overlays supply the exact capacities and parameters. The archive
includes the exact elaboration fingerprints, RTL SHA256s and synthesis checkpoint
hash for each design. The live Vivado working directory was not modified for this snapshot.

Extract the evidence into a separate directory:

```bash
mkdir -p /tmp/lumixed-synthesis-evidence
cd /tmp/lumixed-synthesis-evidence
tar -xzf /path/to/Hardware_Estimation/Synthesis/reports.tar.gz
```

The source manifests retain original absolute paths. To replay, restore or remap
those sources from the archived `rtl/` tree into `sources.txt` and use an empty
`incdirs.txt` for this generated self-contained RTL. The actual flow invocation is:

```bash
/tools/Xilinx/Vivado/2023.2/bin/vivado -mode batch -nojournal -nolog \
  -source flow/vivado_ppa_ooc.tcl -tclargs \
  TOP /absolute/sources.txt /absolute/incdirs.txt \
  xcu250-figd2104-2-e clock 20 synth /absolute/output_directory
```

Use the `top` and `source_sha256` entries in each design's `provenance.json`.
Limit `PPA_MAX_THREADS=2` and CPU affinity as appropriate on a shared server.
No bitstream is needed. Original local evidence, DCPs and the full running PPA
batch remain under `/home/kioannou/lumixed_v1_ppa_package/work/`.

This commit is a synthesis-only archive. The ongoing placement/routing jobs and
all existing Verilator benchmark evidence were left unchanged.
