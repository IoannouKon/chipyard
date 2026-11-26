# Rounded pipelined ID8: four measured U250 implementations

All four configurations: scaling OFF; FP32 activations/output; runtime signed W8/W4/W2; Rmax=4, Kmax=Nmax=3072; DMA ID8, 8-byte beats, two weight buffers. ROUNDED_ACCUMULATION=true uses the automatic subtotal pipeline register. Optional sparsity, flexible grouping, early release and DMA bursts disabled.

Measured RTL revision: c1d7d78e43f8d46fd08f0ad29afcbe26eb1ca2f9. The compiled source snapshot was verified byte-for-byte against that commit, and private JAR classes against the completed fresh compilation. These are measurements of that revision, not a claim that later production revisions were resynthesized.

Vivado 2023.2; U250 xcu250-figd2104-2-e; 20 ns clock (50 MHz); sequential implementation, up to eight physical CPUs. Corrected same-clock registered observable context, standalone core synthesis followed by exported-netlist linking, with full-core retention checks through routing. Resource and power figures include the wrapper; core hierarchy and context overhead are available in the archived reports.

| B/RF/YS | LUT | FF | DSP | BRAM36/18 | URAM | Dynamic W | Static W | Total W | Setup ns | Hold ns | Setup-estimated MHz |
|---|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 4/4/4 | 63748 | 8280 | 0 | 70/1 | 0 | 0.228 | 2.949 | 3.177 | 1.357 | 0.01 | 53.639 |
| 8/4/4 | 123277 | 12266 | 0 | 110/1 | 0 | 0.402 | 2.953 | 3.354 | 0.424 | 0.003 | 51.083 |
| 8/8/2 | 60955 | 8351 | 0 | 70/1 | 0 | 0.182 | 2.948 | 3.131 | 0.851 | 0.01 | 52.222 |
| 16/4/2 | 127556 | 12480 | 0 | 118/1 | 0 | 0.383 | 2.952 | 3.335 | 0.038 | 0.01 | 50.095 |

All four fully routed and passed setup/hold at 50 MHz. Verilator smoke: 48/48 tests PASS, 576/576 output-element bit matches, with guards and registered write-payload checks. Each configuration covers W8/W4/W2, tails, forwarding and multiple K windows. Smoke tests are not full Qwen benchmarks. Per-test logs, reference/input/output hashes and counters are included in reports.tar.gz.

Power is vectorless estimation at 50 MHz, not measured workload energy. Fmax is a setup-slack-derived estimate, not a verified board maximum. Compare power directly only with matching wrapped evidence. The earlier matched B8/RF8/YS2 unpipelined rounded case in ../Rounded_FP32_U250 used 0.218 W dynamic and 3.166 W total with -5.096 ns setup slack; this pipelined case uses 0.182 W dynamic and 3.131 W total with +0.851 ns setup slack. Dynamic power falls 16.5%; this does not establish the same reduction in workload energy.

Files: results.csv contains all four measured summaries; reports.tar.gz contains raw utilization, power, timing, routing, hierarchy/retention evidence, constraints, commands, configurations, source hashes, registered-context Scala and minimal Tcl provenance. Generated binaries, netlists/checkpoints, application software and unrelated results are omitted.

Complete local reproduction runner and evidence remain at:
/home/kioannou/artifacts/lumixed_pipelined_rounded_id8_sweep_20261126/
Runner command: python3 run_sweep.py start (a completed evidence directory must first be copied to a fresh run directory; the runner deliberately refuses to overwrite results). The archived *.command.json and source_compile.command.json preserve exact tool commands; remap absolute paths when replaying. Toolchain/JARs and the C++ smoke driver remain local, so this compact report archive alone is not a standalone build distribution.
