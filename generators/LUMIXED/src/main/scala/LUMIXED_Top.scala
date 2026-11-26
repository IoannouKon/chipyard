package LUMIXED_PACKAGE

import chisel3._
import chisel3.util._

import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.{BuildRoCC, OpcodeSet}
import freechips.rocketchip.diplomacy.LazyModule
import os.truncate
import firrtl.PrimOps.Add
import scala.annotation.meta.param
import freechips.rocketchip.rocket._
import freechips.rocketchip.tilelink.TLMessages.c
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheReq}
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.subsystem.{CacheBlockBytes, CrossesToOnlyOneClockDomain}
import freechips.rocketchip.tilelink.{TLBuffer, TLInwardNode, TLToAXI4}
import chisel3.experimental._

import LUMIXED_PACKAGE.ChunkUtils._
import LUMIXED_PACKAGE.LUMIXED_Config

class LUMIXEDExample(opcodes: OpcodeSet, val params: LUMIXEDParams)
  (implicit p: Parameters) extends LazyRoCC(opcodes = opcodes) {

  override lazy val module = new LUMIXEDExampleModuleImpl(this)

  val dma = LazyModule(new DmaModule(params))

  tlNode   := dma.node  // Connect the memory module to the accelerator’s TileLink node

}

// File layout:
//   1. Parameters, command decode, storage and register groups.
//   2. Submodule instances, default connections and shared selection cursors.
//   3. The controller, including optional scale preload, in execution order.
//   4. Counter readback and weight prefetch that run alongside the controller.
// Packing and DMA helper method bodies live in LUMIXEDTopHelpers.scala.

class LUMIXEDExampleModuleImpl(outer: LUMIXEDExample)(implicit p: Parameters) extends LazyRoCCModuleImp(outer) with HasCoreParameters with LUMIXEDTopHelpers {

  // === Parameters and elaboration requirements ===
  val params: LUMIXEDParams = outer.params
  require(params.parallel_rows >= 1 && params.Rin >= params.parallel_rows,
    "Rin row capacity must be at least parallel_rows")
  if (params.FP_ACTIVATIONS) {
    require(params.XBitWidth == params.FP_BITS && params.OutBitWidth == 32,
      "FP activations require XBitWidth=FP_BITS, OutBitWidth=32")
    require(params.Cin >= 1 && params.Cin <= 65536 && params.WBitWidth == 8,
      "FP16 supports Cin<=65536 and signed W8/W4/W2")
  }
  // Maximum finite FP16 * signed W8 in units of 2^-24 is strictly below 2^47.
  val groupScaling = params.GROUP_SCALE_ALIGNED || params.GROUP_SCALE_DEFERRED
  require(!params.ROUNDED_ACCUMULATION ||
    (params.FP_ACTIVATIONS && params.FP_BITS == 32 && !groupScaling),
    "Rounded accumulation currently requires unscaled FP32 with SRAM outputs")
  require(!(params.GROUP_SCALE_ALIGNED && params.GROUP_SCALE_DEFERRED), "Choose one group-scaling mode")
  if (groupScaling) {
    require(params.DMA_Bytes == 8, "Initial group scaling requires 8-byte DMA beats")
    require(params.FP_ACTIVATIONS && !params.FLEXIBLE_BANK_GROUPING &&
      !params.SKIP_ZERO_WEIGHTS && !params.WEIGHT_DMA_BURSTS && !params.EARLY_WEIGHT_BUFFER_RELEASE,
      "Initial group scaling supports dense fixed-bank FP16 with SRAM outputs")
  }
  val partialWidth = (if (params.FP_BITS == 16) 48 else 285) + log2Ceil(params.Cin)
  val accumulatorWidth = if (groupScaling) (if (params.FP_BITS == 16) 88 else 325) + log2Ceil(params.Cin)
    else if (params.FP_ACTIVATIONS) partialWidth else params.OutBitWidth
  val outputAccumulatorWidth = if (params.ROUNDED_ACCUMULATION) 32 else accumulatorWidth
  val dma = outer.dma.module
  val fpGeometry = params.FP_ACTIVATIONS
  val activationCells = params.DMA_bits / params.XBitWidth
  val activationDepth = (((1 << (params.WBitWidth - params.minWeightBits)) * params.table_capacity_factor) + activationCells - 1) / activationCells
  require(params.OutBitWidth == 32,
    "SRAM outputs require INT32 or FP32 output words")

  // === Command interface and instruction decode ===
  // ROCC interface
  val cmd = Queue(io.cmd)
  val funct = cmd.bits.inst.funct
  val rs1 = cmd.bits.rs1 // First RoCC source operand.
  val rs2 = cmd.bits.rs2 // Second RoCC source operand.

  // RoCC ABI v2: six explicit command IDs; configuration words may arrive
  // in any order. START seals a complete configuration and initializes the run.
  val setXW = funct === 0.U
  val setOutput = funct === 1.U
  val setShape = funct === 2.U
  val setExecution = funct === 3.U
  val startMatmul = funct === 4.U
  val control = funct === 5.U
  val waitDone = control && rs1 === 0.U
  val readCounter = control && rs1 === 1.U
  val configured = RegInit(0.U(4.W))
  val configuredSlice = RegInit(0.U(16.W))
  val configuredWeightSlice = RegInit(0.U(16.W))
  val configuredDmaLimit = RegInit(0.U(16.W))
  val counterResponses = Module(new Queue(chiselTypeOf(io.resp.bits), 1))
  io.resp <> counterResponses.io.deq
  counterResponses.io.enq.valid := false.B
  counterResponses.io.enq.bits := 0.U.asTypeOf(counterResponses.io.enq.bits)

  // === Memory geometry ===
  //SyncMem compile parameters
  val RowsPerBlock      = (1 << (params.WBitWidth - params.minWeightBits)) * params.table_capacity_factor // Maximum logical activation positions per bank.
  val totalSyncMems     =  (params.banks_per_row * params.parallel_rows) // Activation and product-table banks: b times Y_SLICE.
  // Flexible builds keep B*YS physical compute lanes, but provision weight
  // banks for the largest B (YS=1). The same DMA engine serves every mode.
  val weightBanks = if (params.FLEXIBLE_BANK_GROUPING) totalSyncMems else params.banks_per_row
  val groupingRows = if (params.FLEXIBLE_BANK_GROUPING)
    (0 to log2Ceil(params.parallel_rows)).map(1 << _) else Seq(params.parallel_rows)
  if (params.FLEXIBLE_BANK_GROUPING) {
    require(params.FP_ACTIVATIONS,
      "Flexible grouping initially supports FP16 with SRAM outputs")
    require(params.banks_per_row > 0 && isPow2(params.parallel_rows),
      "Flexible grouping requires positive B and power-of-two maximum YS")
    require(!params.SKIP_ZERO_WEIGHTS && !params.EARLY_WEIGHT_BUFFER_RELEASE && !params.WEIGHT_DMA_BURSTS,
      "Flexible grouping initially uses the dense, ordinary-DMA path")
  }
  // Grouping is held throughout a matmul. Tail rows change YS, not this mapping.
  def laneBank(lane: Int): UInt = if (params.FLEXIBLE_BANK_GROUPING)
    MuxLookup(y_slice_Reg, (lane % params.banks_per_row).U)(groupingRows.map(y =>
      y.U -> (lane % (totalSyncMems / y)).U)) else (lane % weightBanks).U
  def laneRow(lane: Int): UInt = if (params.FLEXIBLE_BANK_GROUPING)
    MuxLookup(y_slice_Reg, (lane / params.banks_per_row).U)(groupingRows.map(y =>
      y.U -> (lane / (totalSyncMems / y)).U)) else (lane / weightBanks).U
  def banksForRow: UInt = if (params.FLEXIBLE_BANK_GROUPING)
    MuxLookup(y_slice_Reg, params.banks_per_row.U)(groupingRows.map(y => y.U -> (totalSyncMems / y).U))
    else weightBanks.U
  // Share one constant base-B division across all modes, then use shifts.
  // ceil(n/(B*f)) = ceil(ceil(n/B)/f), with f a power of two. Thus B5/B14
  // do not introduce one divider per candidate split or a variable divider.
  def positionsPerBank(count: UInt): UInt = if (params.FLEXIBLE_BANK_GROUPING) {
    val basePositions = (count + params.banks_per_row.U - 1.U) / params.banks_per_row.U
    MuxLookup(y_slice_Reg, basePositions)(groupingRows.map(y => {
      val factor = params.parallel_rows / y
      y.U -> ((basePositions + factor.U - 1.U) >> log2Ceil(factor))
    }))
  } else (count + weightBanks.U - 1.U) / weightBanks.U
  val maxParts          = params.XBitWidth / params.minInputBits // Maximum packed activations per XBitWidth lane.
  val product_bitwidth  = (params.XBitWidth/params.minInputBits)*(params.WBitWidth + params.minInputBits)
  val elems_per_chunk = params.DMA_bits / params.OutBitWidth
  // Include a possible leading partial beat for every activation row.
  val maxStoreWrites = params.parallel_rows * ((params.Cout + 2 * elems_per_chunk - 2) / elems_per_chunk)

  // === Completion and selection-control registers ===
  // Selection issues one bank-position token per cycle. The existing data
  // path reaches accumulation four clocks later (decode, LUT read and capture).
  // Valid/column/last tags travel with those tokens, including during stalls.
  val selectionIssue = WireDefault(false.B)
  val selectionLast = WireDefault(false.B)
  val zeroSkipMode = WireDefault(false.B)
  val zeroSkipFirst = WireDefault(false.B)
  val zeroSkipSpecial = if (params.SKIP_ZERO_WEIGHTS && params.FP_ACTIVATIONS)
    Some(RegInit(false.B)) else None
  val selectionValid = RegInit(VecInit(Seq.fill(4)(false.B)))
  val selectionColumn = Reg(Vec(4, UInt(log2Ceil(params.Cout + 1).W)))
  val selectionEnd = RegInit(VecInit(Seq.fill(4)(false.B)))
  val selectionFirst = RegInit(VecInit(Seq.fill(4)(false.B)))
  val weightBufferS2 = RegInit(0.U(log2Ceil(params.W_BUFFS).W))
  // A consumed W8 SRAM word supplies eight selection positions. Keep that word
  // outside the double buffer so DMA can refill it after its final read capture.
  // These registers and the alternate control path disappear when disabled.
  val weightColumnActive = if (params.EARLY_WEIGHT_BUFFER_RELEASE) Some(RegInit(false.B)) else None
  val heldWeightWords = if (params.EARLY_WEIGHT_BUFFER_RELEASE) Some(Reg(Vec(weightBanks, UInt(params.DMA_bits.W)))) else None

  val sum    = Reg(Vec(totalSyncMems, SInt((params.XBitWidth + 1).W))) // Signed odd-weight correction for each activation lane.
  val Sign   = Reg(Vec(totalSyncMems, Bool())) // True for a nonnegative product sign.
  val Products = RegInit(VecInit(Seq.fill(totalSyncMems)(0.U(product_bitwidth.W)))) // Store read results

  // === Scratchpad capacity constants ===
  //How many X_reg will neeed in worst case
  val regs_per_mem = RowsPerBlock
  val regs_per_mem_w = maxParts * regs_per_mem
  val max_x_regs  = weightBanks * regs_per_mem // Activation-position capacity for one row group.
  val max_w_elems = maxParts* max_x_regs // Weight-element capacity for the current K window.
  val buffers     =  params.W_BUFFS
  val max_blocks_in_sync_mem = regs_per_mem

  // === Output accumulators and activation/weight memories ===
  val fpMeta = if (params.FP_ACTIVATIONS) Some(Reg(Vec(totalSyncMems, new FPProductMeta(params.FP_BITS)))) else None
  val I_MEM = Seq.fill(totalSyncMems)(SyncReadMem(activationDepth, UInt(params.DMA_bits.W)))
  val W_MEM = Seq.fill(buffers, weightBanks) { SyncReadMem(regs_per_mem_w/8, UInt(params.DMA_bits.W))}
  val weightReadAddress = if (params.SKIP_ZERO_WEIGHTS) {
    Some(WireDefault(VecInit(Seq.fill(buffers) {
      VecInit(Seq.fill(weightBanks)(0.U(math.max(1, log2Ceil(regs_per_mem_w / 8)).W)))
    })))
  } else None
  val weightReadEnable = if (params.SKIP_ZERO_WEIGHTS) {
    Some(WireDefault(VecInit(Seq.fill(buffers) {
      VecInit(Seq.fill(weightBanks)(false.B))
    })))
  } else None
  val weightReadData = if (params.SKIP_ZERO_WEIGHTS) Some(VecInit((0 until buffers).map { b =>
    VecInit((0 until weightBanks).map(i => W_MEM(b)(i).read(weightReadAddress.get(b)(i), weightReadEnable.get(b)(i))))
  })) else None

  // Optional shared activation read port. State-local helpers select its request.
  val activationReadAddress = WireDefault(0.U(math.max(1, log2Ceil(activationDepth)).W))
  val activationReadEnable = WireDefault(false.B)
  // Zero skipping changes read addresses, not port counts: generation/dense
  // replay share the same physical port with each bank's independent replay.
  val sparseActivationAddress = if (params.SKIP_ZERO_WEIGHTS)
    Some(WireDefault(VecInit(Seq.fill(totalSyncMems)(0.U(math.max(1, log2Ceil(activationDepth)).W))))) else None
  val sparseActivationEnable = if (params.SKIP_ZERO_WEIGHTS)
    Some(WireDefault(VecInit(Seq.fill(totalSyncMems)(false.B)))) else None
  val activationReadData = if (params.SKIP_ZERO_WEIGHTS) Some(VecInit(I_MEM.zipWithIndex.map { case (mem, i) =>
    mem.read(Mux(sparseActivationEnable.get(i), sparseActivationAddress.get(i), activationReadAddress),
      sparseActivationEnable.get(i) || activationReadEnable)
  })) else Some(VecInit(I_MEM.map(_.read(activationReadAddress, activationReadEnable))))

  // === DMA response carry registers ===
  val dataResponse = RegInit(0.U(params.DMA_bits.W))

  val dataResponse_w = RegInit(0.U(params.DMA_bits.W))

  val SplitEnable        = RegInit(false.B)

  // === Activation scratchpad read registers ===
  // counter register to iterate rows -- for I_MEM
  val counter_row = RegInit(0.U(log2Ceil(max_blocks_in_sync_mem).W))
  val offset_counter = RegInit(0.U(math.max(1, log2Ceil(activationCells)).W))
  val I_vals = RegInit(VecInit(Seq.fill(totalSyncMems)(0.U(params.DMA_bits.W)))) // Store read results

  val valid_inputs = Reg(UInt(log2Ceil(totalSyncMems * max_blocks_in_sync_mem + 1).W))

  val row_weight = RegInit(0.U(log2Ceil(max_blocks_in_sync_mem + 1).W))
  val elements_counter = RegInit(0.U(log2Ceil(totalSyncMems * max_blocks_in_sync_mem + 1).W))

  // === Weight double-buffer registers ===
  val buffers_mode  = RegInit(VecInit(Seq.fill(buffers)(false.B))) // False: available for loading; true: ready for selection.
  val load_w_buff_idx  = RegInit(0.U(log2Ceil(buffers).W))
  val select_buff_idx  = RegInit(0.U(log2Ceil(buffers).W))
  val w_count          = RegInit(0.U(log2Ceil(params.Cout + 1).W)) // counter to for current Weight matrix Column

  // Reserve a buffer on its first accepted request, including while it fills.
  // Requests and ordered responses have independent column/buffer cursors:
  // j_w/load_w_buff_idx issue; w_count/weightReturnBuffer receive.
  val weightReserved = RegInit(VecInit(Seq.fill(buffers)(false.B)))
  val weightReturnBuffer = RegInit(0.U(log2Ceil(buffers).W))
  val weightReturnBase = RegInit(0.U(matrixIndexWidth(params).W))

  // === Product-table storage and generation/select counters ===
  //Create SyncReadMems (Memory Blocks to store Prodcuts)
  val temp_muls_mem = Seq.fill(totalSyncMems) {SyncReadMem(RowsPerBlock  , UInt(product_bitwidth.W))}
  val productReadAddress = if (params.SKIP_ZERO_WEIGHTS)
    Some(WireDefault(VecInit(Seq.fill(totalSyncMems)(0.U(math.max(1, log2Ceil(RowsPerBlock)).W))))) else None
  val productReadEnable = if (params.SKIP_ZERO_WEIGHTS)
    Some(WireDefault(VecInit(Seq.fill(totalSyncMems)(false.B)))) else None
  val productReadData = if (params.SKIP_ZERO_WEIGHTS) Some(VecInit(temp_muls_mem.zipWithIndex.map {
    case (mem, i) => mem.read(productReadAddress.get(i), productReadEnable.get(i))
  })) else None
  val cycleCount_1    = RegInit(0.U(log2Ceil(64 + 1).W)) // Product-table row within the current activation position.
  val cycleCount_2    = RegInit(0.U(log2Ceil(64 + 1).W)) // Packed activation offset during selection.
  val cycleCount_2_reg    = RegInit(0.U(log2Ceil(64 + 1).W)) // Registered packed activation offset.

  // === Output-column and row-group progress ===
  // Index counters
  val w_idx         = RegInit(0.U(log2Ceil(params.Cout + 1).W)) // counter to for current Weight matrix Column
  val load_x_cnt    = RegInit(0.U(64.W)) // Progress through activation K windows.
  val Rin_cnt       = RegInit(0.U(log2Ceil(params.parallel_rows + 1).W)) // Output row within the active Y_SLICE group.
  val loops_cnt     = RegInit(0.U(64.W))

  selectionValid(0) := selectionIssue
  selectionColumn(0) := w_idx
  selectionEnd(0) := selectionLast
  for (stage <- 1 until 4) {
    selectionValid(stage) := selectionValid(stage - 1)
    selectionColumn(stage) := selectionColumn(stage - 1)
    selectionEnd(stage) := selectionEnd(stage - 1)
  }

  // === Matrix-coordinate registers ===
  //Indexer for Matrices
  val i_w           = RegInit(0.U(log2Ceil(params.Cin + 1).W))
  val j_w           = RegInit(0.U(log2Ceil(params.Cout + 1).W))
  val i_x           = RegInit(0.U(log2Ceil(params.Cin.max(params.Rin) + 1).W))
  val j_x           = RegInit(0.U(log2Ceil(params.Cin + 1).W))
  val i_w_start     = RegInit(0.U((log2Ceil(params.Cin + 1).W)))
  val i_x_start     = RegInit(0.U(log2Ceil(params.Cin.max(params.Rin) + 1).W))
  val j_x_start     = RegInit(0.U(log2Ceil(params.Cin + 1).W)) // Activation K-block offset.
  val LOOPS         = RegInit(0.U(64.W))
  val i_o           = RegInit(0.U(log2Ceil(params.Rin + 1).W))
  val storeWritesIssued = RegInit(0.U(log2Ceil(maxStoreWrites + 1).W))
  val j_x_temp      = RegInit(0.U(log2Ceil(params.Cin + 1).W))
  val i_w_temp      = RegInit(0.U(log2Ceil(params.Cin + 1).W))

  // Running element bases for input/output rows.
  // Advance only at row completion; restore the row-group base for each K window.
  val xRowBase = RegInit(0.U(matrixIndexWidth(params).W))
  val xStartBase = RegInit(0.U(matrixIndexWidth(params).W))
  val outputRowBase = RegInit(0.U(matrixIndexWidth(params).W))
  val bankValidPositions = Reg(Vec(weightBanks, UInt(log2Ceil(RowsPerBlock + 1).W)))

  val init_cycle      = RegInit(true.B)
  val step_new        = RegInit(0.U(log2Ceil(params.Cin + 1).W))

  // Runtime activation window size and active row count.
  val XS            =  RegInit(max_x_regs.U(log2Ceil(max_x_regs + 1).W))
  val YS            =  RegInit(params.parallel_rows.U(log2Ceil(params.parallel_rows + 1).W))
  val done_elems_in_block    =  RegInit(0.U(log2Ceil(elems_per_chunk).W))

  // Independent activation-position cursors for generation and selection.
  val counter_1 = RegInit(0.U(log2Ceil(RowsPerBlock + 2).W)) // Logical activation position during LUT generation.
  val counter_2 = RegInit(0.U(log2Ceil(RowsPerBlock + 2).W)) // Logical activation position during selection.

  selectionFirst(0) := selectionIssue && Mux(zeroSkipMode, zeroSkipFirst,
    counter_2 === 0.U && cycleCount_2 === 0.U)
  for (stage <- 1 until 4) selectionFirst(stage) := selectionFirst(stage - 1)

  //Add registers to track the ongoing DMA transaction's mode
  val dma_send        = RegInit(false.B) //sending DMA requests to A channel on progress
  val dma_resp        = RegInit(false.B) //receiveving  DMA responces from  D channel on progress

  val dma_counter_a   = RegInit(0.U(log2Ceil(math.max(max_x_regs, params.Cout) + 1).W))
  // Read phases count elements; sStoreOutput counts acknowledged write beats.
  val dma_counter_d   = RegInit(0.U(log2Ceil(math.max(math.max(max_x_regs, params.Cout), maxStoreWrites) + 1).W))

  //Select and Accumulte control Signals
  val READ_ON         = RegInit(false.B)

  // Matrices Addresses
  val adr_X      =  RegInit(0.U(64.W))
  val adr_W      =  RegInit(0.U(64.W))
  val adr_O      =  RegInit(0.U(64.W))

  // === Runtime matrix dimensions and slice settings ===
  val dma_limit             = RegInit(0.U(log2Ceil(params.Cin + 1).W)) //Cin/X_slice
  val cout_reg              = RegInit(params.Cout.U(log2Ceil(params.Cout + 1).W))
  val cin_reg               = RegInit(params.Cin.U(log2Ceil(params.Cin + 1).W))
  val rin_reg               = RegInit(params.Rin.U(log2Ceil(params.Rin + 1).W))
  val y_slice_Reg           = RegInit(params.parallel_rows.U(log2Ceil(params.parallel_rows + 1).W))
  val x_slice_weights_reg   = RegInit(max_w_elems.U(log2Ceil(max_w_elems + 1).W)) // to bring all corresping weights for all x_elemnts
  val x_slice_input_reg     = RegInit(max_x_regs.U(log2Ceil(max_x_regs + 1).W)) // so in worst case every sync Mem have hiw own X_reg
  val groupScaleBase = if (groupScaling) Some(RegInit(0.U(64.W))) else None
  val quantGroupShift = if (groupScaling) Some(RegInit(7.U(3.W))) else None
  def quantGroupMask: UInt = Mux(quantGroupShift.get === 5.U, 31.U(7.W),
    Mux(quantGroupShift.get === 6.U, 63.U(7.W), 127.U(7.W)))
  def groupWindow(requested: UInt, k: UInt): UInt = {
    val available = requested.min(cin_reg - k)
    if (params.GROUP_SCALE_ALIGNED) available.min((quantGroupMask +& 1.U) - (k & quantGroupMask))
    else available
  }
  def packedWeightSlots(n: UInt): UInt = (n + w_elems_per_reg - 1.U) >>
    Mux(weight_bits === 8.U, 0.U, Mux(weight_bits === 4.U, 1.U, 2.U))
  val scaleWordCapacity = (((params.Cin + 31) / 32) * params.Cout + 3) / 4
  val scaleIssued = if (groupScaling) Some(RegInit(0.U(math.max(1, log2Ceil(scaleWordCapacity + 1)).W))) else None
  val scaleReturned = if (groupScaling) Some(RegInit(0.U(math.max(1, log2Ceil(scaleWordCapacity + 1)).W))) else None
  val windowRetired = if (groupScaling) Some(RegInit(false.B)) else None

  // === Runtime element formats and performance counters ===
  //bitwidths
  val input_bits  = if (fpGeometry) params.FP_BITS.U(6.W) else RegInit(0.U(6.W)) //  input bits
  val weight_bits = RegInit(0.U(6.W)) //  weights bits

  val PC_loadX    =  RegInit(0.U(64.W))
  val PC_Generate =  RegInit(0.U(64.W))
  val PC_loadW    =  RegInit(0.U(64.W))
  val PC_Select   =  RegInit(0.U(64.W))
  val PC_storeO   =  RegInit(0.U(64.W))
  val PC_loadW_and_Select = RegInit(0.U(64.W))

  // === Activation validity and packed-element metadata ===
  val all_available_elems   = RegInit(0.U(log2Ceil(params.Cin + 1).W))                        // How many elements are available to read in current block (for both W and X since they are the same in worst case)
  val elemOffVec            = Reg(Vec(totalSyncMems, UInt(log2Ceil(maxParts).W)))             // Keep in what offset ot row is located the element weight want to read
  val w_reg_valid           = Reg(Vec(totalSyncMems, Bool()))                                 // true -> selected value from BRAM is valid
  val dirty_elems_x         = RegInit(0.U(log2Ceil(params.DMA_bits/params.minInputBits).W)) // Initial activation-element offset for bank traversal.
  // Counts activation elements along K; output-column capacity must not truncate it.
  val all_available_elems_x = RegInit(0.U(log2Ceil(params.Cin + 1).W))
  val x_elems_per_reg       = if (fpGeometry) 1.U(2.W) else RegInit((params.XBitWidth / params.minInputBits).U)             //How many input elements fit  in one input register
  val w_elems_per_reg = RegInit((params.WBitWidth / params.minWeightBits).U(3.W) )            //How many weight elements fit  in one weight register

  // === Product-table geometry and precision masks ===
  val mask_p               = RegInit(0.U((params.WBitWidth + params.XBitWidth).W))
  val block_rows           = RegInit(0.U(log2Ceil((1 << (params.WBitWidth - 2)) + 1).W))
  val blocks_in_Sync_mem = RegInit(0.U(log2Ceil((max_blocks_in_sync_mem) + 1).W))
  val shiftAmt  = Log2(block_rows) // block_rows is 1, 4 or 64 for W2, W4 or W8.
  val chunks_per_Product_bits =  RegInit(0.U(log2Ceil((((params.WBitWidth +  params.XBitWidth) ))  + 1).W))

  // === DMA request/response coordinates and chunk sizes ===
  //indexing extra registers
  val mul1 = RegInit(0.U(matrixIndexWidth(params).W))
  val mul2 = RegInit(0.U(16.W))

  // A channel
  val elements_to_read = RegInit(0.U((log2Ceil(max_w_elems) + 1).W))
  val chunk_addres     = RegInit(0.U(64.W))
  //D Channel
  val elements_to_read_temp = RegInit(0.U((log2Ceil(max_w_elems) + 1).W))

  // DMA bus parameters (how many register or elements fitr in 64 bits dma channel)
  val elemsPerChunk_in = if (fpGeometry) (params.DMA_bits / params.FP_BITS).U(5.W) else RegInit(0.U(5.W))
  val RegsPerChunk_in  = RegInit((params.DMA_bits / params.XBitWidth).U(math.max(1, log2Ceil(params.DMA_bits / params.XBitWidth) + 1).W))
  val elemsPerChunk_w  = RegInit((params.DMA_bits / params.minInputBits).asUInt)
  val RegsPerChunk_w   = RegInit((params.DMA_bits / params.WBitWidth).U(math.max(1, log2Ceil(params.DMA_bits / params.WBitWidth) + 1).W))
  val  clmp = RegInit(0.U(64.W))


  // === Selection pipeline registers and shared address counters ===
  //----------------------------->  Pipeline Stages in Select Phase  Registers

  // stage 1 Output Registers
  // Shared activation cell and bank rotation retain the selection pipeline latency.
  val activationCellS3 = RegInit(0.U(math.max(1, log2Ceil(params.DMA_bits / params.XBitWidth)).W))
  val activationBankShiftS3 = RegInit(0.U(math.max(1, log2Ceil(totalSyncMems)).W))

  val bankSize = params.DMA_bits/params.WBitWidth

  val bankSize_in = params.DMA_bits/params.XBitWidth

  val wCellIdxW    = log2Ceil(params.DMA_bits / params.WBitWidth)
  val buffIdxW     = log2Ceil(buffers)
  val chunkPartIdx = if (maxParts > 1) 1 else 0

  val weightCellS2 = RegInit(0.U(math.max(1, wCellIdxW).W))
  val weightOffsetS2 = RegInit(0.U(math.max(1, log2Ceil(params.WBitWidth / params.minWeightBits)).W))
  val Valid_In_Vec = Reg(Vec(totalSyncMems, Bool()))

  // stage 2 Output Registers
  val weight_Sint_part_Reg = Reg(Vec(weightBanks, SInt(params.WBitWidth.W)))
  val abs_weight_uint_Reg  = Reg(Vec(weightBanks, UInt(params.WBitWidth.W)))
  val row_idx_Reg          = Reg(Vec(weightBanks, UInt(log2Ceil(RowsPerBlock + 1).W)))
  val elemOffset_Reg       = Reg(Vec(weightBanks, UInt(4.W)))
  val w_reg_valid_temp_Reg = Reg(Vec(weightBanks, Bool()))

  val offsetWidth = log2Ceil(params.WBitWidth / params.minWeightBits)
  val cellWidth   = log2Ceil(params.DMA_bits / params.WBitWidth)

  // All weight banks consume the same local K position together. Count the
  // packed element first (W8: 0; W4: 0..1; W2: 0..3), then the byte (0..7).
  // These counters hold during stalls/drain and restart for every column.
  val offset_counter_1 = RegInit(0.U(math.max(1, offsetWidth).W))
  val cell_counter = RegInit(0.U(math.max(1, cellWidth).W))
  val lastWeightOffset = Mux(weight_bits === 8.U, 0.U,
    Mux(weight_bits === 4.U, 1.U, 3.U))

  // One activation cursor serves both mutually exclusive phases: generate
  // LUT products, then replay activations for every output column. Its bank
  // rotation also preserves legacy dirty offsets across short bank blocks.
  val activationAddress = Module(new ActivationAddressCounter(
    totalSyncMems, bankSize_in, max_blocks_in_sync_mem,
    params.XBitWidth / params.minInputBits,
    params.DMA_bits / params.minInputBits - 1))
  activationAddress.io.restart := false.B
  activationAddress.io.advance := false.B
  activationAddress.io.blocks := blocks_in_Sync_mem
  activationAddress.io.elementsPerCell := x_elems_per_reg
  activationAddress.io.startOffset := dirty_elems_x

  // Sparse replay addresses the logical bank directly for 16-bit inputs.
  // Legacy dirty-start traversal and narrower packed inputs retain dense replay.
  // FP16 special values also retain dense replay, preserving 0*Inf/NaN flags.
  if (params.SKIP_ZERO_WEIGHTS) {
    zeroSkipMode := input_bits === 16.U && dirty_elems_x === 0.U &&
      (if (params.FP_ACTIVATIONS) !zeroSkipSpecial.get else true.B)
  }
  val zeroSelector = if (params.SKIP_ZERO_WEIGHTS)
    Some(Module(new ZeroWeightSelector(weightBanks, RowsPerBlock))) else None
  if (params.SKIP_ZERO_WEIGHTS) {
    val selector = zeroSelector.get
    selector.io.start := false.B
    selector.io.enable := false.B
    selector.io.precision := weight_bits
    selector.io.readData.foreach(_ := 0.U)
    for (bank <- 0 until weightBanks) {
      val start = bankOffset(blocks_in_Sync_mem, bank)
      selector.io.limits(bank) := Mux(valid_inputs > start,
        (valid_inputs - start).min(blocks_in_Sync_mem), 0.U)
    }
  }
  // Sparse operands are delayed to the same four-clock retirement point as
  // dense operands. The accumulator, column tags and output DMA remain shared.
  val sparseProducts = if (params.SKIP_ZERO_WEIGHTS) Some(Wire(Vec(totalSyncMems, UInt(product_bitwidth.W)))) else None
  val sparseSum = if (params.SKIP_ZERO_WEIGHTS) Some(Wire(Vec(totalSyncMems, SInt((params.XBitWidth + 1).W)))) else None
  val sparseSign = if (params.SKIP_ZERO_WEIGHTS) Some(Wire(Vec(totalSyncMems, Bool()))) else None
  val sparseValid = if (params.SKIP_ZERO_WEIGHTS) Some(Wire(Vec(totalSyncMems, Bool()))) else None
  val sparseMeta = if (params.SKIP_ZERO_WEIGHTS && params.FP_ACTIVATIONS)
    Some(Wire(Vec(totalSyncMems, new FPProductMeta(params.FP_BITS)))) else None
  if (params.SKIP_ZERO_WEIGHTS) {
    val selector = zeroSelector.get
    for (bank <- 0 until weightBanks) {
      val weightS1 = RegEnable(selector.io.weight(bank), selector.io.valid && selector.io.laneValid(bank))
      val cellS1 = RegEnable(selector.io.position(bank)(math.max(1, log2Ceil(activationCells)) - 1, 0), selector.io.valid && selector.io.laneValid(bank))
      val weight = selector.io.weight(bank)
      val magnitude = Mux(weight < 0.S, -weight, weight).asUInt
      val tableRow = (selector.io.position(bank) << shiftAmt) + ((magnitude - 1.U) >> 1)
      for (y <- 0 until params.parallel_rows) {
        val lane = y * weightBanks + bank
        val request = selector.io.valid && selector.io.laneValid(bank) && y.U < YS
        val validS1 = RegNext(request, false.B)
        when(zeroSkipMode) {
          sparseActivationAddress.get(lane) := selector.io.position(bank) >> log2Ceil(activationCells)
          sparseActivationEnable.get(lane) := request
          productReadAddress.get(lane) := tableRow
          productReadEnable.get(lane) := request
        }
        val cells = VecInit(Seq.tabulate(activationCells)(i => activationReadData.get(lane)(params.XBitWidth * (i + 1) - 1, params.XBitWidth * i)))
        val activation = cells(cellS1)
        val negative = activation(params.XBitWidth - 1) ^ weightS1(7)
        val absolute = if (params.FP_ACTIVATIONS) FPActivation.significand(activation, params.FP_BITS).zext
          else Mux(activation.asSInt < 0.S, -activation.asSInt.pad(params.XBitWidth + 1), activation.asSInt.pad(params.XBitWidth + 1))
        val signed = Mux(negative, -absolute, absolute)
        val correction = Mux(validS1 && weightS1.asUInt(0), signed, 0.S((params.XBitWidth + 1).W))
        sparseProducts.get(lane) := ShiftRegister(productReadData.get(lane), 3)
        sparseSum.get(lane) := ShiftRegister(correction, 3)
        sparseSign.get(lane) := ShiftRegister(!negative, 3)
        sparseValid.get(lane) := ShiftRegister(validS1, 3, false.B, true.B)
        if (params.FP_ACTIVATIONS) {
          sparseMeta.get(lane).shift := ShiftRegister(FPActivation.shift(activation, params.FP_BITS), 3)
          sparseMeta.get(lane).nonZeroWeight := sparseValid.get(lane)
          sparseMeta.get(lane).flags := 0.U // Special-value windows use the dense path.
        }
      }
    }
  }

  // Helper implementations are in LUMIXEDTopHelpers.scala.

  // === Submodule instances and default connections ===
  //////////////////////////////////////////////////////////////// Modules ////////////////////////////////////////////////////////

  // === DMA interface defaults ===
  //DMA Module inputs
  dma.io.valid     := false.B
  dma.io.addr      := 0.U
  dma.io.mode      := 3.U      // inactive
  dma.io.writeData := 0.U
  dma.io.log_S     := log2Ceil(params.DMA_Bytes).U
  dma.io.mask      := 0.U
  dma.io.d_ready   := false.B // Unused by DmaModule; each d_valid response is consumed.

  // === Activation chunk extenders ===
  // Output SRAM: prefetch DMA words through a four-entry elastic queue.
  // The reader has its own cursor so SRAM latency is hidden while DMA stalls.
  val groupEngine = if (groupScaling) Some(Module(new GroupScaleEngine(
    if (params.GROUP_SCALE_ALIGNED) 1 else weightBanks, params.parallel_rows,
    params.Cout, params.Cin, partialWidth, accumulatorWidth, params.GROUP_SCALE_YS))) else None
  val groupWritePending = if (groupScaling) RegNext(groupEngine.get.io.out.valid, false.B) else false.B
  if (groupScaling) {
    val e = groupEngine.get
    e.io.valid := false.B
    e.io.first := selectionFirst(3) && load_x_cnt === 1.U
    e.io.column := selectionColumn(3)
    e.io.columns := cout_reg
    e.io.scaleWrite := false.B
    e.io.scaleWriteAddress := scaleReturned.get
    e.io.scaleWriteData := dma.io.readData
    for (b <- 0 until (if (params.GROUP_SCALE_ALIGNED) 1 else weightBanks)) {
      val k = i_w_start + bankOffset(blocks_in_Sync_mem, b) + counter_2
      val valid = counter_2 < bankValidPositions(b)
      val end = (k & quantGroupMask) === quantGroupMask || counter_2 +& 1.U >= bankValidPositions(b)
      e.io.groups(b) := ShiftRegister(k >> quantGroupShift.get, 4)
      e.io.laneValid(b) := ShiftRegister(valid, 4, false.B, true.B)
      e.io.last(b) := (if (params.GROUP_SCALE_ALIGNED) selectionEnd(3)
        else ShiftRegister(end, 4, false.B, true.B))
      e.io.values(b).foreach(_ := 0.S)
    }
  }
  val outputMemory = Module(new OutputAccumulator(params.parallel_rows, params.Cout,
    outputAccumulatorWidth, params.ROUNDED_ACCUMULATION))
  val roundedSubtotals = if (params.ROUNDED_ACCUMULATION)
    Some(Seq.fill(params.parallel_rows)(Module(new FixedToFP32(accumulatorWidth, params.fpFractionalBits)))) else None
  roundedSubtotals.foreach(_.foreach { convert =>
    convert.io.value := 0.S
    convert.io.flags := 0.U
  })
  // Rounded mode always registers the subtotal after RNE and before the FP32 adder.
  // Exact mode keeps its original retirement path without this extra stage.
  // Keep tags/row validity with their payload; the input-side column may already
  // have advanced. No extra delay is added to weight reads or buffer release.
  val roundedPending = if (params.ROUNDED_ACCUMULATION)
    Some(RegNext(selectionValid(3), false.B)) else None
  val roundedColumn = if (params.ROUNDED_ACCUMULATION)
    Some(RegEnable(selectionColumn(3), selectionValid(3))) else None
  val roundedFirst = if (params.ROUNDED_ACCUMULATION)
    Some(RegEnable(selectionFirst(3) && load_x_cnt === 1.U, selectionValid(3))) else None
  val roundedWindowLast = if (params.ROUNDED_ACCUMULATION)
    Some(RegEnable(selectionEnd(3) && selectionColumn(3) === cout_reg - 1.U, selectionValid(3))) else None
  val roundedActiveRows = if (params.ROUNDED_ACCUMULATION)
    Some(RegEnable(VecInit((0 until params.parallel_rows).map(y => y.U < YS)), selectionValid(3))) else None
  val roundedValues = if (params.ROUNDED_ACCUMULATION)
    Some(Reg(Vec(params.parallel_rows, UInt(32.W)))) else None
  roundedValues.foreach { values =>
    when(selectionValid(3)) {
      for (y <- 0 until params.parallel_rows) { values(y) := roundedSubtotals.get(y).io.bits }
    }
  }
  val outputWords = Module(new Queue(UInt(params.DMA_bits.W), 4))
  val outputReadIssue = WireDefault(false.B)
  val outputReadPending = RegNext(outputReadIssue, false.B)
  val outputReadRow = RegInit(0.U(log2Ceil(params.parallel_rows + 1).W))
  val outputReadColumn = RegInit(0.U(log2Ceil(params.Cout + 1).W))
  val outputReadParity = RegInit(false.B)
  val outputReadCount = WireDefault(0.U(2.W))
  val mem = outputMemory
  val queue = outputWords
  outputReadCount := (Mux(outputReadParity, 1.U, 2.U)).min(cout_reg - outputReadColumn)
  mem.io.readValid := selectionValid(2)
  mem.io.readColumn := selectionColumn(2)
  mem.io.updateColumn := selectionColumn(3)
  mem.io.first := selectionFirst(3) && load_x_cnt === 1.U
  mem.io.updateValid.foreach(_ := false.B)
  mem.io.delta.foreach(_ := 0.S)
  mem.io.flags.foreach(_ := 0.U)
  if (params.ROUNDED_ACCUMULATION) {
    // Read the old value on the subtotal-register edge. SRAM data and the
    // registered subtotal arrive together for addition on the following cycle.
    // OutputAccumulator's existing previousWords bypass forwards back-to-back
    // updates to the same column before SRAM read/write visibility catches up.
    mem.io.readValid := selectionValid(3)
    mem.io.readColumn := selectionColumn(3)
    mem.io.updateColumn := roundedColumn.get
    mem.io.first := roundedFirst.get
    for (y <- 0 until params.parallel_rows) {
      mem.io.updateValid(y) := roundedPending.get && roundedActiveRows.get(y)
      mem.io.delta(y) := roundedValues.get(y).asSInt
    }
  }
  if (groupScaling) {
    val out = groupEngine.get.io.out
    mem.io.readValid := out.valid
    mem.io.readColumn := out.bits.column
    mem.io.updateColumn := RegEnable(out.bits.column, out.valid)
    mem.io.first := RegEnable(out.bits.first, out.valid)
    for (y <- 0 until params.parallel_rows) {
      mem.io.updateValid(y) := groupWritePending && y.U < YS
      mem.io.delta(y) := RegEnable(out.bits.delta(y), out.valid)
    }
  }
  mem.io.storeValid := outputReadIssue
  mem.io.storeRow := outputReadRow
  mem.io.storeColumn := outputReadColumn
  mem.io.storeCount := outputReadCount
  val count = RegEnable(outputReadCount, outputReadIssue)
  val offset = RegEnable(outputReadParity, outputReadIssue)
  val words = (0 until elems_per_chunk).map { i =>
    val bits = if (params.ROUNDED_ACCUMULATION) {
      mem.io.storeValues(i).asUInt // Stored sums are already IEEE FP32.
    } else if (params.FP_ACTIVATIONS) {
      val convert = Module(new FixedToFP32(accumulatorWidth, params.fpFractionalBits + (if (groupScaling) 24 else 0)))
      convert.io.value := mem.io.storeValues(i)
      convert.io.flags := mem.io.storeFlags(i)
      convert.io.bits
    } else mem.io.storeValues(i).asUInt
    Mux(i.U < count, bits, 0.U(32.W))
  }
  queue.io.enq.valid := outputReadPending
  queue.io.enq.bits := words.zipWithIndex.foldLeft(0.U(params.DMA_bits.W)) {
    case (packed, (word, i)) => packed | (word << ((i.U + offset.asUInt) << 5))
  }
  queue.io.deq.ready := false.B
  when(outputReadPending) { assert(queue.io.enq.ready, "Output prefetch queue overflow") }
  when(outputReadIssue) {
    val next = outputReadColumn + outputReadCount
    outputReadParity := outputReadParity ^ outputReadCount(0)
    when(next === cout_reg) {
      outputReadColumn := 0.U
      outputReadRow := outputReadRow + 1.U
    }.otherwise { outputReadColumn := next }
  }

  // ChunkSignExtender Module
  val chunkExtenders = Seq.fill(totalSyncMems,maxParts) { Module(new ChunkSignExtender(params.XBitWidth,params.minInputBits))}
  for {
    i <- 0 until totalSyncMems
    j <- 0 until maxParts
  } {
    val ext = chunkExtenders(i)(j)
    ext.io.data       := 0.U
    ext.io.input_bits := 0.U
    ext.io.idx        := 0.U
  }

  // === Parallel product generators (b times Y_SLICE) ===
  // Product Generator Mesh Module
  val Product_Generator_Mesh = Seq.fill(totalSyncMems) {
    Module(new Product_Generator(
      totalSyncMems,
      maxParts,
      params.WBitWidth,
      params.XBitWidth,
      params.minWeightBits,
      RowsPerBlock,
      params.DMA_bits,
      product_bitwidth,
      params.minInputBits,
      params.FP_ACTIVATIONS,
      params.FP_BITS,
      if (params.parallel_rows > 1) params.banks_per_row else 0,
      params.FLEXIBLE_BANK_GROUPING
    ))
  }

  for (i <- 0 until totalSyncMems) {
    val gen = Product_Generator_Mesh(i)

    gen.io.sync_mem_idx := i.U
    if (params.FLEXIBLE_BANK_GROUPING) gen.io.logicalStart.get :=
      MuxLookup(y_slice_Reg, bankOffset(blocks_in_Sync_mem, i % params.banks_per_row))(
        groupingRows.map(y => y.U -> bankOffset(blocks_in_Sync_mem, i % (totalSyncMems / y))))
    gen.io.blocks_in_Sync_mem := blocks_in_Sync_mem
    gen.io.counter := counter_1
    gen.io.dirty_elems_x := dirty_elems_x
    gen.io.activation_bank := rotatedActivationBank(i, activationAddress.io.bankShift)
    gen.io.activation_cell := activationAddress.io.cell
    gen.io.X_vals := I_vals
    gen.io.inputs_per_reg := x_elems_per_reg
    gen.io.input_bits := input_bits
    gen.io.valid_inputs := valid_inputs
    gen.io.mask_p := mask_p
    gen.io.shiftAmt := shiftAmt
    gen.io.chunks_per_Product_bits := chunks_per_Product_bits
    gen.io.Product_in := Products(i)
  }

  // === DMA address/chunk-info modules ===
  //A Chunk Offset Module
  val chunkInfoModule_A = Module(new ChunkInfoModule_A(params,max_w_elems))
  //inputs
  chunkInfoModule_A.io.cin_reg          := cin_reg
  chunkInfoModule_A.io.j_x              := 0.U
  chunkInfoModule_A.io.x_elems_per_reg  := x_elems_per_reg
  chunkInfoModule_A.io.elemsPerChunk_in := elemsPerChunk_in
  chunkInfoModule_A.io.adr_X            := adr_X
  chunkInfoModule_A.io.RegsPerChunk_in  := RegsPerChunk_in
  chunkInfoModule_A.io.mul1             := mul1
  chunkInfoModule_A.io.mul2             := mul2

  //D Chunk Offset Module
  val chunkInfoModule_D = Module(new ChunkInfoModule_D(params,max_w_elems,max_x_regs))
  //inputs
  chunkInfoModule_D.io.cin_reg          := cin_reg
  chunkInfoModule_D.io.j_x_temp         := 0.U
  chunkInfoModule_D.io.x_elems_per_reg  := x_elems_per_reg
  chunkInfoModule_D.io.elemsPerChunk_in := elemsPerChunk_in
  chunkInfoModule_D.io.RegsPerChunk_in  := RegsPerChunk_in
  chunkInfoModule_D.io.dma_counter_d    := 0.U
  chunkInfoModule_D.io.mul1             := mul1
  chunkInfoModule_D.io.mul2             := mul2
  chunkInfoModule_D.io.input_bits       := input_bits
  chunkInfoModule_D.io.max_bits         := 0.U

  // === Main controller: six states ===
  //////////////////////////////////////////// FSM Implementation ////////////////////////////////////////////////////////

  // Define states for the FSM
  val sIdle :: sInitialize :: sLoadX :: sGenerateRegFiles :: sLoadW_and_sSelectAndAccumulate :: sStoreOutput :: sDELAY :: sLoadGroupScales :: Nil = Enum(8)
  val state = RegInit(sIdle)

  // Accept commands in sIdle; sDELAY separately accepts completion acknowledgement.
  io.busy     := (state =/= sIdle)
  cmd.ready   := (state === sIdle) && (!readCounter || counterResponses.io.enq.ready)

  switch(state) {
    // === sIdle: accept configuration and start a matmul ===
    is(sIdle) {
      when(cmd.fire) {
        assert(funct <= 5.U, "Unsupported RoCC ABI v2 command")
        when(control) {
          assert(rs1 <= 1.U, "Control selector must be wait=0 or read=1")
          assert(rs2 < 6.U || waitDone, "Counter index must be 0..5")
          assert(cmd.bits.inst.xd === readCounter, "Only counter reads return rd")
        }.otherwise {
          assert(!cmd.bits.inst.xd, "Configuration/start commands do not return rd")
        }
      }
      when(cmd.fire && setXW) {
        adr_X := rs1
        adr_W := rs2
        configured := configured | 1.U
      }
      when(cmd.fire && setOutput) {
        assert(rs2 > 0.U && rs2 <= params.Cout.U, "N exceeds output capacity")
        adr_O := rs1
        cout_reg := rs2
        configured := configured | 2.U
      }
      when(cmd.fire && setShape) {
        val k = rs1(31, 0)
        val rows = rs1(63, 32)
        val parallel = rs2(31, 0)
        val loops = rs2(63, 32)
        assert(k > 0.U && k <= params.Cin.U, "K exceeds input capacity")
        assert(rows > 0.U && rows <= params.Rin.U, "R exceeds row capacity")
        assert(parallel > 0.U && parallel <= params.parallel_rows.U, "Invalid parallel rows")
        if (params.FLEXIBLE_BANK_GROUPING) {
          assert(groupingRows.map(y => parallel === y.U).reduce(_ || _), "Unsupported bank grouping")
        } else {
          assert(parallel === params.parallel_rows.U, "Fixed-bank build requires configured parallel_rows")
        }
        assert(loops === (rows +& parallel - 1.U) / parallel, "Row-group count must equal ceil(R/parallel)")
        cin_reg := k
        rin_reg := rows
        y_slice_Reg := parallel
        LOOPS := loops
        configured := configured | 4.U
      }
      when(cmd.fire && setExecution) {
        val inBits = rs1(53, 48)
        val wBits = rs1(59, 54)
        assert(rs1(63, 60) === 0.U, "Execution reserved bits must be zero")
        assert(rs1(15, 0) > 0.U && rs1(31, 16) > 0.U && rs1(47, 32) > 0.U,
          "Slice sizes and DMA limit must be nonzero")
        assert(wBits === 2.U || wBits === 4.U || wBits === 8.U, "Signed weights must be W2/W4/W8")
        if (fpGeometry) {
          assert(inBits === params.FP_BITS.U, "Activation precision differs from hardware")
        } else {
          assert(Seq(2,4,8,16).map(n => inBits === n.U).reduce(_ || _) && inBits <= params.XBitWidth.U,
            "Unsupported integer activation width")
          input_bits := inBits
        }
        weight_bits := wBits
        configuredSlice := rs1(15, 0)
        configuredWeightSlice := rs1(31, 16)
        configuredDmaLimit := rs1(47, 32)
        if (groupScaling) {
          assert(rs2 === 32.U || rs2 === 64.U || rs2 === 128.U, "Group size must be 32/64/128")
          quantGroupShift.get := Mux(rs2 === 32.U, 5.U, Mux(rs2 === 64.U, 6.U, 7.U))
        } else {
          assert(rs2 === 0.U, "Group scaling is disabled in this build")
        }
        configured := configured | 8.U
      }
      when(cmd.fire && startMatmul) {
        assert(configured.andR, "START requires all four configuration commands")
        assert(rs2 === 0.U, "START rs2 is reserved")
        configured := 0.U
        state := sInitialize
        if (groupScaling) {
          assert(rs1(2, 0) === 0.U, "Scale base must be 8-byte aligned")
          groupScaleBase.get := rs1
          assert(groupEngine.get.io.empty && !groupWritePending, "Previous group pipeline not drained")
          scaleIssued.get := 0.U
          scaleReturned.get := 0.U
          windowRetired.get := false.B
        }
        PC_loadX := 0.U
        PC_Generate := 0.U
        PC_loadW := 0.U
        PC_Select := 0.U
        PC_storeO := 0.U
        PC_loadW_and_Select := 0.U
        //DMA init
        dma_send      := true.B
        dma_resp      := true.B
        dma_counter_a := 0.U
        dma_counter_d := 0.U

        //indexer init
        i_x       := 0.U
        j_x       := 0.U
        i_x_start := 0.U
        j_x_start := 0.U
        loops_cnt := 0.U
        i_o       := 0.U
        locally {
          xRowBase := 0.U
          xStartBase := 0.U
          outputRowBase := 0.U
        }
        storeWritesIssued := 0.U
        i_w_temp  := 0.U
        j_x_temp  := 0.U

        valid_inputs := 0.U
        offset_counter_1 := 0.U
        cell_counter := 0.U
        if (params.parallel_rows > 1) dirty_elems_x := 0.U

        // XS YS Registers Init
        YS      := Mux(rin_reg >= y_slice_Reg,y_slice_Reg,rin_reg)
        if (!fpGeometry) elemsPerChunk_in := params.DMA_bits.U / input_bits
        elemsPerChunk_w  :=   params.DMA_bits.U  / weight_bits
      }
    }
    // Derived values use stored settings, never partially updated command data.
    is(sInitialize) {
        val scaledSlice        = configuredSlice   // 16 bits
        x_slice_weights_reg   := configuredWeightSlice  // 16 bits
        dma_limit             := configuredDmaLimit  // 16 bits

        clmp := scaledSlice
        val clamped = if (params.GROUP_SCALE_ALIGNED) groupWindow(scaledSlice, 0.U)
          else Mux(cin_reg >= scaledSlice, scaledSlice, cin_reg)
        val elems_per_word = if (fpGeometry) 1.U else params.XBitWidth.U/input_bits
        val temp_mul       = clamped * elems_per_word

        x_slice_input_reg := (if (params.GROUP_SCALE_ALIGNED) clamped else scaledSlice)
        if (params.GROUP_SCALE_ALIGNED) {
          x_slice_weights_reg := (clamped + (params.WBitWidth.U / weight_bits) - 1.U) >>
            Mux(weight_bits === 8.U, 0.U, Mux(weight_bits === 4.U, 1.U, 2.U))
        }
        XS                := clamped

        if (!fpGeometry) x_elems_per_reg := elems_per_word
        w_elems_per_reg := params.WBitWidth.U/weight_bits

        //before load X
        all_available_elems_x := (temp_mul).min(cin_reg)
        mul1                  := 0.U
        mul2                  := temp_mul

        //dynamic bitwitdh parameters
        val p_bits = input_bits +& weight_bits
        chunks_per_Product_bits := p_bits
        mask_p                  := (1.U << p_bits) - 1.U
        block_rows              := (1.U << (weight_bits  -2.U ))

        val blocks_in_Sync_mem_max      = ( 1.U << (params.WBitWidth.U - weight_bits ) ) * params.table_capacity_factor.U  // How many blocks can fit in one psysical memory
        val help = positionsPerBank(clamped)

        blocks_in_Sync_mem := Mux(help < blocks_in_Sync_mem_max, help,blocks_in_Sync_mem_max )

        chunkInfoModule_A.io.x_elems_per_reg := elems_per_word
        chunkInfoModule_A.io.mul1 := 0.U
        chunkInfoModule_A.io.mul2 := temp_mul

        elements_to_read := chunkInfoModule_A.io.elements_to_read
        chunk_addres     := chunkInfoModule_A.io.chunk_address

        chunkInfoModule_D.io.x_elems_per_reg  := elems_per_word
        chunkInfoModule_D.io.elemsPerChunk_in := elemsPerChunk_in
        chunkInfoModule_D.io.RegsPerChunk_in  := RegsPerChunk_in
        chunkInfoModule_D.io.mul1             := 0.U
        chunkInfoModule_D.io.mul2             := temp_mul
        chunkInfoModule_D.io.input_bits       := input_bits
        chunkInfoModule_D.io.max_bits         := params.XBitWidth.U
        elements_to_read_temp := chunkInfoModule_D.io.elements_to_read_temp

      state := (if (groupScaling) sLoadGroupScales else sLoadX)
    }
    // === Group-scale DMA preload ===
    is(sLoadGroupScales) {
      if (groupScaling) {
        if (params.DEBUG) PC_loadX := PC_loadX + 1.U // Includes initial scale preload.
        val groups = (cin_reg +& quantGroupMask) >> quantGroupShift.get
        val words = (groups * cout_reg + 3.U) >> 2
        when(scaleIssued.get < words && !dma.io.busy) {
          dma.io.valid := true.B; dma.io.mode := false.B
          dma.io.addr := groupScaleBase.get + (scaleIssued.get << 3)
          when(dma.io.a_fire) { scaleIssued.get := scaleIssued.get + 1.U }
        }
        when(dma.io.d_valid) {
          groupEngine.get.io.scaleWrite := true.B
          scaleReturned.get := scaleReturned.get + 1.U
        }
        when(scaleReturned.get === words && dma.io.empty) { state := sLoadX }
      }
    }
    // === sLoadX: load activation rows into scratchpads ===
    is(sLoadX) {
      if (params.ROUNDED_ACCUMULATION) {
        assert(!roundedPending.get, "Activation window changed before rounded updates drained")
      }
      if(params.DEBUG){
        PC_loadX := PC_loadX + 1.U
      }

      // send request to DMA in A Channel
      when(dma_send && !dma.io.busy && !SplitEnable) {

        j_x := dmaRequest(
          j_x,
          x_elems_per_reg,
          elemsPerChunk_in,
          adr_X,
          RegsPerChunk_in,
          all_available_elems_x
        )

      }

      // DMA response on channel D.
      when(dma.io.d_valid) {

        j_x_temp := dmaResponce(
          j_x_temp,
          x_elems_per_reg,
          elemsPerChunk_in,
          RegsPerChunk_in,
          input_bits,
          params.XBitWidth.U,
          all_available_elems_x,
          true   // update valid_inputs
        )
      }

      when(dma.io.d_valid) {
        if (params.parallel_rows == 1) {
          storePackedResponse(I_MEM, input_bits, dma_counter_d,
            packedReadCount(elements_to_read_temp, dma_counter_d, elemsPerChunk_in),
            mul1 + j_x_temp, dataResponse)
        } else if (params.FLEXIBLE_BANK_GROUPING) {
          // One physical write port per activation bank. Select each bank's
          // logical K interval and row before the existing repacker.
          val starts = (0 until totalSyncMems).map(lane =>
            MuxLookup(y_slice_Reg, bankOffset(blocks_in_Sync_mem, lane % params.banks_per_row))(
              groupingRows.map(y => y.U -> bankOffset(blocks_in_Sync_mem, lane % (totalSyncMems / y)))))
          val enabled = (0 until totalSyncMems).map(lane => laneRow(lane) === Rin_cnt)
          storePackedResponse(I_MEM, input_bits, dma_counter_d,
            packedReadCount(elements_to_read_temp, dma_counter_d, elemsPerChunk_in),
            mul1 + j_x_temp, dataResponse, starts, enabled)
        } else {
          // Each row has b input banks. Repack a row into its own banks while keeping
          // the source address/beat offset specific to that row and K block.
          for (y <- 0 until params.parallel_rows) {
            when(Rin_cnt === y.U) {
              storePackedResponse(I_MEM.slice(y * weightBanks, (y + 1) * weightBanks),
                input_bits, dma_counter_d,
                packedReadCount(elements_to_read_temp, dma_counter_d, elemsPerChunk_in),
                mul1 + j_x_temp, dataResponse)
            }
          }
        }
      }

      when(!dma_resp && !SplitEnable ) {
        dma_send      := true.B && (Rin_cnt < YS -1.U)
        dma_resp      := true.B && (Rin_cnt < YS -1.U)
        dma_counter_a := 0.U
        dma_counter_d := 0.U
        Rin_cnt       := Rin_cnt + 1.U
        if (params.parallel_rows > 1) {
          // All active rows have the same K extent; keep one per-row count.
          when(Rin_cnt < YS - 1.U) { valid_inputs := 0.U }
        }
        j_x           := Mux(Rin_cnt < YS -1.U,j_x_start,j_x)
        i_x           := Mux(Rin_cnt < YS -1.U,i_x + 1.U,i_x)
        mul1          := Mux(Rin_cnt < YS -1.U,(xRowBase + cin_reg),mul1)

        //before load X
        val XS_temp   = Mux(cin_reg - j_x_start  >= x_slice_input_reg,x_slice_input_reg,cin_reg - j_x_start )
        val mul1_temp = xRowBase + cin_reg

        val mul2_temp = XS_temp * x_elems_per_reg
        xRowBase := xRowBase + cin_reg

        XS            := XS_temp
        mul2          := mul2_temp
        mul1          := mul1_temp

        //before load X
        all_available_elems_x := (XS * x_elems_per_reg).min(cin_reg - j_x_start)
        j_x_temp              := j_x_start

        chunkInfoModule_A.io.j_x  := j_x_start
        chunkInfoModule_A.io.mul1 := mul1_temp
        chunkInfoModule_A.io.mul2 := mul2_temp
        elements_to_read          := chunkInfoModule_A.io.elements_to_read
        chunk_addres              := chunkInfoModule_A.io.chunk_address

        chunkInfoModule_D.io.j_x_temp         := j_x_start
        chunkInfoModule_D.io.mul1             := mul1_temp
        chunkInfoModule_D.io.mul2             := mul2_temp
        chunkInfoModule_D.io.input_bits       := input_bits
        chunkInfoModule_D.io.max_bits         := params.XBitWidth.U
        elements_to_read_temp := chunkInfoModule_D.io.elements_to_read_temp

        val initialWords = readActivations(0.U, Rin_cnt + 1.U === YS)
        for (sync_mem_idx <- 0 until totalSyncMems) {
          I_vals(sync_mem_idx) := initialWords(sync_mem_idx)
        }

      }

      when(Rin_cnt === YS && !SplitEnable){
        val x_slice_reg_elems = x_slice_input_reg * x_elems_per_reg
        load_x_cnt := load_x_cnt + 1.U
        locally {
          when(j_x_start + x_slice_reg_elems >= cin_reg) { xStartBase := xRowBase }
        }
        locally {
          // One limit per bank is shared by all Y rows and all output columns.
          // The existing counter_2 advances the position; no per-cycle products.
          val span = blocks_in_Sync_mem * x_elems_per_reg
          for (bank <- 0 until weightBanks) {
            val start = bankOffset(span, bank)
            val remaining = Mux(valid_inputs > start, valid_inputs - start, 0.U)
            val positions = Mux(x_elems_per_reg === 2.U, (remaining + 1.U) >> 1, remaining)
            bankValidPositions(bank) := positions.min(blocks_in_Sync_mem)
          }
        }
        j_x_start  := Mux(j_x_start + x_slice_reg_elems   >= cin_reg , 0.U,j_x_start + x_slice_reg_elems )
        i_x_start  := Mux(j_x_start + x_slice_reg_elems   >= cin_reg , i_x_start + YS -0.U,i_x_start)
        cycleCount_1 := 0.U
        Products.foreach(_ := 0.U)

        val remain_in_column      = cin_reg - i_w
        // Preserve the carry in ceil(K / packed-weights-per-byte). K may
        // equal the maximum value representable by its dimension counter.
        val remain_in_column_regs = if (groupScaling)
          (remain_in_column +& (w_elems_per_reg - 1.U)) / w_elems_per_reg
        else (remain_in_column + w_elems_per_reg - 1.U) / w_elems_per_reg
        val XS_W_temp             = Mux(remain_in_column_regs >= x_slice_weights_reg,x_slice_weights_reg,remain_in_column_regs)  //max_w_elems

        //before load W
        val temp_mul1 = 0.U
        val temp_mul2 = if (params.parallel_rows == 1) XS_W_temp * w_elems_per_reg
        else (XS_W_temp * w_elems_per_reg).min(valid_inputs)
        mul1 := temp_mul1
        mul2 := temp_mul2
        all_available_elems := ( temp_mul2).min(cin_reg - i_w)

        chunkInfoModule_A.io.j_x              := i_w
        chunkInfoModule_A.io.x_elems_per_reg  := w_elems_per_reg
        chunkInfoModule_A.io.elemsPerChunk_in := elemsPerChunk_w
        chunkInfoModule_A.io.adr_X            := adr_W
        chunkInfoModule_A.io.RegsPerChunk_in  := RegsPerChunk_w
        chunkInfoModule_A.io.mul1             := temp_mul1
        chunkInfoModule_A.io.mul2             := temp_mul2

        elements_to_read := chunkInfoModule_A.io.elements_to_read
        chunk_addres     := chunkInfoModule_A.io.chunk_address

        chunkInfoModule_D.io.j_x_temp         := i_w
        chunkInfoModule_D.io.x_elems_per_reg  := w_elems_per_reg
        chunkInfoModule_D.io.elemsPerChunk_in := elemsPerChunk_w
        chunkInfoModule_D.io.RegsPerChunk_in  := RegsPerChunk_w
        chunkInfoModule_D.io.mul1             := temp_mul1
        chunkInfoModule_D.io.mul2             := temp_mul2
        chunkInfoModule_D.io.input_bits       := weight_bits
        chunkInfoModule_D.io.max_bits         := params.WBitWidth.U
        elements_to_read_temp := chunkInfoModule_D.io.elements_to_read_temp

        dma_send      := true.B
        dma_resp      := true.B
        dma_counter_a := 0.U
        dma_counter_d := 0.U
        w_count       := 0.U
        i_w_temp      := i_w

        // X is drained and the previous selection pipeline has retired before
        // entering a new K window. Both weight streams start at column zero.
        assert(dma.io.empty, "New weight window has outstanding activation DMA")
        j_w := 0.U
        load_w_buff_idx := 0.U
        weightReturnBuffer := 0.U
        select_buff_idx := 0.U
        weightReturnBase := temp_mul1
        weightReserved.foreach(_ := false.B)
        buffers_mode.foreach(_ := false.B)

        state      := sGenerateRegFiles
        if (params.SKIP_ZERO_WEIGHTS && params.FP_ACTIVATIONS) zeroSkipSpecial.get := false.B
        // Seed selection once after X is loaded, before producing its LUT rows.
        activationAddress.io.restart := true.B
        Products.foreach(_ := 0.U)

        counter_row := 0.U
        offset_counter := 0.U

      }

    }

    // === sGenerateRegFiles: generate lookup products for each activation lane ===
    is(sGenerateRegFiles) {
      if(params.DEBUG){
        PC_Generate := PC_Generate + 1.U
      }

      val start_row = counter_1 << shiftAmt

      if (params.SKIP_ZERO_WEIGHTS && params.FP_ACTIVATIONS) {
        // Inspect each valid activation during its ordinary LUT generation.
        // Do not suppress 0*Inf/NaN: the whole window then replays densely.
        when(cycleCount_1 === 0.U) {
          for (lane <- 0 until totalSyncMems) {
            val word = I_vals(rotatedActivationBank(lane, activationAddress.io.bankShift))
            val cells = VecInit(Seq.tabulate(activationCells)(i => word(params.XBitWidth * (i + 1) - 1, params.XBitWidth * i)))
            val bits = cells(activationAddress.io.cell)
            val logical = bankOffset(blocks_in_Sync_mem, lane % weightBanks) + counter_1
            when((lane / weightBanks).U < YS && logical < valid_inputs && bits(params.FP_BITS - 2, FPActivation.fractionBits(params.FP_BITS)).andR) {
              zeroSkipSpecial.get := true.B
            }
          }
        }
      }

      for (sync_mem_idx <- 0 until totalSyncMems) {

        when(cycleCount_1 =/= 0.U ) {
          val finalAddressInBRAM =  start_row + (cycleCount_1 -1.U)
          temp_muls_mem(sync_mem_idx).write(finalAddressInBRAM, Products(sync_mem_idx))
        }

      }

      // Product Generator Mesh
      for (sync_mem_idx <- 0 until totalSyncMems) {
        val gen = Product_Generator_Mesh(sync_mem_idx)
        Products(sync_mem_idx) := gen.io.Product_out
      }

      cycleCount_1 := cycleCount_1 + 1.U

      when(cycleCount_1 === block_rows) {
        activationAddress.io.advance := true.B
        counter_1    := counter_1 + 1.U
        cycleCount_1 := 0.U
        Products.foreach(_ := 0.U)

        offset_counter := Mux(offset_counter === (activationCells - 1).U, 0.U, offset_counter + 1.U)
        counter_row    := Mux(offset_counter === (activationCells - 1).U, counter_row + 1.U, counter_row)

      }

      // Request phase (cycle N)
      val enable_read = ((cycleCount_1 === block_rows - 2.U) && (offset_counter === (activationCells - 1).U)) || (block_rows === 1.U)
      val row         = Mux(offset_counter === (activationCells - 1).U, counter_row + 1.U, counter_row)

      // Read request
      val readData = readActivations(row, enable_read)

      // Delay the enable signal by 1 cycle
      val read_valid = RegNext(enable_read, init=false.B)

      // Capture results (cycle N+1)
      when (read_valid) {
        for (i <- 0 until totalSyncMems) {
          I_vals(i) := readData(i)
        }
      }

      when(counter_1 === blocks_in_Sync_mem - 1.U && cycleCount_1 === (block_rows )) { // Finish after the final product row has been written.
        Rin_cnt       := 0.U
        j_x           := j_x_start
        i_x           := i_x_start
        xRowBase := xStartBase
        cycleCount_1  := 0.U
        counter_1     := 0.U

        READ_ON       := true.B


        counter_row := 0.U
        offset_counter := 0.U
        offset_counter_1 := 0.U
        cell_counter   := 0.U
        // Rewind X after generation so column zero reuses its first activation.
        activationAddress.io.restart := true.B

        state            := sLoadW_and_sSelectAndAccumulate
      }

    }
    // === sLoadW_and_sSelectAndAccumulate: consume weight buffers, select products and accumulate ===
    is(sLoadW_and_sSelectAndAccumulate) {

      if(params.DEBUG){
        PC_loadW_and_Select := PC_loadW_and_Select + 1.U
      }

      // Input-side progress is independent of results still in the pipeline.
      val earlyWeightRelease = if (params.EARLY_WEIGHT_BUFFER_RELEASE)
        (weight_bits === 8.U && input_bits === 16.U && !zeroSkipMode) else false.B
      val columnReady = if (params.EARLY_WEIGHT_BUFFER_RELEASE)
        buffers_mode(select_buff_idx) || (earlyWeightRelease && weightColumnActive.get)
      else buffers_mode(select_buff_idx)
      selectionIssue := READ_ON && (w_idx < cout_reg) && columnReady &&
        (if (groupScaling) groupEngine.get.io.canIssue else true.B)
      selectionLast := selectionIssue && (cycleCount_2 === x_elems_per_reg - 1.U) &&
        (counter_2 === blocks_in_Sync_mem - 1.U)
      if (params.SKIP_ZERO_WEIGHTS) {
        val selector = zeroSelector.get
        selector.io.enable := zeroSkipMode && READ_ON && w_idx < cout_reg && columnReady
        selector.io.start := selector.io.enable && !selector.io.busy
        when(zeroSkipMode) {
          selectionIssue := selector.io.valid
          selectionLast := selector.io.last
          zeroSkipFirst := selector.io.first
        }
      }
      weightBufferS2 := select_buff_idx

      // Release only after the final weight read has reached stage 2. Earlier
      // results no longer need this SRAM buffer; they carry their own column tag.
      when(!earlyWeightRelease && selectionValid(0) && selectionEnd(0)) {
        buffers_mode(weightBufferS2) := false.B
        weightReserved(weightBufferS2) := false.B
      }
      if (params.EARLY_WEIGHT_BUFFER_RELEASE) {
        when(earlyWeightRelease && selectionIssue) {
          // Consume readiness once, at column start. A later DMA completion may
          // mark this same SRAM buffer ready for column + W_BUFFS while the
          // current column continues from heldWeightWords.
          when(!weightColumnActive.get) { buffers_mode(select_buff_idx) := false.B }
          weightColumnActive.get := !selectionLast
        }
      }

      def denseSelection(): Unit = { // Payload stages keep advancing even when a new input is unavailable.
        val buff_index = select_buff_idx

        // Pipeline weight addresses by one cycle and activation addresses by two.
        // Capture every active selection cycle, including the final pipeline drain.
        weightCellS2 := cell_counter
        weightOffsetS2 := offset_counter_1
        activationCellS3 := RegNext(activationAddress.io.cell, 0.U)
        activationBankShiftS3 := RegNext(activationAddress.io.bankShift, 0.U)

        if(params.DEBUG) {
          when(selectionIssue || selectionValid.asUInt.orR || roundedPending.getOrElse(false.B)) {
            PC_Select := PC_Select + 1.U
          }
        }

        // Request phase (cycle N)
        val enable_read = selectionIssue && (offset_counter === 0.U)
        val row         = Mux(offset_counter === (activationCells - 1).U, counter_row + 1.U, counter_row)

        // Read request
        val readData = readActivations(row, enable_read)

        // Capture only requested activation words; hold the current word
        // while its remaining cells flow through selection.
        val readValid = RegNext(enable_read, false.B)
        when(readValid) {
          for (i <- 0 until totalSyncMems) {
            I_vals(i) := readData(i)
          }
        }

        when(selectionIssue) {
          when(offset_counter === (activationCells - 1).U && cycleCount_2 === x_elems_per_reg - 1.U) {
            offset_counter := 0.U
            counter_row := counter_row + 1.U
          }.otherwise {
            offset_counter := Mux(cycleCount_2 === x_elems_per_reg - 1.U,
              offset_counter + 1.U, offset_counter)
          }
        }

        val enable_read_w = selectionIssue && (elements_counter === 0.U)

        val W_wire = Wire(Vec(buffers, Vec(weightBanks, UInt(params.DMA_bits.W))))

        for (b <- 0 until buffers) {
          val valid_read = (buff_index === b.U) && enable_read_w
          for (i <- 0 until weightBanks) {
            if (params.SKIP_ZERO_WEIGHTS) {
              weightReadAddress.get(b)(i) := row_weight
              weightReadEnable.get(b)(i) := valid_read
              W_wire(b)(i) := weightReadData.get(b)(i)
            } else W_wire(b)(i) := W_MEM(b)(i).read(row_weight,
              valid_read && (if (params.FLEXIBLE_BANK_GROUPING) i.U < banksForRow else true.B))
          }
        }

        val weightReadCaptured = RegNext(enable_read_w, false.B)
        if (params.EARLY_WEIGHT_BUFFER_RELEASE) {
          when(earlyWeightRelease && weightReadCaptured) {
            for (i <- 0 until weightBanks) {
              heldWeightWords.get(i) := W_wire(weightBufferS2)(i)
            }
          }
          // For W8/input16 each bank's word covers eight positions. The final
          // read can precede the last selection input by up to seven clocks.
          // Release on capture, never on request: a synchronous read still owns
          // its SRAM word until this edge. Result column tags are unchanged.
          val lastWeightReadCaptured = RegNext(
            enable_read_w && earlyWeightRelease && (counter_2 +& 8.U >= blocks_in_Sync_mem), false.B)
          when(lastWeightReadCaptured) { weightReserved(weightBufferS2) := false.B }
        }

        when(selectionIssue) {
          when(elements_counter === elemsPerChunk_w * weightBanks.U - weightBanks.U) {
            elements_counter := 0.U
            row_weight := row_weight + 1.U
          }.otherwise {
            elements_counter := elements_counter + weightBanks.U
          }
        }

        val start_row = RegNext(counter_2 << shiftAmt)

        // Selection pipeline stage 1: activation validity.

        for (sync_mem_idx <- 0 until totalSyncMems) {

          val valid_x = {
            val activeRow = if (params.parallel_rows == 1) true.B else laneRow(sync_mem_idx) < YS
            activeRow && counter_2 < bankValidPositions(laneBank(sync_mem_idx))
          }

          Valid_In_Vec(sync_mem_idx)  := valid_x

        }
        cycleCount_2_reg := cycleCount_2

        // Selection pipeline stage 2: shared weight decoding and LUT row.

        for (sync_mem_idx <- 0 until weightBanks) {

          // Each parallel lane owns this bank; no global-index division.
          val byte_w_idx = weightCellS2
          val buff_idx   = weightBufferS2(buffIdxW - 1, 0)
          // Bypass the arriving word on its capture cycle to retain the original
          // decode latency; use the held copy on subsequent selection positions.
          val data_64 = if (params.EARLY_WEIGHT_BUFFER_RELEASE)
            Mux(earlyWeightRelease && !weightReadCaptured,
              heldWeightWords.get(sync_mem_idx), W_wire(buff_idx)(sync_mem_idx))
          else W_wire(buff_idx)(sync_mem_idx)
          val bytes_8 = VecInit(Seq.tabulate(params.DMA_bits / params.WBitWidth)(i => data_64((i + 1) *  params.WBitWidth - 1, i * params.WBitWidth)))
          val W_value = bytes_8(byte_w_idx)
          // Constant shifts replace offset * runtime weight_bits.
          val shift_amt = Mux(weight_bits === 8.U, 0.U,
            Mux(weight_bits === 4.U, weightOffsetS2 << 2, weightOffsetS2 << 1))

          // Weight data from the previous stage.
          val shiftedReg =  W_value >> shift_amt
          val weight_Sint_part = Wire(SInt(params.WBitWidth.W))
          weight_Sint_part := 0.S  // default initialization to avoid uninitialized error

          when(weight_bits === 2.U) {
            val chunk2 = shiftedReg(1, 0)
            weight_Sint_part := Cat(Fill(6, chunk2(1)), chunk2).asSInt
          }.elsewhen(weight_bits === 4.U) {
            val chunk4 = shiftedReg(3, 0)
            weight_Sint_part := Cat(Fill(4, chunk4(3)), chunk4).asSInt
          }.elsewhen(weight_bits === 8.U) {
            val chunk8 = shiftedReg(7, 0)
            weight_Sint_part := Cat(chunk8(7), chunk8).asSInt
          }

          val abs_weight_uint        = Mux(weight_Sint_part < 0.S, -weight_Sint_part, weight_Sint_part).asUInt

          // 3. Calculate Row Index & Offset for Reading Bram

          val row_in_block = Mux(abs_weight_uint(0) === 0.U, (abs_weight_uint >> 1) - 1.U, (abs_weight_uint - 1.U) >> 1)
          val row_idx      = start_row + row_in_block
          val elemOffset   = cycleCount_2_reg

          // 4. Extract Activation and Compute Product Sign

          val w_reg_valid_temp = if (params.FP_ACTIVATIONS) Valid_In_Vec(sync_mem_idx)
          else (abs_weight_uint =/= 0.U) && Valid_In_Vec(sync_mem_idx)

          // Shared decoded-weight metadata for the next stage.
          weight_Sint_part_Reg(sync_mem_idx) := weight_Sint_part
          abs_weight_uint_Reg(sync_mem_idx)  := abs_weight_uint
          row_idx_Reg(sync_mem_idx)          := row_idx
          elemOffset_Reg(sync_mem_idx)       := elemOffset
          w_reg_valid_temp_Reg(sync_mem_idx) := w_reg_valid_temp
        }
        // Selection pipeline stage 3: per-row products and sign correction.
        for (sync_mem_idx <- 0 until totalSyncMems) {

          // Shared decoded weights from stage 2.
          val weightLane = laneBank(sync_mem_idx)
          val weight_Sint_part = weight_Sint_part_Reg(weightLane)
          val abs_weight_uint  = abs_weight_uint_Reg(weightLane)
          val row_idx          = row_idx_Reg(weightLane)
          val elemOffset       = elemOffset_Reg(weightLane)
          val w_reg_valid_temp = if (params.parallel_rows == 1) w_reg_valid_temp_Reg(weightLane)
          else w_reg_valid_temp_Reg(weightLane) && RegNext(Valid_In_Vec(sync_mem_idx))

          val bank_in_idx = rotatedActivationBank(sync_mem_idx, activationBankShiftS3)
          val byte_in_idx = activationCellS3
          val data_64 =  I_vals(bank_in_idx)
          val bytes = VecInit(Seq.tabulate(params.DMA_bits / params.XBitWidth)(i => data_64((i + 1) *  params.XBitWidth - 1, i *  params.XBitWidth)))
          val data  = bytes(byte_in_idx)

          val extender = chunkExtenders(sync_mem_idx)(chunkPartIdx)
          extender.io.data       := data
          extender.io.input_bits := input_bits
          extender.io.idx        := elemOffset
          val sign_X_reg_part = extender.io.out

          val sign_x       = sign_X_reg_part(input_bits - 1.U)
          val sign_w       = weight_Sint_part(weight_Sint_part.getWidth - 1)
          val product_sign = sign_x ^ sign_w // 0 => + | 1 => -

          // 5.  Compute Signed Product

          val extended_activation = sign_X_reg_part.pad(params.XBitWidth + 1)
          val abs_val_new = if (params.FP_ACTIVATIONS) FPActivation.significand(data, params.FP_BITS).zext
          else Mux(extended_activation < 0.S, -extended_activation, extended_activation)
          val signed_val  = Mux(product_sign === 1.U, -abs_val_new, abs_val_new)

          val disable = (abs_weight_uint(0) === 0.U) || (abs_weight_uint === 0.U)  || !w_reg_valid_temp
          sum(sync_mem_idx) := RegNext(Mux(disable, 0.S, signed_val))
          if (params.FP_ACTIVATIONS) {
            val special = data(params.FP_BITS - 2, FPActivation.fractionBits(params.FP_BITS)).andR
            val infinity = special && !data(FPActivation.fractionBits(params.FP_BITS) - 1, 0).orR
            val nan = w_reg_valid_temp && special && (data(FPActivation.fractionBits(params.FP_BITS) - 1, 0).orR || abs_weight_uint === 0.U)
            val infProduct = w_reg_valid_temp && infinity && abs_weight_uint =/= 0.U
            val meta = fpMeta.get(sync_mem_idx)
            meta.shift := RegNext(FPActivation.shift(data, params.FP_BITS))
            meta.nonZeroWeight := RegNext(w_reg_valid_temp && abs_weight_uint =/= 0.U)
            meta.flags := RegNext(Cat(infProduct && product_sign, infProduct && !product_sign, nan))
          }

          // 6. Read Partial Product from Temp Memory

          val enable_read        = if (params.FP_ACTIVATIONS) selectionValid(1) && w_reg_valid_temp && abs_weight_uint =/= 0.U
          else selectionValid(1) && w_reg_valid_temp
          val finalAddressInBRAM = row_idx

          if (params.SKIP_ZERO_WEIGHTS) {
            productReadAddress.get(sync_mem_idx) := finalAddressInBRAM
            productReadEnable.get(sync_mem_idx) := enable_read
            Products(sync_mem_idx) := productReadData.get(sync_mem_idx)
          } else Products(sync_mem_idx) := temp_muls_mem(sync_mem_idx).read(finalAddressInBRAM, enable_read)
          Sign(sync_mem_idx)     := RegNext(product_sign === 0.U)

          elemOffVec(sync_mem_idx)  := RegNext(elemOffset)
          w_reg_valid(sync_mem_idx) := RegNext(w_reg_valid_temp)
        }


        when(selectionIssue)  {
          when(cycleCount_2 === x_elems_per_reg - 1.U) {
            activationAddress.io.advance := true.B
            when(offset_counter_1 === lastWeightOffset) {
              offset_counter_1 := 0.U
              cell_counter := Mux(cell_counter === (bankSize - 1).U,
                0.U, cell_counter + 1.U)
            }.otherwise {
              offset_counter_1 := offset_counter_1 + 1.U
            }
          }
          cycleCount_2 := Mux(cycleCount_2 === x_elems_per_reg - 1.U , 0.U ,cycleCount_2 + 1.U)
          counter_2    := Mux(cycleCount_2 === x_elems_per_reg - 1.U ,counter_2 + 1.U,counter_2)

        }

      }
      if (params.SKIP_ZERO_WEIGHTS) {
        when(!zeroSkipMode) { denseSelection() }
        // The same single read port of each W SRAM serves dense and sparse
        // replay. Dense reads are disabled above while sparse replay is active.
        val selector = zeroSelector.get
        for (bank <- 0 until weightBanks) {
          val data = Wire(Vec(buffers, UInt(params.DMA_bits.W)))
          for (buffer <- 0 until buffers) {
            when(zeroSkipMode) {
              weightReadAddress.get(buffer)(bank) := selector.io.readAddress(bank)
              weightReadEnable.get(buffer)(bank) := selector.io.readEnable(bank) && select_buff_idx === buffer.U
            }
            data(buffer) := weightReadData.get(buffer)(bank)
          }
          // Capture the buffer tag with its synchronous SRAM request.
          val bufferS1 = RegEnable(select_buff_idx, selector.io.readEnable(bank))
          selector.io.readData(bank) := data(bufferS1)
        }
        if (params.DEBUG) {
          when(zeroSkipMode && (selectionIssue || selectionValid.asUInt.orR)) {
            PC_Select := PC_Select + 1.U
          }
        }
      } else denseSelection()

      locally {
        when(selectionValid(3)) { // Retire only tagged, valid product contributions.

          // Reduce the b bank contributions separately for each activation row.
          val groupSize = totalSyncMems / params.parallel_rows

          val laneValues: Seq[SInt] = (0 until totalSyncMems).map { product_idx =>
                val offset = Mux(zeroSkipMode, 0.U, elemOffVec(product_idx))
                val w_reg_valid_tmp = if (params.SKIP_ZERO_WEIGHTS)
                  Mux(zeroSkipMode, sparseValid.get(product_idx), w_reg_valid(product_idx)) else w_reg_valid(product_idx)
                val product = if (params.SKIP_ZERO_WEIGHTS)
                  Mux(zeroSkipMode, sparseProducts.get(product_idx), Products(product_idx)) else Products(product_idx)
                val correction = if (params.SKIP_ZERO_WEIGHTS)
                  Mux(zeroSkipMode, sparseSum.get(product_idx), sum(product_idx)) else sum(product_idx)
                val positive = if (params.SKIP_ZERO_WEIGHTS)
                  Mux(zeroSkipMode, sparseSign.get(product_idx), Sign(product_idx)) else Sign(product_idx)

                val valid_read = Mux(
                w_reg_valid_tmp,
                product,
                0.U(Products(product_idx).getWidth.W)
              )

                val readDataVec_part = Mux(offset === 0.U,
                valid_read & mask_p,
                (valid_read >> chunks_per_Product_bits) & mask_p
              )

                val unsigned_clean = readDataVec_part // Already masked to the runtime product width.

                val readDataVec_part_sign = unsigned_clean.zext.asSInt

                // Signed accumulation with conditional sign adjustment
              val signedValue = Mux(positive,
                readDataVec_part_sign - correction,
                -readDataVec_part_sign - correction)

                if (params.FP_ACTIVATIONS) {
                  val meta = if (params.SKIP_ZERO_WEIGHTS)
                    Mux(zeroSkipMode, sparseMeta.get(product_idx), fpMeta.get(product_idx)) else fpMeta.get(product_idx)
                  val aligned = (signedValue.pad(accumulatorWidth) << meta.shift)(accumulatorWidth - 1, 0).asSInt
                  Mux(meta.nonZeroWeight, aligned, 0.S(accumulatorWidth.W))
              } else signedValue
          }
          // Each level combines adjacent fixed groups. Flexible modes select
          // tree taps; they do not duplicate a full reduction tree per mode.
          val accum = if (params.FLEXIBLE_BANK_GROUPING) {
            // First reduce a base-B group, which need not be a power of two.
            // Carry an unpaired node to the next level (e.g. 5 -> 3 -> 2 -> 1).
            // The maximum-YS base groups then form a power-of-two hierarchy.
            // B5/YS4 therefore supports B5, B10 and B20 without padding lanes.
            def balancedSum(values: Seq[SInt]): SInt = {
              if (values.size == 1) values.head
              else balancedSum(values.grouped(2).map(v =>
                if (v.size == 1) v.head else v(0) + v(1)).toSeq)
            }
            val baseGroups = laneValues.map(_.pad(accumulatorWidth))
              .grouped(params.banks_per_row).map(v => balancedSum(v.toSeq)).toSeq
            val levels = scala.collection.mutable.ArrayBuffer[Seq[SInt]](baseGroups)
            for (_ <- 0 until log2Ceil(params.parallel_rows)) {
              levels += levels.last.grouped(2).map(v => v(0) + v(1)).toSeq
            }
            VecInit((0 until params.parallel_rows).map(y =>
              MuxLookup(y_slice_Reg, 0.S(accumulatorWidth.W))(
                groupingRows.filter(y < _).map(rows => rows.U -> levels(log2Ceil(params.parallel_rows / rows))(y)))))
          } else VecInit((0 until params.parallel_rows).map(y =>
            laneValues.slice(y * groupSize, (y + 1) * groupSize).map(_.pad(accumulatorWidth)).reduce(_ + _)))
          def rowFlags(y: Int): UInt = if (params.FLEXIBLE_BANK_GROUPING)
            MuxLookup(y_slice_Reg, 0.U(3.W))(groupingRows.filter(y < _).map(rows => {
              val b = totalSyncMems / rows
              rows.U -> (0 until b).map(i => fpMeta.get(y * b + i).flags).reduce(_ | _)
            }))
            else (0 until groupSize).map(i =>
              Mux(zeroSkipMode, 0.U, fpMeta.get(y * groupSize + i).flags)).reduce(_ | _)

          if (groupScaling) {
            val e = groupEngine.get
            e.io.valid := true.B
            for (lane <- 0 until totalSyncMems) {
              assert(!fpMeta.get(lane).flags.orR, "Group activations must be finite FP16")
            }
            for (b <- 0 until (if (params.GROUP_SCALE_ALIGNED) 1 else weightBanks); y <- 0 until params.parallel_rows) {
              val value = if (params.GROUP_SCALE_ALIGNED) accum(y) else laneValues(y * weightBanks + b)
              e.io.values(b)(y) := Mux(y.U < YS, value, 0.S)
            }
          } else for (y <- 0 until params.parallel_rows) {
            val mem = outputMemory
            if (!params.ROUNDED_ACCUMULATION) mem.io.updateValid(y) := y.U < YS
            if (params.ROUNDED_ACCUMULATION) {
              // Keep the existing exact bank reduction; round once per
              // selection token before the FP32 running-sum update.
              val convert = roundedSubtotals.get(y)
              convert.io.value := accum(y)
              convert.io.flags := rowFlags(y)
            } else mem.io.delta(y) := accum(y)
            if (params.FP_ACTIVATIONS) {
              mem.io.flags(y) := (if (params.ROUNDED_ACCUMULATION) 0.U else rowFlags(y))
            }
          }


        }

      }

      when(selectionLast) { // Start the next column before this one's tail retires.

        offset_counter_1 := 0.U
        cell_counter   := 0.U
        // Every output column replays X from the same initial bank/cell.
        activationAddress.io.restart := true.B

        cycleCount_2 := 0.U
        counter_2    := 0.U
        w_idx      := Mux(w_idx === cout_reg,0.U,w_idx + 1.U)

        counter_row := 0.U
        offset_counter := 0.U

        row_weight := 0.U
        elements_counter := 0.U

        READ_ON       := true.B


        select_buff_idx                := select_buff_idx + 1.U

      }

      // The final tagged contribution retires into output SRAM. Only then
      // may the controller change K windows or expose outputs to the DMA.
      val selectionWindowLast = selectionValid(3) && selectionEnd(3) && (selectionColumn(3) === cout_reg - 1.U)
      if (groupScaling) { when(selectionWindowLast) { windowRetired.get := true.B } }
      val finishWindow = if (groupScaling)
        windowRetired.get && groupEngine.get.io.empty && !groupWritePending
        else if (params.ROUNDED_ACCUMULATION) roundedPending.get && roundedWindowLast.get
        else selectionWindowLast
      when(finishWindow) {
        if (groupScaling) windowRetired.get := false.B

        assert(j_w === cout_reg && w_count === cout_reg && dma.io.empty,
          "Selection retired before all weight requests/responses drained")
        assert(!weightReserved.asUInt.orR,
          "Selection retired while a weight buffer was still reserved")

        when(if (params.GROUP_SCALE_ALIGNED) i_w_start + valid_inputs >= cin_reg else load_x_cnt >= dma_limit) {
          i_w        := 0.U
          i_w_start  := 0.U
          init_cycle    := true.B

          load_x_cnt := 0.U
          Rin_cnt    := 0.U
          dma_counter_a := 0.U
          dma_counter_d := 0.U
          storeWritesIssued := 0.U
          dma_send := true.B
          state      := sStoreOutput
          outputReadRow := 0.U
          outputReadColumn := 0.U
          outputReadParity := i_o(0) && cout_reg(0)
        }.otherwise{
          val XS_temp = if (params.GROUP_SCALE_ALIGNED) groupWindow(clmp, j_x)
            else Mux(cin_reg - j_x >= x_slice_input_reg, x_slice_input_reg, cin_reg - j_x)
          if (params.GROUP_SCALE_ALIGNED) {
            x_slice_input_reg := XS_temp
            x_slice_weights_reg := packedWeightSlots(XS_temp)
          }
          val mul1_temp = xRowBase
          val mul2_temp = XS_temp * x_elems_per_reg

          i_w           := i_w_start + step_new
          i_w_start     := i_w_start + step_new
          init_cycle    := true.B

          XS            := XS_temp
          dma_counter_a := 0.U
          dma_counter_d := 0.U
          mul2          := mul2_temp
          mul1          := mul1_temp

          //before load X
          val elems_per_word = if (fpGeometry) 1.U else params.XBitWidth.U / input_bits
          all_available_elems_x := ((if (params.GROUP_SCALE_ALIGNED) XS_temp else XS) * elems_per_word).min(cin_reg - j_x)
          j_x_temp              := j_x

          chunkInfoModule_A.io.j_x  := j_x
          chunkInfoModule_A.io.mul1 := mul1_temp
          chunkInfoModule_A.io.mul2 := mul2_temp
          elements_to_read          := chunkInfoModule_A.io.elements_to_read
          chunk_addres              := chunkInfoModule_A.io.chunk_address

          chunkInfoModule_D.io.j_x_temp         := j_x
          chunkInfoModule_D.io.mul1             := mul1_temp
          chunkInfoModule_D.io.mul2             := mul2_temp
          chunkInfoModule_D.io.input_bits       := input_bits
          chunkInfoModule_D.io.max_bits         := params.XBitWidth.U
          elements_to_read_temp := chunkInfoModule_D.io.elements_to_read_temp

          if (params.GROUP_SCALE_ALIGNED) {
            blocks_in_Sync_mem := positionsPerBank(XS_temp).min(
              (1.U << (params.WBitWidth.U - weight_bits)) * params.table_capacity_factor.U)
          } else if (params.parallel_rows == 1) {
            val remain_in_line = cin_reg - blocks_in_Sync_mem * totalSyncMems.U
            blocks_in_Sync_mem := ((remain_in_line + totalSyncMems.U - 1.U) / totalSyncMems.U) .min(blocks_in_Sync_mem)
          } else {
            blocks_in_Sync_mem := positionsPerBank(XS_temp).min(if (params.GROUP_SCALE_ALIGNED)
              (1.U << (params.WBitWidth.U - weight_bits)) * params.table_capacity_factor.U else blocks_in_Sync_mem)
          }

          state := sLoadX
          valid_inputs :=0.U

        }
        j_w              := 0.U
        w_idx            := 0.U
        cycleCount_2       := 0.U
        counter_2        := 0.U
        offset_counter_1 := 0.U
        cell_counter := 0.U


      }

    }

    // === sStoreOutput: convert/store output rows and advance row groups ===
    is(sStoreOutput) {
      if (params.ROUNDED_ACCUMULATION) {
        assert(!roundedPending.get, "Output storage started before rounded updates drained")
      }
      if(params.DEBUG){
        PC_storeO := PC_storeO + 1.U
      }

      outputReadIssue := outputReadRow < YS &&
        (outputWords.io.count +& outputReadPending.asUInt) < 4.U

      // Packing follows accepted requests, independently of late acknowledgements.
      val remaining_elems = cout_reg - dma_counter_a
      val R_elems_per_chunk = ((elems_per_chunk.U - done_elems_in_block).min(remaining_elems))
      val linear_index  = outputRowBase + dma_counter_a
      val chunk_index   = linear_index / ((elems_per_chunk.U)) // Output DMA-word index.
      val chunk_address = adr_O + (chunk_index * 8.U)

      when(dma_send && !dma.io.busy && outputWords.io.deq.valid) {
        dma.io.mode := true.B

        dma.io.writeData := outputWords.io.deq.bits
        outputWords.io.deq.ready := dma.io.a_fire

        // Enable only the output bytes carried by this DMA write.
        val beatBytes = 8  // Total number of byte lanes in a 64-bit word
        val bytesPerElem = params.OutBitWidth.U >> 3  // Divide by 8
        val startByte = done_elems_in_block * bytesPerElem
        val endByte   = startByte + (R_elems_per_chunk * bytesPerElem)
        dma.io.mask := Cat((0 until beatBytes).map { i => (i.U >= startByte) && (i.U < endByte) }.reverse)

        dma.io.addr   := chunk_address
        dma.io.valid  := true.B
        when(dma.io.a_fire) {
          storeWritesIssued := storeWritesIssued + 1.U
          val nextOffset = done_elems_in_block + R_elems_per_chunk
          done_elems_in_block := Mux(nextOffset === elems_per_chunk.U, 0.U, nextOffset)

          when(dma_counter_a + R_elems_per_chunk === cout_reg) {
            // Start the next row without waiting for this row's acknowledgements.
            dma_counter_a := 0.U
            Rin_cnt := Rin_cnt + 1.U
            i_o := i_o + 1.U
            outputRowBase := outputRowBase + cout_reg
            dma_send := Rin_cnt < YS - 1.U
          }.otherwise {
            dma_counter_a := dma_counter_a + R_elems_per_chunk
          }



        }

      }

      when(dma.io.d_valid) { // DMA response on channel D.
        dma_counter_d := dma_counter_d + 1.U
      }

      // Drain accepted DMA requests and buffered responses before completion
      // or before reinitializing the counters for another activation-row group.
      val outputReadsDrained = !outputWords.io.deq.valid && !outputReadPending
      when(Rin_cnt === YS && !dma_send && dma_counter_d === storeWritesIssued && dma.io.empty && outputReadsDrained) {
        dma_resp := false.B
        when(loops_cnt === LOOPS -1.U){
          Rin_cnt             := 0.U
          state               := sDELAY
          done_elems_in_block := 0.U
          loops_cnt := 0.U

        }.otherwise{
          loops_cnt     := loops_cnt + 1.U
          dma_send      := true.B
          dma_resp      := true.B
          dma_counter_a := 0.U
          dma_counter_d := 0.U
          storeWritesIssued := 0.U

          // Reset local indices before loading the next activation-row group.
          Rin_cnt       := 0.U
          j_x           := 0.U
          j_x_start     := 0.U
          j_x_temp      := 0.U

          // reset the counters used by the sync-read state machine
          counter_row   := 0.U
          offset_counter := 0.U
          cycleCount_1  := 0.U
          counter_1     := 0.U

          // clear Products & valid flags used during generation
          Products.foreach(_ := 0.U)
          valid_inputs := 0.U

          val XS_temp = if (params.GROUP_SCALE_ALIGNED) groupWindow(clmp, 0.U)
            else Mux(cin_reg >= x_slice_input_reg,x_slice_input_reg,cin_reg)
          if (params.GROUP_SCALE_ALIGNED) {
            x_slice_input_reg := XS_temp
            x_slice_weights_reg := packedWeightSlots(XS_temp)
          }
          XS := XS_temp

          // Limit the next row group to the remaining activation rows.
          if (params.parallel_rows > 1) {
            YS := (rin_reg - i_x).min(y_slice_Reg)
          }

          // recompute chunk parameters for next X load
          val elems_per_word = if (fpGeometry) 1.U else params.XBitWidth.U / input_bits
          all_available_elems_x := (XS_temp * elems_per_word).min(cin_reg)

          // reset load counters
          load_x_cnt := 0.U

          // go to LoadX
          state := sLoadX
          valid_inputs := 0.U
          val clamped = if (params.GROUP_SCALE_ALIGNED) groupWindow(clmp, 0.U)
            else Mux(cin_reg >= clmp, clmp, cin_reg)
          if (params.Rin > 1) {
            // A short final K block can shrink the bank span. Restore the
            // full span before loading the first block of the next row group.
            val initialBlocks = positionsPerBank(clamped)
            val capacity = (1.U << (params.WBitWidth.U - weight_bits)) * params.table_capacity_factor.U
            blocks_in_Sync_mem := initialBlocks.min(capacity)
          }
          val mul1_temp = xRowBase
          val mul2_temp = XS_temp * x_elems_per_reg

          mul2          := mul2_temp
          mul1          := mul1_temp

          chunkInfoModule_A.io.j_x  := 0.U
          chunkInfoModule_A.io.mul1 := mul1_temp
          chunkInfoModule_A.io.mul2 := mul2_temp

          elements_to_read          := chunkInfoModule_A.io.elements_to_read
          chunk_addres              := chunkInfoModule_A.io.chunk_address

          chunkInfoModule_D.io.j_x_temp         := 0.U
          chunkInfoModule_D.io.mul1             := mul1_temp
          chunkInfoModule_D.io.mul2             := mul2_temp
          chunkInfoModule_D.io.input_bits       := input_bits
          chunkInfoModule_D.io.max_bits         := params.XBitWidth.U
          elements_to_read_temp := chunkInfoModule_D.io.elements_to_read_temp

          // --- DIRTY INPUT ELEMENTS
          val row_bits = cin_reg * input_bits * YS // Bits covered by the active row group.

          // Calculate dirty bits in the last chunk
          val dirty_bits = row_bits % params.DMA_bits.U
          val dirty_elements = dirty_bits / input_bits
          // storePackedResponse repacks each new row from bank offset 0.
          // Keeping the old source-beat offset here shifts later rows.
          dirty_elems_x := (if (params.parallel_rows == 1 && params.Rin == 1) dirty_elements else 0.U)

          // --- DIRTY INPUT ELEMENTS

        }

      }
    }
    // === sDELAY: wait for DMA drain and completion acknowledgement ===
    is(sDELAY) {
      io.busy     := !dma.io.empty
      cmd.ready   := dma.io.empty && waitDone
      when(cmd.fire && waitDone) {
        state := sIdle
      }

    }

  }

  // Explicit counter selection; response remains valid under backpressure.
  when(state === sIdle && cmd.valid && readCounter) {
    counterResponses.io.enq.valid := true.B
    counterResponses.io.enq.bits.rd := cmd.bits.inst.rd
    val mapping = Seq(
      0.U -> PC_loadX, 1.U -> PC_Generate, 2.U -> PC_loadW,
      3.U -> PC_Select, 4.U -> PC_storeO, 5.U -> PC_loadW_and_Select)
    counterResponses.io.enq.bits.data :=
      (if (params.DEBUG) MuxLookup(rs2, 0.U(64.W))(mapping) else 0.U(64.W))
  }

  // === Background weight prefetch (overlaps generation and selection) ===
  when( ( buffers_mode.map(! _).reduce(_ || _)  && w_count =/= cout_reg ) && (state === sLoadW_and_sSelectAndAccumulate || state === sGenerateRegFiles)   ) { // Load W Phase

    val buff_index = load_w_buff_idx
    if(params.DEBUG){
      PC_loadW := PC_loadW + 1.U
    }

    when(init_cycle){
      step_new := all_available_elems
      init_cycle := false.B
    }

    // Weight memory is column-major and bit-packed. The source-beat offset
    // and destination-word limit must be computed independently for A and D:
    // requests may already belong to B while A's responses are still arriving.
    val beatShift = Mux(weight_bits === 2.U, 5.U,
      Mux(weight_bits === 4.U, 4.U, 3.U))
    val issueLinear = mul1 +& i_w_start +& dma_counter_a
    val returnLinear = weightReturnBase +& i_w_start +& dma_counter_d
    val issueAvailable = elemsPerChunk_w - (issueLinear & (elemsPerChunk_w - 1.U))
    val returnAvailable = elemsPerChunk_w - (returnLinear & (elemsPerChunk_w - 1.U))
    val issueCount = packedReadCount((all_available_elems - dma_counter_a).min(issueAvailable),
      dma_counter_a, elemsPerChunk_w)
    val returnCount = packedReadCount((all_available_elems - dma_counter_d).min(returnAvailable),
      dma_counter_d, elemsPerChunk_w)

    // Continue a filling buffer, or reserve an empty one. A ready/reading
    // buffer cannot be overwritten before selection's final read is captured.
    val canIssue = j_w < cout_reg &&
      (dma_counter_a =/= 0.U || !weightReserved(load_w_buff_idx))
    when(canIssue && !dma.io.busy && !SplitEnable) {
      dma.io.mode := 0.U
      dma.io.addr := adr_W + ((issueLinear >> beatShift) << 3)
      dma.io.valid := true.B
      if (params.WEIGHT_DMA_BURSTS) {
        // Restrict the first version to complete W8 SRAM words. Irregular
        // bank spans and packed/unaligned sources retain the proven beat path.
        val eligible = weight_bits === 8.U && input_bits === 16.U &&
          blocks_in_Sync_mem(2, 0) === 0.U && dma_counter_a(2, 0) === 0.U &&
          issueLinear(2, 0) === 0.U && dma.io.burstAvailable
        for ((bytes, index) <- Seq(16, 32, 64).zipWithIndex if bytes <= params.MAX_WEIGHT_BURST_BYTES) {
          when(eligible && all_available_elems - dma_counter_a >= bytes.U &&
            dma.io.addr(log2Ceil(bytes) - 1, 0) === 0.U && dma.io.burstLegal(index)) {
            dma.io.log_S := log2Ceil(bytes).U
          }
        }
      }
      when(dma.io.a_fire) {
        // A may have been held through backpressure while burst availability
        // changed. Advance by the size actually accepted, not the new proposal.
        val acceptedCount = if (params.WEIGHT_DMA_BURSTS)
          MuxLookup(dma.io.acceptedLogSize, issueCount)(Seq(4.U -> 16.U, 5.U -> 32.U, 6.U -> 64.U))
        else issueCount
        val next = dma_counter_a +& acceptedCount
        assert(issueCount =/= 0.U && next <= all_available_elems,
          "Weight request exceeded its column window")
        when(dma_counter_a === 0.U) { weightReserved(load_w_buff_idx) := true.B }
        when(next === all_available_elems) {
          // Advance after the final accepted request, without waiting for D.
          dma_counter_a := 0.U
          i_w := i_w_start
          j_w := j_w + 1.U
          mul1 := mul1 + cin_reg
          load_w_buff_idx := Mux(load_w_buff_idx === (buffers - 1).U,
            0.U, load_w_buff_idx + 1.U)
        }.otherwise {
          dma_counter_a := next
          i_w := i_w_start + next
        }
      }
    }

    // DmaModule retires in request order. One response cursor and the existing
    // packed-word carry therefore suffice; no per-ID payload table is added.
    when(dma.io.d_valid) {
      val next = dma_counter_d +& returnCount
      assert(w_count < cout_reg && weightReserved(weightReturnBuffer),
        "Weight response has no reserved destination buffer")
      assert(returnCount =/= 0.U && next <= all_available_elems,
        "Weight response exceeded its column window")
      for (b <- 0 until buffers) {
        when(weightReturnBuffer === b.U) {
          storePackedResponse(W_MEM(b), weight_bits, dma_counter_d,
            returnCount, returnLinear, dataResponse_w)
        }
      }
      when(next === all_available_elems) {
        // The SRAM write and ready flag commit together. Selection starts in
        // the following cycle; the extra registered dma_resp handoff is gone.
        buffers_mode(weightReturnBuffer) := true.B
        dma_counter_d := 0.U
        i_w_temp := i_w_start
        w_count := w_count + 1.U
        weightReturnBase := weightReturnBase + cin_reg
        weightReturnBuffer := Mux(weightReturnBuffer === (buffers - 1).U,
          0.U, weightReturnBuffer + 1.U)
      }.otherwise {
        dma_counter_d := next
        i_w_temp := i_w_start + next
      }
    }
  }

}

case object LUMIXEDParamsKey extends Field[LUMIXEDParams](LUMIXED_Config.LUMIXED_Config)

class WithLUMIXEDAccelerator extends Config((site, here, up) => {
    case BuildRoCC => Seq(
    (p: Parameters) => {
      implicit val implicitParams: Parameters = p
      implicit val valName: ValName = ValName("MatMul_LUMIXED_example")

      // Pass fbus to LUMIXEDExample
      LazyModule(
      new LUMIXEDExample(
      opcodes = OpcodeSet.all,            // Opcode used for the accelerator
      params  = site(LUMIXEDParamsKey)
    ) {

    }
    )
  }
  )
})
