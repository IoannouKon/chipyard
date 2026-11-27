package LUMIXED_PACKAGE

import chisel3._
import chisel3.util._
import hardfloat._

/** Finite exact bank fragments -> exact FP16-scaled row vectors.
  * Activation fractional bits: 24/149; scaled fractional bits: 48/173.
  * Each bank owns a running partial and an eight-entry fragment FIFO. Credits
  * reserve space for all four already-issued selection tokens. A row-vector
  * scaler shares one synchronous scale lookup across parallel activation rows.
  * Initialization descriptors precede all fragments of their output column,
  * so arbitrarily ordered bank completions cannot overwrite an earlier sum.
  */
class GroupScaleEngine(banks: Int, rows: Int, columns: Int, maxK: Int,
                       partialWidth: Int, outputWidth: Int, scaleRows: Int, roundedFP32: Boolean = false) extends Module {
  private val storedWidth = if (roundedFP32) 32 else outputWidth
  // Exact mode retains full multiplication/alignment. Rounded mode carries an
  // FP32 fragment and an exactly widened FP16 scale to the shared FP32 FMA.
  def scaleBits(scale: UInt): UInt = {
    // FP16 -> FP32 is an exact widening conversion, including signed zero and
    // subnormals. HardFloat exposes the 13 appended zero significand bits so
    // synthesis can specialize the FMA multiplier rather than prove a numeric
    // invariant through a generic fixed-point normalization network.
    val convert = Module(new RecFNToRecFN(5, 11, 8, 24))
    convert.io.in := recFNFromFN(5, 11, scale)
    convert.io.roundingMode := consts.round_near_even
    convert.io.detectTininess := consts.tininess_afterRounding
    fNFromRecFN(8, 24, convert.io.out)
  }
  def contribution(partial: SInt, scale: UInt): SInt = {
    if (roundedFP32) {
      val convert = Module(new FixedToFP32(partialWidth, 149))
      convert.io.value := partial; convert.io.flags := 0.U
      convert.io.bits.asSInt
    } else {
      val sig = Cat(0.U(1.W), FP16Activation.significand(scale)).asSInt
      val signedSig = Mux(scale(15), -sig, sig)
      val multiplier = Module(new ExactScaleMultiplier(partialWidth, signedSig.getWidth))
      multiplier.io.partial := partial; multiplier.io.scale_sig := signedSig
      val shifted = (multiplier.io.product.pad(outputWidth) << FP16Activation.shift(scale))(outputWidth - 1, 0).asSInt
      shifted
    }
  }
  require(scaleRows >= 1 && scaleRows <= rows, "Scaling lanes must be between 1 and rows")
  private val cw = math.max(1, log2Ceil(columns + 1))
  private val gw = math.max(1, log2Ceil((maxK + 31) / 32 + 1))
  private val entries = ((maxK + 31) / 32) * columns
  private val words = (entries + 3) / 4
  class Fragment extends Bundle {
    val column = UInt(cw.W)
    val group = UInt(gw.W)
    val initialize = Bool()
    val partial = Vec(rows, SInt(partialWidth.W))
  }
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val first = Input(Bool()) // First token for a column in the first K window.
    val column = Input(UInt(cw.W))
    val groups = Input(Vec(banks, UInt(gw.W)))
    val last = Input(Vec(banks, Bool()))
    val laneValid = Input(Vec(banks, Bool()))
    val values = Input(Vec(banks, Vec(rows, SInt(partialWidth.W))))
    val columns = Input(UInt(cw.W))
    val scaleWrite = Input(Bool())
    val scaleWriteAddress = Input(UInt(math.max(1, log2Ceil(words)).W))
    val scaleWriteData = Input(UInt(64.W))
    val canIssue = Output(Bool())
    val empty = Output(Bool())
    val out = Valid(new Bundle {
      val column = UInt(cw.W)
      val first = Bool()
      val delta = Vec(rows, SInt(storedWidth.W))
      val scale = UInt(32.W)
      val rowMask = UInt(rows.W)
    })
  })
  val scales = SyncReadMem(words, UInt(64.W))
  when(io.scaleWrite) { scales.write(io.scaleWriteAddress, io.scaleWriteData) }
  val partials = RegInit(VecInit(Seq.fill(banks)(VecInit(Seq.fill(rows)(0.S(partialWidth.W))))))
  val fifos = Seq.fill(banks)(Module(new Queue(new Fragment, 8)))
  val initializes = Module(new Queue(new Fragment, 8))
  initializes.io.enq.valid := io.valid && io.first
  initializes.io.enq.bits := 0.U.asTypeOf(new Fragment)
  initializes.io.enq.bits.column := io.column
  initializes.io.enq.bits.initialize := true.B
  when(initializes.io.enq.valid) { assert(initializes.io.enq.ready, "Group initialization FIFO overflow") }
  for (b <- 0 until banks) {
    val q = fifos(b)
    val totals = VecInit((0 until rows).map(y => partials(b)(y) + io.values(b)(y)))
    q.io.enq.valid := io.valid && io.laneValid(b) && io.last(b)
    q.io.enq.bits.column := io.column
    q.io.enq.bits.group := io.groups(b)
    q.io.enq.bits.initialize := false.B
    q.io.enq.bits.partial := totals
    when(io.valid && io.laneValid(b)) {
      for (y <- 0 until rows) { partials(b)(y) := Mux(io.last(b), 0.S, totals(y)) }
    }
    when(q.io.enq.valid) { assert(q.io.enq.ready, "Group fragment FIFO overflow") }
  }
  io.canIssue := initializes.io.count < 4.U && fifos.map(_.io.count < 4.U).reduce(_ && _)
  val arb = Module(new RRArbiter(new Fragment, banks))
  for (b <- 0 until banks) { arb.io.in(b) <> fifos(b).io.deq }
  val selected = Wire(Decoupled(new Fragment))
  selected.valid := initializes.io.deq.valid || arb.io.out.valid
  selected.bits := Mux(initializes.io.deq.valid, initializes.io.deq.bits, arb.io.out.bits)
  initializes.io.deq.ready := selected.ready
  arb.io.out.ready := selected.ready && !initializes.io.deq.valid
  val index = selected.bits.group * io.columns + selected.bits.column
  when(selected.fire && !selected.bits.initialize) { assert(index < entries.U, "Scale index exceeds capacity") }
  val read = scales.read(index >> 2, selected.fire && !selected.bits.initialize)
  val readValid = RegNext(selected.fire, false.B)
  val tag = RegEnable(selected.bits, selected.fire)
  val offset = RegEnable(index(1, 0), selected.fire)
  val halves = VecInit((0 until 4).map(i => read(16 * i + 15, 16 * i)))
  if (scaleRows == rows) {
    // Preserve the original full-parallel pipeline, including register boundaries.
    selected.ready := true.B
    val scale = halves(offset)
    when(readValid && !tag.initialize) { assert(!scale(14, 10).andR, "Group scales must be finite FP16") }
    io.out.valid := RegNext(readValid, false.B)
    io.out.bits.column := RegEnable(tag.column, readValid)
    io.out.bits.first := RegEnable(tag.initialize, readValid)
    io.out.bits.scale := (if (roundedFP32) RegEnable(scaleBits(scale), readValid) else "h3f800000".U)
    io.out.bits.rowMask := ((BigInt(1) << rows) - 1).U
    for (y <- 0 until rows) {
      val value = contribution(tag.partial(y), scale)
      io.out.bits.delta(y) := RegEnable(Mux(tag.initialize, 0.S, value), readValid)
    }
    io.empty := !initializes.io.deq.valid && !arb.io.out.valid && !readValid && !io.out.valid
  } else {
    // One fragment stays in tag until all of its row batches have been processed.
    // Accept its successor during the final batch to overlap synchronous scale lookup.
    val batches = (rows + scaleRows - 1) / scaleRows
    val batch = RegInit(0.U(math.max(1, log2Ceil(batches)).W))
    val busy = RegInit(false.B)
    val processing = readValid || busy
    val lastBatch = tag.initialize || batch === (batches - 1).U
    selected.ready := !processing || lastBatch
    val heldScale = RegEnable(halves(offset), readValid)
    val scale = Mux(readValid, halves(offset), heldScale)
    when(readValid && !tag.initialize) { assert(!scale(14, 10).andR, "Group scales must be finite FP16") }
    val results = Reg(Vec(rows, SInt(storedWidth.W)))
    val shifted = (0 until scaleRows).map { lane =>
      val row = batch * scaleRows.U + lane.U
      contribution(Mux(row < rows.U, tag.partial(row), 0.S), scale)
    }
    when(processing) {
      when(lastBatch) {
        batch := 0.U
        busy := false.B
      }.otherwise {
        batch := batch + 1.U
        busy := true.B
      }
      for (y <- 0 until rows) {
        when(tag.initialize) {
          results(y) := 0.S
        }.elsewhen(batch === (y / scaleRows).U) {
          results(y) := shifted(y % scaleRows)
        }
      }
    }
    if (roundedFP32) {
      // Preserve the original fragment/FIFO schedule, but retire one row batch
      // per cycle into matching shared FMA lanes. Initialization bypasses FMA.
      val batchMask = VecInit((0 until rows).map(y => tag.initialize || batch === (y / scaleRows).U)).asUInt
      io.out.valid := RegNext(processing, false.B)
      io.out.bits.column := RegEnable(tag.column, processing)
      io.out.bits.first := RegEnable(tag.initialize, processing)
      io.out.bits.scale := RegEnable(scaleBits(scale), processing)
      io.out.bits.rowMask := RegEnable(batchMask, processing)
    } else {
      io.out.valid := RegNext(processing && lastBatch, false.B)
      io.out.bits.column := RegEnable(tag.column, processing && lastBatch)
      io.out.bits.first := RegEnable(tag.initialize, processing && lastBatch)
      io.out.bits.scale := "h3f800000".U
      io.out.bits.rowMask := ((BigInt(1) << rows) - 1).U
    }
    io.out.bits.delta := results
    io.empty := !initializes.io.deq.valid && !arb.io.out.valid && !readValid && !busy && !io.out.valid
  }
}
