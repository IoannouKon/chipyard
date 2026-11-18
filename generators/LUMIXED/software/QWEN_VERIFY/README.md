# QWEN_VERIFY

Replay the saved real Qwen3-0.6B llama.cpp matmuls in LUMIXED Verilator.
Only this folder is written. Existing Config.scala, build.sbt, Linear-sw.c,
build.sh, and the original simulator are not edited.

The default dataset is E:/Storage/Qwen3MatmulCaptures, accessed in WSL as
/mnt/e/Storage/Qwen3MatmulCaptures. It contains the actual four-token prefill
"The sky is blue", not synthetic activation inputs.

## Commands

From this folder in WSL:

    ./run.sh plan --b 8 --rf 4 --ys 2 --dma-ids 16 --w 8 4 2 --r 4
    ./run.sh build 4 4 1
    ./run.sh 4 4 1 --weights 8 4 2
    ./run.sh 4 4 2 --weights 4 --cases Q K_V O GATE_UP DOWN

Named flags --b, --rf, --ys, --dma-ids, --w and --r select the configuration.
For example:

    ./run.sh --b 8 --rf 4 --ys 2 --dma-ids 16 --w 8 4 2 --r 4

The three legacy positional values are **B RF YS**. The normal run defaults to all
five transformer projection shapes and W8/W4/W2.

Optional controls:

    --r 4                 # actual captured rows: 1..4, default 4
    --captures PATH       # input capture package, read-only
    --cases K_V           # run only selected shapes
    --weights 2           # select one or more weight precisions
    --timeout 7200        # seconds per simulation
    --max-cycles 1000000000

R cannot exceed the captured input rows; activations are never repeated to
invent a longer prompt. New captures are required for R>4.

A quick integration check uses a crop of real input/weight data:

    ./run.sh smoke 4 4 1 --weights 8 4 2

Smoke uses R x 32 x 4 and checks the exact arithmetic reference only. Cropping
K changes the dot product, so it does NOT compare against the original full-K
llama.cpp output and produces no full-model cycle estimate.

## Hardware and isolation

FP_ACTIVATIONS=true, FP_BITS=32, FP16 group scales, FP32 outputs, deferred
group scaling, SRAM outputs, signed W8/W4/W2, 8-byte DMA, configurable DMA IDs (default 2), 2 weight
buffers. Zero skipping, flexible grouping, burst experiments and global
integer scaling are disabled.

G comes from the capture: Q8_0/Q4_0 use 32; Q2_0 uses 64. Q2 weights/scales
were losslessly sign-converted when captured. Scale padding occurs only at
the complete array's end, with 8-byte alignment.

A folder-local Scala configuration overrides LUMIXEDParamsKey. It is compiled
against the existing Chipyard assembly jar and added to a private generator
jar. No SBT build runs in the original repo. The normal simulator capacity is
K=N=3072 and Rin=max(R,YS); it also serves smoke runs. Each B/RF/YS/DMA_ID gets a
separate cache keyed by hardware sources, assembly jar and capacities.

Build outputs, temp files, Java caches, DRAMSim copies, local C snapshots,
binaries, payloads and reports stay under this folder. Existing toolchain,
assembly jars, compiler jars and capture package are read-only dependencies.
The C adapter is copied locally, its test macros are changed locally, and
the new harness calls its existing group-scale/preload/matmul API.

The runner links the existing soft-float LP64 HTIF runtime and Chipyard's
stock atexit workaround, copied inside this folder. It never calls the
existing run_param_test.sh because that script edits its source in place.

## Saved comparison results

Each case produces:

- input.bin, weights.bin, scales.bin: exact selected LUMIXED inputs.
- output.llamacpp.f32.bin: original native CPU output (full-K runs only).
- output.exact.f32.bin: independent Python arbitrary-precision group-scaled
  sum, rounded once to FP32.
- output.lumixed.f32.bin: actual accelerator DMA output from Verilator.
- verilator.raw.log: original simulator output, including binary payload.
- compile/simulation commands and local source snapshots.
- report.json and report.md at the run root.

The host compares every raw FP32 result bit, and also reports max absolute,
relative, RMS and ULP errors against llama.cpp. Optional --atol/--rtol add a
separately labelled tolerance comparison; they do not replace the strict bit
comparison or change the exact RTL reference.

Exit codes:

- 0: every requested RTL exact-reference comparison passes, and every
  applicable native llama.cpp comparison is bit exact.
- 1: a build, simulation, output guard or RTL exact-reference check fails.
- 2: RTL matches its exact arithmetic contract but native llama.cpp bits differ.

Native kernels can internally quantize FP32 activations and round partial
sums. A numerical difference is therefore possible with entirely compatible
input formats. The runner reports it, rather than calling it a hardware PASS
against llama.cpp.

## Cycle formula and the image's operation counts

There are 28 Qwen3 layers, with 7 weight projections in each layer.

| Case | Activation x weight | Occurrences |
|---|---|---:|
| Q | [R,1024] x [1024,2048] | 28 |
| K_V | [R,1024] x [1024,1024] | 56 |
| O | [R,2048] x [2048,1024] | 28 |
| GATE_UP | [R,1024] x [1024,3072] | 56 |
| DOWN | [R,3072] x [3072,1024] | 28 |

For each measured matmul:

    C = load_x + generate_brams + load_w_and_select + store_o

Weight loading and selection overlap; their individual counters must not be
summed again. Group-scale preload/drain is included in controller stages.

For all 196 transformer weight projections:

    C_projections = 28*C_Q + 56*C_K_V + 28*C_O
                  + 56*C_GATE_UP + 28*C_DOWN

The report also expands Key/Value and Gate/Up into separate 28-occurrence rows
as in the supplied image. Totals are withheld until all five full dimensions
for that precision pass the exact RTL check. Native bit differences remain
visible alongside that hardware validation.

This is a weighted estimate from representative layer-0 shape measurements,
not an analytic prediction of DMA/cache stalls and not a full-model execution.
It assumes other layers and same-shape projections have the representative
cost. Attention QK/AV, normalization, RoPE, softmax, SiLU, CPU overhead,
tokenization and build time are excluded.

## Vocabulary head

The default estimate excludes [R,1024] x [1024,151936], which appears once.
Q4_0/Q2_0 models keep that tensor in Q6_K; the suite explicitly reports that
format as unsupported, without converting or inventing W4/W2 weights.

--include-head enables the compatible Q8_0 head and lists incompatible heads
as excluded. That build requires a much larger output/scale memory capacity.
A model total INCLUDING the head is withheld unless it has been measured.
Even then, it is a weight-projection estimate, not end-to-end inference time.

## Validation

    python3 test_runner.py

All 15 full-size projection cases have now completed; see the requested suite below.

## Results from implementation checks

B=4, RF=4, YS=1, R=4:

- Verilator integration smoke: W8/W4/W2 passed the exact reference.
- The bulk HTIF payload exporter passed an additional W2 smoke check.
- Full captured W2 K/V case [4,1024] x [1024,1024]: 4,096/4,096 exact
  Python-reference outputs matched. Measured active cycles: 1,085,641.
- That full case had 4,096/4,096 bit differences against native llama.cpp:
  max absolute error 0.005180925130844116, RMSE 0.0011579278161826365.
  This is explicitly NOT a native bit-exact PASS.
- The later requested 15-case suite completed; see below.
- Six host behavior tests passed. Hashes of existing LUMIXED files outside
  this folder and the shared assembly jar were unchanged.

Detailed evidence: VALIDATION.json and runs/20261118-181110_B4_RF4_YS1_R4/report.md.

## Parallel and resumed runs

--jobs 2 runs independent case simulations on the two WSL CPUs. It does not
change the selected accelerator hardware or simulated memory model.

--resume-from runs/PREVIOUS_RUN reuses completed full-shape measurements only
when the simulator, hardware parameters, adapter and source input bytes match.
The saved accelerator output is checked again against its saved exact reference.
Reused records retain their original measured cycles and source run path.

## Completed requested suite

B=8, RF=4, YS=2, DMA_ID=16, R=4; FP32 activations and FP16 group scales.
All 15 W8/W4/W2 full-shape Verilator cases passed the exact Python reference
(98,304 output elements). Native llama.cpp normalized RMS error is 0.38-0.86%;
outputs are not bit-identical.

Estimated 196-projection cycles: W8 503,132,028; W4 137,299,624;
W2 114,862,776. Excludes the vocabulary head and other inference operations.
Uses representative layer-0 costs across 28 layers.

[Detailed results](runs/20261118-202449_B8_RF4_YS2_ID16_R4/summary.md).
Earlier baseline evidence: VALIDATION_BASELINE_B4_RF4_YS1.json.
