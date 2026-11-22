#!/usr/bin/env python3
"""Self-contained LUMIXED model v8.1 with embedded FP32 calibration.

INT16/FP16 use the original v7 event simulator. FP32 W8/W4/W2 use the
validated stage-calibrated predictor, with deferred group scaling ON/OFF.
Unknown geometries require explicit extrapolation. See README.md for scope,
held-out accuracy, numerical provenance and calibration reproduction.
"""
from __future__ import annotations

# --- Original INT16/FP16 controller model (v7), semantics preserved ---
from collections import OrderedDict
import math

LEGACY_MODEL_VERSION = 'controller-events-v7'


def _integer(name, value, minimum=1):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or int(value) != value or value < minimum:
        raise ValueError(f'{name} must be an integer >= {minimum}')
    return int(value)


def dma_finish_time_exact_bits(total_bits, ID, t_issue=1, L=6, DMA_WIDTH_bits=64, beat_time=1):
    """Last response edge; first issue is edge zero. Released IDs return next edge.

    Responses are ordered and spaced by beat_time. This is exact for the stated
    finite-credit interface, not a claim of fixed-latency cache/DRAM behavior.
    """
    total_bits = _integer('total_bits', total_bits, 0)
    ID, t_issue = _integer('ID', ID), _integer('t_issue', t_issue)
    L = _integer('L', L, 0)
    width, beat_time = _integer('DMA_WIDTH_bits', DMA_WIDTH_bits), _integer('beat_time', beat_time)
    count = (total_bits+width-1)//width
    responses, issued = [], -t_issue
    for i in range(count):
        issued = max(issued+t_issue, responses[i-ID]+1 if i>=ID else 0)
        responses.append(max(issued+L, responses[-1]+beat_time if responses else 0))
    return responses[-1] if responses else 0


def _read_packets(length, source_element, bank_span, precision, width, base):
    """calcChunkInfo_A + packedReadCount; source beats can be reread at bank words."""
    per_word, consumed = width//precision, 0
    while consumed<length:
        linear = source_element+consumed
        count = min(length-consumed, per_word-linear%per_word)
        local = consumed%bank_span
        word_remaining = per_word-local%per_word
        if word_remaining<bank_span-local:
            count = min(count,word_remaining)
        yield base+(linear//per_word)*(width//8),count
        consumed += count


def _store_packets(row_start, active_rows, columns, base):
    """Row-ordered 32-bit outputs; partial words stay separate across rows."""
    for row in range(row_start, row_start+active_rows):
        written = 0
        while written<columns:
            linear = row*columns+written
            count = min(columns-written, 2-linear%2)
            yield base+(linear//2)*8, count, dict(row=row)
            written += count


class _Memory:
    """Approximate LLC/DMA environment, independent of stage-cycle counters.

    The current Chipyard profile has four 8-byte SRAM subbanks and an 8-beat
    64-byte refill. A cache line is not usable by all secondary requests when
    its first response returns. Warm hits keep HS-1 latency. Miss-head wormhole
    service and refill completion are separate; merged requests serialize.
    secondary_cycles is an effective interface calibration (default two), not
    an assertion that every real MSHR response is exactly two cycles apart.
    """
    def __init__(self, hs, profile, line_bytes, cache_bytes, read_penalty,
                 write_penalty, latencies=None, delays=None, trace=False,
                 bypass=True, latency_basis='controller', secondary_cycles=2, subbanks=4, full_writes=True):
        self.hs, self.profile, self.line_bytes = hs, profile, line_bytes
        self.secondary_cycles, self.subbanks, self.full_writes = secondary_cycles, subbanks, full_writes
        self.lines = max(1,cache_bytes//line_bytes)
        self.read_penalty, self.write_penalty = read_penalty,write_penalty
        self.cache = OrderedDict()
        self.latencies, self.delays = latencies,delays
        self.bypass = bypass
        self.controller_replay = latencies is not None and latency_basis=='controller'
        self.index = self.reads = self.writes = self.misses = 0
        self.trace = [] if trace else None
        # One DMA engine spans all columns and stages. Retain its outstanding
        # source credits and D-channel reservations across transfer() calls.
        self.responses = []
        self.return_slots = set()
        self.last_issue = -1
        self.refill_end = -1
        self.line_service = {}
        self.bank_slots = set()
        self.service_slots = set()

    def response(self, address, write, issued, full_write=False):
        i = self.index
        if i and i%4096==0:
            cutoff=issued-2*self.hs
            self.bank_slots={slot for slot in self.bank_slots if slot[0]>=cutoff}
            self.service_slots={slot for slot in self.service_slots if slot>=cutoff}
            self.return_slots.intersection_update(slot for slot in self.return_slots if slot>=cutoff)
        self.index += 1
        self.writes += bool(write)
        self.reads += not write
        if self.latencies is not None:
            if i>=len(self.latencies):
                raise ValueError('DMA replay ran out of response latencies')
            return issued+_integer('DMA replay latency',self.latencies[i],0)
        # Preserve the calibrated memory costs: HS includes the old receiver
        # register. Model the physical return separately, not a faster memory.
        hit = issued+self.hs-1
        if self.profile=='uniform':
            return hit
        line = address//self.line_bytes
        ready = self.cache.pop(line,None)
        if ready is None:
            self.misses += 1
            # Outer refill D channel is 64 bits in LUMAXROcketConfig.
            beats = self.line_bytes//8
            # read_penalty historically includes the seven tail beats;
            # write_penalty describes the first acknowledgement. Preserve those
            # public meanings while reserving the whole physical refill burst.
            first_latency = self.hs-1+(self.write_penalty if write else max(0,self.read_penalty-(beats-1)))
            first = max(issued+first_latency,self.refill_end+1)
            self.refill_end = first+beats-1
            ready = self.refill_end+1
            # BankedStore gives incoming refill writes priority over SourceD
            # reads. Represent their port occupancy; this is approximate because
            # future arbitration can also delay already-pending operations.
            for beat in range(beats):
                self.bank_slots.add((first-(self.hs-1)+beat,beat%self.subbanks))
            response = first if address%self.line_bytes==0 else self.refill_end
            if len(self.cache)>=self.lines:
                self.cache.popitem(last=False)
        else:
            response = max(hit,ready)
        self.cache[line] = ready
        # SourceD has one shared request pipeline. Its SRAM read is four
        # interface cycles before the modeled response; writeback follows three
        # stages after the read. These offsets describe this Chipyard profile,
        # not a portable DRAM timing model.
        service=max(response-4,self.line_service.get(line,-1))
        bank=(address//8)%self.subbanks
        needs_read = not (write and full_write and self.full_writes)
        while service in self.service_slots or (needs_read and (service,bank) in self.bank_slots):
            service+=1
        self.service_slots.add(service)
        if needs_read:
            self.bank_slots.add((service,bank))
        if write:
            self.bank_slots.add((service+3,bank))
        # Requests merged behind a refill have an effective reload interval;
        # an already-warm line can continue at one request per cycle.
        self.line_service[line]=service+(self.secondary_cycles if hit<ready else 1)
        return service+4

    def transfer(self, packets, start, ids, write=False, context=None, prefetch_latency=0, prefetch_depth=0):
        responses, return_slots = self.responses,self.return_slots
        issued = max(start-1,self.last_issue)
        last = None
        local_issues = []
        for packet in packets:
            address,count = packet[:2]
            issued = max(issued+1,responses[-ids]+1 if len(responses)>=ids else start)
            if self.delays is not None:
                if self.index>=len(self.delays):
                    raise ValueError('DMA replay ran out of issue delays')
                issued += _integer('DMA replay issue delay',self.delays[self.index],0)
            if prefetch_depth:
                # SyncReadMem read -> queue enqueue -> non-flow dequeue.
                # A full queue frees a reservation only after an accepted A beat.
                data_ready=start+len(local_issues)+prefetch_latency
                if len(local_issues)>=prefetch_depth:
                    data_ready=max(data_ready,local_issues[-prefetch_depth]+1+prefetch_latency)
                issued=max(issued,data_ready)
            local_issues.append(issued)
            ready = self.response(address,write,issued,full_write=write and count==2)
            if self.controller_replay:
                # Existing diagnostic arrays already describe io.d_valid.
                # Do not subtract a receiver cycle from measured delivery times.
                arrival, bypassed = None,None
                delivered = max(ready,responses[-1]+1 if responses else ready)
            else:
                # A single TileLink D channel accepts one return per cycle.
                # Earlier-issued requests reserve contended return slots first;
                # later requests may otherwise return early/out of order.
                arrival = ready
                while arrival in return_slots:
                    arrival += 1
                return_slots.add(arrival)
                # Queue entries exist after issue; a released head/source is
                # reusable on the next edge. A same-cycle new request/response
                # into an empty queue must be buffered, even with bypass on.
                head_ready = max(issued+1,responses[-1]+1 if responses else start)
                bypassed = self.bypass and arrival>=head_ready
                delivered = max(arrival if bypassed else arrival+1,head_ready)
            responses.append(delivered)
            self.last_issue = issued
            last = delivered
            if self.trace is not None:
                # Optional per-packet metadata preserves rows in a shared transfer.
                self.trace.append(dict(context or {},**(packet[2] if len(packet)>2 else {}),address=address,write=write,
                                       elements=count,issue=issued,response=delivered,
                                       tilelink_response=arrival,bypassed=bypassed))
        return last if last is not None else start-1


def compute_cycles(Rin, Cin, Cout, in_bits, w_bits, XS, YS, ID,
                   buffers_w=2, factor=4, DMA_WIDTH=64,
                   extra_prefetch_cycles=0, cache_8_4=False, cache_reads=1,
                   IN_MAX=16, IN_min=8, W_MAX=8, HS=7,
                   activation='int16', memory_profile='chipyard_llc',
                   cache_line_bytes=64, cache_bytes=512*1024,
                   read_miss_penalty=11, write_miss_penalty=4,
                   x_address=0x10000000, w_address=0x20000000,
                   o_address=0x30000000, block_size=None, trace=False,
                   dma_latencies=None, dma_issue_delays=None,
                   dma_bypass=True, dma_latency_basis='controller',
                   bram_outputs=True, dma_full_writes=True,
                   llc_secondary_cycles=2, llc_subbanks=4):
    """Predict active accelerator cycles; no CPU, printing, wall-clock or startup.

    XS=b, factor=RF, YS=elaborated row parallelism, Rin=actual rows. FP16
    significand, alignment and FP32 conversion are combinational in this RTL:
    they affect area/Fmax, not this controller's number of cycles.

    bram_outputs=True matches the optimized OutputAccumulator: one synchronous
    read plus a non-flow queue, four words including the pending read. False
    models the older output register array. dma_full_writes=True matches the
    PutFullData optimization; set False for historical PutPartial-only logs.
    llc_secondary_cycles (default 2) is an effective transaction-trace calibration
    for merged LLC misses. It is an exposed memory-environment assumption, not
    a fitted stage multiplier or a claim of exact inclusive-cache simulation.

    HS keeps its registered-reference meaning (default 7); the unchanged
    memory path returns at HS-1 (default 6). dma_bypass=False models the old
    registered receiver. The default forwards only an already-queued head.

    Base-address defaults describe disjoint cache-line-aligned buffers. Pass
    saved physical addresses to model actual beat offsets and line sharing.
    The LLC profile assumes cold buffers and no CPU interference. Diagnostic
    dma_latencies/dma_issue_delays replay only external DMA behavior; they never
    take HW stage/total counters, and are not independent accuracy predictions.
    Latency replay defaults to already controller-visible values for backwards
    compatibility. Use dma_latency_basis='tilelink' for physical D-channel
    return latencies, which exercise bypass and ordered buffering explicitly.
    cache_8_4/cache_reads are compatibility arguments for unsupported narrow-X
    experiments; they do not change the implemented input16 datapath.
    """
    names=('Rin','Cin','Cout','XS','YS','ID','buffers_w','factor','DMA_WIDTH')
    vals=dict(zip(names,(Rin,Cin,Cout,XS,YS,ID,buffers_w,factor,DMA_WIDTH)))
    Rin,Cin,Cout,XS,YS,ID,buffers_w,factor,DMA_WIDTH=(_integer(k,vals[k]) for k in names)
    if in_bits!=16 or IN_MAX!=16 or W_MAX!=8 or w_bits not in (2,4,8):
        raise ValueError('Model supports input16 and W8/W4/W2 (IN_MAX=16, W_MAX=8)')
    if activation not in ('int16','fp16'):
        raise ValueError('activation must be int16 or fp16')
    if DMA_WIDTH!=64:
        raise ValueError('Current RTL load/store model requires DMA_WIDTH=64')
    if memory_profile not in ('uniform','chipyard_llc'):
        raise ValueError('memory_profile must be uniform or chipyard_llc')
    if not isinstance(dma_bypass,bool):
        raise ValueError('dma_bypass must be a bool')
    if dma_latency_basis not in ('controller','tilelink'):
        raise ValueError('dma_latency_basis must be controller or tilelink')
    if not isinstance(bram_outputs,bool) or not isinstance(dma_full_writes,bool):
        raise ValueError('bram_outputs and dma_full_writes must be bools')
    secondary=_integer('llc_secondary_cycles',llc_secondary_cycles)
    subbanks=_integer('llc_subbanks',llc_subbanks)
    HS=_integer('HS',HS)
    prefetch_delay=_integer('extra_prefetch_cycles',extra_prefetch_cycles,0)
    line_bytes=_integer('cache_line_bytes',cache_line_bytes)
    cache_bytes=_integer('cache_bytes',cache_bytes)
    if line_bytes<8 or line_bytes%8:
        raise ValueError('cache_line_bytes must be a positive multiple of 8')
    read_penalty=_integer('read_miss_penalty',read_miss_penalty,0)
    write_penalty=_integer('write_miss_penalty',write_miss_penalty,0)
    for name,address in [('x_address',x_address),('w_address',w_address),('o_address',o_address)]:
        _integer(name,address,0)
        if address%8:
            raise ValueError(name+' must be DMA-beat aligned')
    memory=_Memory(HS,memory_profile,line_bytes,cache_bytes,read_penalty,
                   write_penalty,dma_latencies,dma_issue_delays,trace,
                   bypass=dma_bypass,latency_basis=dma_latency_basis,
                   secondary_cycles=secondary,subbanks=subbanks,full_writes=dma_full_writes)
    capacity=factor*(1<<(W_MAX-w_bits))
    window_size=XS*capacity
    if block_size is not None:
        window_size=_integer('block_size',block_size)
        if window_size>XS*capacity:
            raise ValueError('block_size exceeds physical LUT capacity')
    lut_rows=1<<(w_bits-2)
    now=load_x=generation=combined=store_o=load_w=selection=windows=0
    first_producer=first_select=None
    window_trace=[] if trace else None
    column_trace=[] if trace else None
    for row_start in range(0,Rin,YS):
        active_rows=min(YS,Rin-row_start)
        bank_span=min(capacity,(min(Cin,window_size)+XS-1)//XS)
        for k_start in range(0,Cin,window_size):
            length=min(window_size,Cin-k_start)
            windows+=1
            before_x=now
            for row in range(row_start,row_start+active_rows):
                packets=_read_packets(length,row*Cin+k_start,bank_span,16,DMA_WIDTH,x_address)
                last=memory.transfer(packets,now,ID,context=dict(stage='load_x',row=row,k_start=k_start))
                # Final response, then row completion; one final state change.
                now=last+2
            now+=1
            load_x+=now-before_x
            gen_start=now
            gen_cycles=bank_span*(lut_rows+1)
            generation+=gen_cycles
            select_available=gen_start+gen_cycles
            producer_available=gen_start+prefetch_delay
            released_columns=[]
            last_retire=select_available
            select_cycles=bank_span+4
            for col in range(Cout):
                start=max(producer_available,released_columns[col-buffers_w] if col>=buffers_w else gen_start)
                packets=_read_packets(length,col*Cin+k_start,bank_span,w_bits,DMA_WIDTH,w_address)
                last=memory.transfer(packets,start,ID,context=dict(stage='load_w',column=col,k_start=k_start,row_group=row_start))
                # The final response writes SRAM and marks the reserved buffer
                # ready on the same edge; selection sees it next cycle.
                ready=last+1
                # Per-column latency intervals can overlap; this diagnostic
                # sum is not an exclusive stage duration. Total cycles use
                # the combined producer/selection schedule below.
                load_w+=ready-start
                if first_producer is None:
                    first_producer,first_select=ready-start,select_cycles
                # The independent request cursor can fill the next buffer
                # before this column's ordered responses have all returned.
                producer_available=memory.last_issue+1
                begin_select=max(select_available,ready)
                read_end=begin_select+bank_span
                # The next column can issue on the edge after the final input.
                select_available=read_end
                # Stage 2 captures the last weight on that edge; its buffer-mode
                # bit clears for the following cycle. This is not a zero-delay
                # release and does not wait for the four-cycle accumulator tail.
                buffer_release=read_end+1
                released_columns.append(buffer_release)
                retire_end=read_end+4
                # PC_Select counts cycles with an input or any valid tag. Count
                # the union of occupied intervals, not overlapping tails twice.
                selection+=retire_end-max(begin_select,last_retire)
                if column_trace is not None:
                    column_trace.append(dict(row_group=row_start,k_start=k_start,
                        column=col,weight_ready=ready,issue_start=begin_select,
                        read_end=read_end,buffer_release=buffer_release,
                        retire=retire_end))
                last_retire=retire_end
            # The last tagged contribution writes O_reg on the transition edge.
            # There is no additional Done_acc/w_idx handoff cycle in this RTL.
            now=last_retire
            combined+=now-(gen_start+gen_cycles)
            if window_trace is not None:
                window_trace.append(dict(row_group=row_start,k_start=k_start,k=length,
                    bank_span=bank_span,generation=gen_cycles,
                    loadw_select=now-(gen_start+gen_cycles),select_per_column=select_cycles,
                    column_issue_interval=bank_span))
            if k_start+window_size<Cin:
                if YS==1:
                    # Literal current RTL: this uses Cin - b*bank_span, NOT
                    # remaining K. With >=3 windows the final span may stay padded.
                    bank_span=min(bank_span,(Cin-bank_span*XS+XS-1)//XS)
                else:
                    remaining=min(window_size,Cin-k_start-window_size)
                    bank_span=min(bank_span,(remaining+XS-1)//XS)
        before_store=now
        # One credit pool spans the active rows: accepted writes can advance to
        # the next row while earlier acknowledgements are still outstanding.
        packets=_store_packets(row_start,active_rows,Cout,o_address)
        last=memory.transfer(packets,now,ID,write=True,
                             context=dict(stage='store_o',row_group=row_start),
                             prefetch_latency=2 if bram_outputs else 0,
                             prefetch_depth=4 if bram_outputs else 0)
        # Final delivery updates the ack count/empty flag, then the next edge
        # leaves sStoreOutput. There is no per-row wait or completion bubble.
        now=last+2
        store_o+=now-before_store
    if dma_latencies is not None and memory.index!=len(dma_latencies):
        raise ValueError(f'DMA replay consumed {memory.index}/{len(dma_latencies)} responses')
    total=load_x+generation+combined+store_o
    assert total==now
    result={
        'Repeat activation Window':windows,
        'cycles_load_X':load_x,'cycles_generate_Products':generation,
        'cycles_loadW_select':combined,'cycles_store_output':store_o,
        'total_cycles':total,'cycles load w Only':load_w,'cycles select Only':selection,
        'cycles_3_1':first_producer,'cycles_3_2':first_select,
        'LoadOp':memory.reads,'WriteOp':memory.writes,
        'AddOp':(generation+selection)*XS*min(YS,Rin),
        'ShiftOp':Rin*Cin+selection*XS*min(YS,Rin),
        'ReadSramOp':(generation+selection)*XS*min(YS,Rin),
        'WriteSramOp':memory.reads+generation*XS*min(YS,Rin),
        'model_version':LEGACY_MODEL_VERSION,'activation':activation,
        'selection_pipeline_latency':4,'weight_buffer_release_delay':1,
        'dma_response_bypass':dma_bypass,'dma_latency_basis':dma_latency_basis,
        'bram_outputs':bram_outputs,'dma_full_writes':dma_full_writes,
        'memory_profile':memory_profile,
        'memory_assumptions':dict(hit_response_cycles=HS,read_miss_penalty=read_penalty,
            hit_latency_basis='registered_reference',tilelink_hit_response_cycles=HS-1,
            dma_response_bypass=dma_bypass,
            write_miss_penalty=write_penalty,cache_line_bytes=line_bytes,
            initially_cold=True,cpu_interference=False,
            refill_beats=line_bytes//8,llc_subbanks=subbanks,
            secondary_service_cycles=secondary,
            secondary_service_basis='effective interface calibration; not exact LLC RTL',
            full_write_opcode=dma_full_writes),
        'cache_misses':memory.misses,'dma_requests':memory.index,
        'diagnostic_dma_replay':dma_latencies is not None or dma_issue_delays is not None,
    }
    if trace:
        result.update(transactions=memory.trace,windows=window_trace,columns=column_trace)
    return result

_compute_cycles_legacy = compute_cycles

# --- FP32 stage predictor (v8.1) ---
import json
import math
import os
from pathlib import Path
from typing import Any

MODEL_VERSION = 'lumixed-fp32-qwen-stage-calibrated-v8.1'
STAGES = ('load_x', 'generate_brams', 'load_w_and_select', 'store_o')
HERE = Path(__file__).resolve().parent
# Fitted coefficients and provenance, embedded to keep this module standalone.
DEFAULT_CALIBRATION = json.loads(r'''
{
  "model_version": "lumixed-fp32-qwen-stage-calibrated-v8.1",
  "algorithm": "v8 per-config/geometry ridge unchanged; RTL work-count fallback for unseen geometry; analytical generation; dense floor; 1e-7 ridge",
  "groups": {
    "B16_RF4_YS2_ID16_W2_SCALED": {
      "load_x": [
        -1079.2433281383583,
        1.5375006313337518,
        0.09652960598552535
      ],
      "load_w_and_select": [
        -17479.84020227578,
        0.2591372947404039,
        7.345635737174858
      ],
      "store_o": [
        -542.4965290034406,
        0.9764856847004841
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B16_RF4_YS2_ID16_W2_UNSCALED": {
      "load_x": [
        -112.99727770271966,
        1.6270743679817037,
        0.0
      ],
      "load_w_and_select": [
        -16684.585229760643,
        0.2581565915142103,
        6.717315730058043
      ],
      "store_o": [
        -506.9966038033672,
        0.9590754172520488
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B16_RF4_YS2_ID16_W4_SCALED": {
      "load_x": [
        -2297.3440287793383,
        1.8611349057930653,
        0.09828108839281612
      ],
      "load_w_and_select": [
        -79499.31155039607,
        0.30401409397331264,
        -16.721686469856394
      ],
      "store_o": [
        -284.9963895035926,
        1.0583490628056689
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B16_RF4_YS2_ID16_W4_UNSCALED": {
      "load_x": [
        -935.4960885038599,
        2.1018970282145593,
        0.0
      ],
      "load_w_and_select": [
        -71581.39832432187,
        0.2923176507859061,
        -15.032530241882142
      ],
      "store_o": [
        -450.49626510370763,
        1.071059617638143
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B16_RF4_YS2_ID16_W8_SCALED": {
      "load_x": [
        -1522.1732153672529,
        2.221388610072322,
        0.09801319181303403
      ],
      "load_w_and_select": [
        -142533.42285154387,
        0.47553826364855833,
        22.52953845009247
      ],
      "store_o": [
        9.003600396401453,
        1.1001276243138487
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B16_RF4_YS2_ID16_W8_UNSCALED": {
      "load_x": [
        -345.99568650429956,
        2.52715933871578,
        0.0
      ],
      "load_w_and_select": [
        -134789.26385943044,
        0.46574366263012024,
        29.970739587334236
      ],
      "store_o": [
        582.0028350971297,
        0.9540095682834757
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B16_RF4_YS2_ID8_W2_SCALED": {
      "load_x": [
        -319.7269542142112,
        1.716407194326583,
        0.11912261887857305
      ],
      "load_w_and_select": [
        -5860.034866872366,
        0.24963893160011544,
        3.9424145133059527
      ],
      "store_o": [
        -281.4966655033222,
        0.9746546468586714
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B16_RF4_YS2_ID8_W2_UNSCALED": {
      "load_x": [
        -8.996967903029145,
        1.8478994655770038,
        0.0
      ],
      "load_w_and_select": [
        -5854.520722846039,
        0.2495467249461855,
        3.871320796328611
      ],
      "store_o": [
        -359.9966195033585,
        0.9767145775608858
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B16_RF4_YS2_ID8_W4_SCALED": {
      "load_x": [
        -634.8265036957455,
        1.708010800748091,
        0.11899225677242992
      ],
      "load_w_and_select": [
        -32182.12745579226,
        0.2748461555783785,
        -12.145523277543127
      ],
      "store_o": [
        -132.49662170337524,
        1.0107569328236445
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B16_RF4_YS2_ID8_W4_UNSCALED": {
      "load_x": [
        -238.99663310335745,
        1.9820546438607851,
        0.0
      ],
      "load_w_and_select": [
        -35594.374762671476,
        0.27871346144897247,
        -13.874134549862683
      ],
      "store_o": [
        -175.49659070340203,
        1.0136560986714265
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B16_RF4_YS2_ID8_W8_SCALED": {
      "load_x": [
        -440.0836901554612,
        1.9847684417223181,
        0.11887912578349769
      ],
      "load_w_and_select": [
        -71678.88323469688,
        0.3787876522814664,
        2.9064540943436765
      ],
      "store_o": [
        56.00325759674524,
        1.0026850489506423
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B16_RF4_YS2_ID8_W8_UNSCALED": {
      "load_x": [
        -64.99624020376086,
        2.274962233491023,
        0.0
      ],
      "load_w_and_select": [
        47038.54952089753,
        0.33646979004596955,
        7.777038842584347
      ],
      "store_o": [
        69.50324749675565,
        1.0016627114185974
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B4_RF4_YS4_ID16_W2_SCALED": {
      "load_x": [
        -1607.642390504725,
        3.110938799124376,
        0.19948620082208232
      ],
      "load_w_and_select": [
        12.485346050244628,
        1.000044542028352,
        -0.011580495212028566
      ],
      "store_o": [
        -365.9967927031921,
        0.9229426314243883
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B4_RF4_YS4_ID16_W2_UNSCALED": {
      "load_x": [
        -880.496657903298,
        3.5422953771685566,
        0.0
      ],
      "load_w_and_select": [
        4.485341913345053,
        1.0000140244871965,
        -0.003768005211990576
      ],
      "store_o": [
        -395.4968315031465,
        0.906600474708989
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B4_RF4_YS4_ID16_W4_SCALED": {
      "load_x": [
        -2430.4006277496883,
        3.6855511919949864,
        0.2000315842911125
      ],
      "load_w_and_select": [
        43.28537282954562,
        0.5000924613580843,
        -0.04243982804817891
      ],
      "store_o": [
        -812.4960071039495,
        1.0945581189504316
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B4_RF4_YS4_ID16_W4_UNSCALED": {
      "load_x": [
        -707.496188103778,
        4.221372228304075,
        0.0
      ],
      "load_w_and_select": [
        9.456794089913057,
        0.5000407450203643,
        -0.016267987400171838
      ],
      "store_o": [
        -221.4966632033229,
        0.984511823326567
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B4_RF4_YS4_ID16_W8_SCALED": {
      "load_x": [
        -1668.7721853203984,
        6.542580604509141,
        0.19761940897001679
      ],
      "load_w_and_select": [
        -43187.585131118525,
        0.9524604145178235,
        110.71197503680817
      ],
      "store_o": [
        27.50358039641833,
        1.0968469874348792
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B4_RF4_YS4_ID16_W8_UNSCALED": {
      "load_x": [
        -195.49402590596765,
        7.173275174228131,
        0.0
      ],
      "load_w_and_select": [
        -130381.5050315628,
        1.0017154609216081,
        142.21763765672236
      ],
      "store_o": [
        86.50348759651115,
        1.0775293737416292
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B4_RF4_YS4_ID8_W2_SCALED": {
      "load_x": [
        -585.4979712923723,
        3.5240252737357465,
        0.2400919830510021
      ],
      "load_w_and_select": [
        12.485346050244628,
        1.000044542028352,
        -0.011580495212028566
      ],
      "store_o": [
        -316.4966756033082,
        0.9662317973713647
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B4_RF4_YS4_ID8_W2_UNSCALED": {
      "load_x": [
        -250.99674380324268,
        3.8216533013323466,
        0.0
      ],
      "load_w_and_select": [
        4.485341913345053,
        1.0000140244871965,
        -0.003768005211990576
      ],
      "store_o": [
        -264.49678260320707,
        0.9415125746235481
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B4_RF4_YS4_ID8_W4_SCALED": {
      "load_x": [
        -550.8836082051603,
        3.3783263600105853,
        0.23910407156546978
      ],
      "load_w_and_select": [
        41.228251480808744,
        0.5001007992063633,
        -0.007869535569026444
      ],
      "store_o": [
        -132.99662280336906,
        1.0103449456944322
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B4_RF4_YS4_ID8_W4_UNSCALED": {
      "load_x": [
        -136.49663740335654,
        4.021421295808728,
        0.0
      ],
      "load_w_and_select": [
        53.11390937747303,
        0.5000545324440753,
        -0.006111735824861269
      ],
      "store_o": [
        -119.99664460334861,
        1.0056757593693562
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B4_RF4_YS4_ID8_W8_SCALED": {
      "load_x": [
        -536.5683519070662,
        6.461136881268642,
        0.23889743970901045
      ],
      "load_w_and_select": [
        -36002.823838997996,
        0.9518543423681534,
        105.60807241981017
      ],
      "store_o": [
        27.503271296720413,
        1.0025172006153797
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B4_RF4_YS4_ID8_W8_UNSCALED": {
      "load_x": [
        -190.49402690596435,
        7.175106229220805,
        0.0
      ],
      "load_w_and_select": [
        -131010.10274348268,
        1.0026313682773496,
        141.82818522604663
      ],
      "store_o": [
        30.003267896727266,
        1.0018610731663429
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF4_YS4_ID16_W2_SCALED": {
      "load_x": [
        -1306.014485093643,
        2.820509251499881,
        0.1951195769991467
      ],
      "load_w_and_select": [
        12.585526885740261,
        0.5000290284773711,
        -0.006180870802368
      ],
      "store_o": [
        -433.9967911031881,
        0.9130549369053447
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF4_YS4_ID16_W2_UNSCALED": {
      "load_x": [
        -569.9970822028899,
        3.213865441407836,
        0.0
      ],
      "load_w_and_select": [
        3.3283841630115227,
        0.5000067942630753,
        -0.0014933777801576064
      ],
      "store_o": [
        -683.9965336034302,
        0.9534906924443182
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF4_YS4_ID16_W4_SCALED": {
      "load_x": [
        -1969.744805473958,
        3.5209026771643344,
        0.1934432848578879
      ],
      "load_w_and_select": [
        -26740.549799205597,
        0.31396302955030225,
        12.311795063567578
      ],
      "store_o": [
        -166.9966197033682,
        1.0061030023808335
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF4_YS4_ID16_W4_UNSCALED": {
      "load_x": [
        -830.4963785035816,
        3.9138772348347284,
        0.0
      ],
      "load_w_and_select": [
        -23913.930300222615,
        0.3082942334313588,
        10.16687327004588
      ],
      "store_o": [
        -583.9962205037509,
        1.0642999693150899
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF4_YS4_ID16_W8_SCALED": {
      "load_x": [
        -1951.8297399178664,
        5.741213777984283,
        0.19402400125157712
      ],
      "load_w_and_select": [
        -41193.45281293307,
        0.6307087154248957,
        72.71533725857677
      ],
      "store_o": [
        13.503375996621799,
        1.032332858681189
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF4_YS4_ID16_W8_UNSCALED": {
      "load_x": [
        -771.494538005425,
        6.196590951632617,
        0.0
      ],
      "load_w_and_select": [
        -38670.89031620234,
        0.6270287648183493,
        75.06787302693311
      ],
      "store_o": [
        -553.4961620038112,
        1.0868066737905009
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF4_YS4_ID8_W2_SCALED": {
      "load_x": [
        -450.32670275022247,
        3.381251947606188,
        0.24048522334462827
      ],
      "load_w_and_select": [
        12.585526885740261,
        0.5000290284773711,
        -0.006180870802368
      ],
      "store_o": [
        -348.99666640331907,
        0.9640803072056556
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF4_YS4_ID8_W2_UNSCALED": {
      "load_x": [
        -135.99691140308005,
        3.6872539825456365,
        0.0
      ],
      "load_w_and_select": [
        3.3283841630115227,
        0.5000067942630753,
        -0.0014933777801576064
      ],
      "store_o": [
        -251.49683040315722,
        0.9289088219532533
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF4_YS4_ID8_W4_SCALED": {
      "load_x": [
        -613.6550126454677,
        3.362115417007266,
        0.23882200188922026
      ],
      "load_w_and_select": [
        -5696.336870233004,
        0.29186229230700544,
        7.989723461880576
      ],
      "store_o": [
        -143.996626903363,
        1.0074152589878884
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF4_YS4_ID8_W4_UNSCALED": {
      "load_x": [
        -178.49668640330492,
        3.935972109529403,
        0.0
      ],
      "load_w_and_select": [
        -6957.165553929252,
        0.29020077531424565,
        8.66628494637716
      ],
      "store_o": [
        -194.99659610339586,
        1.0090326867070092
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF4_YS4_ID8_W8_SCALED": {
      "load_x": [
        -580.3689356506657,
        4.548638100568242,
        0.238885222901199
      ],
      "load_w_and_select": [
        -39152.62812800485,
        0.629740846906587,
        72.90596180068393
      ],
      "store_o": [
        34.0032644967323,
        1.001433827530356
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF4_YS4_ID8_W8_UNSCALED": {
      "load_x": [
        -161.49569010429877,
        5.16253400039909,
        0.0
      ],
      "load_w_and_select": [
        -40370.94412380059,
        0.6279255711107861,
        74.67080329843458
      ],
      "store_o": [
        34.0032636967353,
        1.0011896870274257
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF8_YS2_ID16_W2_SCALED": {
      "load_x": [
        -1229.3857558809596,
        1.5042975628166457,
        0.09899280634780849
      ],
      "load_w_and_select": [
        32.4853073867586,
        0.49999938287923645,
        6.912145649825619e-05
      ],
      "store_o": [
        -243.49682660316284,
        0.9312891923450998
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF8_YS2_ID16_W2_UNSCALED": {
      "load_x": [
        -193.99713250285822,
        1.6909781347358923,
        0.0
      ],
      "load_w_and_select": [
        8.485331731204983,
        0.49999938286354007,
        6.912028470640011e-05
      ],
      "store_o": [
        -431.49666850331323,
        0.950850938667752
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF8_YS2_ID16_W4_SCALED": {
      "load_x": [
        -2333.943822829642,
        1.7757834281406126,
        0.09920729522321134
      ],
      "load_w_and_select": [
        -36096.48748000834,
        0.30792457995620753,
        -11.596686549406598
      ],
      "store_o": [
        -474.4961809037977,
        1.093093296562716
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF8_YS2_ID16_W4_UNSCALED": {
      "load_x": [
        -589.9964514035146,
        1.985838778809558,
        0.0
      ],
      "load_w_and_select": [
        -30186.459414739867,
        0.29435632302120657,
        -6.625697099865893
      ],
      "store_o": [
        59.50300139699266,
        0.9250331104511575
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF8_YS2_ID16_W8_SCALED": {
      "load_x": [
        -1510.0304099169334,
        2.247267492329604,
        0.09778104068428153
      ],
      "load_w_and_select": [
        -107881.55886592847,
        0.5018156822459705,
        18.11129578136628
      ],
      "store_o": [
        264.5031068968765,
        0.9885096537250996
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF8_YS2_ID16_W8_UNSCALED": {
      "load_x": [
        -309.49570770426544,
        2.5253588069622306,
        0.0
      ],
      "load_w_and_select": [
        -129190.40704420288,
        0.49625842527851555,
        31.735306386085963
      ],
      "store_o": [
        -164.496314103676,
        1.099746144188432
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF8_YS2_ID8_W2_SCALED": {
      "load_x": [
        -329.6126532015842,
        1.7208993788003475,
        0.11914180132577981
      ],
      "load_w_and_select": [
        32.4853073867586,
        0.49999938287923645,
        6.912145649825619e-05
      ],
      "store_o": [
        -391.49656750341273,
        0.9877771921772871
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF8_YS2_ID8_W2_UNSCALED": {
      "load_x": [
        -0.9969666030283406,
        1.8511343282173842,
        0.0
      ],
      "load_w_and_select": [
        8.485331731204983,
        0.49999938286354007,
        6.912028470640011e-05
      ],
      "store_o": [
        -302.99666280332167,
        0.9721979817356853
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF8_YS2_ID8_W4_SCALED": {
      "load_x": [
        -672.1407223337919,
        1.7115264255106297,
        0.11910320975020888
      ],
      "load_w_and_select": [
        704.1523729574892,
        0.273350290860227,
        -9.394156658718915
      ],
      "store_o": [
        -106.99664250335354,
        1.008300270569308
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF8_YS2_ID8_W4_UNSCALED": {
      "load_x": [
        -221.49664340334493,
        1.9811086015481594,
        0.0
      ],
      "load_w_and_select": [
        -4641.3701578331,
        0.2822576212039818,
        -13.03780446884765
      ],
      "store_o": [
        -161.49661970337365,
        1.0069422356953484
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF8_YS2_ID8_W8_SCALED": {
      "load_x": [
        -443.3979757133675,
        1.9868192208500999,
        0.11886037929594406
      ],
      "load_w_and_select": [
        -5836.8342197467355,
        0.3854829000214973,
        9.143468738552025
      ],
      "store_o": [
        49.00326279672936,
        1.003203847092124
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    },
    "B8_RF8_YS2_ID8_W8_UNSCALED": {
      "load_x": [
        -91.9962095037854,
        2.285460271821108,
        0.0
      ],
      "load_w_and_select": [
        -71697.84114098868,
        0.4026492113035122,
        12.930581966213822
      ],
      "store_o": [
        59.00325329674433,
        1.001830557373494
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 5
      }
    }
  },
  "geometry_fallback": {
    "B16_RF4_YS2_W2_SCALED": {
      "load_x": [
        -699.485141176352,
        1.6269539128301695,
        0.1078261124320496
      ],
      "load_w_and_select": [
        -11669.937534573646,
        0.25438811317025933,
        5.644025125240415
      ],
      "store_o": [
        -411.9965972533755,
        0.9755701657795768
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B16_RF4_YS2_W2_UNSCALED": {
      "load_x": [
        -60.99712280287349,
        1.7374869167793536,
        0.0
      ],
      "load_w_and_select": [
        -11269.55297630393,
        0.25385165823019856,
        5.29431826319325
      ],
      "store_o": [
        -433.496611653366,
        0.9678949974064678
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B16_RF4_YS2_W4_SCALED": {
      "load_x": [
        -1466.0852662375328,
        1.784572853270591,
        0.10863667258262284
      ],
      "load_w_and_select": [
        -55840.71950309332,
        0.2894301247758452,
        -14.433604873699734
      ],
      "store_o": [
        -208.74650560348528,
        1.0345529978146568
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B16_RF4_YS2_W4_UNSCALED": {
      "load_x": [
        -587.2463608036051,
        2.0419758360376714,
        0.0
      ],
      "load_w_and_select": [
        -53587.88654349612,
        0.2855155561174391,
        -14.453332395872442
      ],
      "store_o": [
        -312.99642790355665,
        1.042357858154785
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B16_RF4_YS2_W8_SCALED": {
      "load_x": [
        -981.1284527614553,
        2.103078525897325,
        0.10844615879826613
      ],
      "load_w_and_select": [
        -107106.15304311452,
        0.42716295796501064,
        12.717996272218725
      ],
      "store_o": [
        32.50342899657335,
        1.0514063366322455
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B16_RF4_YS2_W8_UNSCALED": {
      "load_x": [
        -205.49596335402657,
        2.4010607861034003,
        0.0
      ],
      "load_w_and_select": [
        -43875.3571692718,
        0.401106726338046,
        18.87388921495929
      ],
      "store_o": [
        325.7530412969427,
        0.9778361398510366
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B4_RF4_YS4_W2_SCALED": {
      "load_x": [
        -1096.570180898514,
        3.317482036430077,
        0.21978909193654136
      ],
      "load_w_and_select": [
        12.485346049594442,
        1.0000445420283544,
        -0.011580495212577662
      ],
      "store_o": [
        -341.2467341532483,
        0.9445872143978763
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B4_RF4_YS4_W2_UNSCALED": {
      "load_x": [
        -565.7467008532649,
        3.6819743392504485,
        0.0
      ],
      "load_w_and_select": [
        4.485341913345053,
        1.0000140244871965,
        -0.003768005211990576
      ],
      "store_o": [
        -329.9968070531768,
        0.9240565246662685
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B4_RF4_YS4_W4_SCALED": {
      "load_x": [
        -1490.642117977426,
        3.5319387760028227,
        0.21956782792829072
      ],
      "load_w_and_select": [
        42.25681215576259,
        0.5000966302822236,
        -0.02515468180883655
      ],
      "store_o": [
        -472.74631495366157,
        1.0524515323224324
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B4_RF4_YS4_W4_UNSCALED": {
      "load_x": [
        -421.99641275356225,
        4.121396762056398,
        0.0
      ],
      "load_w_and_select": [
        31.285351733597963,
        0.5000476387322208,
        -0.01118986161312666
      ],
      "store_o": [
        -170.7466539033394,
        0.995093791347962
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B4_RF4_YS4_W8_SCALED": {
      "load_x": [
        -1102.6702686137032,
        6.501858742888947,
        0.21825842433951279
      ],
      "load_w_and_select": [
        -39595.20448506329,
        0.952157378442991,
        108.16002372830853
      ],
      "store_o": [
        27.50342584657392,
        1.0496820940251286
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B4_RF4_YS4_W8_UNSCALED": {
      "load_x": [
        -192.994026405966,
        7.174190701724467,
        0.0
      ],
      "load_w_and_select": [
        -130695.80388752272,
        1.002173414599479,
        142.02291144138445
      ],
      "store_o": [
        58.25337774662148,
        1.0396952234539858
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B8_RF4_YS4_W2_SCALED": {
      "load_x": [
        -878.1705939219282,
        3.1008805995530415,
        0.21780240017188732
      ],
      "load_w_and_select": [
        12.585526885823562,
        0.5000290284773714,
        -0.006180870802530695
      ],
      "store_o": [
        -391.49672875324904,
        0.9385676220554996
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B8_RF4_YS4_W2_UNSCALED": {
      "load_x": [
        -352.99699680298,
        3.4505597119767333,
        0.0
      ],
      "load_w_and_select": [
        3.3283841629282227,
        0.500006794263075,
        -0.0014933777799949113
      ],
      "store_o": [
        -467.7466820032969,
        0.9411997571987862
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B8_RF4_YS4_W4_SCALED": {
      "load_x": [
        -1291.699909059702,
        3.4415090470858134,
        0.2161326433735539
      ],
      "load_w_and_select": [
        -16218.443334719645,
        0.3029126609286544,
        10.150759262723899
      ],
      "store_o": [
        -155.49662330336378,
        1.0067591306843606
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B8_RF4_YS4_W4_UNSCALED": {
      "load_x": [
        -504.4965324534378,
        3.9249246721820628,
        0.0
      ],
      "load_w_and_select": [
        -15435.547927076386,
        0.2992475043728026,
        9.416579108211494
      ],
      "store_o": [
        -389.4964083035684,
        1.0366663280110486
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B8_RF4_YS4_W8_SCALED": {
      "load_x": [
        -1266.0993377842278,
        5.144925939276272,
        0.21645461207638772
      ],
      "load_w_and_select": [
        -40173.04047046797,
        0.6302247811657411,
        72.81064952963023
      ],
      "store_o": [
        23.753320246674775,
        1.0168833431057729
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B8_RF4_YS4_W8_UNSCALED": {
      "load_x": [
        -466.49511405486373,
        5.679562476015854,
        0.0
      ],
      "load_w_and_select": [
        -39520.917220002106,
        0.6274771679645684,
        74.86933816268328
      ],
      "store_o": [
        -259.7464491535384,
        1.0439981804089633
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B8_RF8_YS2_W2_SCALED": {
      "load_x": [
        -779.49920454126,
        1.6125984708085037,
        0.10906730383679392
      ],
      "load_w_and_select": [
        32.48530738774198,
        0.4999993828792358,
        6.912145644741394e-05
      ],
      "store_o": [
        -317.49669705328597,
        0.9595331922611932
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B8_RF8_YS2_W2_UNSCALED": {
      "load_x": [
        -97.49704955294783,
        1.7710562314766398,
        0.0
      ],
      "load_w_and_select": [
        8.485331731204983,
        0.49999938286354007,
        6.912028470640011e-05
      ],
      "store_o": [
        -367.2466656533161,
        0.9615244602017184
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B8_RF8_YS2_W4_SCALED": {
      "load_x": [
        -1503.0422725816934,
        1.7436549268256185,
        0.10915525248671006
      ],
      "load_w_and_select": [
        -17696.167553525498,
        0.2906374354082178,
        -10.49542160406314
      ],
      "store_o": [
        -290.7464117035679,
        1.0506967835660108
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B8_RF8_YS2_W4_UNSCALED": {
      "load_x": [
        -405.74654740342794,
        1.983473690178858,
        0.0
      ],
      "load_w_and_select": [
        -17413.914786286427,
        0.28830697211259415,
        -9.831750784356771
      ],
      "store_o": [
        -50.996809153186405,
        0.9659876730732525
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B8_RF8_YS2_W8_SCALED": {
      "load_x": [
        -976.7141928151432,
        2.117043356589859,
        0.1083207099901127
      ],
      "load_w_and_select": [
        -56859.19654283786,
        0.44364929113373464,
        13.627382259958157
      ],
      "store_o": [
        156.75318484680884,
        0.9958567504086109
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    },
    "B8_RF8_YS2_W8_UNSCALED": {
      "load_x": [
        -200.7459586040286,
        2.40540953939167,
        0.0
      ],
      "load_w_and_select": [
        -100444.12409259366,
        0.44945381829101344,
        22.332944176150093
      ],
      "store_o": [
        -52.74653040346948,
        1.0507883507809634
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 10
      }
    }
  },
  "fallback": {
    "SCALED_W2": {
      "load_x": [
        10148.46845657241,
        1.303901849368881,
        0.06261431462672179
      ],
      "load_w_and_select": [
        224221.38233888708,
        0.2752876946262114,
        -10.920944111057164
      ],
      "store_o": [
        -365.55918930329517,
        0.9545645486235372
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 40
      }
    },
    "SCALED_W4": {
      "load_x": [
        18266.632640934775,
        0.9694354397908733,
        0.06965836494016661
      ],
      "load_w_and_select": [
        164800.19232707488,
        0.23805092595688876,
        -20.05111229320296
      ],
      "store_o": [
        -281.9339638910242,
        1.0361151110968658
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 40
      }
    },
    "SCALED_W8": {
      "load_x": [
        21867.93249984853,
        1.5289401559844338,
        0.05926105377202293
      ],
      "load_w_and_select": [
        973205.8233387292,
        0.3051815656035941,
        -31.510879013135067
      ],
      "store_o": [
        60.128339984165905,
        1.0284571310429382
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 40
      }
    },
    "UNSCALED_W2": {
      "load_x": [
        1688.3311248973487,
        1.5617039820432703,
        0.0
      ],
      "load_w_and_select": [
        224632.3994500679,
        0.27484468221574865,
        -11.07623915610069
      ],
      "store_o": [
        -399.62169159078894,
        0.9486689348683103
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 40
      }
    },
    "UNSCALED_W4": {
      "load_x": [
        1887.1615497238326,
        1.7197217861165424,
        0.0
      ],
      "load_w_and_select": [
        165357.87211484465,
        0.2353424258584044,
        -19.841524612028255
      ],
      "store_o": [
        -231.05907481591476,
        1.0100264126467624
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 40
      }
    },
    "UNSCALED_W8": {
      "load_x": [
        4388.107199896497,
        1.8505116216197859,
        0.0
      ],
      "load_w_and_select": [
        1016880.3261566521,
        0.2917110196148265,
        -24.989696202264163
      ],
      "store_o": [
        17.87835987164908,
        1.0280794736237358
      ],
      "feature_basis": "legacy_stage_v8",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 40
      }
    }
  },
  "physical_fallback": {
    "SCALED_W2": {
      "load_x": [
        1618.1661097027581,
        1.6073166276357418,
        1035.906960208164,
        0.6115593331294868
      ],
      "load_w_and_select": [
        -1560.217814479618,
        0.979370308054444,
        0.054156461306549365,
        2.8606554101053945
      ],
      "store_o": [
        -365.55918930329517,
        0.9545645486235372
      ],
      "feature_basis": "rtl_work_v1",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 40
      }
    },
    "SCALED_W4": {
      "load_x": [
        6551.361486218264,
        0.5401085881019194,
        601.963702348806,
        0.7282686969951653
      ],
      "load_w_and_select": [
        -40555.16842287217,
        0.19028438069875314,
        1.3821709569897715,
        33.152711092051575
      ],
      "store_o": [
        -281.9339638910242,
        1.0361151110968658
      ],
      "feature_basis": "rtl_work_v1",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 40
      }
    },
    "SCALED_W8": {
      "load_x": [
        6959.817836537641,
        0.5935043315210679,
        49.00169911405075,
        0.7213842586778951
      ],
      "load_w_and_select": [
        38577.85628196056,
        0.3298377947634498,
        1.7248202696572363,
        11.369074838590693
      ],
      "store_o": [
        60.128339984165905,
        1.0284571310429382
      ],
      "feature_basis": "rtl_work_v1",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 40
      }
    },
    "UNSCALED_W2": {
      "load_x": [
        -286.6497827953713,
        1.7638340468650207,
        6.936126394385411,
        0.0
      ],
      "load_w_and_select": [
        -1565.735071951951,
        0.9816102261578388,
        0.04679359219706556,
        2.6649571269088828
      ],
      "store_o": [
        -399.62169159078894,
        0.9486689348683103
      ],
      "feature_basis": "rtl_work_v1",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 40
      }
    },
    "UNSCALED_W4": {
      "load_x": [
        -479.8715356209376,
        1.9680440013329572,
        11.291796878145039,
        0.0
      ],
      "load_w_and_select": [
        -41076.70368906894,
        0.20639038354722702,
        1.3492831207186538,
        32.629690208818126
      ],
      "store_o": [
        -231.05907481591476,
        1.0100264126467624
      ],
      "feature_basis": "rtl_work_v1",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 40
      }
    },
    "UNSCALED_W8": {
      "load_x": [
        -266.4340613954814,
        2.0174484076171093,
        12.654219462691133,
        0.0
      ],
      "load_w_and_select": [
        21490.7010582934,
        0.5468364528811007,
        1.5359626989995403,
        11.568869577372027
      ],
      "store_o": [
        17.87835987164908,
        1.0280794736237358
      ],
      "feature_basis": "rtl_work_v1",
      "training_domain": {
        "min_K": 1024,
        "max_K": 3072,
        "min_N": 1024,
        "max_N": 3072,
        "R": 4,
        "observations": 40
      }
    }
  },
  "source": {
    "repository": "IoannouKon/chipyard",
    "branch": "feature/lumixed-integration",
    "dataset": "verified Qwen R4 FP32 W8/W4/W2",
    "csv": {
      "QWEN_RESULTS_GROUP_SCALED": {
        "relative_path": "generators/LUMIXED/verification/QWEN_RESULTS_GROUP_SCALED/matmul_stage_cycles.csv",
        "sha256": "177bce7018f7612c8bdbc48c8ebc8a7d16cdbb8e4c502838b2f230baffb6ee77"
      },
      "QWEN_RESULTS_UNSCALED": {
        "relative_path": "generators/LUMIXED/verification/QWEN_RESULTS_UNSCALED/matmul_stage_cycles.csv",
        "sha256": "dbe355784e6157d1933923c7f4e1b0cd1f384021a6e457632054753d715f7423"
      }
    },
    "observations": 240,
    "git_head": "6bffd7742b6e5e12ab08503215801ce5a31da324"
  }
}
''')


def positive_int(name: str, val: Any) -> int:
    if isinstance(val, bool) or not isinstance(val, int) or val < 1:
        raise ValueError(f'{name} must be a positive integer: {val!r}')
    return val


def group_key(b: int, rf: int, ys: int, ids: int, w: int, scaled: bool) -> str:
    return f'B{b}_RF{rf}_YS{ys}_ID{ids}_W{w}_{"SCALED" if scaled else "UNSCALED"}'

def geometry_key(b: int, rf: int, ys: int, w: int, scaled: bool) -> str:
    return f'B{b}_RF{rf}_YS{ys}_W{w}_{"SCALED" if scaled else "UNSCALED"}'


def geometric_features(r: int, k: int, n: int, b: int, rf: int, ys: int,
                       ids: int, w: int, scaled: bool, g: int | None = None) -> dict:
    if not isinstance(scaled, bool):
        raise ValueError('scaled must be a Boolean')
    for key, value in dict(R=r, K=k, N=n, B=b, RF=rf, YS=ys, DMA_ID=ids, weight_bits=w).items():
        positive_int(key, value)
    if w not in (2, 4, 8):
        raise ValueError('Only signed W8/W4/W2 supported')
    if ys > r:
        raise ValueError('Expected YS <= R for this benchmark')
    if g is None:
        g = 64 if w == 2 else 32
    if scaled and g not in (32, 64, 128):
        raise ValueError('Unsupported group size')
    if not scaled:
        g = 0
    row_groups = math.ceil(r/ys)
    capacity_per_bank = rf * (1 << (8-w))
    window = b * capacity_per_bank
    span_sum = 0
    gen_one_group = 0
    windows = 0
    for begin in range(0, k, window):
        size = min(window, k-begin)
        span = min(capacity_per_bank, math.ceil(size/b))
        span_sum += span
        gen_one_group += span * ((1 << (w-2)) + 1)
        windows += 1
    return dict(
        row_groups=row_groups, capacity_per_bank=capacity_per_bank,
        window_size=window, windows_per_group=windows,
        dense_selection_cycles=row_groups*n*span_sum,
        exact_generation_cycles=row_groups*gen_one_group,
        weight_bytes=row_groups*k*n*w/8.0,
        scale_bytes=(row_groups*math.ceil(k/g)*n*2.0) if scaled else 0.0,
        x_words=row_groups*k,
        output_words=r*n,
        output_columns=row_groups*n,
        group_size=g,
        # Literal work counters for the aligned archived windows and tails. They are
        # predictive features, not a cycle-exact simulation of cache responses.
        activation_beats=r*math.ceil(k/2),
        activation_window_rows=r*windows,
        weight_beats=row_groups*k*n*w/64.0,
        column_windows=row_groups*n*windows,
    )


def _dot(xs: list[float], coefs: list[float]) -> float:
    return sum(a*b for a,b in zip(xs,coefs))


def feature_vector(stage: str, f: dict, basis: str = 'legacy_stage_v8') -> list[float]:
    if basis == 'rtl_work_v1':
        if stage == 'load_x':
            return [1.0, f['activation_beats'], f['activation_window_rows'], f['scale_bytes']/8]
        if stage == 'load_w_and_select':
            return [1.0, f['dense_selection_cycles'], f['weight_beats'], f['column_windows']]
        if stage == 'store_o':
            return [1.0, f['output_words']]
        raise KeyError(stage)
    if basis != 'legacy_stage_v8':
        raise ValueError(f'Unknown feature basis: {basis}')
    if stage == 'load_x':
        return [1.0, f['x_words'], f['scale_bytes']]
    if stage == 'load_w_and_select':
        return [1.0, f['weight_bytes'], f['output_columns']]
    if stage == 'store_o':
        return [1.0, f['output_words']]
    raise KeyError(stage)


def predict(r: int, k: int, n: int, b: int, rf: int, ys: int, ids: int,
            w: int, scaled: bool = False, calibration: str | Path | dict | None = None,
            group_size: int | None = None, allow_extrapolation: bool = False) -> dict:
    """Returns stage cycle estimates with the same core keys as Model.py.

    A calibrated configuration must be present. If no training JSON is available,
    fail explicitly; do not silently substitute measured results or guess a fixed
    speedup. Recalibrate using scripts/calibrate.py.
    """
    expected_group = 64 if w == 2 else 32
    group_extrapolation = scaled and group_size is not None and group_size != expected_group
    if group_extrapolation and not allow_extrapolation:
        raise ValueError('Unseen group_size for this weight precision; pass allow_extrapolation=True')
    f=geometric_features(r,k,n,b,rf,ys,ids,w,scaled,group_size)
    if calibration is None:
        calibration = DEFAULT_CALIBRATION
    if not isinstance(calibration,dict):
        calibration=json.loads(Path(calibration).read_text())
    key=group_key(b,rf,ys,ids,w,scaled)
    coeff=calibration.get('groups',{}).get(key)
    status='in-domain'
    if not coeff:
        if not allow_extrapolation:
            raise ValueError(f'{key}: no calibrated geometry; refusing extrapolation')
        geom=geometry_key(b,rf,ys,w,scaled)
        coeff=calibration.get('geometry_fallback',{}).get(geom)
        if coeff is not None:
            status='EXTRAPOLATED_DMA_ID'
        else:
            fallback=f'{"SCALED" if scaled else "UNSCALED"}_W{w}'
            coeff=calibration.get('physical_fallback',{}).get(fallback)
            if coeff is None:
                coeff=calibration.get('fallback',{}).get(fallback)
            if coeff is None:
                raise ValueError(f'Fallback coefficients unavailable for {fallback}')
            status='EXTRAPOLATED_GEOMETRY'
    bounds=coeff.get('training_domain',{})
    if bounds and not allow_extrapolation:
        if not bounds['min_K'] <= k <= bounds['max_K'] or not bounds['min_N'] <= n <= bounds['max_N'] or r!=4:
            raise ValueError('Outside calibrated R/K/N domain; pass allow_extrapolation=True to override')
    if allow_extrapolation and status=='in-domain' and (group_extrapolation or r!=4 or (bounds and (not bounds['min_K']<=k<=bounds['max_K'] or not bounds['min_N']<=n<=bounds['max_N']))):
        status='EXTRAPOLATED_SHAPE'
    preds={}
    for stage in ('load_x','load_w_and_select','store_o'):
        if stage not in coeff:
            raise KeyError(f'Calibration missing stage {stage} for {key}')
        xs=feature_vector(stage,f,coeff.get('feature_basis','legacy_stage_v8'))
        if len(xs)!=len(coeff[stage]):
            raise ValueError(f'Calibration feature count mismatch for {stage}')
        raw=_dot(xs,coeff[stage])
        if not math.isfinite(raw):
            raise ValueError(f'Non-finite model value for {stage}')
        preds[stage]=max(0,raw)
    preds['load_w_and_select']=max(preds['load_w_and_select'],f['dense_selection_cycles'])
    preds['generate_brams']=f['exact_generation_cycles']
    stages={k:int(round(preds[k])) for k in STAGES}
    total=sum(stages.values())
    return {
        'model_version':MODEL_VERSION,'prediction_status':status,
        'calibration_group':key,'R':r,'K':k,'N':n,'B':b,'RF':rf,'YS':ys,
        'DMA_ID':ids,'weight_bits':w,'group_scaled':bool(scaled),
        'group_size':f['group_size'],'total_cycles':total,
        'group_scale_implementation':'deferred' if scaled else 'none',
        'cycles_load_X':stages['load_x'],
        'cycles_generate_Products':stages['generate_brams'],
        'cycles_loadW_select':stages['load_w_and_select'],
        'cycles_store_output':stages['store_o'],
        'predicted_stages':stages,
        'Repeat activation Window':f['windows_per_group']*f['row_groups'],
        'dense_selection_lower_bound':f['dense_selection_cycles'],
        'calibration_source':calibration.get('source',{}),
        'memory_model':'empirical effective DMA/LLC (no trace replay)',
        'feature_basis':coeff.get('feature_basis','legacy_stage_v8'),
    }


def compute_cycles_fp32(Rin, Cin, Cout, in_bits, w_bits, XS, YS, ID, *,
                        factor=4, buffers_w=2, DMA_WIDTH=64,
                        group_scale_mode='off', group_size=None,
                        calibration=None, allow_extrapolation=False,
                        activation='fp32', **kwargs):
    """FP32-compatible adapter for the original Model.compute_cycles positional API.

    "XS" in the legacy model corresponds to B; factor corresponds to RF.
    fp32 hardware uses 2 weight buffers, 64-bit DMA and BRAM output storage.
    """
    if in_bits != 32:
        raise ValueError('This module is FP32-only; use legacy Model.py for input16')
    if activation != 'fp32':
        raise ValueError('FP32 inputs require activation=fp32')
    if buffers_w != 2 or DMA_WIDTH != 64:
        raise ValueError('Calibrated model supports W_BUFFS=2, DMA_WIDTH=64 only')
    if group_scale_mode not in ('on','off'):
        raise ValueError('group_scale_mode must be on or off')
    unsupported={k:v for k,v in kwargs.items() if v is not None}
    if unsupported:
        raise ValueError(f'Unsupported FP32 model options: {sorted(unsupported)}')
    z=predict(Rin,Cin,Cout,XS,factor,YS,ID,w_bits,
              scaled=(group_scale_mode=='on'),calibration=calibration,
              group_size=group_size,allow_extrapolation=allow_extrapolation)
    z['activation']='fp32'
    return z


# --- Reproducible calibration and grouped cross-validation ---
import argparse
import csv
import hashlib
import json
from collections import defaultdict
from pathlib import Path
import sys
import math
import subprocess
ROOT=Path(__file__).resolve().parent

_FIT_STAGES=('load_x','load_w_and_select','store_o')
ALL_STAGES=('load_x','generate_brams','load_w_and_select','store_o')
SHAPES={'Q','K_V','O','GATE_UP','DOWN'}


def digest(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def load_rows(repo):
    repo=Path(repo).resolve()
    out=[]; sources={}
    for mode,dirname in ((True,'QWEN_RESULTS_GROUP_SCALED'),(False,'QWEN_RESULTS_UNSCALED')):
        path=repo/'generators/LUMIXED/verification'/dirname/'matmul_stage_cycles.csv'
        if not path.is_file(): raise FileNotFoundError(path)
        sources[dirname]={'relative_path':str(path.relative_to(repo)),'sha256':digest(path)}
        with path.open(newline='') as stream:
            data=list(csv.DictReader(stream))
        for row in data:
            if row['result']!='PASS' or int(row['bit_mismatches']) or int(row['simulator_exit']) or int(row['exact_matches'])!=int(row['elements']):
                raise ValueError(f'Cannot train on failed or unverified row: {row}')
            if row['projection'] not in SHAPES or int(row['R'])!=4:
                raise ValueError('Only archive R=4 five-shape Qwen data are supported')
            d={k:int(row[k]) for k in ('B','RF','YS','DMA_ID','weight_bits','R','K','N','load_x','generate_brams','load_w_and_select','store_o','total','occurrences')}
            d.update(projection=row['projection'],scaled=mode,configuration=row['configuration'])
            if sum(d[x] for x in ALL_STAGES)!=d['total']:
                raise ValueError('Cycle stage sum does not match total')
            expected_group=(64 if d['weight_bits']==2 else 32) if mode else 0
            if 'group_size' in row and int(row['group_size'])!=expected_group:
                raise ValueError('Group-size mismatch with calibrated scaling contract')
            if 'expected_sha256' in row and row['expected_sha256']!=row['output_sha256']:
                raise ValueError('Output/reference SHA256 mismatch')
            f=geometric_features(d['R'],d['K'],d['N'],d['B'],d['RF'],d['YS'],d['DMA_ID'],d['weight_bits'],mode)
            if f['exact_generation_cycles']!=d['generate_brams']:
                raise ValueError(f'Analytical generation stage disagrees: {row["configuration"]} {row["projection"]}')
            d['features']=f
            d['key']=group_key(d['B'],d['RF'],d['YS'],d['DMA_ID'],d['weight_bits'],mode)
            out.append(d)
    keyshape=defaultdict(set)
    for row in out: keyshape[row['key']].add(row['projection'])
    if len(keyshape)!=48 or len(out)!=240 or any(s!=SHAPES for s in keyshape.values()):
        raise ValueError(f'Expected 48 groups x 5 shapes = 240 records; found {len(keyshape)} groups, {len(out)} records')
    return out,sources


def solve(a,b):
    n=len(b); m=[list(row)+[b[i]] for i,row in enumerate(a)]
    for k in range(n):
        ix=max(range(k,n),key=lambda j:abs(m[j][k]))
        if abs(m[ix][k])<1e-12: raise ValueError('Singular ridge system')
        m[k],m[ix]=m[ix],m[k];p=m[k][k]
        for c in range(k,n+1):m[k][c]/=p
        for j in range(n):
            if j==k:continue
            f=m[j][k]
            for c in range(k,n+1):m[j][c]-=f*m[k][c]
    return [row[-1] for row in m]


def regression(rows,stage,lam=1e-7,basis='legacy_stage_v8'):
    xs=[feature_vector(stage,d['features'],basis) for d in rows]
    ys=[d[stage] for d in rows]
    n=len(xs[0])
    norms=[math.sqrt(sum(x[k]**2 for x in xs)/len(xs)) or 1.0 for k in range(n)]
    xscaled=[[x[j]/norms[j] for j in range(n)] for x in xs]
    a=[[sum(x[i]*x[j] for x in xscaled) for j in range(n)] for i in range(n)]
    b=[sum(x[i]*y for x,y in zip(xscaled,ys)) for i in range(n)]
    for k in range(n):a[k][k]+=lam*len(xs)
    sol=solve(a,b)
    return [v/norms[i] for i,v in enumerate(sol)]


def fit(rows):
    grouped=defaultdict(list)
    for row in rows:grouped[row['key']].append(row)
    geo=defaultdict(list)
    for row in rows:geo[geometry_key(row['B'],row['RF'],row['YS'],row['weight_bits'],row['scaled'])].append(row)
    fallback=defaultdict(list)
    for row in rows:fallback[f'{"SCALED" if row["scaled"] else "UNSCALED"}_W{row["weight_bits"]}'].append(row)
    def coefficients(arr,basis='legacy_stage_v8'):
        result={stage:regression(arr,stage,basis=basis) for stage in _FIT_STAGES}
        result['feature_basis']=basis
        result['training_domain']={'min_K':min(r['K'] for r in arr),'max_K':max(r['K'] for r in arr),
                                   'min_N':min(r['N'] for r in arr),'max_N':max(r['N'] for r in arr),'R':4,'observations':len(arr)}
        return result
    return {'model_version':MODEL_VERSION,
            'algorithm':'v8 per-config/geometry ridge unchanged; RTL work-count fallback for unseen geometry; analytical generation; dense floor; 1e-7 ridge',
            'groups':{name:coefficients(v) for name,v in sorted(grouped.items()) if len(v)>=4},
            'geometry_fallback':{name:coefficients(v) for name,v in sorted(geo.items()) if len(v)>=4},
            'fallback':{name:coefficients(v) for name,v in sorted(fallback.items())},
            'physical_fallback':{name:coefficients(v,'rtl_work_v1') for name,v in sorted(fallback.items())}}


def score(pairs):
    if not pairs: return None
    er=sorted(abs(p['total_cycles']-d['total'])/d['total']*100 for p,d in pairs)
    se=sorted(abs(p['total_cycles']-d['total']) for p,d in pairs)
    return {'n':len(er),'MAPE_pct':sum(er)/len(er),'median_APE_pct':er[len(er)//2],
            'p95_APE_pct':er[min(len(er)-1,int(len(er)*.95))],'max_APE_pct':er[-1],
            'mean_absolute_cycles':sum(se)/len(se),
            'by_precision':{str(w):score_basic([(p,d) for p,d in pairs if d['weight_bits']==w]) for w in (8,4,2)},
            'by_mode':{m:score_basic([(p,d) for p,d in pairs if d['scaled']==(m=='scaled')]) for m in ('scaled','unscaled')},
            'stage_MAPE_pct':{stage:sum(abs(p['predicted_stages'][stage]-d[stage])/max(1,d[stage])*100 for p,d in pairs)/len(pairs)
                              for stage in ALL_STAGES}}


def score_basic(pairs):
    return {'n':len(pairs),'MAPE_pct':sum(abs(p['total_cycles']-d['total'])/d['total']*100 for p,d in pairs)/len(pairs)} if pairs else None


def predict_rows(test,cal):
    return [(predict(d['R'],d['K'],d['N'],d['B'],d['RF'],d['YS'],d['DMA_ID'],d['weight_bits'],
                     scaled=d['scaled'],calibration=cal,allow_extrapolation=True),d) for d in test]


def cross_validate(rows,axis):
    selectors={
        'shape':lambda d:d['projection'],
        'configuration':lambda d:(d['B'],d['RF'],d['YS'],d['DMA_ID']),
        'geometry':lambda d:(d['B'],d['RF'],d['YS']),
    }
    selection=selectors[axis]
    pairs=[]
    for holdout in sorted(set(selection(r) for r in rows)):
        train=[r for r in rows if selection(r)!=holdout]
        test=[r for r in rows if selection(r)==holdout]
        cal=fit(train)
        # For unknown config, model uses fallback; never silently insert its true data.
        pairs.extend(predict_rows(test,cal))
    return score(pairs),pairs


def _calibration_cli():
    a=argparse.ArgumentParser(description=__doc__)
    a.add_argument('--chipyard',required=True,type=Path,help='Local Chipyard 1.15 checkout')
    a.add_argument('--out',type=Path,default=ROOT/'calibration.json')
    a.add_argument('--report-dir',type=Path,default=ROOT/'reports')
    a.add_argument('--include-cv-rows',action='store_true')
    args=a.parse_args()
    rows,sources=load_rows(args.chipyard)
    cv={};per_shape_pairs=[];all_cv_pairs={}
    for axis in ('shape','configuration','geometry'):
        cv[axis],pairs=cross_validate(rows,axis)
        all_cv_pairs[axis]=pairs
        if axis=='shape':per_shape_pairs=pairs
    cal=fit(rows)
    cal['source']={'repository':'IoannouKon/chipyard','branch':'feature/lumixed-integration',
                   'dataset':'verified Qwen R4 FP32 W8/W4/W2',
                   'csv':sources,'observations':len(rows)}
    if (args.chipyard/'.git').exists():
        cal['source']['git_head']=subprocess.check_output(['git','-C',str(args.chipyard),'rev-parse','HEAD'],text=True).strip()
    out=args.out.resolve();out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps(cal,indent=2)+'\n')
    train=score(predict_rows(rows,cal))
    report={'dataset_rows':len(rows),'distinct_group_keys':len(cal['groups']),
            'analytical_generation_exact_matches':len(rows),
            'training_apparent_accuracy':train,
            'cross_validation':cv,
            'interpretation':{'shape':'Unseen Qwen matrix shape, but the same hardware configuration has four training shapes.',
                              'configuration':'One DMA-ID configuration held out; same B/RF/YS retained at the other ID. Uses same-geometry pooled regression, without target configuration labels.',
                              'geometry':'One B/RF/YS geometry held out at BOTH IDs. Uses the RTL-work-count empirical fallback; only four geometries exist in this archive.',
                              'accuracy':'Cycle MAPE = mean(abs(pred-measured)/measured)*100. Model is empirical, not trace-accurate.'},
            'source':cal['source']}
    args.report_dir.mkdir(parents=True,exist_ok=True)
    (args.report_dir/'accuracy.json').write_text(json.dumps(report,indent=2)+'\n')
    with (args.report_dir/'shape_holdout_predictions.csv').open('w',newline='') as o:
        w=csv.DictWriter(o,fieldnames=['configuration','scaled','weight_bits','projection','R','K','N','measured_total','predicted_total','absolute_percentage_error','predicted_load_x','predicted_generate_brams','predicted_load_w_and_select','predicted_store_o'])
        w.writeheader()
        for p,d in per_shape_pairs:
            w.writerow(dict(configuration=d['configuration'],scaled=d['scaled'],weight_bits=d['weight_bits'],projection=d['projection'],R=d['R'],K=d['K'],N=d['N'],measured_total=d['total'],predicted_total=p['total_cycles'],absolute_percentage_error=abs(p['total_cycles']-d['total'])/d['total']*100,**{'predicted_'+k:p['predicted_stages'][k] for k in ALL_STAGES}))
    if args.include_cv_rows:
        for axis,pairs in all_cv_pairs.items():
            records=[]
            for p,d in pairs:
                rec={k:d[k] for k in ('configuration','B','RF','YS','DMA_ID','scaled','weight_bits','projection','R','K','N')}
                rec.update(validation_axis=axis,measured_total=d['total'],predicted_total=p['total_cycles'],absolute_percentage_error=abs(p['total_cycles']-d['total'])/d['total']*100,prediction_status=p['prediction_status'],feature_basis=p['feature_basis'])
                for stage in ALL_STAGES:
                    rec['measured_'+stage]=d[stage];rec['predicted_'+stage]=p['predicted_stages'][stage]
                records.append(rec)
            with (args.report_dir/f'{axis}_holdout_full.csv').open('w',newline='') as stream:
                writer=csv.DictWriter(stream,fieldnames=list(records[0]));writer.writeheader();writer.writerows(records)
    print(json.dumps({'calibration_file':str(out),'dataset_rows':len(rows),'shape_cv':cv['shape'],'configuration_cv':cv['configuration'],'geometry_cv':cv['geometry']},indent=2))


# --- Backward-compatible public entry point ---
from functools import wraps as _wraps

@_wraps(_compute_cycles_legacy)
def compute_cycles(*args, **kwargs):
    bits = args[3] if len(args) > 3 else kwargs.get('in_bits')
    if bits == 32:
        return compute_cycles_fp32(*args, **kwargs)
    return _compute_cycles_legacy(*args, **kwargs)


def _prediction_cli():

    import argparse
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('R','K','N','B','RF','YS','ID','W'):
        p.add_argument('--'+name.lower(),type=int,required=True)
    p.add_argument('--scale',choices=['on','off'],default='off')
    p.add_argument('--calibration',type=Path,default=DEFAULT_CALIBRATION)
    p.add_argument('--allow-extrapolation',action='store_true')
    a=p.parse_args()
    print(json.dumps(predict(a.r,a.k,a.n,a.b,a.rf,a.ys,a.id,a.w,
                             scaled=a.scale=='on',calibration=a.calibration,
                             allow_extrapolation=a.allow_extrapolation),indent=2))


if __name__ == '__main__':
    if '--validate' in sys.argv:
        sys.argv.remove('--validate')
        _calibration_cli()
    else:
        _prediction_cli()
