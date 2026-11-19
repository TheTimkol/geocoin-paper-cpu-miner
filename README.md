# GeoCoin Paper Miner

A Paper plugin for operator-controlled GeoCoin mining using CPU threads inside the Paper server process and, optionally, the official GeoCoin GPU miner as a managed child process. The GPU backend and device can be selected from Minecraft chat. Mining never starts automatically.

## How it works

1. An operator configures a GPU backend and device in chat, or leaves GPU mining disabled for CPU-only mining.
2. `/geocoin start` fetches a block template using the configured miner-role JSON-RPC account and starts the Java CPU workers. If a GPU device is selected and enabled, it also launches `geocoin-gpu-miner` with the same node, reward address, backend, and device.
3. CPU workers search nonce values with double SHA-256 in the Paper JVM. The GPU miner performs proof-of-work on the selected device.
4. Both miners submit candidate blocks to the GeoCoin node. `/geocoin status` reports CPU and GPU state and their recent rates.
5. `/geocoin stop` stops CPU workers and asks the GPU process to exit. Paper shutdown also stops the GPU process.

Mining uses the Paper host's computing resources and may affect Minecraft tick time, heat, and power use. Start with a small CPU thread count and observe the server before increasing it.

## GPU selection in chat

Configure `gpu.executable` in `plugins/GeoCoinMinecraft/config.yml` to point to the official `geocoin-gpu-miner` binary. Example for Windows:

```yaml
gpu:
  executable: "C:/GeoCoin/bin/geocoin-gpu-miner.exe"
```

Then restart Paper and run these commands as an operator:

```text
/geocoin gpu backends
/geocoin gpu backend opencl
/geocoin gpu devices
/geocoin gpu device 0
/geocoin gpu status
/geocoin start
```

Selecting a backend clears the previous device selection and scans for compatible devices. Choose a device ID from the list. `amd` selects the GeoCoin miner's HIP backend; on AMD hardware, OpenCL may also be available. `cuda` requires a compatible NVIDIA CUDA setup. Device IDs are backend-local.

Other commands:

- `/geocoin gpu devices` — scan the selected backend again.
- `/geocoin gpu enable` and `/geocoin gpu disable` — toggle GPU use for the next start. Selection and backend changes require mining to be stopped.
- `/geocoin gpu status` — show selected backend/device and GPU process state.
- `/geocoin start`, `/geocoin stop`, `/geocoin status` — manage the combined miner.

Available GPU devices depend on the installed driver/runtime and the official miner build. A backend can be selected even if the current machine has no matching device; the scan will explain that none were found.

## Console output

Routine CPU template-refresh messages are disabled by default. Startup, stop, RPC errors, GPU errors, and accepted blocks remain visible. Set `logging.template-refresh: true` to show periodic template details. GPU statistics are read quietly and shown in `/geocoin status`.

## Safety and operation

Run this only on a server and hardware you own or are authorized to use. Tell server users or administrators when mining uses shared resources. Mining starts only after an operator explicitly runs `/geocoin start`.

## Requirements

- Paper 26.3 build 255 (or a compatible Paper build)
- Java 25
- Maven 3.9 or newer to build
- A running GeoCoin node with miner-role JSON-RPC enabled
- For GPU mining, the official `geocoin-gpu-miner` build and a compatible GPU driver/runtime

## Build

```powershell
mvn clean package -DskipTests
```

The plugin JAR is written to `target/GeoCoinMinecraft.jar`. Paper provides Gson at runtime; it is not bundled in the JAR.

## Install and configure

1. Copy `target/GeoCoinMinecraft.jar` into the Paper server's `plugins` directory.
2. Start Paper once to create `plugins/GeoCoinMinecraft/config.yml`, then stop the server.
3. Configure the node, reward address, worker count, and GPU binary path:

```yaml
node:
  url: "http://127.0.0.1:3033/"
  username: "minecraft-miner"
  password-file: "rpc-miner.pass"
mining:
  address: "YOUR_GEOCOIN_ADDRESS"
  threads: 1
gpu:
  executable: "C:/GeoCoin/bin/geocoin-gpu-miner.exe"
  backend: "opencl"
  device: -1
  enabled: false
logging:
  template-refresh: false
```

4. Put the miner-role RPC password on the first line of `plugins/GeoCoinMinecraft/rpc-miner.pass`. Keep this file private and out of version control. Relative paths are resolved from the plugin's data folder.
5. Start Paper. Select and enable a GPU from chat if desired, then run `/geocoin start` when ready.

Use HTTP only when the node is on the same computer or another loopback address. For a remote node, use HTTPS. Do not expose the node's raw HTTP RPC port directly to the internet. Prefer a private VPN; otherwise terminate TLS at a trusted reverse proxy and restrict access to the Paper host. Create a dedicated miner-role RPC account and never put the admin RPC password in this plugin.

The plugin rejects remote plain HTTP RPC URLs and allows HTTP only for loopback hosts. It does not start mining automatically on server startup.

## Permission

`geocoin.admin` manages CPU/GPU settings and mining commands. Operators have this permission by default.

## Version

Version 1.1.0 adds configurable GPU backend/device selection while retaining CPU-only mode.

## Related project

This plugin integrates with [NoemaCrypt/GeoCoin](https://github.com/NoemaCrypt/GeoCoin), which provides the node, RPC interface, and GPU miner.

## License

MIT. See [LICENSE](LICENSE).
