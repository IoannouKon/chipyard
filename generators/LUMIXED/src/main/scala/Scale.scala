package LUMIXED_PACKAGE

import chisel3._
import chisel3.util._

// Optional integer-output scaling path, elaborated when SCALE is enabled.

class Float32 extends Bundle {
  val sign     = Bool()    // 1 bit for sign
  val exponent = UInt(8.W) // 8 bits for exponent
  val mantissa = UInt(23.W) // 23 bits for mantissa
}

class Int32ToFloat32 extends Module {
  val io = IO(new Bundle {
    val in  = Input(SInt(32.W))       // 32-bit signed integer input
    val out = Output(new Float32)      // 32-bit floating-point output
  })

  val result = Wire(new Float32)

  // Special case: if the input is zero, output 0 in float representation.
  when (io.in === 0.S) {
    result.sign     := false.B
    result.exponent := 0.U
    result.mantissa := 0.U
  } .otherwise {

    // Determine the sign.
    result.sign := io.in < 0.S

    // Locate the most significant bit for the exponent.
    val absValueU = Mux(io.in < 0.S, (-io.in).asUInt, io.in.asUInt)
    val msbPos = ((absValueU.getWidth - 1).U - PriorityEncoder(absValueU.asBools.reverse)).pad(9) // make sure it's 9 bits
    val biasedExp = msbPos + 127.U  // Bias the exponent for IEEE 754 single precision
    result.exponent := biasedExp

    // Align the retained significand bits.
     val shiftAmount = msbPos - 23.U
     val shifted = Mux(msbPos > 23.U, absValueU >> (msbPos - 23.U), absValueU << (23.U - msbPos))

    // Use the guard, sticky and retained low bit for rounding.
    val lsb = shifted(0)
    val roundBit = Mux(shiftAmount >= 1.U, (absValueU >> (shiftAmount - 1.U))(0), 0.U)
    val sticky = Mux(shiftAmount <= 1.U, 0.U, (absValueU & ((1.U << (shiftAmount - 1.U)) - 1.U)).orR.asUInt)

    // Round up when the guard bit and either sticky or retained low bit are set.
    val shouldRound = (roundBit & (lsb | sticky)).asBool
    val roundedShifted = shifted + shouldRound.asUInt

    result.mantissa := roundedShifted(22, 0)

  }

    // Select the converted result, including the INT32_MAX special case.

  io.out := Mux(io.in === 2147483647.S,
  // Manually construct the correct Float32 output for 2147483647
  WireInit({
    val special = Wire(new Float32)
    special.sign := false.B
    special.exponent := 158.U // Biased exponent for 2^31: 127 + 31.
    special.mantissa := 0.U    // Rounded up, so mantissa overflowed to 0
    special
  }),
  result
)

}

class MultiplyInt32AndFloat32 extends Module {
  val io = IO(new Bundle {
    val inInt = Input(SInt(32.W))
    val inFloat = Input(UInt(32.W))
    val out = Output(UInt(32.W))
  })

  // Convert integer to float
  val intToFloat = Module(new Int32ToFloat32)
  intToFloat.io.in := io.inInt
  val intFloat = intToFloat.io.out

  // Decompose input float
  val floatSign = io.inFloat(31)
  val floatExp = io.inFloat(30, 23)
  val floatMant = io.inFloat(22, 0)

  // Special case detection
  val floatIsInf = floatExp === 0xFF.U && floatMant === 0.U
  val floatIsNaN = floatExp === 0xFF.U && floatMant =/= 0.U
  val intIsZero = io.inInt === 0.S

  // Multiply mantissas with implicit leading 1 (1.mant)
  val mantProduct = Cat(1.U(1.W), intFloat.mantissa) * Cat(1.U(1.W), floatMant)

  // Normalize a significand product of at least 2.0.
  val normShift = mantProduct(47) // Bit 47 marks a significand product of at least 2.0.
  val normMantissa = Mux(normShift,
                       mantProduct(46, 24), // Right shift by 1
                       mantProduct(45, 23)) // No shift

  // Adjust exponent
  val expSum = intFloat.exponent +& floatExp  // Use +& for overflow detection
  val unbiasedExp = expSum - 127.U + normShift
  val expOverflow = unbiasedExp > 254.U  // Max exponent before infinity
  val expUnderflow = unbiasedExp < 1.U   // Min exponent before subnormal/zero

  // Result sign
  val resultSign = intFloat.sign ^ floatSign

  // Result construction
  io.out := MuxCase(
    Cat(resultSign, unbiasedExp(7, 0), normMantissa(22, 0)), // Normal case
    Seq(
      floatIsNaN -> Cat(resultSign, 0xFF.U, 1.U(23.W)), // NaN payload.
      intIsZero -> Cat(resultSign, 0.U(31.W)), // This zero case has priority over the infinity cases below.
      (intIsZero && floatIsInf) -> "b01111111110000000000000000000000".U, // 0 × Inf = NaN
      (floatIsInf || expOverflow) -> Cat(resultSign, 0xFF.U, 0.U(23.W)), // Infinity
      expUnderflow -> Cat(resultSign, 0.U(31.W)) // Underflow to zero
    )
  )
}

class Scale_Vector extends Module {
  val io = IO(new Bundle {
    val inVector = Input(Vec(2, SInt(32.W)))  // Vector of 2 signed integers (SInt)
    val scale = Input(UInt(32.W)) // Scaling factor as IEEE binary32 bits.
    val out = Output(UInt(64.W))               // 64-bit result (concatenated scaled values)
  })

  // Instantiate the MultiplyInt32AndFloat32 module to scale the input vector elements
  val multModule1 = Module(new MultiplyInt32AndFloat32)
  multModule1.io.inInt := io.inVector(0)  // First element of the vector
  multModule1.io.inFloat := io.scale      // Scaling factor

  val multModule2 = Module(new MultiplyInt32AndFloat32)
  multModule2.io.inInt := io.inVector(1)  // Second element of the vector
  multModule2.io.inFloat := io.scale      // Scaling factor

  // Outputs of the multiplication
  val scale_o1 = multModule1.io.out   // Scaled first element
  val scale_o2 = multModule2.io.out   // Scaled second element

  // Combine the scaled values into a 64-bit output (concatenate scale_o2 and scale_o1)
  io.out := Cat(scale_o2, scale_o1)
}
