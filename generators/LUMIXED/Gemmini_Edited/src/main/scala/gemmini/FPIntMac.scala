// See Gemmini LICENSE for license details. Port of the optional mixed datapath.
package gemmini
import chisel3._
import chisel3.util._
import hardfloat._

/** IEEE binary32 activation times signed integer, then FP32 addition.
  * Contract: RNE32(RNE32(x * w) + partial), not fused and not an FP16 cast.
  * The product uses only a 24 x weightBits unsigned significand multiplier.
  * Integer magnitudes include abs(-128). Subnormal products are exact because
  * a nonzero integer cannot make the input's 2^-149 quantum any smaller.
  * NaN/Inf/zero handling and normal-product RNE precede HardFloat FP32 add.
  */
class FPIntMac(weightBits: Int) extends Module {
  require(weightBits == 8, "Edited Gemmini currently validates signed INT8 only")
  val io = IO(new Bundle {
    val activation = Input(UInt(32.W))
    val weight = Input(SInt(weightBits.W))
    val partial = Input(UInt(32.W))
    val result = Output(UInt(32.W))
  })
  val x = io.activation
  val exponent = x(30,23)
  val fraction = x(22,0)
  val mantissa = Cat(exponent.orR, fraction)
  val negativeWeight = io.weight < 0.S
  val magnitude = Wire(UInt(weightBits.W))
  magnitude := Mux(negativeWeight, ~io.weight.asUInt + 1.U, io.weight.asUInt)
  val product = mantissa * magnitude // 24 x 8, never an FP32 x FP32 multiplier
  val leading = 31.U - PriorityEncoder(Reverse(product))
  val right = Mux(leading > 23.U, leading - 23.U, 0.U)
  val left = Mux(leading < 23.U, 23.U - leading, 0.U)
  val retained = Mux(right.orR, product >> right, (product << left)(31,0))
  val mask = ((1.U(33.W) << right) - 1.U)(31,0)
  val discarded = product & mask
  val half = (1.U(32.W) << Mux(right.orR, right - 1.U, 0.U))(31,0)
  val increment = right.orR && ((discarded > half) || (discarded === half && retained(0)))
  val rounded = retained +& increment
  val carry = rounded(24)
  val normalized = Mux(carry, rounded(24,1), rounded(23,0))
  val base = Mux(exponent === 0.U, (-22).S(12.W), exponent.zext - 23.S)
  val biased = base + leading.zext + carry.asUInt.zext
  val sign = x(31) ^ negativeWeight
  val finite = Mux(biased <= 0.S, Cat(sign, 0.U(8.W), product(22,0)),
    Mux(biased >= 255.S, Cat(sign, "hff".U(8.W), 0.U(23.W)),
      Cat(sign, biased.asUInt(7,0), normalized(22,0))))
  val nan = exponent.andR && fraction.orR
  val invalid = exponent.andR && !magnitude.orR
  val encoded = Mux(nan || invalid, "h7fc00000".U,
    Mux(exponent.andR, Cat(sign, "h7f800000".U(31.W)),
      Mux(!product.orR, Cat(sign, 0.U(31.W)), finite)))
  val add = Module(new AddRecFN(8,24))
  add.io.subOp := false.B
  add.io.a := recFNFromFN(8,24,encoded)
  add.io.b := recFNFromFN(8,24,io.partial)
  add.io.roundingMode := consts.round_near_even
  add.io.detectTininess := consts.tininess_afterRounding
  io.result := fNFromRecFN(8,24,add.io.out)
}
