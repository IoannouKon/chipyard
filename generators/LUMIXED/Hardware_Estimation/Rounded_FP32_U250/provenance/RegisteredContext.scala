import chisel3._
import chisel3.util._
import scala.collection.immutable.ListMap
class CorePorts extends Record {
  val elements = ListMap(
"clock" -> Input(Clock()),
"reset" -> Input(Bool()),
"auto_tl_out_a_ready" -> Input(Bool()),
"auto_tl_out_a_valid" -> Output(Bool()),
"auto_tl_out_a_bits_opcode" -> Output(UInt(3.W)),
"auto_tl_out_a_bits_source" -> Output(UInt(3.W)),
"auto_tl_out_a_bits_address" -> Output(UInt(32.W)),
"auto_tl_out_a_bits_mask" -> Output(UInt(8.W)),
"auto_tl_out_a_bits_data" -> Output(UInt(64.W)),
"auto_tl_out_d_valid" -> Input(Bool()),
"auto_tl_out_d_bits_source" -> Input(UInt(3.W)),
"auto_tl_out_d_bits_data" -> Input(UInt(64.W)),
"io_cmd_ready" -> Output(Bool()),
"io_cmd_valid" -> Input(Bool()),
"io_cmd_bits_inst_funct" -> Input(UInt(7.W)),
"io_cmd_bits_inst_rs2" -> Input(UInt(5.W)),
"io_cmd_bits_inst_rs1" -> Input(UInt(5.W)),
"io_cmd_bits_inst_xd" -> Input(Bool()),
"io_cmd_bits_inst_xs1" -> Input(Bool()),
"io_cmd_bits_inst_xs2" -> Input(Bool()),
"io_cmd_bits_inst_rd" -> Input(UInt(5.W)),
"io_cmd_bits_inst_opcode" -> Input(UInt(7.W)),
"io_cmd_bits_rs1" -> Input(UInt(64.W)),
"io_cmd_bits_rs2" -> Input(UInt(64.W)),
"io_cmd_bits_status_debug" -> Input(Bool()),
"io_cmd_bits_status_cease" -> Input(Bool()),
"io_cmd_bits_status_wfi" -> Input(Bool()),
"io_cmd_bits_status_isa" -> Input(UInt(32.W)),
"io_cmd_bits_status_dprv" -> Input(UInt(2.W)),
"io_cmd_bits_status_dv" -> Input(Bool()),
"io_cmd_bits_status_prv" -> Input(UInt(2.W)),
"io_cmd_bits_status_v" -> Input(Bool()),
"io_cmd_bits_status_sd" -> Input(Bool()),
"io_cmd_bits_status_zero2" -> Input(UInt(23.W)),
"io_cmd_bits_status_mpv" -> Input(Bool()),
"io_cmd_bits_status_gva" -> Input(Bool()),
"io_cmd_bits_status_mbe" -> Input(Bool()),
"io_cmd_bits_status_sbe" -> Input(Bool()),
"io_cmd_bits_status_sxl" -> Input(UInt(2.W)),
"io_cmd_bits_status_uxl" -> Input(UInt(2.W)),
"io_cmd_bits_status_sd_rv32" -> Input(Bool()),
"io_cmd_bits_status_zero1" -> Input(UInt(8.W)),
"io_cmd_bits_status_tsr" -> Input(Bool()),
"io_cmd_bits_status_tw" -> Input(Bool()),
"io_cmd_bits_status_tvm" -> Input(Bool()),
"io_cmd_bits_status_mxr" -> Input(Bool()),
"io_cmd_bits_status_sum" -> Input(Bool()),
"io_cmd_bits_status_mprv" -> Input(Bool()),
"io_cmd_bits_status_xs" -> Input(UInt(2.W)),
"io_cmd_bits_status_fs" -> Input(UInt(2.W)),
"io_cmd_bits_status_mpp" -> Input(UInt(2.W)),
"io_cmd_bits_status_vs" -> Input(UInt(2.W)),
"io_cmd_bits_status_spp" -> Input(Bool()),
"io_cmd_bits_status_mpie" -> Input(Bool()),
"io_cmd_bits_status_ube" -> Input(Bool()),
"io_cmd_bits_status_spie" -> Input(Bool()),
"io_cmd_bits_status_upie" -> Input(Bool()),
"io_cmd_bits_status_mie" -> Input(Bool()),
"io_cmd_bits_status_hie" -> Input(Bool()),
"io_cmd_bits_status_sie" -> Input(Bool()),
"io_cmd_bits_status_uie" -> Input(Bool()),
"io_resp_ready" -> Input(Bool()),
"io_resp_valid" -> Output(Bool()),
"io_resp_bits_rd" -> Output(UInt(5.W)),
"io_resp_bits_data" -> Output(UInt(64.W)),
"io_busy" -> Output(Bool()))
}
class CoreBlackBox extends BlackBox {
  override def desiredName = "LUMIXEDExample"
  val io = IO(new CorePorts)
}
// Standalone representative synchronous host + registered TL-UL memory manager.
// It is an evaluation context, not a full Rocket/DDR system or a Qwen benchmark.
class RegisteredContext extends Module {
  val io = IO(new Bundle {
    val initialized = Output(Bool())
    val completedJobs = Output(UInt(32.W))
    val digest = Output(UInt(64.W))
    val counters = Output(Vec(6, UInt(64.W)))
    val commandStalls = Output(UInt(32.W))
    val memoryStalls = Output(UInt(32.W))
    val responseStalls = Output(UInt(32.W))
    // Real registered visibility, including every bit written by the accelerator.
    val lastWriteData = Output(UInt(64.W))
    val writeCount = Output(UInt(32.W))
    val readCount = Output(UInt(32.W))
    val lastWriteAddress = Output(UInt(32.W))
    val lastWriteMask = Output(UInt(8.W))
    val memoryReadback = Output(UInt(64.W))
  })
  val core = Module(new CoreBlackBox)
  def in(n:String,v:UInt):Unit = { core.io.elements(n) match {
    case x: Bool => x := v.asBool
    case x: UInt => x := v
  }}
  def u(n:String):UInt = core.io.elements(n).asUInt
  in("auto_tl_out_a_ready",0.U)
  in("auto_tl_out_d_valid",0.U)
  in("auto_tl_out_d_bits_source",0.U)
  in("auto_tl_out_d_bits_data",0.U)
  in("io_cmd_valid",0.U)
  in("io_cmd_bits_inst_funct",0.U)
  in("io_cmd_bits_inst_rs2",0.U)
  in("io_cmd_bits_inst_rs1",0.U)
  in("io_cmd_bits_inst_xd",0.U)
  in("io_cmd_bits_inst_xs1",0.U)
  in("io_cmd_bits_inst_xs2",0.U)
  in("io_cmd_bits_inst_rd",0.U)
  in("io_cmd_bits_inst_opcode",0.U)
  in("io_cmd_bits_rs1",0.U)
  in("io_cmd_bits_rs2",0.U)
  in("io_cmd_bits_status_debug",0.U)
  in("io_cmd_bits_status_cease",0.U)
  in("io_cmd_bits_status_wfi",0.U)
  in("io_cmd_bits_status_isa",0.U)
  in("io_cmd_bits_status_dprv",0.U)
  in("io_cmd_bits_status_dv",0.U)
  in("io_cmd_bits_status_prv",0.U)
  in("io_cmd_bits_status_v",0.U)
  in("io_cmd_bits_status_sd",0.U)
  in("io_cmd_bits_status_zero2",0.U)
  in("io_cmd_bits_status_mpv",0.U)
  in("io_cmd_bits_status_gva",0.U)
  in("io_cmd_bits_status_mbe",0.U)
  in("io_cmd_bits_status_sbe",0.U)
  in("io_cmd_bits_status_sxl",0.U)
  in("io_cmd_bits_status_uxl",0.U)
  in("io_cmd_bits_status_sd_rv32",0.U)
  in("io_cmd_bits_status_zero1",0.U)
  in("io_cmd_bits_status_tsr",0.U)
  in("io_cmd_bits_status_tw",0.U)
  in("io_cmd_bits_status_tvm",0.U)
  in("io_cmd_bits_status_mxr",0.U)
  in("io_cmd_bits_status_sum",0.U)
  in("io_cmd_bits_status_mprv",0.U)
  in("io_cmd_bits_status_xs",0.U)
  in("io_cmd_bits_status_fs",0.U)
  in("io_cmd_bits_status_mpp",0.U)
  in("io_cmd_bits_status_vs",0.U)
  in("io_cmd_bits_status_spp",0.U)
  in("io_cmd_bits_status_mpie",0.U)
  in("io_cmd_bits_status_ube",0.U)
  in("io_cmd_bits_status_spie",0.U)
  in("io_cmd_bits_status_upie",0.U)
  in("io_cmd_bits_status_mie",0.U)
  in("io_cmd_bits_status_hie",0.U)
  in("io_cmd_bits_status_sie",0.U)
  in("io_cmd_bits_status_uie",0.U)
  in("io_resp_ready",0.U)
  core.io.elements("clock").asInstanceOf[Clock] := clock
  core.io.elements("reset").asInstanceOf[Bool] := reset.asBool
  val rng = RegInit("h6a09e667f3bcc909".U(64.W))
  rng := Cat(rng(62,0), rng(63)^rng(62)^rng(60)^rng(59))
  val backing = SyncReadMem(8192,UInt(64.W))
  val memWriteEnable=WireDefault(false.B);val memWriteAddr=WireDefault(0.U(13.W));val memWriteData=WireDefault(0.U(64.W))
  val initAddr = RegInit(0.U(13.W))
  val initialized = RegInit(false.B)
  when (!initialized) {
    memWriteEnable:=true.B;memWriteAddr:=initAddr;memWriteData:=rng
    initAddr := initAddr+1.U
    when (initAddr.andR) {initialized := true.B}
  }
  io.initialized := initialized
  // Dynamic next-job configuration prevents synthesis specializing the accelerator
  // to one fixed K/N/weight precision. Simulation initializes these host registers
  // and the context SRAM before launch (the DUT itself is never backdoor modified).
  val cfgK = RegInit(32.U(12.W)); val cfgN = RegInit(3.U(12.W))
  val cfgBits = RegInit(8.U(4.W))
  dontTouch(cfgK); dontTouch(cfgN); dontTouch(cfgBits)
  val jobs = RegInit(0.U(32.W)); io.completedJobs := jobs
  val digest = RegInit(0.U(64.W)); io.digest := digest
  val counters = RegInit(VecInit(Seq.fill(6)(0.U(64.W))))
  io.counters := counters
  val warmup = RegInit(0.U(5.W))
  when(initialized && !warmup.andR){warmup := warmup+1.U}
  val cmdIndex = RegInit(0.U(4.W))
  val cmdValid = RegInit(false.B)
  val cmdFunct = Reg(UInt(7.W)); val cmdRs1 = Reg(UInt(64.W)); val cmdRs2=Reg(UInt(64.W))
  val cmdRd=RegInit(0.U(5.W)); val responseIndex=RegInit(0.U(3.W))
  val slotsMax = 64.U(16.W) << (8.U-cfgBits)
  val slots = slotsMax(15,0)
  val weightBytes = ((slots*cfgBits)+7.U)>>3
  val windows = Wire(UInt(16.W)); windows := Mux(cfgBits===8.U,(cfgK+63.U(16.W))>>6,Mux(cfgBits===4.U,(cfgK+1023.U(16.W))>>10,1.U))
  val execution = Cat(0.U(4.W),0.U(2.W),cfgBits,32.U(6.W),windows,weightBytes(15,0),slots)
  when(initialized && warmup.andR && !cmdValid && cmdIndex<12.U && rng(3,0) =/= 0.U) {
    cmdValid := true.B; cmdRd:=0.U
    cmdFunct:=0.U; cmdRs1:=0.U; cmdRs2:=0.U
    switch(cmdIndex){
      is(0.U){cmdFunct:=0.U;cmdRs1:=0.U;cmdRs2:="h8000".U}
      is(1.U){cmdFunct:=1.U;cmdRs1:="hf008".U;cmdRs2:=cfgN}
      is(2.U){cmdFunct:=2.U;cmdRs1:=(4.U(64.W)<<32)|cfgK;cmdRs2:=(2.U(64.W)<<32)|2.U}
      is(3.U){cmdFunct:=3.U;cmdRs1:=execution}
      is(4.U){cmdFunct:=4.U}
      is(5.U){cmdFunct:=5.U}
    }
    when(cmdIndex >= 6.U){cmdFunct:=5.U;cmdRs1:=1.U;cmdRs2:=cmdIndex-6.U;cmdRd:=1.U}
  }
  in("io_cmd_valid",cmdValid.asUInt)
  in("io_cmd_bits_inst_funct",cmdFunct)
  in("io_cmd_bits_inst_xd",(cmdRd=/=0.U).asUInt)
  in("io_cmd_bits_inst_xs1",1.U); in("io_cmd_bits_inst_xs2",1.U)
  in("io_cmd_bits_inst_rd",cmdRd);in("io_cmd_bits_inst_opcode","h0b".U)
  in("io_cmd_bits_rs1",cmdRs1);in("io_cmd_bits_rs2",cmdRs2)
  val responseReady = RegNext(rng(5,4)=/=0.U,false.B)
  in("io_resp_ready",responseReady.asUInt)
  when(cmdValid && u("io_cmd_ready").asBool){
    cmdValid:=false.B;cmdIndex:=cmdIndex+1.U
  }
  when(u("io_resp_valid").asBool && responseReady){
    assert(cmdIndex >= 6.U)
    counters(responseIndex):=u("io_resp_bits_data")
    digest:=digest^u("io_resp_bits_data")
    when(responseIndex===5.U){
      jobs:=jobs+1.U;cmdIndex:=0.U;responseIndex:=0.U;warmup:=0.U
      cfgK:=Cat(0.U(1.W),rng(10,2),0.U(2.W))+4.U
      cfgN:=Cat(0.U(1.W),rng(23,13))+1.U
      cfgBits:=Mux(rng(26),8.U,Mux(rng(27),4.U,2.U))
    }.otherwise{responseIndex:=responseIndex+1.U}
  }
  val responseStalls=RegInit(0.U(32.W));io.responseStalls:=responseStalls
  when(u("io_resp_valid").asBool && !responseReady){responseStalls:=responseStalls+1.U}
  val commandStalls = RegInit(0.U(32.W));io.commandStalls:=commandStalls
  when(cmdValid && !u("io_cmd_ready").asBool){commandStalls:=commandStalls+1.U}
  // Registered 8-entry request FIFO. Enqueue exactly on A.valid && A.ready;
  // output ownership survives all random stalls; byte masks apply before ack.
  class Request extends Bundle {val opcode=UInt(3.W);val source=UInt(3.W);val address=UInt(32.W);val mask=UInt(8.W);val data=UInt(64.W)}
  val requests=Reg(Vec(8,new Request));val wr=RegInit(0.U(3.W));val rd=RegInit(0.U(3.W));val count=RegInit(0.U(4.W))
  val aGate=RegNext(rng(8,6)=/=0.U,false.B)
  val aReady=initialized && count<8.U && aGate
  in("auto_tl_out_a_ready",aReady.asUInt)
  val push=u("auto_tl_out_a_valid").asBool && aReady
  val memoryState=RegInit(0.U(2.W))
  val serviceGate=RegNext(rng(11,9)=/=0.U,false.B)
  val pop=initialized && memoryState===0.U && count=/=0.U && serviceGate
  val current=Reg(new Request)
  val ramData=backing.read(requests(rd).address(15,3),pop)
  val writeCount=RegInit(0.U(32.W));io.writeCount:=writeCount
  val readCount=RegInit(0.U(32.W));io.readCount:=readCount
  val lastWriteData=RegInit(0.U(64.W));io.lastWriteData:=lastWriteData
  val lastWriteAddress=RegInit(0.U(32.W));io.lastWriteAddress:=lastWriteAddress
  val lastWriteMask=RegInit(0.U(8.W));io.lastWriteMask:=lastWriteMask
  val memoryReadback=RegInit(0.U(64.W));io.memoryReadback:=memoryReadback
  when(push && u("auto_tl_out_a_bits_opcode")=/=4.U){
    writeCount:=writeCount+1.U
    lastWriteData:=u("auto_tl_out_a_bits_data")
    lastWriteAddress:=u("auto_tl_out_a_bits_address")
    lastWriteMask:=u("auto_tl_out_a_bits_mask")
  }
  when(push){
    requests(wr).opcode:=u("auto_tl_out_a_bits_opcode")
    requests(wr).source:=u("auto_tl_out_a_bits_source")
    requests(wr).address:=u("auto_tl_out_a_bits_address")
    requests(wr).mask:=u("auto_tl_out_a_bits_mask")
    requests(wr).data:=u("auto_tl_out_a_bits_data")
    assert(u("auto_tl_out_a_bits_address")(31,16)===0.U)
    assert(u("auto_tl_out_a_bits_opcode")===4.U || u("auto_tl_out_a_bits_opcode")<=1.U)
    wr:=wr+1.U
  }
  when(pop){current:=requests(rd);rd:=rd+1.U;memoryState:=1.U}
  when(push=/=pop){count:=Mux(push,count+1.U,count-1.U)}
  val dValid=RegInit(false.B);val dSource=Reg(UInt(3.W));val dData=Reg(UInt(64.W))
  dValid:=false.B // This pruned TL interface's D.ready is structurally constant 1.
  when(memoryState===1.U){
    val mask=Cat((0 until 8).reverse.map(i=>Fill(8,current.mask(i))))
    when(current.opcode=/=4.U){memWriteEnable:=true.B;memWriteAddr:=current.address(15,3);memWriteData:=(ramData & ~mask)|(current.data & mask)}
    when(current.opcode===4.U){memoryReadback:=ramData;readCount:=readCount+1.U}
    dSource:=current.source;dData:=ramData;memoryState:=2.U
  }
  when(memoryState===2.U && serviceGate){dValid:=true.B;memoryState:=0.U}
  in("auto_tl_out_d_valid",dValid.asUInt);in("auto_tl_out_d_bits_source",dSource);in("auto_tl_out_d_bits_data",dData)
  val memoryStalls=RegInit(0.U(32.W));io.memoryStalls:=memoryStalls
  when(u("auto_tl_out_a_valid").asBool && !aReady){memoryStalls:=memoryStalls+1.U}
  when(memWriteEnable){backing.write(memWriteAddr,memWriteData)}
  val inflight=RegInit(0.U(8.W))
  when(push){assert(!inflight(u("auto_tl_out_a_bits_source")))}
  // A source cannot be reused before the registered D beat is delivered.
  val addMask=Mux(push,1.U(8.W)<<u("auto_tl_out_a_bits_source"),0.U)
  val delMask=Mux(dValid,1.U(8.W)<<dSource,0.U)
  inflight:=(inflight|addMask)& ~delMask
  val stalled=RegNext(cmdValid && !u("io_cmd_ready").asBool,false.B)
  val priorCommand=RegNext(Cat(cmdFunct,cmdRs1,cmdRs2,cmdRd))
  when(stalled){assert(cmdValid && Cat(cmdFunct,cmdRs1,cmdRs2,cmdRd)===priorCommand)}
  // TL-UL is Decoupled, not Irrevocable: an unaccepted A beat may change.
  // Only the beat sampled on A.fire owns a source and enters the request FIFO.
  when(dValid){assert(inflight(dSource),"Unexpected or duplicate D response")}

}
object EmitContext extends App {
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(new RegisteredContext,Array("--target-dir",args(0)),Array("--disable-all-randomization"))
}
