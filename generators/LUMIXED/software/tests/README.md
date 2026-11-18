# LUMIXED software tests

The test folder keeps one build script and one single-case runner.

| File | Purpose |
|---|---|
| `src/Linear-sw.c` | Base RoCC test, accelerator invocation, and exact output comparison |
| `src/build.sh` | Compile the C test; matches format flags to `Config.scala` |
| `src/compiler.h`, `src/rocc.h` | Required C and RoCC instruction helpers |
| `src/prepare_test_vectors.py` | Generate deterministic integer inputs, packed/transposed weights, and Python matrix-product reference |
| `src/run_param_test.sh` | Configure one case, build, run Verilator, save a log, and require a correctness PASS |

## Python reference workflow

After activating the Chipyard environment and integrating the LUMIXED hardware configuration:

```bash
cd generators/LUMIXED/software/tests/src
./run_param_test.sh 1 64 64 16 4
```

Python creates `lumixed_test_vectors.h` containing inputs, packed weights, and expected outputs. The C program runs the accelerator and compares every hardware output with that Python reference. A successful simulator exit alone is insufficient: the runner also requires the C correctness PASS. Logs are saved under `src/Log/`.

The runner reads `banks_per_row`, `parallel_rows`, and `table_capacity_factor` from the current Scala configuration. It updates the dimensions, precision, and geometry defines in `Linear-sw.c` in place. Rebuilds use one job by default; override with `BUILD_JOBS`. Use `REBUILD=0` to reuse an existing matching simulator.

To generate a header separately:

```bash
python3 prepare_test_vectors.py 1 64 64 16 4 --seed 1 --output lumixed_test_vectors.h
```

`FAST_VECTORS=0 ./run_param_test.sh 1 64 64 16 4` uses the C reference on the RISC-V CPU instead. Python vectors support signed integer activations (8/16 bits) and weights (2/4/8 bits); they are not an FP16 reference. The current C harness requires the packed weight matrix to occupy whole bytes; the runner rejects partial-byte shapes. Generated binaries, objects, headers, and logs are ignored by Git.

TODO: connect LUMIXED to Chipyard 1.15.0 and validate these flows against its simulator. No LUMIXED hardware simulation has been run in this workspace.
