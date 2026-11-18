# LUMIXED

LUT-based mixed-precision matrix multiplication accelerator for quantized DNN inference.

TODO: document the accelerator and its Chipyard 1.15.0 integration.

- Imported from source revision `7b6e6c8c9f8484693180fe4b5806f4a58ba6984d`.
- Add the LUMIXED SBT project and Chipyard dependency.
- Add a LUMIXED Rocket configuration.
- Check the imported software scripts against the current parameter names and workspace.
- Compile and elaborate LUMIXED, then run exact-output Verilator checks.
- Record the supported configurations and validation results.

The folder and internal names have been changed to LUMIXED. The RTL behavior has not been intentionally changed. Chipyard 1.15.0 has been verified with Gemmini; LUMIXED has not yet been built or tested here.
