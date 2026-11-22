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
