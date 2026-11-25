# Baseline-compatible Gemmini ID8: completed U250 implementation

Both FP32 x signed INT8 -> FP32 configurations completed fresh synthesis,
placement and routing on Vivado 2023.2, U250 `xcu250-figd2104-2-e`.
Completed 2026-11-25 10:05 EET. No cycle benchmarks were rerun.

Settings: WS-only, no arithmetic scaling, SP32 KiB/ACC4 KiB, four SP banks,
one ACC bank, 64-bit DMA, 64-byte maximum transactions, ID8 per DMA pool,
`acc_read_small_width=false`, `has_dw_convs=false`. Existing ID16 comparison
selectors remain unchanged. Configuration sources and patch are in
[Gemmini_Edited](../../Gemmini_Edited/README.md).

| Result | 4x4 | 8x8 |
|---|---:|---:|
| Synthesis | PASS | PASS |
| Fully routed nets | 88,092/88,092 | 191,320/191,320 |
| Routing errors | 0 | 0 |
| Global setup slack ns | -0.051 | +0.243 |
| Global hold slack ns | -2.325 | -2.548 |
| Internal setup slack ns | -0.051 | +0.243 |
| Internal hold slack ns | +0.002 | +0.009 |
| Internal setup-based Fmax MHz | 49.873 | 50.615 |
| Post-route LUTs (including LUTRAM) | 54,494 | 130,038 |
| Post-route registers | 26,464 | 46,210 |
| Post-route DSPs | 169 | 224 |
| RAMB18 / RAMB36 / URAM | 64 / 0 / 0 | 0 / 0 / 0 |
| Post-route dynamic W | 0.209 | 0.277 |
| Device static W | 2.948 | 2.949 |
| Total W | 3.157 | 3.227 |

Both statuses are **ROUTED_TIMING_FAIL**: successful routing does not establish
timing closure. The 8x8 passes internal setup/hold at 50 MHz; the 4x4 narrowly
misses setup. Neither passes overall hold. Fmax uses 1000/(20-internal_setup_slack)
and is a setup-only estimate, not a verified usable system frequency.

Actual constraints: `ppa_clk` 20 ns (50 MHz), 50% duty cycle, always-enabled
BUFGCE, zero external input/output delays, reset false path. Worst hold paths
are input-boundary paths into distributed-memory queues: TileLink response
`auto_spad_id_out_d_bits_opcode[2]` (4x4) and command `io_cmd_bits_rs1[40]` (8x8).
Real system interface timing is needed to resolve those OOC boundary results.
Both check_timing reports show no unclocked registers or unconstrained internal
endpoints. DRC has DSP pipelining warnings and RTSTAT-10: 194 nets without
routable loads, including scratchpad queue outputs; zero routing errors.
Internal setup-critical paths run from TLB arbitration to DMA writer register
enables, with approximately 85-86% routing delay.

Power is vectorless at constrained 50 MHz, Medium confidence, without SAIF/VCD.
Device static power includes the FPGA. Historical full-system ZCU106 FP16
measurements are a separate implementation environment.

`results.csv` is the compact summary. `reports.tar.gz` preserves original
synthesis/place/route utilization, timing, boundary diagnostics, DRC, clock,
power, Vivado logs, source hashes, configuration overlays, exact commands,
parameter assertions, flow scripts and runner. Use utilization reports as the
authoritative resource counts; raw primitive-pattern counters in metrics JSON
can overcount DSP subcomponents. Large DCP files remain local with hashes in
`local_checkpoint_sha256.json`.

Original evidence: `/home/kioannou/artifacts/gemmini_baseline_id8_ppa_20261125/`.
To reproduce, extract the archive there (or update runner paths), then:

```sh
python3 run_ppa.py start
python3 run_ppa.py status
```

The runner records absolute tool/package locations and reuses the supplied PPA
infrastructure. It needs the local cached toolchain JARs and referenced Scala
sources; the report archive does not bundle those tool dependencies.
