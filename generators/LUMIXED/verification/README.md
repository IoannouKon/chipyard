# LUMIXED Qwen-shaped Verilator benchmarks

**Current matched comparison: 120/120 group-scaled and 120/120 unscaled matmuls PASS**, zero bit mismatches; 786,432 exact FP32 outputs per mode (1,572,864 total). Both modes use the same current RTL revision, ABI-v2 C harness, inputs, memory model and controller-cycle boundaries. Six smoke tests also passed (96 output elements).

## Files

```text
verification/
├── README.md
├── QWEN_RESULTS_GROUP_SCALED/
│   ├── matmul_stage_cycles.csv
│   ├── total_cycles.csv
│   └── logs/                  # Original evidence, source hashes, legacy baseline
└── QWEN_RESULTS_UNSCALED/
    ├── matmul_stage_cycles.csv
    ├── total_cycles.csv
    └── logs/
```

Each CSV row identifies B, RF, YS, DMA IDs, precision and R. The stage table also identifies projection, K/N, verification counts and input/output hashes. Total tables contain the estimated 196-projection cycles and their stage totals. Raw logs and exact output bytes are compressed in `logs/<configuration>/evidence.tar.gz`; `verilator.log` provides the readable output. `logs/provenance.tar.gz` contains full settings, source hashes, commands, smoke results and the test sources. Input/weight arrays are represented by hashes and their reproducible original generator; locally retained complete run folders preserve those bytes too. No ELF, simulator or build cache is committed.

## Common hardware and arithmetic

Chipyard 1.15.0, FP32 activations, packed signed W8/W4/W2, FP32 outputs. Capacity R=4, K=N=3072; 8-byte DMA beats; two weight buffers; BRAM outputs and performance counters ON. Fixed bank grouping, zero skipping, early weight-buffer release, bursts and other scheduling experiments OFF. INT_BITS=16 (ignored in FP mode), minInputBits=8, minWeightBits=2, WBitWidth=8, OutBitWidth=32, num_filters=1, MAX_WEIGHT_BURST_BYTES=32 (bursts disabled). B/RF/YS/IDs vary in the tables.

Group-scaled: deferred mode ON, aligned mode OFF, FP16 scales, G32 for W8/W4 and G64 for W2. Unscaled: both group modes OFF at elaboration, no scale arrays or group engine, G=0 and START(NULL). The removed legacy integer SCALE parameter was not restored.

Unscaled output = FP32_RNE(sum_k X[r,k] × W[k,n]). Scaled output = FP32_RNE(sum_g scale[g,n] × sum_{k in g} X[r,k] × W[k,n]). References use arbitrary-precision fixed-point integers (2^-149 unscaled, 2^-173 with FP16 scales), rounded once to FP32; no running FP32 reference accumulation. Every element, output guards and simulator exit were checked.

All data are synthetic Qwen3-0.6B-shaped inputs, not native Qwen inference. The exact original activation and weight bytes were reused and checked against the archived input hashes across every configuration.

## Projection estimate and stage accounting

| Projection | X × W | Occurrences |
|---|---|---:|
| Q | 4×1024 × 1024×2048 | 28 |
| K/V | 4×1024 × 1024×1024 | 56 |
| O | 4×2048 × 2048×1024 | 28 |
| Gate/Up | 4×1024 × 1024×3072 | 56 |
| Down | 4×3072 × 3072×1024 | 28 |

Estimated cycles = 28×Q + 56×KV + 28×O + 56×GateUp + 28×Down, using measured same-shape Verilator cycles. These are representative projection estimates across 28 layers, not measured end-to-end inference. Attention, RoPE, normalization, vocabulary head and non-GEMM operations are excluded.

Active total = load_x + generate_brams + load_w_and_select + store_o. The separate load_w and select_accumulate counters overlap and must not be added again. Readback occurs after output-DMA completion.

## Matched scaling overhead

Overhead (%) = 100 × (scaled cycles / unscaled cycles − 1). Both measurements below use the current implementation and same harness; negative numbers mean this measured scaled run took fewer cycles. Controller/cache/DMA timing interactions can be non-monotonic; percentages are cycle measurements, not clock-frequency or energy comparisons.

| Configuration | W | Group-scaled cycles | Unscaled cycles | Overhead % |
|---|---|---:|---:|---:|
| B8_RF8_YS2_ID8_R4 | W8 | 356,713,028 | 356,193,348 | 0.146 |
| B8_RF8_YS2_ID8_R4 | W4 | 123,106,480 | 117,213,768 | 5.027 |
| B8_RF8_YS2_ID8_R4 | W2 | 115,730,216 | 112,581,532 | 2.797 |
| B8_RF8_YS2_ID16_R4 | W8 | 444,157,560 | 439,802,356 | 0.990 |
| B8_RF8_YS2_ID16_R4 | W4 | 128,475,760 | 121,897,244 | 5.397 |
| B8_RF8_YS2_ID16_R4 | W2 | 114,820,496 | 112,396,004 | 2.157 |
| B4_RF4_YS4_ID8_R4 | W8 | 461,851,936 | 469,846,272 | -1.701 |
| B4_RF4_YS4_ID8_R4 | W4 | 119,296,324 | 112,962,248 | 5.607 |
| B4_RF4_YS4_ID8_R4 | W2 | 115,706,696 | 112,529,704 | 2.823 |
| B4_RF4_YS4_ID16_R4 | W8 | 461,243,608 | 469,863,016 | -1.834 |
| B4_RF4_YS4_ID16_R4 | W4 | 117,919,648 | 112,843,780 | 4.498 |
| B4_RF4_YS4_ID16_R4 | W2 | 114,769,648 | 112,260,596 | 2.235 |
| B16_RF4_YS2_ID8_R4 | W8 | 335,077,008 | 316,357,720 | 5.917 |
| B16_RF4_YS2_ID8_R4 | W4 | 115,186,120 | 108,762,724 | 5.906 |
| B16_RF4_YS2_ID8_R4 | W2 | 62,114,220 | 58,884,812 | 5.484 |
| B16_RF4_YS2_ID16_R4 | W8 | 416,011,232 | 408,738,960 | 1.779 |
| B16_RF4_YS2_ID16_R4 | W4 | 114,529,380 | 107,023,196 | 7.014 |
| B16_RF4_YS2_ID16_R4 | W2 | 63,422,184 | 60,498,060 | 4.833 |
| B8_RF4_YS4_ID8_R4 | W8 | 305,294,668 | 298,512,872 | 2.272 |
| B8_RF4_YS4_ID8_R4 | W4 | 74,785,704 | 68,084,380 | 9.843 |
| B8_RF4_YS4_ID8_R4 | W2 | 60,570,188 | 57,380,288 | 5.559 |
| B8_RF4_YS4_ID16_R4 | W8 | 304,145,604 | 298,837,364 | 1.776 |
| B8_RF4_YS4_ID16_R4 | W4 | 75,613,216 | 69,207,460 | 9.256 |
| B8_RF4_YS4_ID16_R4 | W2 | 59,535,952 | 57,114,736 | 4.239 |

## Best unscaled configurations and bottlenecks

- W8: **B8_RF4_YS4_ID8_R4**, 298,512,872 projection cycles. Largest stage: load_w_and_select, 98.27%.
- W4: **B8_RF4_YS4_ID8_R4**, 68,084,380 projection cycles. Largest stage: load_w_and_select, 96.15%.
- W2: **B8_RF4_YS4_ID16_R4**, 57,114,736 projection cycles. Largest stage: load_w_and_select, 96.39%.

## Historical baseline

The previous 120 scaled passes came from pre-API/RoCC-refactor sources. Their original tables and evidence are preserved under `QWEN_RESULTS_GROUP_SCALED/logs/legacy_baseline/`. They are historical measurements, not the scaled controls used for the overhead table above. Full original notes are retained as `baseline_notes.txt`; numerical results were not changed.

The unabridged completed current run, including original inputs, ELF binaries and diagnostics, remains locally under `software/QWEN_UNSCALED_VERIFY/runs/completed_full_evidence_20261122/`. Only the verification presentation was reorganized. No accelerator, API, scheduling or test-runner source was edited by this organization change. The evidence archives retain the original commands, paths and test-source snapshot as executed; original path names in those records refer to the completed run before this reorganization.

