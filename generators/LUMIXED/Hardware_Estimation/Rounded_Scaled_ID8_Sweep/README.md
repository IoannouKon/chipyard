# Routed rounded group scaling: four ID8 designs

Four complete U250 implementations, all with scaling ON and ROUNDED_ACCUMULATION=true (automatic former Option B). FP32 activations/output, exact FP16 scale widening, single-round FP32 FMA. Runtime signed W8/W4/W2 and G32/64/128. GROUP_SCALE_DEFERRED=true, GROUP_SCALE_ALIGNED=false, GROUP_SCALE_YS=parallel_rows, capacities R4/K3072/N3072, DMA8-byte beats, two weight buffers, counters enabled, optional sparsity/bank regrouping/bursts/early release disabled.

Measured source: experimental commit a6c24594e8198d00558e76a66256ac9cecf23bf1. The same five hardware files are integrated alongside this report; there is no Option A selector. Default ROUNDED_ACCUMULATION=false retains exact arithmetic; setting it true without scaling retains the existing pipelined FP32 subtotal/add path.

Vivado2023.2, xcu250-figd2104-2-e, target20ns/50MHz. Corrected full-core observable registered same-clock wrapper; standalone synthesis/export/link, full-core retention checks, placement, physical optimization and routing. No artificial delay padding, frozen physical optimization or false paths on synchronous interfaces. All four passed setup and hold at50MHz.

| B/RF/YS | ID | LUT | FF | DSP | BRAM36/18 | URAM | Dynamic W | Static W | Total W | Setup ns | Hold ns | Setup-est. MHz |
|---|---:|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 4/4/4 | 8 | 80151 | 14534 | 10 | 199/1 | 0 | 0.344 | 2.952 | 3.296 | 4.313 | 0.01 | 63.747 |
| 8/4/4 | 8 | 158429 | 23289 | 10 | 239/1 | 0 | 0.487 | 2.955 | 3.443 | 3.74 | 0.014 | 61.501 |
| 8/8/2 | 8 | 78794 | 14099 | 6 | 199/1 | 0 | 0.333 | 2.952 | 3.285 | 3.741 | 0.004 | 61.504 |
| 16/4/2 | 8 | 159635 | 22935 | 6 | 247/1 | 0 | 0.444 | 2.954 | 3.398 | 4.452 | 0.012 | 64.317 |

Resource/power figures include the registered wrapper. Power is post-route vectorless estimation at50MHz (Medium confidence), not measured workload energy. Fmax is derived from setup slack at a20ns constraint, not a verified maximum operating clock. Device static power is included in total power. Inspect hierarchy reports for accelerator/core and context costs.

The four rounded-OFF counterparts are already archived in ../Rounded_Pipelined_ID8_Sweep/. Both wrappers have the same64KiB SRAM, registered8-entry TileLink manager, observable write data and same clock/integration strategy. ON necessarily adds scale commands/pointer/group configuration; its host random-N generator is bounded to8 to fit the small context memory, unlike the earlier OFF wrapper. Therefore top-level power differences include these context changes as well as the genuine scaling cost. Core capacities and standalone arithmetic/RAM retention are preserved. Do not describe this comparison as an isolated FMA-energy measurement or mix these wrapped counts with bare-core historical reports.

48/48 Verilator smoke tests passed (480/480 FP32 elements), with independent arbitrary-precision fragment/FMA references, guards and captured write-payload checks. Each geometry covers W8/W4/W2, G32/64/128, tails, forwarding and multi-window cases; YS2 geometries also validate both R4 row groups. Observed FIFO metadata establishes association order, validates independent per-bank/group coverage, and never supplies arithmetic expected values. VPI initializes host memory/configuration only; all arithmetic executes in accelerator RTL.

Full-core retention checks count physical DSP48E2 primitives (not their DSP subparts), require the expected FP32 FMA instances, exact linked LUT/FF/RAM footprint, and retained arithmetic/observable data paths throughout optimization/routing. No low-utilization pruned run is accepted. Initial packing and checker failures were corrected before these final runs; failed attempts remain local and are not mixed into the completed evidence.

## Software tests integrated with the hardware

build.sh/run_param_test.sh propagate ROUNDED_ACCUMULATION into the test metadata; explicit conflicting flags are rejected. prepare_test_vectors.py keeps the arbitrary-precision exact mathematical reference unchanged. For rounded tests the bare-metal harness emits every output as raw FP32 bits and does not print a numerical PASS itself. run_param_test.sh invokes the host checker and requires complete unique outputs, successful simulation and explicit numeric acceptance against the exact baseline.

Generic software acceptance is deliberately NOT called bit-exact rounded-order verification: FMA association depends on fragment retirement order, which this generic C runner does not capture. The dedicated smoke evidence above verifies the actual rounded order bit-for-bit. Exact-mode software comparisons remain unchanged. Rounded acceptance uses abs(error)<=ROUNDED_ATOL+ROUNDED_RTOL*abs(exact); both defaults are0. Nonzero limits must be selected explicitly (e.g. ROUNDED_ATOL=1e-5 ROUNDED_RTOL=1e-4 is a user-selected numerical test, not a validated LLM-quality threshold). Unexpected nonfinite outputs and incomplete/duplicate logs fail validation. Host-side arithmetic avoids requiring CPU FPU instructions for the new check.

Integration checks:30 exact-vector cases unchanged across INT8/INT16/FP16/FP32 and scaled formats; host checker positive/negative cases; actual captured FMA payload acceptance; four RISC-V bare-metal cross-compiles (rounding ON/OFF, scaling ON/OFF); inconsistent rounding macro rejection. No new full Rocket/RISC-V Verilator run was performed for these software-only logging/checker changes. API and RoCC commands are unchanged.

## Evidence and replay

results.csv contains all four actual routed summaries. reports.tar.gz contains final raw utilization, hierarchy, clocks, constraints, routing/timing/power, retention/reachability reports, source hashes/configurations, reproduction commands, corrected wrapper Scala/Tcl, smoke verification/hashes, test reference/harness, and software integration checks. Generated executables, object files, JARs, EDIFs, DCPs and large memory dumps are excluded.

Complete local runner/evidence: /var/tmp/lumixed-rounded-scaling.3q0lokdm/rounded_on_ppa_20261127/; status: python3 run_sweep.py status. The script intentionally refuses overwriting completed runs: copy/remap it and its required toolchain to a fresh directory for replay. Source revision and per-command JSON identify exact inputs/tool options; all caches/builds in this campaign were on /var/tmp. Remap absolute paths when reproducing elsewhere. Archived OFF evidence is preserved without modification.
