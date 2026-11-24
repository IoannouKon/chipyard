# Unscaled LUMIXED and Edited Gemmini implementation snapshot

Snapshot of the **stopped** common-device PPA campaign: seven unscaled LUMIXED
designs completed placement/routing, one completed placement but was interrupted
during routing, and both Edited Gemmini designs completed synthesis only.

**None of these designs has a validated operating clock from this campaign.**
Every completed route still fails hold timing; two also fail setup at 50 MHz.
The internal Fmax numbers below are setup-based register-to-register estimates,
not verified usable clock frequencies. Lowering the clock does not fix hold.

## Available results

| Configuration | P&R status | Dynamic power (W) | Total FPGA power (W) | Internal setup Fmax (MHz) | Setup slack (ns) | Hold slack (ns) |
|---|---|---|---|---|---|---|
| B4/RF4/YS4/ID8 | Routed; timing FAIL | 0.492 | 3.449 | 39.760 | -5.151 | -3.426 |
| B4/RF4/YS4/ID16 | Routed; timing FAIL | 0.511 | 3.468 | 66.756 | 5.020 | -2.502 |
| B8/RF4/YS4/ID8 | Routed; timing FAIL | 0.643 | 3.604 | 48.049 | -0.812 | -3.558 |
| B8/RF4/YS4/ID16 | Routed; timing FAIL | 0.635 | 3.596 | 54.392 | 1.615 | -2.839 |
| B8/RF8/YS2/ID8 | Routed; timing FAIL | 0.422 | 3.376 | 62.329 | 3.956 | -3.378 |
| B8/RF8/YS2/ID16 | Routed; timing FAIL | 0.446 | 3.401 | 54.484 | 1.646 | -3.426 |
| B16/RF4/YS2/ID8 | Routed; timing FAIL | 0.638 | 3.597 | 60.230 | 3.397 | -2.610 |
| B16/RF4/YS2/ID16 | Placed; routing stopped | — | — | — | — | — |
| Edited Gemmini 4x4 | Synthesis only | — | — | — | — | — |
| Edited Gemmini 8x8 | Synthesis only | — | — | — | — | — |

Target: `xcu250-figd2104-2-e` (Alveo U250), Vivado 2023.2, 50-MHz implementation
constraint. Source hardware is from benchmark revision
`6bffd7742b6e5e12ab08503215801ce5a31da324`, before the later scale-multiplier fix.
Scaling is OFF for every design in this snapshot. The separate, ongoing updated
scaled synthesis batch is not included here.

## Files and evidence

- `results.csv`: ten explicit status rows, stage-specific resources, setup/hold,
  indicative internal setup Fmax, vectorless power and report hashes.
- `reports.tar.gz`: original Vivado utilization, timing, routing and power reports,
  completed or interrupted implementation logs, exact commands, source metadata,
  configuration parameters and checkpoint hashes. Checkpoints remain local.

Gemmini routed timing/power fields and unfinished LUMIXED routed fields are blank,
not zero. The original `metrics.json` for the stopped case records its preceding
completed synthesis; its `status.tsv` and post-place checkpoint/report establish
that placement finished. The CSV explicitly labels the interrupted routing stage.
Resource stages differ: post-route for seven rows, post-place for the interrupted
row, post-synthesis for Gemmini. Do not mix these as a common-stage area comparison.
Use the sibling [synthesis snapshot](../Synthesis/README.md) for common-stage counts.

## Method and limitations

Actual accelerator-only OOC boundaries include the internal DMA and all original
ports; Rocket, SoC interconnect, external memory controller/model, board shell and
pads are excluded. A common constant-enabled BUFGCE makes the clock tree internal
and routable. Post-route resources/power include that clock integration.
External synchronous I/O was modeled with zero delay; reset is false-pathed.
The OOC boundary assumptions need review, particularly for the external-DMA hold
paths. These reports must not be interpreted as validated board-level timing.

Power uses Vivado's **vectorless** activity estimation at the 50-MHz target, not
activity from Qwen simulation and not a physical measurement. Total on-chip power
includes about 2.96 W of device static power; this is not accelerator-only dynamic
power. The confidence field is preserved in the CSV. No claim of workload energy,
50-MHz operating feasibility, maximum system frequency or Gemmini implementation
power is supported by this incomplete campaign.

LUMIXED uses FP32 activations, runtime signed W8/W4/W2 and FP32 output, capacities
R4/K3072/N3072, two weight buffers, 8-byte DMA, BRAM outputs and counters; optional
experiments are OFF. Edited Gemmini is FP32xW8 WS, SP32 KiB/ACC4 KiB, 64-bit DMA,
with sixteen independent slots per read/write pool. LUMIXED uses shared IDs.

No existing hardware or Verilator results were modified to create this archive.
The original PPA queue remains stopped. Live logs/checkpoints and the supplied
implementation flow remain under `/home/kioannou/lumixed_v1_ppa_package/work/`.
The synthesis archive includes generated RTL and the flow needed to replay these
commands after remapping their original absolute source paths. This commit stores
available evidence, without restarting or claiming completion of missing runs.
