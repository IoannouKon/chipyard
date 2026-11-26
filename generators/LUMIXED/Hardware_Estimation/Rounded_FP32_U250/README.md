# Rounded FP32 accumulation — full-core registered-context U250 results

`ROUNDED_ACCUMULATION` is opt-in and defaults to **false**, preserving the exact
path. When true, it now always uses the verified pipelined rounded path: exact
bank reduction → FP32 RNE subtotal → subtotal/tag register → FP32 RNE running
addition. Existing SRAM forwarding, metadata alignment and drain boundaries are
preserved. This numerical contract can differ from rounding the full sum once.

## Matched routed measurements

Vivado **2023.2**, `xcu250-figd2104-2-e`, **20 ns / 50 MHz**, eight eligible CPUs,
Default opt/place/phys_opt/route, identical registered context and vectorless power
assumptions. B8/RF8/YS2/ID8, R capacity 4, K/N capacities 3072; FP32 activations /
outputs and signed runtime W8/W4/W2; scaling OFF, 8-byte DMA, two weight buffers.

| Measurement | Rounded without subtotal register | Rounded with subtotal register |
|---|---:|---:|
| Routing | Fully routed, zero routing errors | Fully routed, zero routing errors |
| 50 MHz setup / hold | FAIL / PASS | PASS / PASS |
| Global/internal setup slack | −5.096 ns | +0.851 ns |
| Global/internal hold slack | +0.006 ns | +0.010 ns |
| Core-only hold slack | +0.019 ns | +0.011 ns |
| Worst core data-path delay | 24.695 ns | 18.799 ns |
| Setup-derived minimum period | 25.096 ns | 19.149 ns |
| Internal setup-based Fmax estimate | 39.847 MHz | 52.222 MHz |
| Top LUTs / FFs | 61,868 / 8,334 | 60,955 / 8,351 |
| Core LUTs / FFs | 61,419 / 6,471 | 60,504 / 6,488 |
| Top BRAM36 / BRAM18 / URAM / DSP | 70 / 1 / 0 / 0 | 70 / 1 / 0 / 0 |
| Core BRAM36 / DSP | 56 / 0 | 56 / 0 |
| Top estimated dynamic power | 0.218 W | 0.182 W |
| Core hierarchy dynamic estimate | 0.206 W | 0.172 W |
| Device static power | 2.949 W | 2.948 W |
| Total estimated on-chip power | 3.166 W | 3.131 W |

Dynamic power is **16.5% lower**; the worst reported core data path is **23.9%
shorter**. Values are report-rounded; independently rounded power rows need not
sum exactly. Both were analyzed with a 50 MHz clock, but the unpipelined baseline
fails setup at that target. It completed routing; it is not a pruned or timed-out
trial. Only its matched comparison summary and source-report hashes are included.

Fmax here means `1000 / (20 − internal_setup_slack_ns)` for the worst same-clock
20 ns register-to-register path, including the report's skew/uncertainty effects.
It is a **post-route setup-based estimate**, not a measured board frequency or
proof that timing was closed in a new run at 52.222 MHz. The pipelined context
closes setup and hold **at the tested 50 MHz constraint**. Vectorless power is an
estimate, not measured FPGA power or workload-specific energy.

## Full core and context scope

The full standalone synthesized core was exported with `write_edif` and linked
into the identical registered command/TileLink memory context. No core placement
or routing was locked; `DONT_TOUCH=0`, `IS_BLACKBOX=0`. Linked synthesis matches the
full standalone LUT/FF/BRAM footprints; retention checks also pass after opt,
phys_opt and route. Each core retains all **56 BRAM36** plus product-generation
and FP32-adder hierarchy. The rejected stripped-wrapper experiment is excluded.
Connectivity checks observe all 64 TileLink write-data and registered-capture
bits; Verilator separately checked registered write payloads and RAM outputs.
This is not a formal post-synthesis equivalence proof.

Top totals include a representative host, request queue and 64 KiB local SRAM.
Context overhead is 449/451 LUTs (unpipelined/pipelined), 1,863 FFs, 14 BRAM36 and
one BRAM18. Estimated top-minus-core dynamic residual is 12/10 mW, including
shared clock/context attribution; it is not an isolated measured power rail.

All clocked endpoints use the same physical BUFGCE clock tree. Only reset is
false-pathed; no synchronous interface false paths, artificial input delays or
delay padding are added. Timing checks report no unclocked registers or
unconstrained internal endpoints. The OOC report retains **RTSTAT-10**, a warning
for diagnostic top-level outputs with no board-level routable loads. This is a
registered evaluation context, not a complete board/system implementation.

## Evidence predates final flag cleanup

The routed pipelined evidence was generated on **2026-11-26**, **before** removing
`PIPELINED_ROUNDED_ACCUMULATION`, with that flag **true** and
`ROUNDED_ACCUMULATION=true`. The production source in this commit removes the
extra selector and makes that same pipeline unconditional within rounded mode.
**No new Vivado run was made after this cleanup.** `provenance.json` identifies
both source snapshots by SHA-256 and records the original base revision, exact
parameters, core-checkpoint hashes, shared wrapper/flow hashes and commands.

After cleanup, two **fresh full-system Chipyard/Rocket/RoCC Verilator builds**
passed **48/48** small MatMuls: rounded 24/24 (264/264 bit matches), exact 24/24
(264/264), all output guards PASS, covering W8/W4/W2, forwarding, K-window and row
group boundaries, tails, cancellation, subnormals and overflow. All 24 rounded
outputs and **every measured stage counter** match the previous pipelined
reference exactly (zero cycle delta). Three available prior exact baselines also
match bits and counters. The independent references use arbitrary precision and
the appropriate rounding order. Detailed fresh logs remain at:
`/home/kioannou/artifacts/lumixed_rounded_unified_smoke_20261126/`.
No application code, benchmark runners or C/C++ harnesses are included here.

## Files and reproduction provenance

- `comparison.csv`: the two completed matched routes and core/context metrics.
- `pipelined/`: unmodified successful route utilization, power, clocks,
  setup/hold, routing, DRC, constraints, retention and connectivity reports.
- `provenance/RegisteredContext.scala`: exact evaluation-context Chisel.
- `provenance/*.tcl`: exact Vivado flow, export, clock integration and guards.
- `provenance.json`: source/evidence hashes, parameter and invocation records.

To reproduce, regenerate the full standalone core and `RegisteredContext` RTL
from the recorded parameters (current source uses only `ROUNDED_ACCUMULATION=true`),
export the standalone synthesis DCP using Vivado `write_edif`, synthesize the
context with the matching core black-box stub, and link using the included flow.
For `vivado_ppa_ooc.tcl`, supply `PPA_MAX_THREADS=8`, `PPA_CORE_EDIF`,
`PPA_EXPECT_LUTS=60891`, `PPA_EXPECT_FFS=6506`, and `PPA_REUSE_LINKED=0` for the
initial linked synthesis. Its arguments are:

```
vivado -mode batch -source provenance/vivado_ppa_ooc.tcl -tclargs \
  RegisteredContext sources.txt include_dirs.txt xcu250-figd2104-2-e clock 20 synth OUTPUT
```

After validating that linked checkpoint/source hashes match, set
`PPA_REUSE_LINKED=1` and replace `synth` with `route` for implementation. The
original invocation is recorded verbatim in `provenance.json`; local paths must
be adapted. Generated RTL, EDIF, DCPs, JARs, simulators and bitstreams are deliberately
not in Git. A new implementation of regenerated final source may yield different
physical results; the post-cleanup equivalence evidence here is Verilator output
and cycle equality, not a newly measured PPA claim.
