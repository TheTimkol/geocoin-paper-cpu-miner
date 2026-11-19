# GeoCoin Paper CPU Miner

A CPU-only GeoCoin mining plugin for Paper. It searches for GeoCoin proof-of-work using worker threads inside the Paper server's Java process and submits valid blocks to a GeoCoin node over the miner-role JSON-RPC API. It does not launch another miner process and contains no GPU mining code.

## Related project

This plugin is a Paper integration for [NoemaCrypt/GeoCoin](https://github.com/NoemaCrypt/GeoCoin), which provides the GeoCoin node and JSON-RPC interface. The plugin contains only the Paper-side CPU miner; run and configure the node separately.

## How it works

1. An operator starts mining with `/geocoin start`. Mining is stopped when Paper loads the plugin and never starts automatically.
2. The plugin requests a block template from the configured GeoCoin node using the dedicated miner-role account.
3. It splits nonce values among the configured Java worker threads. Each worker hashes the 124-byte block header twice with SHA-256 and compares the result with the target from the template.
4. If there is no solution after about 10 seconds, the plugin fetches a fresh template. When a worker finds a valid nonce, the plugin submits the block to the node.
5. `/geocoin stop` interrupts the workers. `/geocoin status` shows whether mining is active and the approximate recent hashrate.

All hashing runs on the Paper host's CPU. More worker threads use more CPU and can affect Minecraft tick performance. Start with one thread and monitor the server and machine temperatures.

## Console output

Routine template-refresh lines are disabled by default because templates refresh every 10 seconds. The plugin still logs startup, stop, RPC/mining errors, and accepted blocks. `/geocoin status` remains available to operators. Set `logging.template-refresh: true` in `config.yml` to enable the periodic detail lines.

## Safety and operation

Run this only on a server and hardware you own or are authorized to use. Tell server users or administrators when mining uses shared resources. Mining starts only after an operator explicitly runs `/geocoin start`.

## Requirements

- Paper 26.3 build 255 (or a compatible Paper build)
- Java 25
- Maven 3.9 or newer to build
- A running GeoCoin node with miner-role JSON-RPC enabled

## Build

```powershell
mvn package -DskipTests
```

The plugin JAR is written to `target/GeoCoinMinecraft.jar`. Paper provides Gson at runtime; it is not bundled in the JAR.

## Install and configure

1. Copy `target/GeoCoinMinecraft.jar` into the Paper server's `plugins` directory.
2. Start Paper once to create `plugins/GeoCoinMinecraft/config.yml`, then stop the server.
3. Edit `config.yml`:

```yaml
node:
  url: "http://127.0.0.1:3033/"
  username: "minecraft-miner"
  password-file: "rpc-miner.pass"
mining:
  address: "YOUR_GEOCOIN_ADDRESS"
  threads: 1
logging:
  template-refresh: false
```

Use HTTP only when the node is on the same computer or another loopback address. For a remote node, use HTTPS. Do not expose the node's raw HTTP RPC port directly to the internet. Prefer a private VPN; otherwise terminate TLS at a trusted reverse proxy and restrict access to the Paper host. Create a dedicated miner-role RPC account and never put the admin RPC password in this plugin.

4. Put the miner-role password on the first line of `plugins/GeoCoinMinecraft/rpc-miner.pass`. Keep this file private and out of version control. The default relative password path is resolved from the plugin's data folder.
5. Start Paper. As an operator, run `/geocoin status`, then `/geocoin start` when you are ready to mine. Run `/geocoin stop` to stop.

The plugin rejects remote plain HTTP RPC URLs and allows HTTP only for loopback hosts. It does not start mining automatically on server startup.

## Commands and permission

- `/geocoin start`
- `/geocoin stop`
- `/geocoin status`
- Permission: `geocoin.admin` (operators by default)

## Version

Version 1.0.0. This first release implements CPU-only mining.

## License

MIT. See [LICENSE](LICENSE).
