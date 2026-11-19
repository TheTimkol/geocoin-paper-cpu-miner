# GeoCoin Paper Miner

A Paper plugin for operator-controlled GeoCoin mining. CPU mining runs inside the Paper process. OpenCL GPU mining runs inside the Paper JVM through JOCL and a kernel bundled in the plugin JAR. CUDA and AMD HIP remain available through the official GeoCoin GPU miner as managed child processes. Mining never starts automatically.

## How it works

1. An operator configures a GPU backend and device in chat, or leaves GPU mining disabled for CPU-only mining.
2. `/geocoin start` fetches a block template using the configured miner-role JSON-RPC account and starts the Java CPU workers. If OpenCL is selected and enabled, it also starts OpenCL work in a Java worker thread in the Paper JVM. CUDA and HIP start the official GPU miner binary.
3. CPU workers and the OpenCL kernel search nonce values using GeoCoin's double SHA-256 proof-of-work.
4. Both miners submit candidate blocks to the GeoCoin node. `/geocoin status` reports CPU and GPU state and their recent rates.
5. `/geocoin stop` stops CPU workers and cancels OpenCL work, or asks the native GPU process to exit. Paper shutdown also stops mining.

Mining uses the Paper host's computing resources and may affect Minecraft tick time, heat, and power use. Start with a small CPU thread count and observe the server before increasing it.

## GPU selection in chat

OpenCL does not need `geocoin-gpu-miner.exe`; the bundled kernel runs in the plugin JVM. The host still needs a GPU driver with an OpenCL runtime. The `gpu.executable` path is only used by CUDA and AMD HIP. Example for those backends on Windows:

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

Selecting a backend clears the previous device selection and scans for compatible devices. Choose a device ID from the list. OpenCL computes in the plugin JVM; CUDA and AMD use the official native miner. Device IDs are backend-local.

Other commands:

- `/geocoin gpu devices` — scan the selected backend again.
- `/geocoin gpu enable` — enable GPU mining for the next start. `/geocoin gpu disable` cancels GPU work; CPU mining can continue. Selection and backend changes require mining to be stopped.
- `/geocoin gpu status` — show selected backend/device and whether in-JVM OpenCL or the native miner is active.
- `/geocoin start`, `/geocoin stop`, `/geocoin status` — manage the combined miner.

## Resource limits

Set the CPU duty cycle and the in-JVM OpenCL GPU duty cycle independently from chat:

```text
/geocoin limit cpu 50
/geocoin limit gpu 35
/geocoin limit status
```

Limits accept whole percentages from 0 to 100 and apply while mining is running. `0` pauses work on that device; `100` allows continuous work. CPU percentage is the average duty cycle per configured mining worker. The GPU percentage controls OpenCL kernel pacing. The plugin cannot throttle CUDA or AMD HIP child processes; when GPU mining is enabled on those backends, set `limits.gpu-percent` to `100`.

The same defaults can be stored in `plugins/GeoCoinMinecraft/config.yml`:

```yaml
limits:
  cpu-percent: 100
  gpu-percent: 100
```

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
- For OpenCL GPU mining, a GPU driver/runtime that provides OpenCL
- For CUDA or AMD HIP mining, the official `geocoin-gpu-miner` build and compatible GPU drivers

## Build

```powershell
mvn clean package -DskipTests
```

The plugin JAR is written to `target/GeoCoinMinecraft.jar`. JOCL and its supported native bindings are bundled; the machine still needs a vendor OpenCL driver/runtime. Paper provides Gson at runtime.

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
limits:
  cpu-percent: 100
  gpu-percent: 100
gpu:
  # Required only for cuda and amd (HIP); OpenCL runs in the Paper JVM.
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

Version 1.3.0 adds configurable CPU and in-process OpenCL duty-cycle limits. CUDA and AMD HIP still use the official GeoCoin GPU miner executable and cannot be throttled by this plugin.

## Related project

This plugin integrates with [NoemaCrypt/GeoCoin](https://github.com/NoemaCrypt/GeoCoin), which provides the node, RPC interface, and optional native CUDA/HIP miner. OpenCL bindings are provided by [JOCL](https://github.com/gpu/JOCL).

## License

The plugin is MIT licensed. JOCL is separately MIT licensed; its copyright and
license notice is included in [JOCL-LICENSE.txt](JOCL-LICENSE.txt).
