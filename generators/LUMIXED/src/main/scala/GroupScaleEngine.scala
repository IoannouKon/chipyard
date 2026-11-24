package LUMIXED_PACKAGE

import chisel3._
import chisel3.util._

/** Finite exact bank fragments -> exact FP16-scaled row vectors.
  * Activation fractional bits: 24/149; scaled fractional bits: 48/173.
  * Each bank owns a running partial and an eight-entry fragment FIFO. Credits
  * reserve space for all four already-issued selection tokens. A row-vector
  * scaler shares one synchronous scale lookup across parallel activation rows.
  * Initialization descriptors precede all fragments of their output column,
  * so arbitrarily ordered bank completions cannot overwrite an earlier sum.
  */
class GroupScaleEngine(banks: Int, rows: Int, columns: Int, maxK: Int,
                       partialWidth: Int, outputWidth: Int) extends Module {
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
      val delta = Vec(rows, SInt(outputWidth.W))
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
  selected.ready := true.B
  initializes.io.deq.ready := true.B
  arb.io.out.ready := !initializes.io.deq.valid
  val index = selected.bits.group * io.columns + selected.bits.column
  when(selected.valid && !selected.bits.initialize) { assert(index < entries.U, "Scale index exceeds capacity") }
  val read = scales.read(index >> 2, selected.valid && !selected.bits.initialize)
  val readValid = RegNext(selected.valid, false.B)
  val tag = RegEnable(selected.bits, selected.valid)
  val offset = RegEnable(index(1, 0), selected.valid)
  val halves = VecInit((0 until 4).map(i => read(16 * i + 15, 16 * i)))
  val scale = halves(offset)
  when(readValid && !tag.initialize) { assert(!scale(14, 10).andR, "Group scales must be finite FP16") }
  val sig = Cat(0.U(1.W), FP16Activation.significand(scale)).asSInt
  val signedSig = Mux(scale(15), -sig, sig)
  io.out.valid := RegNext(readValid, false.B)
  io.out.bits.column := RegEnable(tag.column, readValid)
  io.out.bits.first := RegEnable(tag.initialize, readValid)
  for (y <- 0 until rows) {
    val multiplier = Module(new ExactScaleMultiplier(partialWidth, signedSig.getWidth))
    multiplier.io.partial := tag.partial(y)
    multiplier.io.scale_sig := signedSig
    val product = multiplier.io.product
    val shifted = (product.pad(outputWidth) << FP16Activation.shift(scale))(outputWidth - 1, 0).asSInt
    io.out.bits.delta(y) := RegEnable(Mux(tag.initialize, 0.S, shifted), readValid)
  }
  io.empty := !initializes.io.deq.valid && !arb.io.out.valid && !readValid && !io.out.valid
}
