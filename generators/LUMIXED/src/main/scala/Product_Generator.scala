package LUMIXED_PACKAGE

import chisel3._
import chisel3.util._

class Product_Generator(
    val totalSyncMems: Int,
    val maxParts: Int,
    val WBitWidth: Int,
    val XBitWidth: Int,
    val minWeightBits:Int,
    val RowsPerBlock:Int,
    val DMA_bits:Int,
    val product_bitwidth:Int,
    val minInputBits:Int,
    val FP16_ACTIVATIONS: Boolean = false,
    val banksPerRow: Int = 0,
    val flexibleGrouping: Boolean = false
) extends Module {

  val io = IO(new Bundle {

    val logicalStart = if (flexibleGrouping) Some(Input(UInt(math.max(1, log2Ceil(totalSyncMems * RowsPerBlock + 1)).W))) else None
    val sync_mem_idx            = Input(UInt(log2Ceil(totalSyncMems).W))
    val blocks_in_Sync_mem      = Input(UInt(log2Ceil(RowsPerBlock + 1).W))
    val counter                 = Input(UInt(log2Ceil(RowsPerBlock + 2).W))
    val dirty_elems_x           = Input(UInt(log2Ceil(DMA_bits / minInputBits).W))
    // The shared address counter holds these throughout one activation's LUT
    // generation; it advances only after all product rows have been written.
    val activation_bank        = Input(UInt(math.max(1, log2Ceil(totalSyncMems)).W))
    val activation_cell        = Input(UInt(math.max(1, log2Ceil(DMA_bits / XBitWidth)).W))
    val X_vals                  = Input(Vec(totalSyncMems, UInt(DMA_bits.W)))
    val inputs_per_reg          = Input(UInt(2.W))
    val input_bits              = Input(UInt(6.W))
    // Validity is a count, not a bitmap; use its count width.
    val valid_inputs = Input(UInt(log2Ceil(totalSyncMems * RowsPerBlock + 1).W))
    val mask_p                  = Input(UInt(log2Ceil((1 << (WBitWidth + XBitWidth))).W))
    val shiftAmt                = Input(UInt(7.W))
    val chunks_per_Product_bits = Input(UInt(log2Ceil(WBitWidth + XBitWidth + 1).W))
    val Product_in              = Input(UInt(product_bitwidth.W))

    val Product_out             = Output(UInt(product_bitwidth.W))
  })

  // Instantiate chunkExtenders
  val chunkExtenders: Seq[ChunkSignExtender] = Seq.fill(maxParts) {
    Module(new ChunkSignExtender(XBitWidth, minInputBits))
  }

  // Initialize all chunkExtender IOs to safe defaults
  for (j <- 0 until maxParts) {
    val ext = chunkExtenders(j)
    ext.io.data       := 0.U
    ext.io.input_bits := 0.U
    ext.io.idx        := 0.U
  }

  val global_block_idx   =  io.sync_mem_idx * io.blocks_in_Sync_mem + io.counter
  val x_th_x_reg = global_block_idx + io.dirty_elems_x

        val data_64 = io.X_vals(io.activation_bank)
        val bytes = VecInit(Seq.tabulate(DMA_bits / XBitWidth)(i => data_64((i + 1) *  XBitWidth - 1, i *  XBitWidth)))
        val elem_start = if (banksPerRow > 0) {
          // The table's bank includes its activation row; validity is per row.
          val localBlock = (if (flexibleGrouping) io.logicalStart.get else (io.sync_mem_idx % banksPerRow.U) * io.blocks_in_Sync_mem) + io.counter + io.dirty_elems_x
          localBlock * io.inputs_per_reg
        } else x_th_x_reg * io.inputs_per_reg

        // Create a properly-sized wire for the index
        val byte_index = Wire(UInt(log2Ceil(math.max(DMA_bits / XBitWidth, 2)).W))
        byte_index := io.activation_cell
        val data = bytes(byte_index)
        val P_data    =   io.Product_in
        val P_slices  =   Wire(Vec(maxParts, UInt((product_bitwidth).W)))

        // Generate packed product slices from the selected activation.
        for (i <- 0 until maxParts) {

          val extender = chunkExtenders(i)
          extender.io.data       := data
          extender.io.input_bits := io.input_bits
          extender.io.idx        := i.U
          val signedChunk = extender.io.out

          val absVal_new = Mux(signedChunk < 0.S, (-signedChunk), signedChunk)
          // Retain a carry bit so narrowing the port does not truncate the sum,
          // even at the maximum representable count plus a dirty-cell offset.
          val validEnd = io.valid_inputs +& io.dirty_elems_x
          val valid_x = validEnd > elem_start + i.U
          val absVal = if (FP16_ACTIVATIONS) {
            // One FP16 per 16-bit lane. The table contains exact integer significands.
            if (i == 0) Mux(valid_x, FP16Activation.significand(data), 0.U(11.W))
            else 0.U(11.W)
          } else {
            Mux(valid_x ,absVal_new.asUInt,0.U)
          }

          val chunk_p   = (P_data >> (i.U * io.chunks_per_Product_bits)) & io.mask_p
          val scaled    = (absVal << 1)
          P_slices(i)   := Mux(i.U < (XBitWidth/minInputBits).U , chunk_p + scaled,chunk_p)

        }

        val packed = Wire(UInt(product_bitwidth.W))
        var acc: UInt = 0.U

        // Pack the updated slices into one product-table word.
        for (i <- 0 until maxParts) {
          val shiftAmt = i.U * io.chunks_per_Product_bits
          val shifted  = P_slices(i) << shiftAmt
          acc = acc | shifted
        }

        packed := acc
        io.Product_out   := packed
}

