package LUMIXED_PACKAGE

case class LUMIXEDParams(
  Rin:                         Int,
  Cin:                         Int,
  Cout:                        Int,
  INT_BITS:                    Int,
  WBitWidth:                   Int,
  OutBitWidth:                 Int,
  minInputBits:                Int,
  minWeightBits:               Int,
  banks_per_row:               Int,
  parallel_rows:               Int,
  Dma_Ids:                     Int,
  DMA_Bytes:                   Int,
  DEBUG:                       Boolean,
  W_BUFFS:                     Int,
  table_capacity_factor:       Int,
  FP_ACTIVATIONS:              Boolean = false,
  FP_BITS:                      Int = 16,
  EARLY_WEIGHT_BUFFER_RELEASE: Boolean = false,
  WEIGHT_DMA_BURSTS:            Boolean = false,
  MAX_WEIGHT_BURST_BYTES:       Int = 32,
  SKIP_ZERO_WEIGHTS:           Boolean = false,
  FLEXIBLE_BANK_GROUPING:      Boolean = false,
  GROUP_SCALE_ALIGNED:         Boolean = false,
  GROUP_SCALE_DEFERRED:        Boolean = false,
  GROUP_SCALE_YS:              Int = 1,
  ROUNDED_ACCUMULATION:        Boolean = false,
) {
  require(GROUP_SCALE_YS >= 1 && GROUP_SCALE_YS <= parallel_rows,
    "GROUP_SCALE_YS must be between 1 and parallel_rows")

  // Derived width: configure the beat size in bytes only.
  val DMA_bits: Int = DMA_Bytes * 8
  require(Set(16, 32).contains(FP_BITS), "FP_BITS must be 16 or 32")
  val XBitWidth: Int = if (FP_ACTIVATIONS) FP_BITS else INT_BITS
  require(FP_ACTIVATIONS || Set(8, 16).contains(XBitWidth), "Integer activation lanes must be 8 or 16 bits")
  val fpFractionalBits: Int = if (FP_BITS == 16) 24 else 149
}

// Build-time settings: rebuild the RTL after changing any value below.
object LUMIXED_Config {
  val LUMIXED_Config = LUMIXEDParams(

    // 1. Matrix capacities: X[R, K] * W[K, N] -> O[R, N].
    // Runtime dimensions must fit these limits. Rin must be >= parallel_rows.
    Rin  = 32,    // Maximum R: activation rows.
    Cin  = 256, // Maximum K: inner dimension; also sizes exact accumulators.
    Cout = 32, // Maximum N: output columns; determines output storage depth.

    // 2. Activation format and supported element widths.
    // false: signed integer activations. true: FP16/FP32 activations / FP32 outputs.
    FP_ACTIVATIONS = true,
    FP_BITS        = 32, // Build-time activation precision: 16 or 32.
    INT_BITS        = 16, // Integer lane width; ignored when FP_ACTIVATIONS=true.
    OutBitWidth     = 32, // Output storage word width.
    WBitWidth       = 8,  // Maximum signed weight width.
    minInputBits    = 8,  // Minimum integer input width used to size packed lanes.
    minWeightBits   = 2,  // Minimum weight width used to size product tables.

    // 3. Compute parallelism and product-table capacity.
    parallel_rows         = 1, // YS: activation rows processed together.
    banks_per_row         = 4, // B: parallel banks per activation row.
    table_capacity_factor = 4, // RF: base product-table capacity factor.

    // 4. DMA and weight prefetch storage.
    Dma_Ids   = 2, // Shared request slots; held until ordered response delivery.
    DMA_Bytes = 8, // Beat size; DMA_bits is derived as DMA_Bytes * 8.
    W_BUFFS   = 2, // Prefetch buffers, shared across parallel activation rows.

    // 5. SRAM output accumulation (always enabled).
    ROUNDED_ACCUMULATION = false, // Opt-in unscaled FP32: register each rounded subtotal/tag, then FP32 RNE accumulation; drain before advancing.

    // 6. Optional scheduling experiments (disabled by default).
    // W8/input16: retain captured weight words so their buffer can refill early.
    EARLY_WEIGHT_BUFFER_RELEASE = false,

    // Aligned W8 DMA reads using one shared burst-response slot.
    WEIGHT_DMA_BURSTS      = false,
    MAX_WEIGHT_BURST_BYTES = 32, // Maximum burst: 16, 32 or 64 bytes.

    // Independent per-bank zero skipping for eligible FP16/INT16 windows.
    // FP16 special values and legacy dirty offsets retain dense selection.
    SKIP_ZERO_WEIGHTS = false,

    // Fixed B*YS lanes; software may select YS=1,2,...,parallel_rows before
    // each matmul. B becomes total lanes / selected YS. Dense FP16 only.
    FLEXIBLE_BANK_GROUPING = false,

    // Mutually exclusive finite-FP activation / FP16-scale group-scaling implementations.
    GROUP_SCALE_YS = 1, // Build-time scaling multipliers: 1..parallel_rows; full parallelism when equal.
    GROUP_SCALE_ALIGNED = false, // Clamp windows to quantization boundaries.
    GROUP_SCALE_DEFERRED = true, // Full windows; scale bank fragments. SW selects G=32/64/128 before each matmul (no rebuild); G stays fixed during the matmul.

    // 7. Instrumentation.
    DEBUG = true, // Enable hardware performance counters.
  )
}

