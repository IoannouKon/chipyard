package LUMIXED_PACKAGE

import chisel3._
import chisel3.util._
import freechips.rocketchip.subsystem.CacheBlockBytes
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import LUMIXED_PACKAGE.LUMIXED_Config

import freechips.rocketchip.diplomacy._

class DmaModule(val params: LUMIXEDParams)(implicit p: Parameters) extends LazyModule {
  val dmaIds = params.Dma_Ids

  val node = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(
    TLClientParameters(
      name = "DMA",
      sourceId = IdRange(0, dmaIds),
      requestFifo = false,
      visibility = Seq(AddressSet(0x0L, 0xFFFFFFFFFFFFFFFFL)) // 64-bit address space
    )
  ))))

  lazy val module = new DmaModuleImp(this)
}
class DmaModuleImp(outer: DmaModule) extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val addr       = Input(UInt(64.W))
    val log_S      = Input(UInt(64.W))
    val mode       = Input(UInt(2.W))
    val valid      = Input(Bool())
    val mask       = Input(UInt(64.W))
    val writeData = Input(UInt((outer.params.DMA_bits).W))
    val a_fire     = Output(Bool())
    val a_cor      = Output(Bool())
    val readData   = Output(UInt((outer.params.DMA_bits).W))
    val d_ready    = Input(Bool())
    val d_valid    = Output(Bool())
    val d_cor      = Output(Bool())
    val d_den      = Output(Bool())
    val tag        = Output(UInt(64.W))
    val busy       = Output(Bool())
    val empty      = Output(Bool())
    val err        = Output(Bool())
    val SourceOut  = Output(UInt(64.W))
    val acceptedLogSize = Output(UInt(7.W)) // Size of the actual accepted A request.
    val burstAvailable = Output(Bool())
    val burstLegal = Output(Vec(3, Bool())) // Get support at addr for 16/32/64 bytes.
  })

  val params = outer.params
  val ID = params.Dma_Ids
  require(ID >= 1, "DMA needs at least one request slot")

  require(!params.WEIGHT_DMA_BURSTS || (params.DMA_bits == 64 &&
    params.DMA_Bytes == 8 && Seq(16, 32, 64).contains(params.MAX_WEIGHT_BURST_BYTES)),
    "Weight bursts require a 64-bit DMA and a 16/32/64-byte maximum")
  val (mem, edge) = outer.node.out(0)
  val burstActive = if (params.WEIGHT_DMA_BURSTS) Some(RegInit(false.B)) else None
  val pendingA = WireDefault(false.B)
  io.burstAvailable := (if (params.WEIGHT_DMA_BURSTS) !burstActive.get else false.B)
  for ((bytes, index) <- Seq(16, 32, 64).zipWithIndex) {
    io.burstLegal(index) := (if (params.WEIGHT_DMA_BURSTS && bytes <= params.MAX_WEIGHT_BURST_BYTES)
      edge.Get(0.U, io.addr, log2Ceil(bytes).U)._1 else false.B)
  }
  // A source remains occupied until its response is delivered to LUMIXED, not
  // merely until TileLink returns it. This also reserves early-response storage.
  val outstanding = RegInit(VecInit(Seq.fill(ID)(false.B)))
  val sourceWidth = log2Ceil(ID).max(1) // Keep a legal one-bit source for ID=1.
  val requestOrder = Module(new Queue(UInt(sourceWidth.W), ID))
  val responseData = Reg(Vec(ID, UInt(params.DMA_bits.W)))
  val responseCorrupt = Reg(Vec(ID, Bool()))
  val responseDenied = Reg(Vec(ID, Bool()))
  val responseValid = RegInit(VecInit(Seq.fill(ID)(false.B)))
  val available = VecInit(outstanding.map(!_)).asUInt
  val nextSource = Wire(UInt(sourceWidth.W))
  nextSource := PriorityEncoder(available)
  val hasFree = available.orR

  val a_bits = Wire(mem.a.bits.cloneType)
  // A complete transfer is a PutFullData, not a masked read/modify/write.
  // Compare against the transfer mask (also correct for sub-beat sizes).
  // Partial output tails keep their original byte enables and PutPartialData.
  val fullWrite = edge.Put(fromSource = nextSource, toAddress = io.addr,
    lgSize = io.log_S, data = io.writeData)
  val writeMask = io.mask(params.DMA_Bytes - 1, 0)
  val useFullWrite = fullWrite._1 && writeMask === edge.mask(io.addr, io.log_S)
  a_bits := Mux(io.mode === 0.U,
    edge.Get(fromSource = nextSource, toAddress = io.addr, lgSize = io.log_S)._2,
    Mux(useFullWrite, fullWrite._2,
      edge.Put(fromSource = nextSource, toAddress = io.addr, lgSize = io.log_S, data = io.writeData, mask = writeMask)._2)
  )

  if (params.WEIGHT_DMA_BURSTS) {
    // A request holder preserves source/address/size through A backpressure,
    // without adding a cycle when A is ready.
    val heldValid = RegInit(false.B)
    val held = Reg(chiselTypeOf(a_bits))
    val selected = Mux(heldValid, held, a_bits)
    val needsBurstSlot = selected.opcode === TLMessages.Get && selected.size > 3.U
    mem.a.bits := selected
    mem.a.valid := (heldValid || io.valid) && hasFree && requestOrder.io.enq.ready &&
      (!needsBurstSlot || !burstActive.get)
    when(mem.a.valid && !mem.a.ready && !heldValid) { held := a_bits; heldValid := true.B }
    when(mem.a.fire) { heldValid := false.B }
    pendingA := heldValid
  } else {
    mem.a.bits := a_bits
    mem.a.valid := io.valid && hasFree && requestOrder.io.enq.ready
  }
  io.acceptedLogSize := mem.a.bits.size
  io.a_fire := mem.a.fire
  io.a_cor := mem.a.bits.corrupt

  requestOrder.io.enq.valid := mem.a.fire
  requestOrder.io.enq.bits := mem.a.bits.source

  /*
   Previous registered response path, before the bypass optimization.
   Reference only: this block is inactive and is not elaborated.
   To restore it, replace the active response block below; do not enable both.
   Request issue and source-ID retirement remain shared and unchanged.

  // LUMIXED consumes every d_valid pulse (the legacy d_ready input is unused).
  // Capture both Get data and Put acknowledgements, then retire in issue order.
  mem.d.ready := true.B
  when(mem.d.fire) {
    val source = mem.d.bits.source
    assert(source < ID.U, "DMA received an out-of-range response source")
    assert(outstanding(source) || (mem.a.fire && nextSource === source),
      "DMA received a response without an accepted request")
    assert(!responseValid(source), "DMA response slot overwritten")
    responseData(source) := mem.d.bits.data
    responseCorrupt(source) := mem.d.bits.corrupt
    responseDenied(source) := mem.d.bits.denied
    responseValid(source) := true.B
  }

  val oldest = requestOrder.io.deq.bits
  val deliver = requestOrder.io.deq.valid && responseValid(oldest)
  io.readData := responseData(oldest)
  io.d_valid := deliver
  io.d_cor := responseCorrupt(oldest)
  io.d_den := responseDenied(oldest)
  io.tag := oldest
  requestOrder.io.deq.ready := deliver
  */

  if (!params.WEIGHT_DMA_BURSTS) {
  // LUMIXED consumes every d_valid pulse (the legacy d_ready input is unused).
  // Retire at most one Get/Put response per cycle, strictly in request order.
  mem.d.ready := true.B
  val oldest = requestOrder.io.deq.bits
  val buffered = requestOrder.io.deq.valid && responseValid(oldest)
  // A queued head response can flow directly to LUMIXED. Replies for later
  // requests still reserve their source slots and wait in the existing buffer.
  // An empty request queue cannot bypass, including a same-cycle new request.
  val bypass = requestOrder.io.deq.valid && !responseValid(oldest) &&
    mem.d.fire && (mem.d.bits.source === oldest)

  when(mem.d.fire) {
    val source = mem.d.bits.source
    assert(source < ID.U, "DMA received an out-of-range response source")
    assert(outstanding(source) || (mem.a.fire && nextSource === source),
      "DMA received a response without an accepted request")
    assert(!responseValid(source), "DMA response slot overwritten")
    // Forwarded responses are not also buffered: the normal queue dequeue
    // below retires them and frees their source exactly once on this edge.
    when(!bypass) {
      responseData(source) := mem.d.bits.data
      responseCorrupt(source) := mem.d.bits.corrupt
      responseDenied(source) := mem.d.bits.denied
      responseValid(source) := true.B
    }
  }

  val deliver = buffered || bypass
  io.readData := Mux(bypass, mem.d.bits.data, responseData(oldest))
  io.d_valid := deliver
  io.d_cor := Mux(bypass, mem.d.bits.corrupt, responseCorrupt(oldest))
  io.d_den := Mux(bypass, mem.d.bits.denied, responseDenied(oldest))
  io.tag := oldest
  requestOrder.io.deq.ready := deliver

  } else {
    // One shared, pre-reserved burst slot bounds response storage independently
    // of ID. Other sources still use their original one-word response slots.
    val maxBeats = params.MAX_WEIGHT_BURST_BYTES / 8
    val countWidth = log2Ceil(maxBeats + 1)
    val burstSource = Reg(UInt(sourceWidth.W))
    val burstBeats = Reg(UInt(countWidth.W))
    val received = RegInit(0.U(countWidth.W))
    val delivered = RegInit(0.U(countWidth.W))
    val words = Reg(Vec(maxBeats, UInt(params.DMA_bits.W)))
    val corrupt = Reg(Vec(maxBeats, Bool()))
    val denied = Reg(Vec(maxBeats, Bool()))
    val acceptedBurst = mem.a.fire && mem.a.bits.opcode === TLMessages.Get && mem.a.bits.size > 3.U
    val acceptedBeats = MuxLookup(mem.a.bits.size, 1.U)(Seq(4.U -> 2.U, 5.U -> 4.U, 6.U -> 8.U))
    val oldest = requestOrder.io.deq.bits
    val burstHead = requestOrder.io.deq.valid && burstActive.get && oldest === burstSource
    val bufferedBurst = burstHead && delivered < received
    val incomingBurst = mem.d.fire && ((burstActive.get && mem.d.bits.source === burstSource) ||
      (acceptedBurst && mem.d.bits.source === mem.a.bits.source))
    val burstBypass = burstHead && !bufferedBurst && incomingBurst
    val bufferedSingle = requestOrder.io.deq.valid && !burstHead && responseValid(oldest)
    val singleBypass = requestOrder.io.deq.valid && !burstHead && !responseValid(oldest) &&
      mem.d.fire && !incomingBurst && mem.d.bits.source === oldest
    val deliver = bufferedBurst || burstBypass || bufferedSingle || singleBypass
    val lastBurstBeat = delivered +& 1.U === burstBeats
    val wordIndex = delivered(log2Ceil(maxBeats) - 1, 0)

    mem.d.ready := true.B // Every accepted request has enough reserved storage.
    when(acceptedBurst) {
      assert(!burstActive.get, "A second burst exceeded the shared response slot")
      assert(acceptedBeats <= maxBeats.U, "Burst exceeds configured storage")
      burstActive.get := true.B
      burstSource := mem.a.bits.source
      burstBeats := acceptedBeats
      received := 0.U
      delivered := 0.U
    }
    when(mem.d.fire) {
      val source = mem.d.bits.source
      assert(source < ID.U, "DMA received an out-of-range source")
      assert(outstanding(source) || (mem.a.fire && mem.a.bits.source === source),
        "DMA response has no accepted request")
      when(incomingBurst) {
        // Handle even a zero-latency first response to a newly accepted Get.
        val count = Mux(burstActive.get, received, 0.U)
        val limit = Mux(burstActive.get, burstBeats, acceptedBeats)
        assert(count < limit, "Too many burst response beats")
        assert(mem.d.bits.opcode === TLMessages.AccessAckData, "Burst response has no data")
        assert(mem.d.bits.size === Mux(burstActive.get,
          MuxLookup(burstBeats, 4.U)(Seq(4.U -> 5.U, 8.U -> 6.U)), mem.a.bits.size),
          "Burst response size differs from its request")
        val index = count(log2Ceil(maxBeats) - 1, 0)
        words(index) := mem.d.bits.data
        corrupt(index) := mem.d.bits.corrupt
        denied(index) := mem.d.bits.denied
        received := count + 1.U
      }.otherwise {
        assert(!responseValid(source), "Single-beat DMA response slot overwritten")
        when(!singleBypass) {
          responseData(source) := mem.d.bits.data
          responseCorrupt(source) := mem.d.bits.corrupt
          responseDenied(source) := mem.d.bits.denied
          responseValid(source) := true.B
        }
      }
    }
    val bypass = burstBypass || singleBypass
    io.readData := Mux(bypass, mem.d.bits.data,
      Mux(burstHead, words(wordIndex), responseData(oldest)))
    io.d_cor := Mux(bypass, mem.d.bits.corrupt,
      Mux(burstHead, corrupt(wordIndex), responseCorrupt(oldest)))
    io.d_den := Mux(bypass, mem.d.bits.denied,
      Mux(burstHead, denied(wordIndex), responseDenied(oldest)))
    io.d_valid := deliver
    io.tag := oldest
    // One d_valid per payload beat, but one request-order dequeue per entire
    // transaction. The source cannot be reused until its last delivered beat.
    requestOrder.io.deq.ready := deliver && (!burstHead || lastBurstBeat)
    when(deliver && burstHead) {
      assert(delivered < burstBeats, "Burst delivery exceeded accepted size")
      delivered := delivered + 1.U
      when(lastBurstBeat) { burstActive.get := false.B }
    }
    when(burstActive.get) {
      assert(outstanding(burstSource), "Burst lost its reserved source ID")
      assert(delivered <= received && received <= burstBeats, "Burst beat counters inconsistent")
    }

  }

  when(mem.a.fire) {
    outstanding(mem.a.bits.source) := true.B
  }
  when(requestOrder.io.deq.fire) {
    responseValid(requestOrder.io.deq.bits) := false.B
    outstanding(requestOrder.io.deq.bits) := false.B
  }

  assert(PopCount(outstanding) === requestOrder.io.count,
    "DMA request queue and occupied slots disagree")
  for (source <- 0 until ID) {
    assert(!responseValid(source) || outstanding(source),
      "DMA buffered response lost its request slot")
  }

  io.SourceOut := mem.a.bits.source
  io.busy := !hasFree || !requestOrder.io.enq.ready
  io.empty := !outstanding.asUInt.orR && !pendingA
  io.err := false.B // Corrupt/denied response flags are exposed separately.
}
