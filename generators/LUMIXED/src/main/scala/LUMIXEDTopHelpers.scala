package LUMIXED_PACKAGE

import chisel3._
import chisel3.util._

// Helper calls elaborate in their caller's when/switch scope.
// Controller state and registers are declared in LUMIXED_Top.scala.
trait LUMIXEDTopHelpers {
  this: LUMIXEDExampleModuleImpl =>

  // Only the supported power-of-two precisions reach the packing datapath.
  // Keeping the decode explicit avoids inferring general divider/remainder logic.
  protected def wordShift(precision: UInt): UInt = MuxLookup(precision, 0.U(3.W))(Seq(
    2.U -> 5.U(3.W), 4.U -> 4.U(3.W), 8.U -> 3.U(3.W), 16.U -> 2.U(3.W), 32.U -> 1.U(3.W)))
  protected def bitShift(precision: UInt): UInt = MuxLookup(precision, 0.U(3.W))(Seq(
    2.U -> 1.U(3.W), 4.U -> 2.U(3.W), 8.U -> 3.U(3.W), 16.U -> 4.U(3.W), 32.U -> 5.U(3.W)))

  protected def bankOffset(span: UInt, bank: Int): UInt = {
    // The bank number is an elaboration constant. At most log2(b) shifted
    // terms replace the generic multiplication; no run-time bank multiplier.
    val terms = (0 until math.max(1, log2Ceil(bank + 1))).filter(i => (bank & (1 << i)) != 0)
    if (terms.isEmpty) 0.U else terms.map(i => span << i).reduce(_ + _)
  }

  protected def readActivations(address: UInt, enable: Bool): Seq[UInt] = {
    // Callers occupy mutually exclusive controller states. Mux before the
    // single physical read port, preserving its existing one-cycle latency.
    activationReadAddress := address
    activationReadEnable := enable
    activationReadData.get.toSeq
  }

  // Each lane's starting bank is known at elaboration. Only a bank crossing
  // from the shared cursor can rotate it; a compare/subtract handles wrap.
  protected def rotatedActivationBank(lane: Int, shift: UInt): UInt = {
    val bank = lane.U +& shift
    Mux(bank >= totalSyncMems.U, bank - totalSyncMems.U, bank)
  }

  //////////////////////////////////////////////////////////////// Help Functions ////////////////////////////////////////////////////////

  // A source beat may start partway through a destination word. Consume only
  // through that word's end; the next request can reread the same source beat.
  // Bank boundaries can be crossed in parallel.
  protected def packedReadCount(count: UInt, firstElement: UInt, elementsPerWord: UInt): UInt = {
    val elementsPerBank = blocks_in_Sync_mem * x_elems_per_reg
    val limited = WireDefault(count)
    for (bank <- 0 until weightBanks) {
      val bankStart = bankOffset(elementsPerBank, bank)
      val bankEnd = bankStart + elementsPerBank
      val local = firstElement - bankStart
      val wordRemaining = elementsPerWord - (local & (elementsPerWord - 1.U))
      when(firstElement >= bankStart && firstElement < bankEnd &&
        wordRemaining < bankEnd - firstElement) {
        limited := count.min(wordRemaining)
      }
    }
    limited
  }

  // Keep the unfinished word across responses. At most the last bank touched
  // by a response is unfinished, so one existing carry register per stream is
  // sufficient; no additional memory ports or per-bank register arrays are used.
  protected def storePackedResponse(
    memories: Seq[SyncReadMem[UInt]],
    precision: UInt,
    firstElement: UInt,
    elementCount: UInt,
    linearElement: UInt,
    carry: UInt,
    bankStarts: Seq[UInt] = Seq.empty,
    bankEnables: Seq[Bool] = Seq.empty
  ): Unit = {
    val elementsPerBank = blocks_in_Sync_mem * x_elems_per_reg
    val elementsPerWord = 1.U(7.W) << wordShift(precision)
    val beatOffset = (linearElement << bitShift(precision)) & (params.DMA_bits - 1).U
    val responseEnd = firstElement + elementCount
    for (bank <- memories.indices) {
      val bankStart = if (bankStarts.nonEmpty) bankStarts(bank) else bankOffset(elementsPerBank, bank)
      val bankEnabled = if (bankEnables.nonEmpty) bankEnables(bank) else true.B
      val first = firstElement.max(bankStart)
      val end = responseEnd.min(bankStart + elementsPerBank)
      when(bankEnabled && first < end) {
        val local = first - bankStart
        val row = local >> wordShift(precision)
        val bitOffset = Wire(UInt(log2Ceil(params.DMA_bits).W))
        bitOffset := ((local & (elementsPerWord - 1.U)) << bitShift(precision))
        val responseShift = Wire(UInt(log2Ceil(params.DMA_bits).W))
        responseShift := beatOffset + ((first - firstElement) << bitShift(precision))
        val validBits = Wire(UInt(log2Ceil(params.DMA_bits + 1).W))
        validBits := ((end - first) << bitShift(precision))
        val mask = ((1.U((params.DMA_bits + 1).W) << validBits) - 1.U)(params.DMA_bits - 1, 0)
        val packed = ((dma.io.readData >> responseShift) & mask) << bitOffset
        val assembled = Mux(bitOffset === 0.U, 0.U, carry) | packed
        memories(bank).write(row, assembled)
        when(end === responseEnd) {
          carry := assembled
        }
      }
    }
  }

  protected def dmaRequest(
    index: UInt,
    elemsPerReg: UInt,
    elemsPerChunk: UInt,
    baseAddr: UInt,
    regsPerChunk: UInt,
    allAvailableElems: UInt
  ): UInt = {

    // calculate next index
    val consumed = packedReadCount(elements_to_read, dma_counter_a, elemsPerChunk)
    val index_next = index + consumed

    // send DMA request
    dma.io.mode  := false.B
    dma.io.addr  := chunk_addres
    dma.io.valid := true.B

    when(dma.io.a_fire) {
      // update DMA counter
      dma_counter_a := dma_counter_a + consumed

      // update send flag
      dma_send := dma_counter_a < allAvailableElems - consumed

      // configure chunk info module inputs
      chunkInfoModule_A.io.j_x              := index_next
      chunkInfoModule_A.io.x_elems_per_reg  := elemsPerReg
      chunkInfoModule_A.io.elemsPerChunk_in := elemsPerChunk
      chunkInfoModule_A.io.adr_X            := baseAddr
      chunkInfoModule_A.io.RegsPerChunk_in  := regsPerChunk

      // calculate next counter
      val dma_counter_a_next = dma_counter_a + consumed

      // update outputs
      elements_to_read := Mux(
        chunkInfoModule_A.io.elements_to_read <= (allAvailableElems - dma_counter_a_next),
        chunkInfoModule_A.io.elements_to_read,
        allAvailableElems - dma_counter_a_next
      )

      chunk_addres := chunkInfoModule_A.io.chunk_address

    }
    Mux(dma.io.a_fire, index_next, index)

  }

  protected def dmaResponce(
    indexTemp: UInt,
    elemsPerReg: UInt,
    elemsPerChunk: UInt,
    regsPerChunk: UInt,
    inputBits: UInt,
    maxBits: UInt,
    allAvailableElems: UInt,
    updateValidInputs: Boolean
  ): UInt = {

    // next values
    val consumed = packedReadCount(elements_to_read_temp, dma_counter_d, elemsPerChunk)
    val index_temp_next      = indexTemp + consumed
    val dma_counter_d_next   = dma_counter_d + consumed

    // optional valid_inputs update (only for X)
    if (updateValidInputs) {
      valid_inputs := valid_inputs + consumed
    }

    // update registers
    dma_counter_d := dma_counter_d_next
    dma_resp      := dma_counter_d < allAvailableElems - consumed

    // configure chunk info inputs
    chunkInfoModule_D.io.j_x_temp         := index_temp_next
    chunkInfoModule_D.io.x_elems_per_reg  := elemsPerReg
    chunkInfoModule_D.io.elemsPerChunk_in := elemsPerChunk
    chunkInfoModule_D.io.RegsPerChunk_in  := regsPerChunk
    chunkInfoModule_D.io.dma_counter_d    := dma_counter_d_next
    chunkInfoModule_D.io.input_bits       := inputBits
    chunkInfoModule_D.io.max_bits         := maxBits

    // safe clamp
    elements_to_read_temp := Mux(
      chunkInfoModule_D.io.elements_to_read_temp <= (allAvailableElems - dma_counter_d_next),
      chunkInfoModule_D.io.elements_to_read_temp,
      allAvailableElems - dma_counter_d_next
    )

    // return updated index
    index_temp_next
  }

}
