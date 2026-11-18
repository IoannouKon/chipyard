package LUMIXED_PACKAGE

import chisel3._
import chisel3.util._

/** Replay dense packed weights without issuing their zero-valued entries.
  * Each bank owns one SRAM-word register and a remaining-nonzero bitmap. Its
  * local K position travels with the weight: compacting the schedule must never
  * change which activation/product table the weight addresses. No compressed
  * external format, extra weight buffer, or DMA engine is required.
  */
class ZeroWeightSelector(banks: Int, maxPositions: Int) extends Module {
  private val posWidth = math.max(1, log2Ceil(maxPositions + 33))
  val io = IO(new Bundle {
    val start = Input(Bool())
    val enable = Input(Bool())
    val precision = Input(UInt(6.W))
    val limits = Input(Vec(banks, UInt(posWidth.W)))
    val readEnable = Output(Vec(banks, Bool()))
    val readAddress = Output(Vec(banks, UInt(posWidth.W)))
    val readData = Input(Vec(banks, UInt(64.W)))
    val busy = Output(Bool())
    val valid = Output(Bool())
    val first = Output(Bool())
    val last = Output(Bool())
    val laneValid = Output(Vec(banks, Bool()))
    val position = Output(Vec(banks, UInt(posWidth.W)))
    val weight = Output(Vec(banks, SInt(8.W)))
  })

  val active = RegInit(false.B)
  val first = RegInit(false.B)
  val base = RegInit(VecInit(Seq.fill(banks)(0.U(posWidth.W))))
  val words = Reg(Vec(banks, UInt(64.W)))
  val remaining = RegInit(VecInit(Seq.fill(banks)(0.U(32.W))))
  val loaded = RegInit(VecInit(Seq.fill(banks)(false.B)))
  val pending = RegNext(io.readEnable, VecInit(Seq.fill(banks)(false.B)))
  val wordShift = Mux(io.precision === 8.U, 3.U, Mux(io.precision === 4.U, 4.U, 5.U))
  val perWord = 1.U(7.W) << wordShift
  val done = Wire(Vec(banks, Bool()))
  val doneAfterIssue = Wire(Vec(banks, Bool()))

  for (bank <- 0 until banks) {
    io.readEnable(bank) := io.enable && ((active && !loaded(bank) &&
      !pending(bank) && base(bank) < io.limits(bank)) || (io.start && io.limits(bank) =/= 0.U))
    io.readAddress(bank) := Mux(io.start, 0.U, base(bank) >> wordShift)
    done(bank) := base(bank) >= io.limits(bank) && !loaded(bank) && !pending(bank)
    doneAfterIssue(bank) := done(bank) || (loaded(bank) &&
      (remaining(bank) & (remaining(bank) - 1.U)) === 0.U && base(bank) +& perWord >= io.limits(bank))
    io.laneValid(bank) := loaded(bank) && remaining(bank).orR

    // W8/W4/W2 select 8/16/32 candidates. Exclude padding in the final word.
    val mask = VecInit((0 until 32).map { i =>
      val nonzero8 = if (i < 8) io.readData(bank)(8 * i + 7, 8 * i).orR else false.B
      val nonzero4 = if (i < 16) io.readData(bank)(4 * i + 3, 4 * i).orR else false.B
      val nonzero2 = io.readData(bank)(2 * i + 1, 2 * i).orR
      val nonzero = Mux(io.precision === 8.U, nonzero8,
        Mux(io.precision === 4.U, nonzero4, nonzero2))
      i.U < perWord && base(bank) +& i.U < io.limits(bank) && nonzero
    }).asUInt
    val index = PriorityEncoder(remaining(bank))
    io.position(bank) := base(bank) + index
    val shift = Mux(io.precision === 8.U, index << 3,
      Mux(io.precision === 4.U, index << 2, index << 1))
    val value = words(bank) >> shift
    io.weight(bank) := Mux(io.precision === 8.U, value(7, 0).asSInt,
      Mux(io.precision === 4.U, Cat(Fill(4, value(3)), value(3, 0)).asSInt,
        Cat(Fill(6, value(1)), value(1, 0)).asSInt))

    when(pending(bank)) {
      words(bank) := io.readData(bank)
      remaining(bank) := mask
      loaded(bank) := mask.orR
      // An all-zero word consumes no selection token; inspect the next word.
      when(!mask.orR) { base(bank) := base(bank) + perWord }
    }
    when(io.valid && io.laneValid(bank)) {
      val rest = remaining(bank) & ~(1.U(32.W) << index)(31, 0)
      remaining(bank) := rest
      when(!rest.orR) {
        loaded(bank) := false.B
        base(bank) := base(bank) + perWord
      }
    }
    when(io.start) {
      base(bank) := 0.U
      remaining(bank) := 0.U
      loaded(bank) := false.B
    }
  }

  io.busy := active
  // A zero-valued end token is mandatory even for an entirely zero column.
  // It initializes a first-window accumulator and carries normal end/drain tags.
  io.valid := io.enable && active && (io.laneValid.asUInt.orR || done.asUInt.andR)
  io.first := first
  io.last := io.valid && doneAfterIssue.asUInt.andR
  when(io.valid) { first := false.B }
  when(io.last) { active := false.B }
  when(io.start) {
    assert(!active && !pending.asUInt.orR, "Restarted zero selector with an outstanding SRAM read")
    active := true.B
    first := true.B
  }
}
