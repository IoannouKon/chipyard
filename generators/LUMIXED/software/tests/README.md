# LUMIXED bare-metal tests

The runner reads hardware geometry and arithmetic mode from Config.scala.
Source the Chipyard workspace environment before running it. Changing
build-time parameters requires rebuilding the simulator.

## Activation precision

    FP_ACTIVATIONS = true,
    FP_BITS        = 32, // 16 or 32
    INT_BITS       = 16, // Integer lane width; ignored in floating-point mode.

XBitWidth is derived from FP_BITS in floating-point mode. Weights remain signed
W8/W4/W2 integers; output words contain raw FP32 bits. With FP_ACTIVATIONS=false,
integer activation lanes use INT_BITS (8 or 16).

The LUT computes integer significand products. Exact accumulators use 24
fractional bits for FP16 activations or 149 for FP32. Output conversion rounds
once to FP32, nearest/ties-to-even, with gradual underflow and overflow to
infinity. Exact cancellation returns +0; unscaled NaNs are canonicalized.

## Per-group scaling

Select at most one of GROUP_SCALE_ALIGNED and GROUP_SCALE_DEFERRED.
Both implement the same exact mathematical result:

    O[r,n] = FP32_RNE(sum_g scale[g,n] * sum_(k in group g) X[r,k] * W[k,n])

Scales remain finite **FP16**, including when activations are FP32. Store them
group-major as [ceil(K/G)][N], pad only the final DMA beat, and align the scale
array to 8 bytes. GROUP_SIZE=32, 64, or 128 selects G at runtime.

Group-scaled activation inputs must be finite. Scaled accumulators use 48
fractional bits for FP16 activations or 173 for FP32. The legacy integer SCALE
option is separate and must remain disabled in floating-point mode.
Group modes currently require fixed banks and the other optional
scheduling experiments disabled, as enforced by RTL requirements.

## Run

From software/tests/src, for FP32 activations and FP16 group scales:

    GROUP_SIZE=128 FAST_VECTORS=1 bash ./run_param_test.sh 2 256 4 32 8
    GROUP_SIZE=128 FAST_VECTORS=1 bash ./run_param_test.sh 2 256 4 32 4
    GROUP_SIZE=128 FAST_VECTORS=1 bash ./run_param_test.sh 2 256 4 32 2

These commands require Cin >= 256 and Cout >= 4. They exercise two scale groups
using synthetic data, not model inference. For FP16, set FP_BITS=16, rebuild,
and pass 16 as the fourth argument.

REBUILD=auto rebuilds when RTL/configuration timestamps change. REBUILD=1
forces a build; use REBUILD=0 only with a simulator matching the source.
FAST_VECTORS=1 generates inputs, packed weights, scales and independent Python
expected outputs. FP32 and group-scaled tests require this mode; the C reference
supports integer and unscaled FP16 tests only.

The Rocket CPU sends RoCC commands; LUMIXED RTL computes the hardware output.
The C harness requires every output to match Python bit for bit and the
simulator to exit successfully. Python uses arbitrary-precision integer sums,
so its reference does not lose FP32 exponent range through an int64 sum.

TEST_SEED chooses the deterministic seed. TEST_PATTERN=0 uses the legacy weight
sequence, 1 uses minimum activations, and 2 randomizes signed weights. Logs
include simulator exit status and exact match counts.

The medium Rocket configuration has no FPU. Both compile and link must use the
soft-float LP64 ABI and its matching HTIF runtime. The verification artifacts
contain the compiler wrapper and stock Chipyard atexit workaround used here.

## Scope

The calculation is symmetric signed quantization. GGUF Q4_K/Q2_K affine
offsets, packing conversions, and a llama.cpp backend are separate work.
Matching this exact reference does not imply bit-identical llama.cpp results.

## Verified FP32 configuration

The FP32 activation / FP16 deferred-group-scale configuration passed native
Verilator: W8, W4, W2 on X[2,256] * W[256,4] with G=128 (24/24 exact outputs).
Four directed W8 cases covered subnormals, normal rounding ties, exact
cancellation after oversized partial sums, and FP16-scale underflow (32/32).
An odd-dimension W4 case X[3,33] * W[33,3], G=32 passed 9/9.
All eight simulator processes exited 0. This run did not rebuild FP16 RTL.

### Output storage

LUMIXED output accumulation always uses synchronous SRAM and the existing four-entry DMA prefetch queue. The BRAM_OUTPUTS switch and the hardware num_filters parameter have been removed; omit those fields from generated hardware configurations. Output packing, RoCC commands and the C API are unchanged.
