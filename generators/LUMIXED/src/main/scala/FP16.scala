package LUMIXED_PACKAGE

import chisel3._
import chisel3.util._
import hardfloat._

/** IEEE FP32 addition, round-nearest/ties-to-even, with canonical NaN output. */
class FP32AccumulatorAdd extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(32.W))
    val b = Input(UInt(32.W))
    val result = Output(UInt(32.W))
  })
  val add = Module(new AddRecFN(8, 24))
  add.io.subOp := false.B
  add.io.a := recFNFromFN(8, 24, io.a)
  add.io.b := recFNFromFN(8, 24, io.b)
  add.io.roundingMode := consts.round_near_even
  add.io.detectTininess := consts.tininess_afterRounding
  val bits = fNFromRecFN(8, 24, add.io.out)
  io.result := Mux(bits(30, 23).andR && bits(22, 0).orR, "h7fc00000".U, bits)
}

/** Single-round IEEE FP32 fused multiply-add. No intermediate product rounding.
  * The scale originates in finite FP16, but its exact FP32 encoding is carried
  * to HardFloat. A standard FMA is the initial reference implementation: do not
  * assume synthesis specializes the low significand bits without measurement.
  */
class FP32AccumulatorFMA extends Module {
  val io = IO(new Bundle {
    val fragment = Input(UInt(32.W))
    val scale = Input(UInt(32.W))
    val previous = Input(UInt(32.W))
    val result = Output(UInt(32.W))
  })
  val fma = Module(new MulAddRecFN(8, 24))
  fma.io.op := 0.U
  fma.io.a := recFNFromFN(8, 24, io.fragment)
  fma.io.b := recFNFromFN(8, 24, io.scale)
  fma.io.c := recFNFromFN(8, 24, io.previous)
  fma.io.roundingMode := consts.round_near_even
  fma.io.detectTininess := consts.tininess_afterRounding
  val bits = fNFromRecFN(8, 24, fma.io.out)
  io.result := Mux(bits(30, 23).andR && bits(22, 0).orR, "h7fc00000".U, bits)
}

/** Decode binary16/binary32 exactly as an integer significand and binary shift.
  * Finite values use units of 2^-24 (FP16) or 2^-149 (FP32).
  */
object FPActivation {
  def fractionBits(bits: Int): Int = if (bits == 16) 10 else 23
  def exponentBits(bits: Int): Int = if (bits == 16) 5 else 8
  def significand(value: UInt, bits: Int): UInt = {
    val f = fractionBits(bits)
    val exponent = value(bits - 2, f)
    Mux(exponent.andR, 0.U((f + 1).W), Cat(exponent.orR, value(f - 1, 0)))
  }
  def shift(value: UInt, bits: Int): UInt = {
    val exponent = value(bits - 2, fractionBits(bits))
    Mux(exponent === 0.U, 0.U, exponent - 1.U)
  }
}

// Quantization group scales remain binary16 in both activation modes.
object FP16Activation {
  def significand(bits: UInt): UInt = FPActivation.significand(bits, 16)
  def shift(bits: UInt): UInt = FPActivation.shift(bits, 16)
}

class FPProductMeta(bits: Int) extends Bundle {
  val shift = UInt(FPActivation.exponentBits(bits).W)
  val nonZeroWeight = Bool()
  // bit 0: NaN, bit 1: positive infinity, bit 2: negative infinity.
  val flags = UInt(3.W)
}

/** Round the exact sum once to FP32, nearest/ties-to-even.
  * Supports gradual underflow, overflow, canonical NaNs and +0 cancellation.
  * Group scaling adds 24 fractional bits because scales are still FP16.
  */
class FixedToFP32(width: Int, fractionalBits: Int) extends Module {
  require(width >= 26 && Set(24, 48, 149, 173).contains(fractionalBits))
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
  val exponent = top.zext + (127 - fractionalBits).S + rounded(24).asUInt.zext
  val fraction = Mux(rounded(24), 0.U(23.W), rounded(22, 0))
  val normal = Mux(exponent >= 255.S, Cat(negative, "h7f800000".U(31.W)),
    Cat(negative, exponent.asUInt(7, 0), fraction))
  val finiteNonzero = if (fractionalBits >= 149) {
    val drop = fractionalBits - 149
    val sub = if (drop == 0) magnitude else {
      val upper = magnitude >> drop
      val guard = magnitude(drop - 1)
      val sticky = if (drop > 1) magnitude(drop - 2, 0).orR else false.B
      upper +& (guard && (sticky || upper(0)))
    }
    // Bit 23 naturally carries a rounded subnormal into the smallest normal.
    Mux(top < (fractionalBits - 126).U, Cat(negative, 0.U(7.W), sub(23, 0)), normal)
  } else normal
  val finite = Mux(magnitude === 0.U, 0.U(32.W), finiteNonzero)
  val nan = io.flags(0) || (io.flags(1) && io.flags(2))
  io.bits := Mux(nan, "h7fc00000".U,
    Mux(io.flags(1), "h7f800000".U,
      Mux(io.flags(2), "hff800000".U, finite)))
}
