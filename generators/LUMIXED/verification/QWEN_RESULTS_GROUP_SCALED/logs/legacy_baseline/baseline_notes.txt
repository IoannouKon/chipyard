# Qwen3-shaped Verilator results

Chipyard 1.15.0 / LUMIXED. Synthetic matrices, not captured Qwen inputs and not full-model inference.

FP32 activations × signed W8/W4/W2; FP16 group scales; deferred scaling ON. G=32 for W8/W4, G=64 for W2. BRAM outputs ON; zero skipping, flexible grouping, early buffer release, DMA bursts and integer scaling OFF.

R=4; hardware capacity R=4, K=N=3072; 8-byte DMA beats; two weight buffers; performance counters ON. B/RF/YS/IDs vary below.

| Design | IDs | Exact tests | W8 projection cycles | W4 projection cycles | W2 projection cycles |
|---|---:|---:|---:|---:|---:|
| B8/RF8/YS2 | 8 | 15/15 PASS | 364,114,604 | 124,794,684 | 115,750,964 |
| B8/RF8/YS2 | 16 | 15/15 PASS | 438,715,116 | 125,490,596 | 114,884,644 |
| B4/RF4/YS4 | 8 | 15/15 PASS | 479,209,836 | 119,302,232 | 115,727,192 |
| B4/RF4/YS4 | 16 | 15/15 PASS | 477,936,200 | 117,888,820 | 114,786,028 |
| B16/RF4/YS2 | 8 | 15/15 PASS | 385,142,016 | 116,472,832 | 62,096,468 |
| B16/RF4/YS2 | 16 | 15/15 PASS | 409,457,860 | 112,218,876 | 63,108,612 |
| B8/RF4/YS4 | 8 | 15/15 PASS | 260,320,592 | 77,951,300 | 60,571,504 |
| B8/RF4/YS4 | 16 | 15/15 PASS | 259,321,216 | 72,981,776 | 59,604,356 |

These results validate the pre-API/RoCC-refactor hardware and C adapter. The final configuration used a frozen copy with the same source hashes and compiled assembly as the first 105 tests; the newer local API/RoCC files were not substituted. Full source hashes and the elaborated configuration are recorded in each design log.

## How to read these results

8 completed configurations, 120 exact-reference passes; no failures or pending cases.

- `configurations.csv`: complete explicit build choices for each archived design.
- `matmul_cycles.csv`: measured cycles per stage, dimensions, precision and exact-match counts for each matmul.
- `projection_estimates.csv`: estimated total and stage cycles for the 196 projections at R=4.
- `logs/<design>/verilator.log`: readable simulator output, configuration, commands and source/input/output hashes.
- `logs/<design>/raw_verilator_logs.tar.gz`: all 15 original simulator logs, including unmodified binary output payloads.

## Shapes and projection estimate

| Case | X × W | Occurrences across 28 layers |
|---|---|---:|
| Q | 4×1024 × 1024×2048 | 28 |
| K/V | 4×1024 × 1024×1024 | 56 |
| O | 4×2048 × 2048×1024 | 28 |
| Gate/Up | 4×1024 × 1024×3072 | 56 |
| Down | 4×3072 × 3072×1024 | 28 |

Estimated projection cycles = 28×Q + 56×K/V + 28×O + 56×Gate/Up + 28×Down. These reuse representative same-shape timings; they are not measurements of all layers or a complete prompt execution. Attention, vocabulary head, normalization, RoPE, activations, tokenization and CPU overhead are excluded.

Total active cycles = load_x + generate_brams + load_w_and_select + store_o. The separate load_w and select_accumulate counters overlap and must not be added again. No time or energy estimates are implied without a matching implemented clock/power result.

Only completed full-size runs are archived. Saved raw outputs were checked again against the saved exact Python outputs, and raw stage counters were checked against the CSV values during export. Native llama.cpp numerical agreement is not claimed.
