# LUMIXED Python cycle model v8.1

`Model.py` is self-contained: the original INT16/FP16 event simulator, the latest validated FP32 predictor, its calibration coefficients, and calibration/cross-validation routines are in this one Python file. No sibling module or calibration JSON is required for ordinary predictions. Python 3.10+ and the standard library suffice.

FP32 is an **empirical stage-calibrated predictor**, not a cycle-exact TileLink/cache simulation. The accuracy below is prediction error (MAPE), not numerical-output error or an accelerator speedup.

## Usage

```bash
python3 Model.py --r 4 --k 1024 --n 2048 --b 8 --rf 8 --ys 2 --id 8 --w 8 --scale off
python3 Model.py --r 4 --k 1024 --n 2048 --b 8 --rf 8 --ys 2 --id 8 --w 4 --scale on
```

```python
from Model import compute_cycles

# FP32 activations, signed W8, FP32 outputs, no group scaling.
p = compute_cycles(4, 1024, 2048, 32, 8, 8, 2, 8,
                   factor=8, activation='fp32', group_scale_mode='off')
print(p['total_cycles'], p['predicted_stages'])

# Existing INT16/FP16 calls keep the original v7 event-based implementation.
legacy = compute_cycles(1, 32, 32, 16, 4, 4, 1, 8,
                        factor=4, activation='fp16')
```

Mapping: `XS=B`, `factor=RF`, `YS=parallel rows`, `ID=shared DMA source slots`. `MODEL_VERSION` identifies FP32 v8.1; `LEGACY_MODEL_VERSION` and legacy result metadata retain `controller-events-v7`. A separate `predict(r,k,n,b,rf,ys,ids,w,scaled=False)` FP32 interface is also available. The legacy signature is preserved for introspection; new FP32 options are handled by the dispatcher.

## Validated domain

- R=4, FP32 activations, signed W8/W4/W2, FP32 outputs.
- Eight configurations: B8/RF8/YS2, B4/RF4/YS4, B16/RF4/YS2 and B8/RF4/YS4, each at DMA IDs 8 and 16.
- 8-byte DMA beats, two weight buffers, BRAM outputs; fixed bank grouping and dense selection. Optional zero skipping, bursts and early release are OFF.
- Scaling OFF, or **deferred FP16 group scaling with G32 for W8/W4 and G64 for W2**, matching the archive. G128 and aligned group scaling are not validated.
- The five synthetic Qwen3-0.6B-shaped GEMMs below, with the archived mm_magic environment. These are projection benchmarks, not actual Qwen inference.

| Projection | R×K×N | Shape-holdout MAPE |
|---|---|---|
| Q | 4×1024×2048 | 0.679% |
| K_V | 4×1024×1024 | 6.745% |
| O | 4×2048×1024 | 2.408% |
| GATE_UP | 4×1024×3072 | 0.901% |
| DOWN | 4×3072×1024 | 3.415% |

Unknown hardware and out-of-bounds shapes/group sizes are rejected by default. `predict(..., allow_extrapolation=True)` or CLI `--allow-extrapolation` explicitly enables experimental fallback estimates. This does not validate new DMA IDs, arbitrary R, group sizes, alignment, tails, memory systems or hardware geometries. The FP32 model currently requires YS≤R. Unsupported legacy memory/trace overrides for FP32 fail explicitly; the original INT16/FP16 options remain available.

## Accuracy and validation

All **240 archived measurements** were checked against original JSON and raw simulator counter lines: 240 PASS, zero simulator failures, guards PASS, zero bit mismatches. All **1,572,864 archived output elements** match their reference payload bytes. Fifteen input/weight hash pairs are reused across configurations and modes. Generation is analytically exact in **240/240** cases.

| Holdout, 240 predictions each | Supplied v8 MAPE | Updated v8.1 MAPE |
|---|---|---|
| shape | 2.830% | 2.830% |
| configuration | 5.361% | 5.361% |
| geometry | 34.622% | 8.051% |

- **Shape holdout:** five folds, train 192/test 48; all configurations, precisions and modes for one K/N shape are held out together.
- **Configuration holdout:** eight folds, train 210/test 30; the same B/RF/YS remains in training at the other DMA ID. This is not new-geometry validation.
- **Geometry holdout:** four folds, train 180/test 60; both DMA-ID variants and both modes of the held-out B/RF/YS are excluded.

There are eight configurations but **only four distinct geometries** and five distinct shapes. Rows share inputs and are not 240 independent workload distributions. Shape and configuration predictions are identical to supplied v8; only the unseen-geometry fallback improved. Unknown geometry stays guarded despite its improved mean error.

| Precision | Shape holdout | Configuration holdout | Geometry holdout |
|---|---|---|---|
| W8 | 6.674% | 12.475% | 16.256% |
| W4 | 1.472% | 2.571% | 6.452% |
| W2 | 0.343% | 1.038% | 1.445% |

| Scale mode | Shape holdout | Configuration holdout | Geometry holdout |
|---|---|---|---|
| scaled | 2.872% | 5.382% | 8.377% |
| unscaled | 2.787% | 5.340% | 7.725% |

Shape-held-out median APE is 0.622%; maximum 32.554%. Geometry-held-out median is 4.455%; maximum **42.777%**. The all-data training MAPE is 0.975%; it is an in-sample diagnostic, not validation. No result here supports a universal 2.83% error bound or simultaneous generalization to unseen shapes and hardware.

| Exclusive stage | Shape-holdout MAPE | Geometry-holdout MAPE |
|---|---|---|
| load_x | 2.460% | 18.672% |
| generate_brams | 0.000% | 0.000% |
| load_w_and_select | 2.901% | 8.034% |
| store_o | 6.516% | 5.296% |

`total = load_x + generate_brams + load_w_and_select + store_o`.

The overlapping `load_w` and `select_accumulate` diagnostics are not summed into total and are not separately predicted. Weight prefetch already overlaps generation. In scaled runs, scale preload increments the load-X counter; that cost belongs to `load_x` here.

## What v8.1 changes

The known-configuration and same-geometry fits remain unchanged. The previous generic fallback omitted explicit geometry-dependent work; v8.1 fits the following work features when a genuinely unseen geometry is explicitly requested:

- Load X: constant, FP32 input beats, activation-row/window handoffs, FP16 scale beats.
- Combined W/select: constant, dense selection inputs, packed weight beats, output-column/window handoffs.
- Store: constant and output elements.
- Generation: analytic per-bank span × `(2^(Wbits−2)+1)` across K windows and row groups; no fitted coefficient.

`K_window = B×RF×2^(8−Wbits)`, `row_groups = ceil(R/YS)`. Dense selection inputs are row_groups×N×the sum of per-window bank spans. A dense-selection lower bound is enforced. Regression uses the supplied normalized ridge solver with fixed regularization 1e-7; no configuration/shape cycle lookup table is used.

The work counts follow `LUMIXED_Top.scala` generation, buffer readiness/release, selection and scale-preload control, plus the word/bank-limited reads in `LUMIXEDTopHelpers.scala`. They explain why an unchanged byte count can take different cycles across geometries. They do not replace memory arbitration with an exact event model.

Exploratory ID-pressure terms were evaluated but not adopted. In a nested check, each outer fold held out one complete geometry; inner folds used only the remaining geometries to compare the simple fallback with more complex alternatives. All four inner selections preferred this simpler feature set; outer MAPE was 8.051%. This is evidence within a small reused archive, not a fresh independent RTL campaign.

## Remaining W8 errors

Across these archived measurements, the combined W/select stage accounts for 97.49% of summed W8 cycles. About 77.91% of that stage lies above the dense input-feeding floor. This excess includes pipeline/control overhead, weight readiness, DMA/cache stalls and group-engine backpressure; it must not all be called DMA latency. W2's corresponding excess is 0.65%, so its timing is much closer to dense selection throughput.

Two reserved weight buffers allow request issuance to advance before earlier responses finish. Final response delivery establishes readiness; the final synchronous read capture releases the consumed buffer while tagged results still retire. Scaled mode also gates selection on `groupEngine.canIssue`. Bytes alone do not capture these interactions.

The archive contains aggregate counters but not complete DMA address/response/LLC arbitration traces. Cache-line boundaries, strides, source-credit reuse and shared return service remain plausible residual causes; their separate latencies cannot be established from these counters. No missing RTL fix was demonstrated, and no hardware was changed.

Ten largest **shape-held-out** errors (out-of-fold, not training fit):

| Configuration | Scale | W | Shape | Measured | Predicted | APE |
|---|---|---|---|---|---|---|
| B4_RF4_YS4_ID8_R4 | OFF | 8 | K_V | 1,000,430 | 1,326,111 | 32.554% |
| B4_RF4_YS4_ID16_R4 | OFF | 8 | K_V | 1,001,377 | 1,325,245 | 32.342% |
| B16_RF4_YS2_ID8_R4 | ON | 8 | K_V | 811,232 | 633,082 | 21.960% |
| B8_RF4_YS4_ID8_R4 | OFF | 8 | K_V | 676,133 | 802,077 | 18.627% |
| B8_RF4_YS4_ID16_R4 | OFF | 8 | K_V | 678,458 | 802,122 | 18.227% |
| B8_RF8_YS2_ID8_R4 | ON | 8 | K_V | 819,707 | 965,280 | 17.759% |
| B8_RF4_YS4_ID16_R4 | ON | 8 | K_V | 689,034 | 811,318 | 17.747% |
| B8_RF4_YS4_ID8_R4 | ON | 8 | K_V | 693,090 | 815,350 | 17.640% |
| B8_RF8_YS2_ID16_R4 | ON | 8 | K_V | 974,294 | 1,129,110 | 15.890% |
| B8_RF8_YS2_ID16_R4 | OFF | 8 | K_V | 959,895 | 1,108,894 | 15.522% |

The worst is the unscaled B4/RF4/YS4/ID8 K/V case: combined stage 972,509 measured versus 1,298,176 predicted, explaining almost all of the total error. Geometry extrapolation can still miss by 42.777%; do not select an untested design solely from this model.

## Reproduce calibration and cross-validation

From the Chipyard root, using the existing archived measurements:

```bash
mkdir -p /tmp/lumixed-model-v8.1-validation
python3 generators/LUMIXED/Performance_Model/Model.py --validate \
  --chipyard "$PWD" \
  --out /tmp/lumixed-model-v8.1-validation/calibration.json \
  --report-dir /tmp/lumixed-model-v8.1-validation \
  --include-cv-rows
```

This reruns all three holdouts and writes accuracy JSON, held-out per-stage prediction CSVs and an optional external calibration file. It does not rewrite the embedded coefficients or any RTL/Verilator/Vivado data. Fit output is not used as a target when scoring held-out rows. Baseline v8 comparison figures and nested-selection evidence were generated during the external audit; only this model and README are committed.

Calibration sources:

- [Group-scaled stage counters](../verification/QWEN_RESULTS_GROUP_SCALED/matmul_stage_cycles.csv), SHA256 `177bce7018f7612c8bdbc48c8ebc8a7d16cdbb8e4c502838b2f230baffb6ee77`.
- [Unscaled stage counters](../verification/QWEN_RESULTS_UNSCALED/matmul_stage_cycles.csv), SHA256 `dbe355784e6157d1933923c7f4e1b0cd1f384021a6e457632054753d715f7423`.
- Benchmark source revision: Chipyard `6bffd7742b6e5e12ab08503215801ce5a31da324`.
- Original event model: IoannouKon/LUMAX `7b6e6c8c9f8484693180fe4b5806f4a58ba6984d`, `Performance Modeling/Model.py` (v7).
- FP32 extension/calibration: supplied `LUMIXED_Cycle_Model_FP32_v8.zip`, validated and extended to v8.1.

The supplied calibrator retains its original upper-middle median and floor-index P95 convention; headline MAPE is independently reproduced. Medians quoted in this README use the standard median.

## Compatibility checks performed

- Standalone model matches all 240 validated FP32 predictions, including metadata, and reproduces all three reported cross-validation MAPEs.
- All 1,152 INT16/FP16 parameter combinations match complete original-v7 result dictionaries. Public FP32 calls pass for all six precision/mode combinations.
- External integration tests also covered signatures, extrapolation guards, traces, DMA replay and module-path isolation.
- The old regression suite had 24/30 passing with six failures **both before and after** the FP32 integration; one expected version v5. Those expectations were not rewritten to force a pass.
- No new Verilator/Vivado simulation or synthesis was launched for this model validation. Existing hardware jobs and archived results were left untouched.
