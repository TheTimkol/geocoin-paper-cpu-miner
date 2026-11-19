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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class GeoCoinMinecraftPlugin extends JavaPlugin {
    private static final int HEADER_BYTES = 124;
    private static final int NONCE_OFFSET = 116;
    private static final long TEMPLATE_MILLIS = 10_000;
    private final AtomicBoolean stopRequested = new AtomicBoolean(true);
    private volatile Thread controller;
    private volatile long hashesPerSecond;
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
        if (args.length != 1) return false;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> startMiner(sender);
            case "stop" -> stopMiner(sender);
            case "status" -> sender.sendMessage(statusMessage());
            default -> { return false; }
        }
        return true;
    }

    private synchronized void startMiner(CommandSender sender) {
        if (isCpuRunning()) {
            sender.sendMessage(ChatColor.YELLOW + "GeoCoin CPU miner is already running.");
            return;
        }
        try {
            MinerSettings settings = readSettings();
            stopRequested.set(false);
            Thread worker = new Thread(() -> mineForever(settings), "geocoin-paper-miner");
            worker.setDaemon(true);
            controller = worker;
            worker.start();
            sender.sendMessage(ChatColor.GREEN + "GeoCoin CPU miner started.");
            getLogger().info("GeoCoin CPU mining started inside Paper (" + settings.threads + " worker threads).");
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
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return new MinerSettings(uri, username, pass, address, threads, http);
    }

    private boolean isLoopback(String host) {
        try { return InetAddress.getByName(host).isLoopbackAddress(); }
        catch (Exception ignored) { return false; }
    }

    private boolean isCpuRunning() {
        Thread current = controller;
        return current != null && current.isAlive() && !stopRequested.get();
    }

    private String statusMessage() {
        return (isCpuRunning() ? ChatColor.GREEN : ChatColor.YELLOW) +
                "GeoCoin CPU mining: " + (isCpuRunning() ? "running (~" + hashesPerSecond + " H/s)" : "stopped") + ".";
    }

    private void stopMiner(CommandSender sender) {
        stopRequested.set(true);
        Thread current = controller;
        boolean cpuWasRunning = current != null && current.isAlive();
        if (!cpuWasRunning) {
            sender.sendMessage(ChatColor.YELLOW + "GeoCoin miner is already stopped.");
            return;
        }
        if (cpuWasRunning) current.interrupt();
        sender.sendMessage(ChatColor.GREEN + "Stop requested for GeoCoin CPU miner.");
        getLogger().info("In-process GeoCoin mining stop requested.");
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
    }

    private record MinerSettings(URI url, String username, String password, String address, int threads,
                                 HttpClient http) { }
    private record MiningResult(Long nonce, long hashes, long elapsedMillis) { }
}
