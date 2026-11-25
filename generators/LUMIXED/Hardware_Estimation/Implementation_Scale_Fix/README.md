# Updated scaled LUMIXED versus Edited Gemmini: routed PPA

**All six designs completed placement and routing** on the common Alveo U250
`xcu250-figd2104-2-e`, Vivado 2023.2, with a 50-MHz target (20 ns).
The detached batch ran sequentially with up to eight CPU threads and finished
2026-11-25 04:18 EET, about 5h52m after launch. All original synthesis checkpoints
were hash-validated and reused; no RTL or synthesis architecture was changed.

**None fully closes timing.** All have external-interface hold violations.
Both Gemmini configurations also fail internal setup at 50 MHz. All four updated
LUMIXED configurations pass internal setup at 50 MHz; B16/RF4/YS2 additionally has
an internal hold violation of −0.006 ns. The other five internal hold slacks are
positive. No setup-based Fmax below is a verified usable system clock.

## Actual post-route results

All LUMIXED rows: **deferred FP16 scaling ON, multiplier width fix, DMA ID8**.

| Design | LUTs | FFs | DSPs | Dynamic W | Total W | Internal setup Fmax MHz | Setup ns | Internal hold ns | Worst hold ns |
|---|---|---|---|---|---|---|---|---|---|
| Edited Gemmini 4×4 | 55,428 | 27,151 | 169 | 0.213 | 3.161 | 39.341 | -5.419 | 0.009 | -3.346 |
| Edited Gemmini 8×8 | 130,998 | 46,732 | 224 | 0.282 | 3.231 | 40.447 | -4.724 | 0.010 | -3.555 |
| B8/RF8/YS2/ID8 | 80,215 | 13,951 | 36 | 0.435 | 3.39 | 55.885 | 2.106 | 0.012 | -3.217 |
| B4/RF4/YS4/ID8 | 84,921 | 16,343 | 70 | 0.454 | 3.411 | 64.263 | 4.439 | 0.010 | -2.599 |
| B8/RF4/YS4/ID8 | 161,623 | 25,114 | 70 | 0.636 | 3.597 | 60.606 | 3.5 | 0.010 | -2.598 |
| B16/RF4/YS2/ID8 | 168,523 | 22,941 | 36 | 0.746 | 3.708 | 56.233 | 2.217 | -0.006 | -3.187 |

`results.csv` also separates logic/memory LUTs, BRAM18/BRAM36/URAM, scope identifiers,
clock checks, vectorless static power and confidence. No unscaled LUMIXED or
hypothetical Gemmini W4/W2 results are included in this six-design batch.

## Evidence

- `results.csv`: six actual post-route rows; failures and timing boundaries retained.
- `reports.tar.gz`: original Vivado logs, synthesis/place/route utilization, setup
  and hold path reports, internal versus input/output hold diagnostics, clock/DRC
  reports, vectorless power, configuration/source metadata, checkpoint/report
  hashes, exact executed Tcl and the detached Python runner.

Large placed/routed DCPs remain local rather than in Git. Their recorded hashes
and original locations are preserved. Generated RTL and source provenance are
also available in the sibling [updated synthesis archive](../Synthesis_Scale_Fix/README.md)
for LUMIXED and [original synthesis archive](../Synthesis/README.md) for Gemmini.
The [older implementation snapshot](../Implementation/README.md) remains separate;
it used unscaled LUMIXED and did not implement Gemmini.

## Parameters and provenance

LUMIXED source revision: `62b02896b6c5cf5b8fbd36e62fc776f331a77c74` with the
mixed-width scale helper. Four geometries: B8/RF8/YS2, B4/RF4/YS4, B8/RF4/YS4,
B16/RF4/YS2. All have capacities R4/K3072/N3072, FP32 activations/output,
runtime signed W8/W4/W2, deferred FP16 group scaling, two weight buffers,
8-byte DMA, BRAM outputs and performance counters; other optional experiments OFF.

Edited Gemmini uses the verified FP32×signed W8 port at hardware snapshot
`6bffd7742b6e5e12ab08503215801ce5a31da324`, with 4×4 or 8×8 WS arrays,
32-KiB scratchpad, 4-KiB accumulator, 64-bit DMA and sixteen source slots per
independent read/write pool. LUMIXED has a shared ID pool. No group scaling,
bias or activation functions are benchmarked for Gemmini here.

## Interpretation and limitations

These are accelerator-only OOC implementations including internal DMA. Rocket,
SoC interconnect, external memory/controller/model, board shell and pads are
excluded. All original data interfaces are retained. An identical always-enabled
BUFGCE provides a real internal clock tree for every accelerator.

The original synchronous **zero-delay external I/O assumptions are unchanged**.
They generate important external DMA hold failures and require further boundary
constraint analysis. Internal setup/hold reports were added as diagnostics,
without relaxing timing exceptions or editing the datapath. The reports show
zero no-clock endpoints and zero unconstrained internal endpoints, but those
checks alone do not establish correct external-interface timing.

Internal Fmax is an indicative same-clock register-to-register setup estimate
from the routed netlist. It is not a frequency-sweep result, a board clock claim,
or proof of hold closure. Lowering frequency does not resolve hold violations.
No clock is described as achieved unless all relevant setup/hold checks pass.

Power is **post-route vectorless estimation at the 50-MHz target**, with Medium
confidence from Vivado, not measured Qwen switching or hardware power. Total
on-chip power includes roughly 2.95–2.96 W of FPGA static power; only the dynamic
column is accelerator activity-related, still under an estimated activity model.
No workload energy, EDP or clock-normalized speedup is claimed from these data.
The same LUMIXED runtime-precision RTL is used for W8/W4/W2; this power estimate
is not separately measured per precision. These PPA runs do not replace numerical
Verilator verification of the multiplier edit.

## Reproduction

Extract the evidence outside the repository. Each design's `command.json` names
its exact immutable Tcl flow, original source lists and output directory. Restore
or remap the archived generated sources from the corresponding synthesis archive,
and supply the matching source-hashed `post_synth.dcp` when replaying the same
implementation without resynthesis. Per-design executed flow files are included;
run the recorded Vivado command with `PPA_REUSE_SYNTH=1`, `PPA_MAX_THREADS=8`,
`OMP_NUM_THREADS=8` and an appropriate eight-core CPU affinity mask.

The complete original detached runner and its checkpoint preflight are inside
`reports.tar.gz` under `runner/`. It references the preserved local experiment
paths; adjust them explicitly for a different machine. Source checks must not be
removed merely to force reuse of an incompatible checkpoint. No bitstream is
required. Full local evidence is under:

`/home/kioannou/artifacts/lumixed_gemmini_implementation_8cores_20261124/`
