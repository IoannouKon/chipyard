# Edited Gemmini hardware for Chipyard 1.15.0

Hardware-only archive of the tested optional FP32 × signed INT8 → FP32 port.
Upstream Gemmini base: `8c3f9923a44a2fe2c7930587be297d6d4f8c09ca`.
Chipyard base: `c4cbf50c`, branch `feature/lumixed-integration`.

`GEMMINI_EDITED` is an elaboration-time Boolean, default `false`. The standard
Gemmini path and configurations retain their existing behavior. Edited mode uses
weight-stationary execution, actual one-byte INT8 weight DMA, FP32 activation
loads and FP32 outputs. There are no scaling units. The mixed PE uses a 24×8
significand multiplier and FP32 RNE addition; its arithmetic is
`RNE32(RNE32(X * signed_W8) + partial)`. Tile partials accumulate in FP32.

The supplied configurations are `EditedGemmini4RocketConfig` and
`EditedGemmini8RocketConfig`: SP32 KiB, ACC4 KiB, four scratchpad banks, 64-bit DMA,
64-byte maximum transactions, 16 in-flight requests per existing DMA pool,
eight counters. `StockGemminiFP32RocketConfig` exercises the flag-OFF path.
This port validates W8 only.

### Archived-baseline-compatible FP32 4x4 selector

`EditedGemmini4BaselineRocketConfig` selects the closest FP32 counterpart of the
archived Chipyard 1.13 FP16/W8 4x4 configuration that closed 50 MHz on ZCU106.
It keeps the existing FP32 mixed MAC and changes only these hardware settings
relative to `EditedGemmini4RocketConfig`:

```scala
max_in_flight_mem_reqs = 8
acc_read_small_width = false
has_dw_convs = false
```

The header is separately named `gemmini_edited_fp32_w8_4_baseline_id8.h`.
The original 4x4/8x8 ID16 selectors and `GEMMINI_EDITED=false` are preserved.
This is WS-only, FP32 activations, signed W8, FP32 accumulation/output; input,
accumulator and output scaling, bias, pooling and nonlinear functions are OFF.
SP32 KiB/ACC4 KiB, four SP banks, one ACC bank, 64-bit DMA, 64-byte maximum
transactions, tile latency 2, eight counters and disabled clock gating match
the archived baseline. DMA IDs are per separate read/write pool.

Select it through the normal Chipyard entry point:

```bash
cd sims/verilator
make CONFIG=EditedGemmini4BaselineRocketConfig verilog
```

The local ZCU106 platform also provides `EditedGemmini4Baselinezcu106`:

```bash
cd fpga
make SUB_PROJECT=LUMIXEDzcu106 CONFIG=EditedGemmini4Baselinezcu106 verilog
```

`SUB_PROJECT=LUMIXEDzcu106` selects the existing board platform, while `CONFIG`
chooses Gemmini instead of LUMIXED. `verilog` stops before Vivado implementation.
The ZCU106 platform files remain local/uncommitted separately from this port.

FP32 inherently changes activation SRAM slots from 16 to 32 bits, halving
activation element capacity at fixed SP32 KiB and doubling activation bytes.
The significand multiplier is 24x8 rather than 11x8 and the FP32 product requires
RNE before FP32 addition. These precision effects cannot be removed while
preserving genuine FP32 input support. Historical full-SoC ZCU106 timing/power
and current accelerator-only U250 OOC results are separate environments; the historical result does not establish timing/power for these FP32 configurations.
Fresh U250 implementation is archived in
[Gemmini_Baseline_ID8](../Hardware_Estimation/Gemmini_Baseline_ID8/README.md).

Quick validation of the baseline-compatible selector: Scala parameter assertions,
real Chipyard RTL elaboration and the ZCU106 selector compilation passed. The
elaborated array contains 16 FP32/W8 MACs. Its fresh MAC RTL passed 21,296 directed
and random Verilator vectors against an arbitrary-precision two-round FP32
reference. Identity transport modules may retain `Scale`/`Multiplier` names;
their configured arithmetic scaling is bypassed. No full-accelerator DMA-ID8
simulation was rerun. Fresh Vivado implementation completed; both arrays routed,
but neither closes overall timing due to boundary hold violations.

The matching 8x8 selector is `EditedGemmini8BaselineRocketConfig` (FPGA:
`EditedGemmini8Baselinezcu106`). It retains the same ID8, SP32/ACC4, WS-only,
no-scaling settings and changes the array dimensions to 8x8. The original ID16
comparison selectors remain available. Both new selectors passed Scala parameter
assertions and the FPGA selector compilation. Fresh synthesis and implementation completed on U250; see the linked PPA
archive for setup/hold and power. Historical 50 MHz timing remains separate.

`src/main/scala/gemmini/` contains the 16 new/modified Gemmini source files for
review. `gemmini.patch` applies those changes to the pinned upstream submodule.
`chipyard/` contains the matching Chipyard configuration. These archived Scala
files are outside LUMIXED's compiled source directory.

From the Chipyard repository root, on an unmodified submodule at the base above:

```sh
git submodule update --init generators/gemmini
git -C generators/gemmini apply --check ../LUMIXED/Gemmini_Edited/gemmini.patch
git -C generators/gemmini apply ../LUMIXED/Gemmini_Edited/gemmini.patch
cp generators/LUMIXED/Gemmini_Edited/chipyard/GemminiEditedConfigs.scala generators/chipyard/src/main/scala/config/
```

An already-applied port must not be applied twice. The active checkout used for
the measurements already has these changes. The upstream submodule pointer is
preserved; this archive avoids depending on an unpublished Gemmini Git revision.

Validation completed before archival: stock FP32 smoke; edited 4×4/8×8 directed,
random, tail and multi-K-chunk smoke tests; 21,296 MAC RTL vectors; 10 complete
unscaled Qwen-shaped Verilator GEMMs, with 65,536/65,536 outputs bit-exact to the
independent tiled FP32 reference. Results, benchmark software and build artifacts
are intentionally outside this hardware-only archive.

The Gemmini license is preserved in `LICENSE`; upstream source attribution and
comments are retained. This extends Gemmini rather than replacing its standard
implementation.
