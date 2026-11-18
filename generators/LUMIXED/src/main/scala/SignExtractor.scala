package LUMIXED_PACKAGE

import chisel3._
import chisel3.util._

import chisel3._
import chisel3.util._

class ChunkSignExtender(val XBitWidth: Int, val minInputBits: Int) extends Module {
  require(Set(8, 16, 32).contains(XBitWidth), "XBitWidth must be 8, 16 or 32")
  require(Set(2, 4, 8).contains(minInputBits), "minInputBits must be 2, 4, or 8")

  val io = IO(new Bundle {
    val data       = Input(UInt(XBitWidth.W))
    val input_bits = Input(UInt(6.W)) // Runtime chunk width.
    val idx        = Input(UInt(8.W))              // Which chunk to extract

    val out        = Output(SInt(XBitWidth.W))     // Sign-extended output
  })

  val shift_amt = io.idx * io.input_bits
  val shifted = io.data >> shift_amt

  if (XBitWidth == 32) {
    // FP32 uses one complete lane; integer packing stays restricted to 8/16.
    io.out := io.data.asSInt
  } else if (XBitWidth == 8) {
    if (minInputBits == 2) {
      val chunk2 = shifted(1, 0)
      val chunk4 = shifted(3, 0)
      val chunk8 = shifted(7, 0)

      val se2 = Cat(Fill(6, chunk2(1)), chunk2).asSInt
      val se4 = Cat(Fill(4, chunk4(3)), chunk4).asSInt
      val se8 = Cat(chunk8(7), chunk8).asSInt

      io.out := Mux(io.input_bits === 2.U, se2,
                 Mux(io.input_bits === 4.U, se4, se8))
    } else if (minInputBits == 4) {
      val chunk4 = shifted(3, 0)
      val chunk8 = shifted(7, 0)

      val se4 = Cat(Fill(4, chunk4(3)), chunk4).asSInt
      val se8 = Cat(chunk8(7), chunk8).asSInt

      io.out := Mux(io.input_bits === 4.U, se4, se8)
    } else { // minInputBits == 8
      val chunk8 = shifted(7, 0)
      val se8 = Cat(chunk8(7), chunk8).asSInt
      io.out := se8
    }
  } else if (XBitWidth == 16) {
    if (minInputBits == 2) {

      val out_sint = Wire(SInt(XBitWidth.W))
      out_sint := 0.S                  // default initialization

      when(io.input_bits === 2.U) {
        out_sint := shifted(1, 0).asSInt
      }.elsewhen(io.input_bits === 4.U) {
        out_sint := shifted(3, 0).asSInt
      }.elsewhen(io.input_bits === 8.U) {
        out_sint := shifted(7, 0).asSInt
      }.otherwise {
        out_sint := shifted(15, 0).asSInt
      }

    } else if (minInputBits == 4) {
      val chunk4 = shifted(3, 0)
      val chunk8 = shifted(7, 0)
      val chunk16 = shifted(15, 0)

      val se4 = Cat(Fill(12, chunk4(3)), chunk4).asSInt
      val se8 = Cat(Fill(8, chunk8(7)), chunk8).asSInt
      val se16 = Cat(chunk16(15), chunk16).asSInt

      io.out := Mux(io.input_bits === 4.U, se4,
                 Mux(io.input_bits === 8.U, se8, se16))
    } else { // minInputBits == 8
      val chunk8 = shifted(7, 0)
      val chunk16 = shifted(15, 0)

      val se8 = Cat(Fill(8, chunk8(7)), chunk8).asSInt
      val se16 = Cat(chunk16(15), chunk16).asSInt

      io.out := Mux(io.input_bits === 8.U, se8, se16)
    }
  }
}

