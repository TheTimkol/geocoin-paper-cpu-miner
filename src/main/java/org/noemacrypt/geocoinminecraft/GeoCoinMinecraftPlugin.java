package org.noemacrypt.geocoinminecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

public final class GeoCoinMinecraftPlugin extends JavaPlugin {
    private static final int HEADER_BYTES = 124;
    private static final int NONCE_OFFSET = 116;
    private static final long TEMPLATE_MILLIS = 10_000;
    private static final Pattern DEVICE_LINE = Pattern.compile("^\\s*(opencl|cuda|hip):(\\d+)\\s+(.+?)\\s*$", Pattern.CASE_INSENSITIVE);
    private final AtomicBoolean stopRequested = new AtomicBoolean(true);
    private final AtomicBoolean gpuStopPending = new AtomicBoolean(false);
    private volatile Thread controller;
    private volatile long hashesPerSecond;
    private volatile Process gpuMiner;
    private volatile long gpuHashesPerSecond;
    private volatile boolean logTemplateRefresh;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        logTemplateRefresh = getConfig().getBoolean("logging.template-refresh", false);
        getLogger().info("Loaded. Mining is stopped; an operator can run /geocoin start.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("geocoin.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return true;
        }
        if (args.length == 0) return false;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> startMiner(sender);
            case "stop" -> stopMiner(sender);
            case "status" -> sender.sendMessage(statusMessage());
            case "gpu" -> handleGpuCommand(sender, args);
            default -> { return false; }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("geocoin.admin")) return List.of();
        if (args.length == 1) return matching(List.of("start", "stop", "status", "gpu"), args[0]);
        if (!args[0].equalsIgnoreCase("gpu")) return List.of();
        if (args.length == 2) return matching(List.of("backends", "backend", "devices", "device", "enable", "disable", "status"), args[1]);
        if (args.length == 3 && args[1].equalsIgnoreCase("backend"))
            return matching(List.of("opencl", "cuda", "amd"), args[2]);
        if (args.length == 3 && args[1].equalsIgnoreCase("device"))
            return matching(List.of("0", "1", "2", "3"), args[2]);
        return List.of();
    }

    private List<String> matching(List<String> options, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(normalized)).toList();
    }

    private void handleGpuCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "GPU: /geocoin gpu <backends|backend|devices|device|enable|disable|status>");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "backends" -> sender.sendMessage(ChatColor.GREEN + "GPU backends: opencl, cuda, amd (AMD HIP). Choose one with /geocoin gpu backend <name>.");
            case "backend" -> selectGpuBackend(sender, args);
            case "devices" -> listGpuDevices(sender);
            case "device" -> selectGpuDevice(sender, args);
            case "enable" -> setGpuEnabled(sender, true);
            case "disable" -> setGpuEnabled(sender, false);
            case "status" -> sender.sendMessage(gpuStatusMessage());
            default -> sender.sendMessage(ChatColor.RED + "Unknown GPU option. Use /geocoin gpu backends.");
        }
    }

    private void selectGpuBackend(CommandSender sender, String[] args) {
        if (args.length == 2) {
            sender.sendMessage(ChatColor.YELLOW + "Choose: /geocoin gpu backend opencl, cuda, or amd (HIP).");
            return;
        }
        if (args.length != 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /geocoin gpu backend <opencl|cuda|amd>");
            return;
        }
        if (isMiningActive()) {
            sender.sendMessage(ChatColor.RED + "Stop mining before changing the GPU backend.");
            return;
        }
        String backend = normalizeBackend(args[2]);
        if (backend == null) {
            sender.sendMessage(ChatColor.RED + "Unknown backend. Choose opencl, cuda, or amd (HIP).");
            return;
        }
        getConfig().set("gpu.backend", backend);
        getConfig().set("gpu.device", -1);
        getConfig().set("gpu.enabled", false);
        saveConfig();
        sender.sendMessage(ChatColor.GREEN + "Backend set to " + displayBackend(backend) + ". Looking for compatible GPUs...");
        scanGpuDevices(sender, backend, null);
    }

    private String normalizeBackend(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "opencl" -> "opencl";
            case "cuda" -> "cuda";
            case "amd", "hip" -> "hip";
            default -> null;
        };
    }

    private String displayBackend(String backend) {
        return "hip".equalsIgnoreCase(backend) ? "AMD HIP" : backend.toUpperCase(Locale.ROOT);
    }

    private void listGpuDevices(CommandSender sender) {
        String backend = normalizeBackend(getConfig().getString("gpu.backend", "opencl"));
        if (backend == null) {
            sender.sendMessage(ChatColor.RED + "Invalid gpu.backend in config.yml. Choose opencl, cuda, or amd (HIP).");
            return;
        }
        scanGpuDevices(sender, backend, null);
    }

    private void selectGpuDevice(CommandSender sender, String[] args) {
        if (args.length != 3) {
            sender.sendMessage(ChatColor.YELLOW + "First choose a backend, then run /geocoin gpu devices and /geocoin gpu device <id>.");
            return;
        }
        if (isMiningActive()) {
            sender.sendMessage(ChatColor.RED + "Stop mining before changing the GPU device.");
            return;
        }
        int requested;
        try { requested = Integer.parseInt(args[2]); }
        catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "GPU id must be a number shown by /geocoin gpu devices.");
            return;
        }
        if (requested < 0) {
            sender.sendMessage(ChatColor.RED + "GPU id must be zero or greater.");
            return;
        }
        String backend = normalizeBackend(getConfig().getString("gpu.backend", "opencl"));
        if (backend == null) {
            sender.sendMessage(ChatColor.RED + "Invalid gpu.backend in config.yml. Choose opencl, cuda, or amd (HIP).");
            return;
        }
        scanGpuDevices(sender, backend, requested);
    }

    private void scanGpuDevices(CommandSender sender, String backend, Integer selectId) {
        Path executable = resolveGpuExecutable();
        if (!Files.isRegularFile(executable)) {
            sender.sendMessage(ChatColor.RED + "GPU miner executable not found: " + executable);
            sender.sendMessage(ChatColor.YELLOW + "Set gpu.executable in plugins/GeoCoinMinecraft/config.yml, then restart Paper.");
            return;
        }
        sender.sendMessage(ChatColor.GRAY + "Scanning " + displayBackend(backend) + " devices...");
        Thread scan = new Thread(() -> {
            List<GpuDevice> devices;
            try { devices = discoverGpuDevices(executable, backend); }
            catch (Exception e) {
                try {
                    getServer().getScheduler().runTask(this, () -> {
                        if (isEnabled()) sender.sendMessage(ChatColor.RED + "GPU scan failed: " + e.getMessage());
                    });
                } catch (IllegalStateException ignored) { }
                return;
            }
            getServer().getScheduler().runTask(this, () -> {
                if (!isEnabled()) return;
                if (!backend.equalsIgnoreCase(getConfig().getString("gpu.backend", "opencl"))) {
                    sender.sendMessage(ChatColor.YELLOW + "GPU scan result ignored because the selected backend changed.");
                    return;
                }
                if (devices.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "No " + displayBackend(backend) + " devices detected. Check GPU driver/runtime and gpu.executable.");
                    return;
                }
                sender.sendMessage(ChatColor.GREEN + "Available " + displayBackend(backend) + " devices:");
                for (GpuDevice device : devices) sender.sendMessage(ChatColor.GRAY + "  " + device.id + ": " + device.name);
                if (selectId == null) {
                    sender.sendMessage(ChatColor.YELLOW + "Select a device with /geocoin gpu device <id>.");
                    return;
                }
                GpuDevice selected = devices.stream().filter(device -> device.id == selectId).findFirst().orElse(null);
                if (selected == null) {
                    sender.sendMessage(ChatColor.RED + "GPU id " + selectId + " was not found for " + displayBackend(backend) + ". Choose an id from the list above.");
                    return;
                }
                getConfig().set("gpu.device", selected.id);
                getConfig().set("gpu.enabled", true);
                saveConfig();
                sender.sendMessage(ChatColor.GREEN + "Selected " + displayBackend(backend) + " GPU " + selected.id + " (" + selected.name + "). GPU mining will start with /geocoin start.");
            });
        }, "geocoin-gpu-device-scan");
        scan.setDaemon(true);
        scan.start();
    }

    private List<GpuDevice> discoverGpuDevices(Path executable, String backend) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(executable.toString(), "--list-devices").redirectErrorStream(true).start();
        List<String> lines = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) lines.add(line);
            } catch (IOException ignored) { }
        }, "geocoin-gpu-device-output");
        reader.setDaemon(true);
        reader.start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("device detection timed out after 20 seconds");
        }
        reader.join(2000);
        if (process.exitValue() != 0) throw new IOException("device detector exited with code " + process.exitValue() + ": " + String.join(" | ", lines));
        List<GpuDevice> devices = new ArrayList<>();
        for (String line : lines) {
            Matcher match = DEVICE_LINE.matcher(line);
            if (match.matches() && match.group(1).equalsIgnoreCase(backend)) {
                devices.add(new GpuDevice(Integer.parseInt(match.group(2)), match.group(3).trim()));
            }
        }
        return devices;
    }

    private Path resolveGpuExecutable() {
        String configured = getConfig().getString("gpu.executable", "geocoin-gpu-miner.exe").trim();
        Path executable = Path.of(configured);
        if (!executable.isAbsolute()) executable = getDataFolder().toPath().resolve(executable);
        return executable.normalize().toAbsolutePath();
    }

    private void setGpuEnabled(CommandSender sender, boolean enabled) {
        if (enabled && isMiningActive() && !isGpuRunning()) {
            sender.sendMessage(ChatColor.RED + "Stop CPU mining before enabling GPU mining.");
            return;
        }
        if (enabled && getConfig().getInt("gpu.device", -1) < 0) {
            sender.sendMessage(ChatColor.RED + "Choose a backend and GPU first: /geocoin gpu backend <opencl|cuda|amd>, then /geocoin gpu devices.");
            return;
        }
        if (enabled && !Files.isRegularFile(resolveGpuExecutable())) {
            sender.sendMessage(ChatColor.RED + "GPU miner executable not found: " + resolveGpuExecutable());
            return;
        }
        getConfig().set("gpu.enabled", enabled);
        saveConfig();
        if (!enabled) requestGpuStop();
        sender.sendMessage(ChatColor.GREEN + "GPU mining " + (enabled ? "enabled" : "disabled") + ".");
    }

    private synchronized void startMiner(CommandSender sender) {
        if (isCpuRunning() || isGpuRunning()) {
            sender.sendMessage(ChatColor.YELLOW + "GeoCoin miner is already running.");
            return;
        }
        try {
            MinerSettings settings = readSettings();
            if (settings.gpu.enabled) {
                try {
                    startGpuMiner(settings);
                } catch (Exception e) {
                    getLogger().severe("Could not start GPU miner; CPU mining will continue: " + e.getMessage());
                    sender.sendMessage(ChatColor.RED + "GPU miner could not start; CPU mining will continue. See server console.");
                }
            }
            stopRequested.set(false);
            Thread worker = new Thread(() -> mineForever(settings), "geocoin-paper-miner");
            worker.setDaemon(true);
            controller = worker;
            worker.start();
            sender.sendMessage(ChatColor.GREEN + "GeoCoin CPU miner started" + (isGpuRunning() ? " with GPU." : "."));
            getLogger().info("GeoCoin CPU mining started inside Paper (" + settings.threads + " worker threads)." + (isGpuRunning() ? " GPU miner process is running." : ""));
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "Could not start miner: " + e.getMessage());
            getLogger().severe("Could not start GeoCoin miner: " + e.getMessage());
        }
    }

    private MinerSettings readSettings() throws IOException {
        String urlText = getConfig().getString("node.url", "").trim();
        String username = getConfig().getString("node.username", "").trim();
        String passName = getConfig().getString("node.password-file", "rpc-miner.pass").trim();
        String address = getConfig().getString("mining.address", "").trim();
        int threads = getConfig().getInt("mining.threads", 1);
        boolean gpuEnabled = getConfig().getBoolean("gpu.enabled", false);
        String backend = normalizeBackend(getConfig().getString("gpu.backend", "opencl"));
        int device = getConfig().getInt("gpu.device", -1);
        if (urlText.isBlank() || username.isBlank() || passName.isBlank() || address.isBlank())
            throw new IllegalArgumentException("set node.url, node.username, node.password-file, and mining.address in config.yml");
        if (threads < 1 || threads > 256) throw new IllegalArgumentException("mining.threads must be between 1 and 256");
        URI uri = URI.create(urlText);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean localHttp = "http".equalsIgnoreCase(scheme) && host != null && isLoopback(host);
        if (!"https".equalsIgnoreCase(scheme) && !localHttp)
            throw new IllegalArgumentException("remote node RPC must use HTTPS; plain HTTP is allowed only for localhost");
        if (host == null || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("node.url must be a valid RPC URL without embedded credentials or a fragment");
        Path password = Path.of(passName);
        if (!password.isAbsolute()) password = getDataFolder().toPath().resolve(password);
        password = password.normalize().toAbsolutePath();
        if (!Files.isRegularFile(password) || !Files.isReadable(password))
            throw new IllegalArgumentException("password file is missing or unreadable: " + password);
        String pass = Files.readString(password, StandardCharsets.UTF_8).lines().findFirst().orElse("").trim();
        if (pass.isEmpty()) throw new IllegalArgumentException("password file is empty");
        if (backend == null) throw new IllegalArgumentException("gpu.backend must be opencl, cuda, or amd (HIP)");
        Path gpuExecutable = resolveGpuExecutable();
        if (gpuEnabled && !Files.isRegularFile(gpuExecutable))
            throw new IllegalArgumentException("GPU miner executable not found: " + gpuExecutable);
        if (gpuEnabled && device < 0) throw new IllegalArgumentException("choose a GPU with /geocoin gpu devices before starting mining");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return new MinerSettings(uri, username, pass, address, threads, http, password.toString(),
                new GpuSettings(gpuEnabled, gpuExecutable, backend, device));
    }

    private boolean isLoopback(String host) {
        try { return InetAddress.getByName(host).isLoopbackAddress(); }
        catch (Exception ignored) { return false; }
    }

    private boolean isCpuRunning() {
        Thread current = controller;
        return current != null && current.isAlive() && !stopRequested.get();
    }

    private boolean isGpuRunning() {
        Process process = gpuMiner;
        return process != null && process.isAlive();
    }

    private boolean isMiningActive() { return isCpuRunning() || isGpuRunning(); }

    private String statusMessage() {
        String cpu = isCpuRunning() ? "CPU running (~" + hashesPerSecond + " H/s)" : "CPU stopped";
        String backend = getConfig().getString("gpu.backend", "opencl");
        String gpu = isGpuRunning() ? displayBackend(backend) + " GPU " + getConfig().getInt("gpu.device", -1) +
                " running (~" + gpuHashesPerSecond + " H/s)" : "GPU " + (getConfig().getBoolean("gpu.enabled", false) ? "enabled, stopped" : "disabled");
        return (isMiningActive() ? ChatColor.GREEN : ChatColor.YELLOW) + "GeoCoin mining: " + cpu + "; " + gpu + ".";
    }

    private String gpuStatusMessage() {
        String backend = getConfig().getString("gpu.backend", "opencl");
        int device = getConfig().getInt("gpu.device", -1);
        String selected = device < 0 ? "no GPU selected" : displayBackend(backend) + " GPU " + device;
        String process = isGpuRunning() ? "running (~" + gpuHashesPerSecond + " H/s)" : "stopped";
        return ChatColor.GREEN + "GPU mining: " + selected + "; " + (getConfig().getBoolean("gpu.enabled", false) ? "enabled" : "disabled") + "; process " + process + ".";
    }

    private void stopMiner(CommandSender sender) {
        Thread current = controller;
        boolean cpuWasRunning = current != null && current.isAlive();
        boolean gpuWasRunning = isGpuRunning();
        if (!cpuWasRunning && !gpuWasRunning) {
            sender.sendMessage(ChatColor.YELLOW + "GeoCoin miner is already stopped.");
            return;
        }
        stopRequested.set(true);
        if (cpuWasRunning) current.interrupt();
        requestGpuStop();
        sender.sendMessage(ChatColor.GREEN + "Stop requested for GeoCoin CPU and GPU miners.");
        getLogger().info("GeoCoin CPU/GPU mining stop requested.");
    }

    private void startGpuMiner(MinerSettings settings) throws IOException {
        List<String> args = new ArrayList<>();
        args.add(settings.gpu.executable.toString());
        args.add("--url"); args.add(settings.url.toString());
        args.add("--rpc-user"); args.add(settings.username);
        args.add("--rpc-pass-file"); args.add(settings.passwordFile);
        args.add("--address"); args.add(settings.address);
        args.add("--backend"); args.add(settings.gpu.backend);
        args.add("--devices"); args.add(Integer.toString(settings.gpu.device));
        args.add("--continuous");
        args.add("--control-stdin");
        args.add("--stats-json");
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        gpuMiner = process;
        gpuHashesPerSecond = 0;
        gpuStopPending.set(false);
        Thread output = new Thread(() -> relayGpuOutput(process), "geocoin-gpu-output");
        output.setDaemon(true);
        output.start();
        getLogger().info("Started GeoCoin GPU miner (" + displayBackend(settings.gpu.backend) + " device " + settings.gpu.device + ").");
    }

    private void relayGpuOutput(Process process) {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                try {
                    JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                    String type = event.has("event") ? event.get("event").getAsString() : "";
                    if ("status".equals(type) && event.has("hashrate")) {
                        gpuHashesPerSecond = event.get("hashrate").getAsLong();
                    } else if ("block_accepted".equals(type)) {
                        getLogger().info("[GeoCoin GPU] Accepted block at height " + event.get("height").getAsLong() + ".");
                    } else if ("error".equals(type)) {
                        getLogger().severe("[GeoCoin GPU] " + event.get("message").getAsString());
                    } else if (!"status".equals(type) && !line.isBlank()) {
                        getLogger().info("[GeoCoin GPU] " + line);
                    }
                } catch (RuntimeException ignored) {
                    getLogger().info("[GeoCoin GPU] " + line);
                }
            }
        } catch (IOException e) {
            if (process.isAlive()) getLogger().warning("Could not read GPU miner output: " + e.getMessage());
        } finally {
            try {
                int exitCode = process.waitFor();
                if (gpuMiner == process) {
                    gpuMiner = null;
                    gpuHashesPerSecond = 0;
                }
                if (isEnabled()) getLogger().info("GeoCoin GPU miner exited (code " + exitCode + ").");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void requestGpuStop() {
        Process process = gpuMiner;
        if (process == null || !process.isAlive() || !gpuStopPending.compareAndSet(false, true)) return;
        Thread stopper = new Thread(() -> stopGpuProcess(process), "geocoin-gpu-stop");
        stopper.setDaemon(true);
        stopper.start();
    }

    private void stopGpuProcess(Process process) {
        try {
            process.getOutputStream().write("stop\n".getBytes(StandardCharsets.US_ASCII));
            process.getOutputStream().flush();
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            }
        } catch (IOException e) {
            process.destroy();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } finally {
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
            gpuStopPending.set(false);
        }
    }

    private void mineForever(MinerSettings settings) {
        try {
            while (!stopRequested.get()) {
                JsonObject templateRequest = new JsonObject();
                templateRequest.addProperty("address", settings.address);
                JsonArray templateParams = new JsonArray();
                templateParams.add(templateRequest);
                JsonObject template = rpc(settings, "getblocktemplate", templateParams).getAsJsonObject();
                String blockHex = template.get("blockhex").getAsString();
                byte[] block = decodeHex(blockHex);
                if (block.length < HEADER_BYTES) throw new IllegalStateException("node returned a short block template");
                int bits = template.get("bits").getAsInt();
                long height = template.get("height").getAsLong();
                MiningResult result = mineTemplate(block, bits, settings.threads);
                hashesPerSecond = result.hashes * 1000L / Math.max(1, result.elapsedMillis);
                if (stopRequested.get()) break;
                if (result.nonce == null) {
                    if (logTemplateRefresh) {
                        getLogger().info("Template refreshed at height " + height + " (" + result.hashes + " hashes, ~" + hashesPerSecond + " H/s).");
                    }
                    continue;
                }
                ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN).putLong(NONCE_OFFSET, result.nonce);
                JsonArray submitArgs = new JsonArray();
                submitArgs.add(encodeHex(block));
                JsonElement submitResult = rpc(settings, "submitblock", submitArgs);
                if (submitResult.isJsonNull()) {
                    getLogger().info("Node accepted GeoCoin block at height " + height + " (nonce " + Long.toUnsignedString(result.nonce) + ", " + result.hashes + " hashes).");
                } else {
                    getLogger().warning("Node did not accept block at height " + height + ": " + submitResult);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!stopRequested.get()) getLogger().severe("GeoCoin miner stopped after RPC/mining error: " + e.getMessage());
        } finally {
            hashesPerSecond = 0;
            controller = null;
            stopRequested.set(true);
            requestGpuStop();
            getLogger().info("In-process GeoCoin mining stopped.");
        }
    }

    private MiningResult mineTemplate(byte[] block, int bits, int threads) throws InterruptedException {
        BigInteger target = targetFromCompact(bits);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Long> winner = new AtomicReference<>();
        AtomicLong totalHashes = new AtomicLong();
        long start = System.nanoTime();
        long deadline = start + TEMPLATE_MILLIS * 1_000_000L;
        List<Thread> workers = new ArrayList<>();
        for (int id = 0; id < threads; id++) {
            final int workerId = id;
            Thread worker = new Thread(() -> {
                byte[] candidate = block.clone();
                MessageDigest sha;
                try { sha = MessageDigest.getInstance("SHA-256"); }
                catch (NoSuchAlgorithmException e) { running.set(false); return; }
                long nonce = Integer.toUnsignedLong(workerId);
                long localHashes = 0;
                while (running.get() && !stopRequested.get() && System.nanoTime() < deadline) {
                    ByteBuffer.wrap(candidate).order(ByteOrder.LITTLE_ENDIAN).putLong(NONCE_OFFSET, nonce);
                    sha.update(candidate, 0, HEADER_BYTES);
                    byte[] digest = sha.digest(sha.digest());
                    localHashes++;
                    if (new BigInteger(1, digest).compareTo(target) <= 0) {
                        if (winner.compareAndSet(null, nonce)) running.set(false);
                        break;
                    }
                    nonce += threads;
                    if ((localHashes & 2047) == 0) {
                        totalHashes.addAndGet(2048);
                        localHashes = 0;
                    }
                }
                totalHashes.addAndGet(localHashes);
            }, "geocoin-pow-" + id);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
        while (System.nanoTime() < deadline && !stopRequested.get() && winner.get() == null) Thread.sleep(25);
        running.set(false);
        for (Thread worker : workers) worker.join(2000);
        long elapsed = (System.nanoTime() - start) / 1_000_000L;
        return new MiningResult(winner.get(), totalHashes.get(), elapsed);
    }

    private JsonElement rpc(MinerSettings settings, String method, JsonElement params) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("jsonrpc", "2.0");
        body.addProperty("id", 1);
        body.addProperty("method", method);
        body.add("params", params);
        String auth = Base64.getEncoder().encodeToString((settings.username + ":" + settings.password).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(settings.url).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Basic " + auth).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> response = settings.http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException("RPC HTTP status " + response.statusCode());
        JsonObject parsed = JsonParser.parseString(response.body()).getAsJsonObject();
        if (parsed.has("error") && !parsed.get("error").isJsonNull()) throw new IOException(method + " RPC error: " + parsed.get("error"));
        JsonElement value = parsed.get("result");
        if (value == null) throw new IOException(method + " returned no result field");
        return value;
    }

    private BigInteger targetFromCompact(int compact) {
        long bits = Integer.toUnsignedLong(compact);
        int exponent = (int) (bits >>> 24);
        long mantissa = bits & 0x007fffffL;
        if ((bits & 0x00800000L) != 0 || mantissa == 0) throw new IllegalArgumentException("invalid compact difficulty target");
        BigInteger target = BigInteger.valueOf(mantissa);
        return exponent <= 3 ? target.shiftRight(8 * (3 - exponent)) : target.shiftLeft(8 * (exponent - 3));
    }

    private byte[] decodeHex(String value) {
        if ((value.length() & 1) != 0) throw new IllegalArgumentException("invalid blockhex length");
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private String encodeHex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    @Override
    public void onDisable() {
        stopRequested.set(true);
        Thread current = controller;
        if (current != null) current.interrupt();
        Process process = gpuMiner;
        if (process != null && process.isAlive()) {
            try {
                process.getOutputStream().write("stop\n".getBytes(StandardCharsets.US_ASCII));
                process.getOutputStream().flush();
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (IOException e) {
                process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    private record MinerSettings(URI url, String username, String password, String address, int threads,
                                 HttpClient http, String passwordFile, GpuSettings gpu) { }
    private record GpuSettings(boolean enabled, Path executable, String backend, int device) { }
    private record GpuDevice(int id, String name) { }
    private record MiningResult(Long nonce, long hashes, long elapsedMillis) { }
}
