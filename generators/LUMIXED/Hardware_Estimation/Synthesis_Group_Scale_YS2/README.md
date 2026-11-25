# GROUP_SCALE_YS=2: Vivado synthesis-only measurement

Source revision: `405d4229c53ce83e8c01042876746e0b762002d5`.
Configuration: B8/RF4/YS4/ID8, Rin4/Cin3072/Cout3072, FP32 activations/output,
signed runtime W8/W4/W2, deferred FP16 group scales ON, aligned scaling OFF,
BRAM outputs ON, two weight buffers, DMA beat 8 bytes, performance counters ON,
all other optional experiments OFF. Only the private overlay sets
`GROUP_SCALE_YS=2`; production RTL was not edited.

Vivado 2023.2, `xcu250-figd2104-2-e`, accelerator-only OOC synthesis,
`-flatten_hierarchy none`, two CPU cores. The synthesis Tcl is byte-identical
to the archived baseline. Same post-synthesis constraints: ppa_clk 20 ns/50 MHz,
zero external I/O delays, reset false path. The supplied flow creates these
constraints after synth_design, identically to the archived experiment.

Synthesis PASS, zero unresolved black boxes. Generated engine RTL and the
instantiated accelerator hierarchy both contain exactly TWO ExactScaleMultiplier
instances (shifted_multiplier and shifted_multiplier_1).

| Resource | Archived GSYS4 | New GSYS2 | Change |
|---|---:|---:|---:|
| dsp | 70 | 36 | -34 |
| scaling_engine_dsp | 69 | 35 | -34 |
| lut_total | 164,287 | 162,363 | -1,924 |
| ff | 25,134 | 25,152 | +18 |
| bram36 | 209 | 209 | +0 |
| bram18 | 0 | 0 | +0 |
| uram | 40 | 40 | +0 |

Total DSP reduction: 48.571%.
Baseline hierarchy: {'_words_T_3': 1, 'groupEngine/_index_T_1': 1, 'groupEngine/multiplier': 17, 'groupEngine/multiplier_1': 17, 'groupEngine/multiplier_2': 17, 'groupEngine/multiplier_3': 17}.
New hierarchy: {'_words_T_3': 1, 'groupEngine/_index_T_1': 1, 'groupEngine/shifted_multiplier': 17, 'groupEngine/shifted_multiplier_1': 17}.

The scaling-engine total includes the group-index calculation, not just the
exact scaling multipliers. DSP48E2 cells were counted by exact REF_NAME and
cross-checked against utilization reports; broad DSP* patterns can overcount
subcomponents. Baseline checkpoint SHA256 matches the committed archive:
5a921bcb1b2c492150c09bfde9e3d38d3ba3c05d33e74dd0dbf187c706aa53df.
The reused baseline has GROUP_SCALE_YS=4-equivalent full-row parallel scaling.
Its source revision is 62b02896b6c5cf5b8fbd36e62fc776f331a77c74;
its synthesis was not rerun. Original archived reports remain unchanged.

No placement, routing, bitstream, cycle benchmark or functional simulation was
run. This experiment establishes resource counts, not timing, power or numerical
correctness for the new serialization mode. Preliminary synthesis timing/power
files produced by the supplied flow are not implementation measurements.

`resources.csv` contains the measured comparison. `reports.tar.gz` contains
original completed synthesis logs and utilization, hierarchical utilization,
exact DSP48E2 paths for baseline and variant, RTL-instance checks, configuration
overlays, source hashes/revision, exact executed commands, runner and flow scripts.
No generated binaries, checkpoints, JARs or netlists are committed. Original
artifacts remain at `/home/kioannou/artifacts/lumixed_group_scale_ys2_synth_20261125/`.
The incomplete first attempt is retained locally and is not part of this archive.

Reproduce the full synthesis-only experiment at the recorded repository revision:

```sh
cd /home/kioannou/artifacts/lumixed_group_scale_ys2_synth_20261125
python3 run.py
/tools/Xilinx/Vivado/2023.2/bin/vivado -mode batch -source baseline_hierarchy.tcl
python3 summarize.py
```

The local package/toolchain paths are recorded in run.py. finish.py resumes
from verified generated RTL and was used for the detached retry; it performs
synthesis and report-only checkpoint inspection, never place_design/route_design.

The baseline is reused from the existing
[archived scale-fix synthesis](../Synthesis_Scale_Fix/README.md), not overwritten.
Reproduction requires the recorded Chipyard revision and local toolchain/package
paths; tool binaries and cached JARs are not bundled. The supplied synthesis flow
is unchanged; hierarchy inspection only opens synthesis checkpoints for reports.
