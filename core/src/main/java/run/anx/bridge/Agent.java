package run.anx.bridge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The agent. All network I/O runs on one daemon thread and the JDK HTTP client, never on a game thread.
 * Measurements are kept in a bounded queue (10 entries, at most 4 minutes old) and sent in order after an outage;
 * the key is read from agent.key and never written to the log.
 */
public final class Agent {
    public static final String VERSION = "1.0.0";
    static final int QUEUE_MAX = 10;
    static final Duration QUEUE_MAX_AGE = Duration.ofMinutes(4);

    private final Platform platform;
    private final ScheduledExecutorService exec;
    private final HttpClient http;
    private volatile Config config;
    private volatile String token;
    private volatile String agentId;
    private final String instanceId;
    private final AtomicLong sequence = new AtomicLong();
    final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private volatile List<String> motdCodes = List.of();
    private volatile boolean rewardsEnabled;
    private volatile String disabledReason;
    private volatile Instant lastSuccess, lastError, backoffUntil = Instant.EPOCH;
    private volatile String lastErrorText = "";
    private int failures;
    private final LinkedHashSet<String> rewarded = new LinkedHashSet<>();

    record Pending(long seq, Instant created, Map<String, Object> body) {}

    public Agent(Platform platform) {
        this.platform = platform;
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "anx-network-bridge"); t.setDaemon(true); return t; });
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        byte[] b = new byte[6]; new SecureRandom().nextBytes(b);
        this.instanceId = "boot_" + HexFormat.of().formatHex(b);
    }

    public void start() {
        try { config = Config.load(platform.dataDir().resolve("config.yml")); } catch (IOException e) { platform.warn("ANX: cannot read config.yml: " + e.getMessage()); return; }
        token = readKey();
        loadRewarded();
        if (token == null) { platform.warn("ANX: not paired yet. Create an agent on anx.run and run: anx pair <code>"); }
        exec.scheduleWithFixedDelay(this::safeHeartbeat, 3, config.heartbeatSeconds, TimeUnit.SECONDS);
        exec.scheduleWithFixedDelay(this::safeTelemetry, 5, config.telemetrySeconds, TimeUnit.SECONDS);
        exec.scheduleWithFixedDelay(this::safeConfig, 1, 300, TimeUnit.SECONDS);
        exec.scheduleWithFixedDelay(this::safeRewards, 20, 30, TimeUnit.SECONDS);
    }

    public void stop() { exec.shutdownNow(); }

    /** Text to append to this proxy's ping answer while anx.run waits for a proof; empty otherwise. */
    public String motdSuffix() { List<String> c = motdCodes; return c.isEmpty() || config == null || !config.motdProofs ? "" : " " + String.join(" ", c); }

    // ---------------------------------------------------------------- pairing

    public CompletableFuture<String> pair(String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.trim()); body.put("platform", platform.id()); body.put("version", VERSION);
        return send("POST", "/agents/pair", Json.write(body), null).thenApply(r -> {
            if (r.statusCode() != 201) return "Pairing failed (HTTP " + r.statusCode() + "): " + message(r.body());
            Map<String, Object> d = Json.obj(Json.obj(Json.parse(r.body())).get("data"));
            try { writeKey(Json.text(d.get("token"))); } catch (IOException e) { return "Paired, but agent.key could not be written: " + e.getMessage(); }
            token = Json.text(d.get("token")); agentId = Json.text(d.get("agent_id")); disabledReason = null;
            exec.execute(this::safeConfig);
            return "Paired as " + agentId + " for network " + d.get("network") + ". The key is stored in agent.key.";
        }).exceptionally(e -> "Pairing failed: " + rootMessage(e));
    }

    public List<String> status() {
        List<String> l = new ArrayList<>();
        l.add("ANX Network Bridge " + VERSION + " on " + platform.software() + " " + platform.softwareVersion());
        l.add("Agent: " + (agentId != null ? agentId : token == null ? "not paired" : "key loaded, waiting for anx.run") + "   instance " + instanceId);
        l.add("Last success: " + (lastSuccess == null ? "never" : lastSuccess) + "   queued measurements: " + queue.size());
        if (lastError != null) l.add("Last error: " + lastError + " — " + lastErrorText);
        if (disabledReason != null) l.add("Sending stopped: " + disabledReason);
        if (!motdCodes.isEmpty()) l.add("Serving " + motdCodes.size() + " anx.run proof code(s) in the ping answer.");
        return l;
    }

    // ---------------------------------------------------------------- scheduled work

    private void safeHeartbeat() { guard(this::heartbeat); }
    private void safeTelemetry() { guard(this::telemetry); }
    private void safeConfig() { guard(this::refreshConfig); }
    private void safeRewards() { guard(this::rewards); }

    private void guard(Runnable r) { try { if (token != null && disabledReason == null) r.run(); } catch (Throwable t) { fail("internal: " + t); } }

    private void refreshConfig() {
        HttpResponse<String> r = await(send("GET", "/agents/config", null, token));
        if (r == null || !handleAuth(r)) return;
        Map<String, Object> d = Json.obj(Json.obj(Json.parse(r.body())).get("data"));
        agentId = Json.text(d.get("agent_id"));
        List<String> codes = new ArrayList<>();
        for (Object p : Json.arr(d.get("motd_proofs"))) { String c = Json.text(Json.obj(p).get("motd_code")); if (c != null && c.matches("[A-Za-z0-9_-]{8,64}")) codes.add(c); }
        motdCodes = List.copyOf(codes);
        rewardsEnabled = Boolean.TRUE.equals(Json.obj(d.get("rewards")).get("enabled"));
        ok();
    }

    private void heartbeat() {
        if (agentId == null) { refreshConfig(); if (agentId == null) return; }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("agent_id", agentId); b.put("instance_id", instanceId); b.put("version", VERSION); b.put("platform", platform.id());
        HttpResponse<String> r = await(send("POST", "/agents/heartbeat", Json.write(b), token));
        if (r == null || !handleAuth(r)) return;
        if (!Json.arr(Json.obj(Json.obj(Json.parse(r.body())).get("data")).get("verified_endpoints")).isEmpty()) { platform.info("ANX: a public address was proven through this proxy."); refreshConfig(); }
        ok();
    }

    private void telemetry() {
        if (agentId == null) return;
        Platform.Stats s;
        try { s = platform.stats().get(10, TimeUnit.SECONDS); } catch (Exception e) { fail("stats: " + rootMessage(e)); return; }
        enqueue(Telemetry.build(agentId, instanceId, sequence.incrementAndGet(), Instant.now(), s, config, Bedrock.count(s.playerIds())));
        flush();
    }

    void enqueue(Map<String, Object> body) {
        Instant now = Instant.now();
        synchronized (queue) {
            queue.addLast(new Pending(((Number) body.get("sequence")).longValue(), now, body));
            while (queue.size() > QUEUE_MAX) queue.removeFirst();
            queue.removeIf(p -> p.created().isBefore(now.minus(QUEUE_MAX_AGE)));
        }
    }

    private void flush() {
        if (Instant.now().isBefore(backoffUntil)) return;
        while (true) {
            Pending p;
            synchronized (queue) { p = queue.peekFirst(); }
            if (p == null) return;
            HttpResponse<String> r = await(send("POST", "/agents/telemetry", Json.write(p.body()), token));
            if (r == null) return; // network error: keep the queue, retry later
            int c = r.statusCode();
            if (c == 202 || c == 409 || c == 422 || c == 413 || c == 400) {
                synchronized (queue) { queue.remove(p); }
                if (c == 202) ok(); else fail("telemetry rejected (" + c + "): " + message(r.body()));
                continue;
            }
            if (!handleAuth(r)) return;
            backoff("telemetry HTTP " + c);
            return;
        }
    }

    // ---------------------------------------------------------------- rewards

    private void rewards() {
        if (!rewardsEnabled || config.rewardCommands.isEmpty()) return;
        HttpResponse<String> r = await(send("GET", "/agents/votes", null, token));
        if (r == null || r.statusCode() == 403) return;
        if (!handleAuth(r)) return;
        for (Object o : Json.arr(Json.obj(Json.parse(r.body())).get("data"))) {
            Map<String, Object> v = Json.obj(o);
            String id = Json.text(v.get("vote_id")), nick = Json.text(v.get("nickname")), mode = Json.text(v.get("mode"));
            if (id == null || !id.matches("[0-9a-fA-F-]{36}")) continue;
            if (nick != null && nick.matches("[A-Za-z0-9_ .\\-]{1,32}") && rememberRewarded(id)) {
                // remembered before running: a lost acknowledgement never gives the reward twice
                for (String cmd : config.rewardCommands) platform.dispatchConsole(cmd.replace("{player}", nick.trim()).replace("{nick}", nick.trim()).replace("{mode}", mode == null ? "" : mode));
            }
            await(send("POST", "/agents/votes/" + id + "/ack", "{}", token));
        }
    }

    private synchronized boolean rememberRewarded(String id) {
        if (rewarded.contains(id)) return false;
        rewarded.add(id);
        while (rewarded.size() > 2000) rewarded.remove(rewarded.iterator().next());
        try { Files.writeString(platform.dataDir().resolve("rewarded.txt"), String.join("\n", rewarded), StandardCharsets.UTF_8); } catch (IOException e) { platform.warn("ANX: cannot write rewarded.txt: " + e.getMessage()); }
        return true;
    }

    private void loadRewarded() {
        try { Path f = platform.dataDir().resolve("rewarded.txt"); if (Files.exists(f)) rewarded.addAll(Files.readAllLines(f)); } catch (IOException ignored) {}
    }

    // ---------------------------------------------------------------- HTTP

    private CompletableFuture<HttpResponse<String>> send(String method, String path, String body, String key) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.apiUrl + path)).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json").header("User-Agent", "ANX-Network-Bridge/" + VERSION + " (" + platform.id() + ")");
        if (key != null) b.header("Authorization", "Bearer " + key);
        if (body != null) b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        else b.method(method, HttpRequest.BodyPublishers.noBody());
        return http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> await(CompletableFuture<HttpResponse<String>> f) {
        try { return f.get(20, TimeUnit.SECONDS); } catch (Exception e) { backoff("network: " + rootMessage(e)); return null; }
    }

    /** 401/403 on our own key means it was revoked or replaced: stop and tell the owner instead of hammering the API. */
    private boolean handleAuth(HttpResponse<String> r) {
        int c = r.statusCode();
        if (c == 401 || (c == 403 && !r.uri().getPath().endsWith("/agents/votes"))) {
            disabledReason = "anx.run refused the agent key (HTTP " + c + "). Create a new pairing code and run: anx pair <code>";
            platform.warn("ANX: " + disabledReason);
            return false;
        }
        if (c == 429 || c >= 500) { backoff("HTTP " + c); return false; }
        return c < 400 || c == 409 || c == 422;
    }

    private void ok() { lastSuccess = Instant.now(); failures = 0; backoffUntil = Instant.EPOCH; }
    private void fail(String text) { lastError = Instant.now(); lastErrorText = text; }
    private void backoff(String text) {
        fail(text);
        failures = Math.min(failures + 1, 8);
        backoffUntil = Instant.now().plusSeconds(Math.min(300, 5L << failures));
        if (failures == 3) platform.warn("ANX: anx.run is not reachable (" + text + "); measurements are queued and will be sent later.");
    }

    // ---------------------------------------------------------------- key storage

    private String readKey() {
        if (config.hasConfiguredToken()) return config.agentToken.trim();
        try { Path f = platform.dataDir().resolve("agent.key"); return Files.exists(f) ? Files.readString(f).trim() : null; } catch (IOException e) { return null; }
    }

    private void writeKey(String key) throws IOException {
        Path f = platform.dataDir().resolve("agent.key");
        Files.writeString(f, key, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try { Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) {}
    }

    private static String message(String body) { try { return String.valueOf(Json.obj(Json.parse(body)).getOrDefault("message", "")); } catch (RuntimeException e) { return ""; } }
    private static String rootMessage(Throwable e) { while (e.getCause() != null) e = e.getCause(); return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()); }
}
