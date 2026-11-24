package LUMIXED_PACKAGE

import chisel3._
import chisel3.experimental.IntParam
import chisel3.util.HasBlackBoxInline

/** Combinational, full-precision signed multiplication with unequal operands.
  *
  * CIRCT's FIRRTL-to-HW lowering extends both SInt multiply operands to the
  * result width. Keeping this small operation in signed SystemVerilog exposes
  * the original operand widths to downstream synthesis. No registers or latency.
  */
class ExactScaleMultiplier(partialWidth: Int, scaleWidth: Int)
    extends BlackBox(Map(
      "PARTIAL_WIDTH" -> IntParam(partialWidth),
      "SCALE_WIDTH" -> IntParam(scaleWidth))) with HasBlackBoxInline {
  require(partialWidth > 0 && scaleWidth > 0)
  val io = IO(new Bundle {
    val partial = Input(SInt(partialWidth.W))
    val scale_sig = Input(SInt(scaleWidth.W))
    val product = Output(SInt((partialWidth + scaleWidth).W))
  })

  setInline("ExactScaleMultiplier.sv",
    """module ExactScaleMultiplier #(
      |  parameter integer PARTIAL_WIDTH = 1,
      |  parameter integer SCALE_WIDTH = 1
      |) (
      |  input  wire signed [PARTIAL_WIDTH-1:0] partial,
      |  input  wire signed [SCALE_WIDTH-1:0] scale_sig,
      |  output wire signed [PARTIAL_WIDTH+SCALE_WIDTH-1:0] product
      |);
      |  assign product = partial * scale_sig;
      |endmodule
      |""".stripMargin)
}
