package LUMIXED_PACKAGE

case class LUMIXEDParams(

  // 1. Matrix dimensions
  Rin: Int,
  Cin: Int,
  Cout: Int,

  // 2. Numerical formats
  FP_ACTIVATIONS: Boolean,
  FP_BITS:        Int,
  INT_BITS:       Int,
  MIN_INT_BITS:   Int,
  WBitWidth:      Int,
  minWeightBits:  Int,
  OutBitWidth:    Int,

  // 3. Compute architecture
  banks_per_row:          Int,
  table_capacity_factor:  Int,
  parallel_rows:          Int,
  FLEXIBLE_BANK_GROUPING: Boolean,

  // 4. DMA and weight buffering
  Dma_Ids:                     Int,
  DMA_Bytes:                   Int,
  W_BUFFS:                     Int,
  EARLY_WEIGHT_BUFFER_RELEASE: Boolean,
  WEIGHT_DMA_BURSTS:            Boolean,
  MAX_WEIGHT_BURST_BYTES:       Int,

  // 5. Output accumulation
  ROUNDED_ACCUMULATION: Boolean,

  // 6. Group-wise quantization
  GROUP_SCALE_ALIGNED:  Boolean,
  GROUP_SCALE_DEFERRED: Boolean,
  GROUP_SCALE_YS:       Int,

  // 7. Sparsity
  SKIP_ZERO_WEIGHTS: Boolean,

  // 8. Instrumentation
  DEBUG: Boolean,

) {

  // Derived hardware parameters
  val DMA_bits: Int = DMA_Bytes * 8

  val XBitWidth: Int =
    if (FP_ACTIVATIONS) FP_BITS else INT_BITS

  val maxParts: Int =
    if (FP_ACTIVATIONS) 1
    else INT_BITS / MIN_INT_BITS

  val fpFractionalBits: Int =
    if (FP_BITS == 16) 24 else 149

  // Configuration validation
  require(Rin >= parallel_rows)
  require(Cin > 0 && Cout > 0)

  require(Set(16, 32).contains(FP_BITS))
  require(Set(8, 16).contains(INT_BITS))

  require(Set(2, 4, 8, 16).contains(MIN_INT_BITS))
  require(MIN_INT_BITS <= INT_BITS)

  require(GROUP_SCALE_YS >= 1 &&
          GROUP_SCALE_YS <= parallel_rows)

  require(!(GROUP_SCALE_ALIGNED &&
            GROUP_SCALE_DEFERRED))

  require(!ROUNDED_ACCUMULATION ||
    (FP_ACTIVATIONS && FP_BITS == 32 &&
     !GROUP_SCALE_ALIGNED && !GROUP_SCALE_DEFERRED))
}


// ======================================================
// LUMIXED Hardware Configuration
// All parameters are selected at hardware generation time.
// ======================================================

object LUMIXED_Config {

  val LUMIXED_Config = LUMIXEDParams(

    // ==================================================
    // 1. Matrix Dimensions
    // ==================================================

    Rin  = 32,  // Maximum activation rows (R).
    Cin  = 256, // Maximum inner dimension (K).
    Cout = 32,  // Maximum output columns (N).

    // ==================================================
    // 2. Numerical Formats
    // ==================================================

    // Floating-point activations
    FP_ACTIVATIONS = true, // false: integer activations.
    FP_BITS        = 32,   // FP16 or FP32.

    // Integer activations (ignored in FP mode)
    INT_BITS     = 16, // Maximum integer activation width.
    MIN_INT_BITS = 8,  // Minimum runtime integer precision.

    // Signed weights (runtime-selectable INT2/4/8)
    WBitWidth     = 8, // Maximum weight precision.
    minWeightBits = 2, // Minimum weight precision.

    // Outputs
    OutBitWidth = 32, // INT32 or FP32 output word.

    // ==================================================
    // 3. Compute Architecture
    // ==================================================

    banks_per_row         = 4, // B: parallel banks per row.
    table_capacity_factor = 4, // RF: product-table capacity.
    parallel_rows         = 1, // YS: parallel activation rows.

    // Runtime redistribution of physical B * YS lanes.
    // Experimental; incompatible with group scaling.
    FLEXIBLE_BANK_GROUPING = false,

    // ==================================================
    // 4. DMA and Weight Buffering
    // ==================================================

    Dma_Ids   = 2, // Outstanding DMA request slots.
    DMA_Bytes = 8, // DMA beat size in bytes (64-bit).
    W_BUFFS   = 2, // Number of weight prefetch buffers.

    // Release weight buffers earlier for refilling.
    // Currently restricted to W8/16-bit inputs.
    EARLY_WEIGHT_BUFFER_RELEASE = false,

    // Combine multiple DMA beats into one request.
    // Currently restricted to aligned W8/16-bit inputs.
    WEIGHT_DMA_BURSTS      = false,
    MAX_WEIGHT_BURST_BYTES = 32, // 16, 32 or 64 bytes.

    // ==================================================
    // 5. Output Accumulation
    // ==================================================

    // SRAM output storage is always enabled.
    // false: exact accumulation with final rounding.
    // true:  FP32-rounded running accumulation.
    // Rounded mode currently requires unscaled FP32.
    ROUNDED_ACCUMULATION = false,

    // ==================================================
    // 6. Group-wise Quantization
    // ==================================================

    // Restrict activation windows to group boundaries.
    GROUP_SCALE_ALIGNED = false,

    // Preserve full windows and scale bank fragments.
    GROUP_SCALE_DEFERRED = true,

    // Parallel scaling multipliers (1..parallel_rows).
    GROUP_SCALE_YS = 1,

    // Scaling modes are mutually exclusive.
    // Both false: unscaled computation.
    // FP16 scales; runtime group size: 32, 64 or 128.

    // ==================================================
    // 7. Sparsity Optimization
    // ==================================================

    // Skip zero-weight products during selection.
    // Currently restricted to eligible 16-bit inputs.
    SKIP_ZERO_WEIGHTS = false,

    // ==================================================
    // 8. Instrumentation
    // ==================================================

    DEBUG = true, // Enable hardware performance counters.

  )
}
