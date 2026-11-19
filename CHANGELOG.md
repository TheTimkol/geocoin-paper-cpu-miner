# Changelog

## 1.1.0 - 2026-11-19

- Added operator chat commands to select OpenCL, CUDA, or AMD HIP and enumerate/select a compatible GPU.
- Added managed GPU miner launch, status, and graceful stop alongside CPU mining.
- Persisted GPU selections in the plugin config; GPU mining remains opt-in.
- Added tab completion for command choices.

## 1.0.0 - 2026-11-19

- First public CPU-only release.
- Mining is started and stopped explicitly by an operator.
- Added miner-role RPC authentication, password-file configuration, and remote HTTPS enforcement.
- Routine template refresh messages are disabled by default; lifecycle, error, and accepted-block messages remain visible.
