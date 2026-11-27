# Rounded group scaling: comparison and finalized single-flag mode

The current experimental implementation has one rounding flag:

| Group scaling | ROUNDED_ACCUMULATION | Arithmetic |
|---|---|---|
| OFF | false | Exact accumulation, final FP32 rounding |
| OFF | true | Existing pipelined FP32 subtotal/add |
| ON | false | Exact group scaling and accumulation |
| ON | true | Former Option B: fragment RNE, exact FP16 widening, FP32 FMA |

GROUP_SCALE_ROUNDING and the active Option A path were removed. Group modes remain mutually exclusive GROUP_SCALE_ALIGNED / GROUP_SCALE_DEFERRED. Runtime G32/64/128, signed W8/W4/W2, GROUP_SCALE_YS lane sharing, existing RoCC/DMA interfaces, valid/tag/first/row masks, SRAM forwarding and drains are preserved. Rounded accumulation requires FP32. Default ROUNDED_ACCUMULATION=false preserves exact arithmetic. No production feature-branch merge is implied.

## Measured investigation before selector cleanup

All modes used B8/RF4/YS4/ID8; capacities R4/K3072/N3072; FP32 X/O, FP16 group scales, two weight buffers and 8-byte DMA; optional sparsity, flexible grouping, bursts and early release OFF. The primary run used GROUP_SCALE_YS4; the separate sharing run used2. Six larger synthetic Qwen3-shaped GEMMs per mode: R4/K1024/N1024 and R4/K1024/N3072, each W8/W4/W2, G128. Smoke tests also cover G32/64, tails, forwarding, multi-window K, inactive rows, signed scales, ties, cancellation, underflow/overflow and random backpressure.

Vivado2023.2, U250 xcu250-figd2104-2-e, accelerator-only OOC synthesis, clock20ns. No placement/routing. Frequency and vectorless power below are preliminary synthesis estimates, not routed closure, measured power or an operating clock. Resources exclude the simulation context. Comparisons are within this matched fresh experiment, not directly against historical wrapped PPA.

| Mode | Scale lanes | Larger cycles | LUT | FF | DSP | BRAM36/18 | URAM | Synth-est. MHz | Synth dynamic W | Max relative L2 |
|---|---:|---:|---:|---:|---:|---|---:|---:|---:|---:|
| off_exact | 4 | 3307637 | 115568 | 11313 | 0 | 80/0 | 40 | 85.785 | 0.47 | 0 |
| off_rounded | 4 | 3307707 | 123962 | 10403 | 0 | 96/0 | 0 | 79.441 | 0.41 | 2.74e-07 |
| on_exact | 4 | 3391365 | 164287 | 25134 | 70 | 209/0 | 40 | 81.840 | 0.569 | 0 |
| on_a | 4 | 3391365 | 164985 | 21480 | 70 | 225/0 | 0 | 55.154 | 0.548 | 2.33e-07 |
| on_b | 4 | 3391365 | 161098 | 21455 | 10 | 225/0 | 0 | 78.438 | 0.479 | 2.32e-07 |
| on_exact | 2 | 3733484 | 162363 | 25152 | 36 | 209/0 | 40 | 81.840 | 0.584 | 0 |
| on_a | 2 | 3733484 | 157180 | 21498 | 36 | 225/0 | 0 | 55.574 | 0.495 | 2.33e-07 |
| on_b | 2 | 3733484 | 152478 | 21463 | 6 | 225/0 | 0 | 77.077 | 0.44 | 2.32e-07 |

178/178 investigation tests passed their own independent reference (115 full-parallel, 63 shared), including48 full-sized GEMMs. Rounded outputs are not required to match exact output bits: relative L2 compares identical quantized inputs against the corresponding exact mode. All data are synthetic, not captured Qwen inference. No inference-quality conclusion is supported.

With4 scale lanes, B reduces DSPs70->10, removes40 URAMs (BRAM36 increases209->225), and uses identical aggregate cycles to ON exact. Maximum larger-test relative L2 is2.32074e-7. Synthesis dynamic power changes0.569->0.479W; this is an estimate, not measured energy. With2 lanes B uses6 DSPs; aggregate cycles are10.09% higher than full-parallel B, while matching the corresponding shared exact mode. Exact FP16 widening uses HardFloat RecFNToRecFN; the FMA uses MulAddRecFN. B's generated accelerator closure contains no ExactScaleMultiplier. Full-parallel B has4 FMA instances; sharing uses matching row batches and physical lane ownership.

## Cleanup verification and provenance limit

The above synthesis measurements and178 checks used the original experimental A/B selector. They are preserved historical measurements; no new synthesis was run after removing that selector and Option A. Fresh Verilator testing of the finalized automatic B path passed15/15 tests (137/137 output elements), including W8/W4/W2, G32/64/128, inactive rows, ties, underflow/overflow, forwarding and multiple windows. All15 output hashes and controller-active cycle counts match the prior B run exactly (zero cycle delta). Historical A code appears only inside the archived investigation snapshot for reproducibility; it is not an active configuration.

The investigation base was d8946796bc63859626bd8713df6aaa84b9ed4d49. The archive contains measured-source snapshots, source hashes, raw utilization/power/timing/hierarchy reports, exact configurations and commands, cycle/accuracy tables, independent references, smoke harness/provenance, and finalized-flag cleanup logs. Large JARs, executables, object files, checkpoints and memory dumps are excluded. The integer-exact single-round FMA reference additionally matched10,000 independent libm.fmaf cases. It recomputes fragment sums from inputs and validates observed FIFO metadata against theoretical bank/window/group coverage; observed retirement order is used for association, never DUT arithmetic as expected values.

Reproduction requires Scala2.13.16/Chisel6.7, firtool1.75, Verilator and Vivado2023.2. Extract reports.tar.gz into an isolated directory, restore historical source snapshot for the historical comparison, remap archived absolute paths and use the included exact commands/runners. The complete build caches remain locally under /var/tmp/lumixed-rounded-scaling.3q0lokdm/evidence/. Finalized single-flag smoke evidence is under rounded_flag_cleanup/. Rebuilding the historical A/B comparison against the current selector-free sources requires its archived source snapshot; do not restore obsolete flags into the active code.
