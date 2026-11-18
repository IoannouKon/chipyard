# LUMIXED — host Python references for integer, FP16 and group-scaled matmul

This package is designed as a **drop-in update to your current LUMIXED test harness** (`software/tests/src/`). It replaces the old restriction that prohibited Python reference testing with `FP16_ACTIVATIONS=true`.

## Files

| File | Purpose |
|---|---|
| `prepare_test_vectors.py` | Independent Python X/W generation, quantized-weight packing, exact reference matmul and C header generation |
| `run_param_test.sh` | Organized Bash runner; automatically reads the mathematical hardware mode from `Config.scala` |
| `Linear-sw.c` | Your previously cleaned C harness, with group scale setup before each hardware matmul and safe ceil-byte packed-weight allocation |
| `test_reference.py` | Host-only Python unit tests covering every mathematical mode, group size and partial group |
| `README.md` | Setup, assumptions, usage and test limitations |

**Keep your existing `build.sh`, `rocc.h`, and `compiler.h`.** `build.sh` must pass `TEST_CFLAGS` to `riscv64-unknown-elf-gcc`, as your current build flow does. This package uses `LUMIXED_*` macro names matching the C file you supplied; the public upstream repository uses `LUMAX_*` names instead. Do not mix these without renaming consistently.

## Calculation modes

`run_param_test.sh` reads all three Scala flags from `LUMIXED/src/main/scala/Config.scala`:

| Scala `FP16_ACTIVATIONS` | Scala `GROUP_SCALE_ALIGNED` | Scala `GROUP_SCALE_DEFERRED` | C macro `LUMIXED_GROUP_SCALE_MODE` | Python reference | Output |
|---|---|---|---|---|---|
| `false` | `false` | `false` | 0 | Signed INT8/16 × signed W2/4/8 | INT32 |
| `true` | `false` | `false` | 0 | Exact FP16 × signed W2/4/8 | FP32 raw bits |
| `true` | `true` | `false` | 1 | Exact group-scaled FP16 × signed W2/4 | FP32 raw bits |
| `true` | `false` | `true` | 2 | **Same exact math** as mode 1, different RTL schedule | FP32 raw bits |

When both group flags are true, the runner rejects the configuration. Group scaling without FP16 is also rejected. W8 group scaling, special FP16 activation/scale values, zero-points and Q4_K affine min offsets are not currently supported group-scaling RTL contracts. The Python generator creates finite FP16 X and finite FP16 scales; the *unscaled* FP16 math function also implements the RTL's canonical NaN/Infinity policy for manually supplied tests.

`GROUP_SCALE_MODE` is not a toggle chosen independently of RTL; it is derived automatically from the two Scala build-time flags.

### Exact output arithmetic

**FP16 unscaled:** each FP16 activation is represented exactly as an integer multiple of 2^-24. Multiply by the signed integer weight, accumulate exactly, and round once to IEEE binary32 nearest/ties-to-even.

**FP16 grouped:** for each output `O[row, col]`, use `G`=32, 64 or 128 and FP16 scales stored **group-major** in `[ceil(K/G)][N]` order:

```text
O[row,col] = FP32_RNE(sum_g (scale[g,col] *
                    sum_(k in group g) X[row,k] * W[k,col]))
```

Group partial sums use exact fixed24; scaled totals use exact fixed48; a single final rounding produces the FP32 output word. The C header contains a padded 8-byte-aligned `lumixed_vectors_scales[]` array so the RoCC DMA can read a complete last 64-bit beat. Padding occurs at the **end of the complete scale array**, not between group rows.

**Integer mode:** the Python output is a signed INT32 dot product. Tests abort if the mathematically exact result is outside signed INT32 range; no overflow/wrap is silently accepted.

## Installation

Make a backup of the existing files first, then copy the three corresponding files into:

```text
chipyard/generators/LUMIXED/software/tests/src/
    Linear-sw.c
    prepare_test_vectors.py
    run_param_test.sh
```

Make the runner executable (`chmod +x run_param_test.sh`). `build.sh` is unchanged.

## Usage

### A. Signed integer activations

In `Config.scala`:

```scala
FP16_ACTIVATIONS = false,
GROUP_SCALE_ALIGNED = false,
GROUP_SCALE_DEFERRED = false,
```

Run, for example:

```bash
FAST_VECTORS=1 ./run_param_test.sh 4 224 224 16 4
FAST_VECTORS=1 ./run_param_test.sh 4 224 224 8 2
```

### B. FP16 activations without scaling

In `Config.scala`:

```scala
FP16_ACTIVATIONS = true,
GROUP_SCALE_ALIGNED = false,
GROUP_SCALE_DEFERRED = false,
```

Run:

```bash
FAST_VECTORS=1 ./run_param_test.sh 4 224 224 16 4
```

### C. FP16 + aligned group scaling

In `Config.scala`:

```scala
FP16_ACTIVATIONS = true,
GROUP_SCALE_ALIGNED = true,
GROUP_SCALE_DEFERRED = false,
```

Run:

```bash
GROUP_SIZE=64 FAST_VECTORS=1 ./run_param_test.sh 4 224 224 16 4
```

### D. FP16 + deferred group scaling

In `Config.scala`:

```scala
FP16_ACTIVATIONS = true,
GROUP_SCALE_ALIGNED = false,
GROUP_SCALE_DEFERRED = true,
```

Run:

```bash
GROUP_SIZE=32 FAST_VECTORS=1 ./run_param_test.sh 4 224 224 16 2
```

**Rebuild the RTL** after changing Scala flags. The runner's default `REBUILD=auto` checks source timestamps; `REBUILD=1` forces it. `GROUP_SIZE` is runtime-configurable (32/64/128), without changing the Scala group-scaling flags. `FAST_VECTORS=0` is still supported for integer and unscaled FP16 using the RISC-V C reference; it is explicitly blocked for grouped tests because the current C reference has no group-scale arithmetic.

### Extra controls

```bash
TEST_SEED=42 TEST_PATTERN=0 GROUP_SIZE=128 FAST_VECTORS=1 ./run_param_test.sh 2 129 9 16 4
```

The Python reference, input X and packed W are placed into `lumixed_test_vectors.h`; the C harness compiles this header into `Linear-sw.riscv`. The simulated Rocket CPU configures LUMIXED using RoCC commands, and **only LUMIXED RTL** computes the hardware result. The C harness compares against Python's precomputed `output_t` array, bit-for-bit. For grouped cases, the C harness now calls `lumixed_set_group_scales(...)` before `preload_hw(...)`, so the hardware reads the scales by DMA.

## Verification performed for this package

- Host-side `python3 test_reference.py`: 11 unit tests passed, including 18 integer cases, nine FP16 cases and 48 aligned/deferred group cases.
- 240 independently computed FP32 group-scaled values matched the older `group_scale_reference.py` bit-for-bit.
- Eight host-GCC syntax checks of the generated header plus modified C harness passed across integer/FP16/group mode combinations. These use stand-in RoCC macros and do **not** exercise the actual accelerator.
- Four Bash runner smoke checks passed using a **mock** simulator (integer, FP16, aligned and deferred). These verify mode/flag selection, not RTL correctness.
- `bash -n run_param_test.sh` passed.

**Not performed here:** RISC-V cross-compilation, Chipyard/Verilator execution, RTL elaboration, group DMA behavior, or measured active cycles. Your repository has separate archived Verilator group-scale validation; that does not validate this newly integrated test harness until you run it.

## Scope boundary

`SCALE=1` (legacy *global/uniform* integer-output scaling) is a **fourth mathematical case** but is not part of this initial generator, because the current supplied harness uses `SCALE=0`, and legacy scaling has separate RTL/output-mode restrictions. The runner rejects `FAST_VECTORS=1` if `SCALE=1` rather than claiming an unscaled reference is valid. Bank grouping, BRAM output storage and DMA scheduling options are not different mathematical results; their hardware output should still match the same reference.

This is **symmetric signed quantization**. A real llama.cpp Qwen GGUF `Q4_K` block adds asymmetric per-group minimum/offset terms and a different block packing convention. It is **not** directly supported by these vectors, and would require a conversion/backend path and more arithmetic support.
