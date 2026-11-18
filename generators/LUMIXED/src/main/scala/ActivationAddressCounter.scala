package LUMIXED_PACKAGE

import chisel3._
import chisel3.util._

/** Sequential activation positions, shared by all banks and activation rows.
  * A bank holds `blocks` logical positions. `cell` selects a 16-bit lane of the
  * current DMA word; `packedOffset` counts smaller inputs inside that lane.
  * The RAM word address remains controlled by the existing memory-read logic.
  */
class ActivationAddressCounter(
    banks: Int, cellsPerWord: Int, maxBlocks: Int,
    maxElementsPerCell: Int, maxStartOffset: Int) extends Module {
  require(banks > 0 && cellsPerWord > 0 && maxElementsPerCell > 0)
  require(isPow2(cellsPerWord) && isPow2(maxElementsPerCell))
  private val bankWidth = math.max(1, log2Ceil(banks))
  private val cellWidth = math.max(1, log2Ceil(cellsPerWord))
  private val localWidth = math.max(1, log2Ceil(math.max(maxBlocks, maxStartOffset) + 1))
  private val packedWidth = math.max(1, log2Ceil(maxElementsPerCell))

  val io = IO(new Bundle {
    val restart = Input(Bool())
    val advance = Input(Bool())
    val blocks = Input(UInt(localWidth.W))
    val elementsPerCell = Input(UInt(log2Ceil(maxElementsPerCell + 1).W))
    val startOffset = Input(UInt(math.max(1, log2Ceil(maxStartOffset + 1)).W))
    val bankShift = Output(UInt(bankWidth.W))
    val cell = Output(UInt(cellWidth.W))
  })

  val local = RegInit(0.U(localWidth.W))
  val bankShift = RegInit(0.U(bankWidth.W))
  val cell = RegInit(0.U(cellWidth.W))
  val packedOffset = RegInit(0.U(packedWidth.W))

  // Startup only: normalize the small legacy dirty-element offset using
  // bounded compare/subtract steps, never a divider or modulo operator.
  // For example, blocks=2 and startOffset=3 starts in bank+1 at local slot 1.
  val seedLocal = Wire(Vec(maxStartOffset + 1, UInt(localWidth.W)))
  val seedBank = Wire(Vec(maxStartOffset + 1, UInt(bankWidth.W)))
  seedLocal(0) := io.startOffset
  seedBank(0) := 0.U
  for (i <- 0 until maxStartOffset) {
    val crossBank = io.blocks =/= 0.U && seedLocal(i) >= io.blocks
    seedLocal(i + 1) := Mux(crossBank, seedLocal(i) - io.blocks, seedLocal(i))
    seedBank(i + 1) := Mux(crossBank,
      Mux(seedBank(i) === (banks - 1).U, 0.U, seedBank(i) + 1.U), seedBank(i))
  }
  val packingShift = WireDefault(0.U(math.max(1, log2Ceil(packedWidth + 1)).W))
  for (shift <- 0 to log2Ceil(maxElementsPerCell)) {
    when(io.elementsPerCell === (1 << shift).U) { packingShift := shift.U }
  }

  // Restart has priority over advance, including the last LUT-generation
  // cycle. Every output column replays the same activation bank/cell sequence.
  when(io.restart) {
    local := seedLocal(maxStartOffset)
    bankShift := seedBank(maxStartOffset)
    cell := (seedLocal(maxStartOffset) >> packingShift)
    packedOffset := seedLocal(maxStartOffset) & (io.elementsPerCell - 1.U)
  }.elsewhen(io.advance) {
    assert(io.blocks =/= 0.U, "Activation traversal needs a nonempty bank")
    when(local === io.blocks - 1.U) {
      // A partial last word also restarts at cell zero in the next bank.
      local := 0.U
      cell := 0.U
      packedOffset := 0.U
      bankShift := Mux(bankShift === (banks - 1).U, 0.U, bankShift + 1.U)
    }.otherwise {
      local := local + 1.U
      when(packedOffset === io.elementsPerCell - 1.U) {
        packedOffset := 0.U
        cell := Mux(cell === (cellsPerWord - 1).U, 0.U, cell + 1.U)
      }.otherwise {
        packedOffset := packedOffset + 1.U
      }
    }
  }

  io.bankShift := bankShift
  io.cell := cell
}
