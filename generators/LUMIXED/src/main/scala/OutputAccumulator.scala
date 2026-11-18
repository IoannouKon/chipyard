package LUMIXED_PACKAGE

import chisel3._
import chisel3.util._

/** Exact partial sums, banked by output-column parity for two-word DMA reads.
  * Compute reads issue one cycle before retirement. Forwarding covers SRAM
  * read/write collisions when consecutive contributions update the same column.
  * The first contribution in the first K window overwrites old contents, so
  * storage needs neither a whole-array reset nor clearing after DMA acceptance.
  */
class OutputAccumulator(rows: Int, columns: Int, width: Int) extends Module {
  private val columnWidth = math.max(1, log2Ceil(columns + 1))
  private val rowWidth = math.max(1, log2Ceil(rows + 1))
  private val depth = (columns + 1) / 2
  val io = IO(new Bundle {
    val readValid = Input(Bool())
    val readColumn = Input(UInt(columnWidth.W))
    val updateValid = Input(Vec(rows, Bool()))
    val updateColumn = Input(UInt(columnWidth.W))
    val delta = Input(Vec(rows, SInt(width.W)))
    val flags = Input(Vec(rows, UInt(3.W)))
    val first = Input(Bool())
    val storeValid = Input(Bool())
    val storeRow = Input(UInt(rowWidth.W))
    val storeColumn = Input(UInt(columnWidth.W))
    val storeCount = Input(UInt(2.W))
    val storeValues = Output(Vec(2, SInt(width.W)))
    val storeFlags = Output(Vec(2, UInt(3.W)))
  })

  val memories = Seq.fill(rows, 2)(SyncReadMem(depth, UInt((width + 3).W)))
  val readWords = Wire(Vec(rows, Vec(2, UInt((width + 3).W))))
  val previousValid = RegNext(io.updateValid, VecInit(Seq.fill(rows)(false.B)))
  val previousColumn = RegEnable(io.updateColumn, io.updateValid.asUInt.orR)
  val previousWords = Reg(Vec(rows, UInt((width + 3).W)))
  val storeColumn = RegEnable(io.storeColumn, io.storeValid)
  val storeRow = RegEnable(io.storeRow, io.storeValid)

  assert(!(io.readValid && io.storeValid), "Output SRAM compute/store ports overlap")
  for (y <- 0 until rows) {
    val oldWord = Mux(previousValid(y) && previousColumn === io.updateColumn,
      previousWords(y), readWords(y)(io.updateColumn(0)))
    val oldValue = Mux(io.first, 0.S(width.W), oldWord(width - 1, 0).asSInt)
    val oldFlags = Mux(io.first, 0.U(3.W), oldWord(width + 2, width))
    val sum = (oldValue + io.delta(y)).asUInt
    val written = Cat(oldFlags | io.flags(y), sum)
    when(io.updateValid(y)) { previousWords(y) := written }

    for (bank <- 0 until 2) {
      // With an odd starting column, the even bank supplies the second word.
      val storeIndex = io.storeColumn + Mux(io.storeColumn(0) === bank.U, 0.U, 1.U)
      val storeEnabled = io.storeValid && io.storeRow === y.U &&
        (io.storeColumn(0) === bank.U || io.storeCount === 2.U)
      val address = Mux(io.storeValid, storeIndex >> 1, io.readColumn >> 1)
      readWords(y)(bank) := memories(y)(bank).read(address,
        storeEnabled || (io.readValid && io.readColumn(0) === bank.U))
      when(io.updateValid(y) && io.updateColumn(0) === bank.U) {
        memories(y)(bank).write(io.updateColumn >> 1, written)
      }
    }
  }
  for (i <- 0 until 2) {
    val word = readWords(storeRow)((storeColumn + i.U)(0))
    io.storeValues(i) := word(width - 1, 0).asSInt
    io.storeFlags(i) := word(width + 2, width)
  }
}
