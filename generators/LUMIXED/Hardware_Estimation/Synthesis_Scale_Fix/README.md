# Scaled ID8 synthesis after the mixed-width multiplier fix

**4/4 source-current synthesis results passed**, with zero unresolved black boxes.
Vivado 2023.2, common Alveo U250 part `xcu250-figd2104-2-e`, accelerator-only OOC
scope. These are post-synthesis utilization counts, not implementation, power,
numerical-correctness or timing-closure results.

## Before / after

All rows use **DMA ID8 and deferred group scaling ON**.

| B/RF/YS | DSP before → after | DSP reduction | LUT before → after | FF before → after | BRAM36 | URAM |
|---|---|---|---|---|---|---|
| B4/RF4/YS4 | 1,298 → **70** | 94.61% | 104,551 → 87,258 | 15,176 → 16,360 | 169 | 40 |
| B8/RF4/YS4 | 1,298 → **70** | 94.61% | 181,582 → 164,287 | 23,950 → 25,134 | 209 | 40 |
| B8/RF8/YS2 | 650 → **36** | 94.46% | 91,630 → 82,972 | 13,385 → 13,977 | 177 | 20 |
| B16/RF4/YS2 | 650 → **36** | 94.46% | 181,859 → 172,833 | 22,358 → 22,950 | 225 | 20 |

BRAM and UltraRAM counts are unchanged in every pair; BRAM18 is zero throughout.
LUT totals include distributed RAM and shift-register LUTs. Fabric FFs increase,
while DSPs decrease by 94.46–94.61%. Do not infer operating frequency or power from
these counts alone.

## What was measured

Baseline hardware revision: `6bffd7742b6e5e12ab08503215801ce5a31da324`.
Updated hardware revision: `62b02896b6c5cf5b8fbd36e62fc776f331a77c74`, including
`fdc5c1d3`'s `ExactScaleMultiplier.scala` and GroupScaleEngine connection.
Only those two Scala source files differ from the baseline hardware snapshot.

The original lowering widened both multiply operands to 309 bits. Vivado inferred
309x309 operations and allocated 324 DSPs per scaling lane. The signed inline
SystemVerilog helper exposes **297-bit partial × 12-bit scale significand**.
Updated inference uses **17 DSPs per row plus two shared control DSPs**: 36 for
YS2, 70 for YS4. The arithmetic product width is retained and no pipeline stages are added
by the source change; bit-exact functional tests were not rerun in this campaign.

Original INT16/FP16/FP32 accelerator classes were not taken from a stale JAR:
all 13 current LUMIXED Scala files were compiled with Scala 2.13.16 and the Chisel 6.7
plugin into a private assembly. Each exact private configuration was elaborated,
its actual accelerator boundary reviewed and the inline multiplier's 297/12-bit
parameters checked. No production Scala source was edited during measurement.

Hardware settings match the archived counterparts: FP32 activations, FP32 output,
runtime signed W8/W4/W2, capacities R4/K3072/N3072, two weight buffers, 8-byte DMA,
BRAM outputs and performance counters enabled. Group scaling is deferred, with
FP16 scales. Zero skipping, flexible grouping, early buffer release and DMA bursts
are OFF. One synthesis covers all three runtime weight precisions. ID16 was not
rerun and must not be inferred from these ID8 measurements.

B8/RF8/YS2/ID8 was synthesized first, then its verified matching result was reused
in the four-design summary. The other three were synthesized afresh. Identical
part, tool, synthesis-flow prefix, capacities and parameters were checked across
old/new pairs. No old design checkpoint was reused for updated RTL synthesis.

## Files

- `resources.csv`: four before/after rows, detailed LUT/memory categories,
  configuration identifiers, source revision and report/checkpoint SHA256s.
- `reports.tar.gz`: raw baseline/updated utilization reports, complete updated
  Vivado logs, commands, metadata, source hashes, generated accelerator RTL,
  current Scala source snapshot and exact immutable Tcl flow. Large DCPs/JARs
  remain in the local experiment directories.

The original [18-design synthesis snapshot](../Synthesis/README.md) preserves
the historical pre-fix results. The earlier
[unscaled implementation snapshot](../Implementation/README.md) is a separate,
older placement/routing campaign and does not establish timing or power for
these updated scaled designs.

## Replay and limitations

Extract the evidence in an isolated directory. `provenance.json` and
`updated/input_metadata.json` identify each top, exact configuration and original
source paths. Restore/remap those generated sources from `rtl/` into a file list,
then invoke the archived flow with stage `synth`:

```bash
PPA_MAX_THREADS=2 /tools/Xilinx/Vivado/2023.2/bin/vivado \
  -mode batch -nojournal -nolog -source flow/vivado_ppa_ooc.tcl -tclargs \
  LUMIXEDExample /absolute/sources.txt /absolute/incdirs.txt \
  xcu250-figd2104-2-e clock 20 synth /absolute/output_directory
```

The 20-ns clock setting is an evaluation constraint, not verified Fmax.
Synthesis-only clock/power reports, where present in raw logs/metadata, remain
preliminary. No placement/routing, bitstream, new Verilator test or power/timing
validation was run. Numerical correctness must be separately checked before
claiming the updated implementation functionally verified.

Local complete evidence:
`/home/kioannou/artifacts/lumixed_scale_multiplier_synth_20261124/` and
`/home/kioannou/artifacts/lumixed_scaled_id8_synthesis_20261124/`.
The older full PPA queue remains stopped.
