# Edited Gemmini FP32 x W8 Qwen-shaped measurements

Full cases: 10/10 PASS. No completion claimed until all ten pass.

Current optional GEMMINI_EDITED flag; default false. Genuine FP32 activation bits, signed W8 DRAM bytes, FP32 partial sums/output. WS, 4x4 or 8x8, SP32 KiB, ACC4 KiB, 64-bit DMA, 64-byte maximum transaction, 16 in-flight requests per DMA pool. Existing Gemmini read/write DMA engines retained. No mvin/acc scale units, bias or activation function. R=4.

Edited PE contract: RNE32(RNE32(X*W)+partial), specialized 24x8 significand multiplier plus HardFloat AddRecFN. Accumulator combines tile partial sums in FP32. Acceptance was fixed before results: gamma_(3K+2)*sum(abs(XW)) + (3K+2)*2^-149; finite outputs/guards/exit mandatory. Exact-bit, error and ULP statistics vs single-rounded exact-real Python reference reported separately. This is not a claim of bit-identical LUMIXED arithmetic. See acceptance.txt and per-value verification.json.

Invocation cycles use rdcycle around software configuration+HW loops+final fence. Setup measured separately. Controller-active cycles are the sum of seven exclusive MAIN_LD/ST/EX busy combinations; execute_active is an overlapping diagnostic. No overlapping counters added to invocation cycles. LUMIXED counts active FSM stages including stalls, not host configuration/loop gaps. Both Gemmini active and invocation comparisons are reported to expose boundary differences. No physical speedup without matching clock evidence.

Input values match archived LUMIXED W8 synthetic GEMMs; only W column-major to row-major conversion performed. Original and converted hashes plus mapping are saved. Not actual model inference. Projection estimates = 28 Q + 56 KV + 28 O + 56 gate/up + 28 down; other inference ops excluded.

| Array | Invocation projection cycles | Controller-active projection cycles |
|---|---:|---:|
| 4x4_SP32_ACC4_ID16 | 163,487,884 | 163,439,752 |
| 8x8_SP32_ACC4_ID16 | 80,147,088 | 80,097,024 |

Numerical results below distinguish mathematical-reference exact bits from acceptance under the predeclared FP32 rounding bound. A PASS requires every element within that bound, intact guards, a complete payload and simulator exit zero. Relative error excludes exact-zero reference values, for which absolute error is reported. No tolerance was tuned to the results.

| Array | Shape | Result | Accepted / outputs | Exact bits | Max abs error | Max rel error | RMSE | Max ULP |
|---|---|---|---:|---:|---:|---:|---:|---:|
| edited_4x4 | 4x3072 x 3072x1024 | PASS | 4096/4096 | 3513 | 0.00927734375 | 5.893515953747687e-05 | 0.0007158531237390381 | 640 |
| edited_4x4 | 4x1024 x 1024x3072 | PASS | 12288/12288 | 12258 | 0.002685546875 | 6.02029331416326e-07 | 4.3494240764585034e-05 | 6 |
| edited_4x4 | 4x1024 x 1024x1024 | PASS | 4096/4096 | 4085 | 0.001708984375 | 3.63074211590833e-07 | 4.090807073502963e-05 | 4 |
| edited_4x4 | 4x2048 x 2048x1024 | PASS | 4096/4096 | 3879 | 0.00439453125 | 1.486794735200371e-06 | 0.000324496007051647 | 20 |
| edited_4x4 | 4x1024 x 1024x2048 | PASS | 8192/8192 | 8167 | 0.003173828125 | 5.998077016508507e-07 | 6.507386058514396e-05 | 7 |
| edited_8x8 | 4x3072 x 3072x1024 | PASS | 4096/4096 | 3559 | 0.00634765625 | 1.587326782964809e-05 | 0.0005253074989713364 | 192 |
| edited_8x8 | 4x1024 x 1024x3072 | PASS | 12288/12288 | 12258 | 0.001953125 | 4.2450294684639414e-07 | 3.544453904410388e-05 | 6 |
| edited_8x8 | 4x1024 x 1024x1024 | PASS | 4096/4096 | 4087 | 0.00244140625 | 4.6696916929453565e-07 | 4.810150227706334e-05 | 5 |
| edited_8x8 | 4x2048 x 2048x1024 | PASS | 4096/4096 | 3897 | 0.004638671875 | 9.00811705049025e-07 | 0.0002654985449417925 | 12 |
| edited_8x8 | 4x1024 x 1024x2048 | PASS | 8192/8192 | 8170 | 0.001220703125 | 3.348445986217796e-07 | 3.737624729588589e-05 | 5 |

| Array | Projection | Invocation | Setup | Controller union | Load busy* | Store busy* | Execute busy* | PE execute active* |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| edited_4x4 | DOWN | 1,155,178 | 73 | 1,154,942 | 921,538 | 14,957 | 1,154,687 | 958,021 |
| edited_4x4 | GATE_UP | 1,172,924 | 62 | 1,172,648 | 921,846 | 47,086 | 1,171,731 | 973,058 |
| edited_4x4 | K_V | 391,258 | 60 | 391,035 | 307,034 | 14,819 | 390,683 | 324,355 |
| edited_4x4 | O | 773,210 | 61 | 772,998 | 614,212 | 15,058 | 772,742 | 641,603 |
| edited_4x4 | Q | 782,101 | 62 | 781,828 | 614,195 | 30,639 | 781,326 | 648,805 |
| edited_8x8 | DOWN | 570,436 | 73 | 570,179 | 569,651 | 5,344 | 570,013 | 520,779 |
| edited_8x8 | GATE_UP | 580,789 | 62 | 580,496 | 571,899 | 18,163 | 580,329 | 531,089 |
| edited_8x8 | K_V | 188,469 | 60 | 188,260 | 181,547 | 5,549 | 188,086 | 171,620 |
| edited_8x8 | O | 374,868 | 61 | 374,636 | 374,136 | 5,385 | 374,469 | 341,617 |
| edited_8x8 | Q | 378,576 | 62 | 378,281 | 373,437 | 11,342 | 378,115 | 345,265 |

*Busy/active counters overlap and must not be summed. The seven exclusive LD/ST/EX combinations are preserved in matmul_cycles.csv and raw logs. HTIF output transfer and Python verification occur after the measured interval.

| LUMIXED configuration | LUMIXED W8 active projection cycles | Edited Gemmini array | Gemmini active / LUMIXED | Gemmini invocation / LUMIXED |
|---|---:|---|---:|---:|
| B8_RF8_YS2_ID8_R4 | 356,193,348 | 4x4_SP32_ACC4_ID16 | 0.45885 | 0.45899 |
| B8_RF8_YS2_ID8_R4 | 356,193,348 | 8x8_SP32_ACC4_ID16 | 0.22487 | 0.22501 |
| B8_RF8_YS2_ID16_R4 | 439,802,356 | 4x4_SP32_ACC4_ID16 | 0.37162 | 0.37173 |
| B8_RF8_YS2_ID16_R4 | 439,802,356 | 8x8_SP32_ACC4_ID16 | 0.18212 | 0.18223 |
| B4_RF4_YS4_ID8_R4 | 469,846,272 | 4x4_SP32_ACC4_ID16 | 0.34786 | 0.34796 |
| B4_RF4_YS4_ID8_R4 | 469,846,272 | 8x8_SP32_ACC4_ID16 | 0.17047 | 0.17058 |
| B4_RF4_YS4_ID16_R4 | 469,863,016 | 4x4_SP32_ACC4_ID16 | 0.34785 | 0.34795 |
| B4_RF4_YS4_ID16_R4 | 469,863,016 | 8x8_SP32_ACC4_ID16 | 0.17047 | 0.17058 |
| B16_RF4_YS2_ID8_R4 | 316,357,720 | 4x4_SP32_ACC4_ID16 | 0.51663 | 0.51678 |
| B16_RF4_YS2_ID8_R4 | 316,357,720 | 8x8_SP32_ACC4_ID16 | 0.25318 | 0.25334 |
| B16_RF4_YS2_ID16_R4 | 408,738,960 | 4x4_SP32_ACC4_ID16 | 0.39986 | 0.39998 |
| B16_RF4_YS2_ID16_R4 | 408,738,960 | 8x8_SP32_ACC4_ID16 | 0.19596 | 0.19608 |
| B8_RF4_YS4_ID8_R4 | 298,512,872 | 4x4_SP32_ACC4_ID16 | 0.54751 | 0.54767 |
| B8_RF4_YS4_ID8_R4 | 298,512,872 | 8x8_SP32_ACC4_ID16 | 0.26832 | 0.26849 |
| B8_RF4_YS4_ID16_R4 | 298,837,364 | 4x4_SP32_ACC4_ID16 | 0.54692 | 0.54708 |
| B8_RF4_YS4_ID16_R4 | 298,837,364 | 8x8_SP32_ACC4_ID16 | 0.26803 | 0.26820 |

Ratios below 1 mean fewer Gemmini cycles. These are cycle ratios, not physical speedups. Gemmini has separate read/write DMA pools (16 slots per pool) and up-to-64-byte transactions; LUMIXED uses its existing shared DMA source-ID pool. All configurations and measured-boundary differences remain explicit.

Both suites use the default SimDRAM mm_magic memory model: no +dramsim argument. DRAMSim2 is linked by the standard build but is not enabled. The completed-experiment files under logs/ include exact commands, raw Verilator output, test ELF, input/output bytes, expected single-rounded FP32 bytes, per-value Python verification, guards and hashes.

Smoke results (not included in the ten full GEMMs):

| Array | Smoke | Result | Exact bits / outputs |
|---|---|---|---:|
| edited_4x4 | fp32_directed | PASS | 16/16 |
| edited_4x4 | multi_k_chunk | PASS | 260/260 |
| edited_4x4 | partial_tail | PASS | 28/28 |
| edited_4x4 | small_random | PASS | 36/36 |
| edited_8x8 | fp32_directed | PASS | 16/16 |
| edited_8x8 | multi_k_chunk | PASS | 260/260 |
| edited_8x8 | partial_tail | PASS | 28/28 |
| edited_8x8 | small_random | PASS | 36/36 |
| stock_4x4 | stock_gate | PASS | 36/36 |

Standalone emitted FPIntMac RTL: 21,296 vectors covering random FP32 values, product/addition rounding, subnormal/overflow/zero and NaN/Inf behavior. See mac_unit/verification.log; NaNs compare by IEEE class rather than a mandated payload. Stock flag-OFF smoke uses ordinary FP32 weights and is not an INT8 benchmark.

Reproduce on this server:

```sh
cd /home/kioannou/chipyard_1.15.0/generators/LUMIXED/software/GEMMINI_EDITED_QWEN_W8
python3 run_benchmark.py start
python3 run_benchmark.py status
```

The runner builds all simulators before running at most two isolated tests on a two-core affinity mask at reduced priority. Valid existing results are reused only with matching simulator, inputs, output hashes, guards, successful exit and identical C harness. Source hashes are checked before every full test. Exact build/simulation/compile arguments are preserved in builds/ and each case directory.

Source state: Chipyard base c4cbf50c, upstream Gemmini 8c3f9923a44a2fe2c7930587be297d6d4f8c09ca plus the uncommitted optional port archived in sources/gemmini_port.patch. Build/tool/runtime/simulator hashes are in build_provenance.json. This directory does not claim that HEAD already contains those edits.

Full individual-GEMM ratios: gemmini_vs_lumixed_matmuls.csv. Projection-only ratios: gemmini_vs_lumixed.csv. Estimates use actual invocation/active cycles multiplied by 28/56/28/56/28, not a Python performance model. They exclude attention, normalization, RoPE, nonlinearities and the vocabulary head. Diagnostic cycle-capped traces are not benchmark failures or completed GEMMs.

Additional exact FP32 accumulation verification:

**65,536/65,536 outputs bit-exact to Gemmini tiled FP32 arithmetic; 0 mismatches.**

For these captured inputs, X is exactly an integer multiple of 2^-12 with |X|<=1. Products and within-DIM PE sums fit the FP32 significand exactly (8*4096*128 < 2^24). An independent integer reference forms each exact DIM-wide dot, then RNE-rounds every accumulator addition to 24 significant bits. No floating-point reduction is used in this reference. The result is additionally compared bit-for-bit with all hardware outputs. This explains differences from LUMIXED-style single-rounded exact sums without relaxing the original criterion. See fp32_accumulation_verification.csv and each case tiled_reference_verification.json.

```sh
python3 verify_tiled_reference.py
```

To execute a fresh replay rather than reuse existing verified results, choose a new empty destination (compiled simulators can still be reused):

```sh
GEMMINI_EDITED_RESULTS_DIR=/tmp/gemmini-qwen-replay python3 run_benchmark.py start
GEMMINI_EDITED_RESULTS_DIR=/tmp/gemmini-qwen-replay python3 run_benchmark.py status
```

Git snapshot scope: this results-only commit preserves measured counters, raw
Verilator logs/output payloads, input/output/reference data, exact verification,
hashes and build commands. Compiled `*.riscv` executables and intentionally
cycle-capped diagnostic traces remain local; their recorded commands/hashes are
retained. The standalone benchmark Python runner under
`software/GEMMINI_EDITED_QWEN_W8/` remains local and is not included in this
results-only commit. Individual C/assembly test sources are preserved with each
case. The hardware port was archived separately in commit `64406954`.
