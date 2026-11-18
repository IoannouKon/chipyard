package LUMIXED_PACKAGE

import chisel3._
import chisel3.util._

/** Decode without rounding: every finite binary16 is an integer times 2^-24. */
object FP16Activation {
  def significand(bits: UInt): UInt = {
    val exponent = bits(14, 10)
    Mux(exponent.andR, 0.U(11.W), Cat(exponent.orR, bits(9, 0)))
  }
  def shift(bits: UInt): UInt = Mux(bits(14, 10) === 0.U, 0.U, bits(14, 10) - 1.U)
}

class FP16ProductMeta extends Bundle {
  val shift = UInt(5.W)
  val nonZeroWeight = Bool()
  // bit 0: NaN, bit 1: positive infinity, bit 2: negative infinity.
  val flags = UInt(3.W)
}

/** Round an exact fixed-point dot product once to FP32 (nearest, ties to even).
  * There is no intermediate FP16 rounding, multiplier, or FP adder here.
  * Accumulation starts at +0; exact cancellation returns +0; NaNs are canonical.
  */
class Fixed24ToFP32(width: Int, fractionalBits: Int = 24) extends Module {
  require(width >= 26 && (fractionalBits == 24 || fractionalBits == 48))
  val io = IO(new Bundle {
    val value = Input(SInt(width.W))
    val flags = Input(UInt(3.W))
    val bits = Output(UInt(32.W))
  })
  val negative = io.value < 0.S
  val magnitude = Mux(negative, -io.value, io.value).asUInt
  val leadingZeros = PriorityEncoder(Reverse(magnitude))
  val top = (width - 1).U - leadingZeros
  val normalized = (magnitude << leadingZeros)(width - 1, 0)
  val significand = normalized(width - 1, width - 24)
  val guard = normalized(width - 25)
  val sticky = normalized(width - 26, 0).orR
  val rounded = significand +& (guard && (sticky || significand(0)))
  val exponent = Wire(UInt(8.W))
  exponent := (top +& (127 - fractionalBits).U) + rounded(24)
  val fraction = Mux(rounded(24), 0.U(23.W), rounded(22, 0))
  val finite = Mux(magnitude === 0.U, 0.U(32.W), Cat(negative, exponent, fraction))
  val nan = io.flags(0) || (io.flags(1) && io.flags(2))
  io.bits := Mux(nan, "h7fc00000".U,
    Mux(io.flags(1), "h7f800000".U,
      Mux(io.flags(2), "hff800000".U, finite)))
}
