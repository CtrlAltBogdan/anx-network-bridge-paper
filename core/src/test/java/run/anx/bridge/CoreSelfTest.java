package run.anx.bridge;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free tests of the agent core, including a full agent run against a local stand-in for the anx.run API. */
public final class CoreSelfTest {
    static int passed;

    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); passed++; }

    public static void main(String[] a) throws Exception {
        json(); config(); telemetry(); queue(); agentRun();
        System.out.println("core self-test: " + passed + " checks passed");
    }

    static void json() {
        Object v = Json.parse("{\"a\":[1,2,{\"b\":\"x\\\"y\\u00e9\"}],\"c\":null,\"d\":true,\"e\":-3.5}");
        check(Json.obj(v).get("c") == null && Json.obj(v).get("d").equals(true), "json literals");
        check("x\"yé".equals(Json.obj(Json.arr(Json.obj(v).get("a")).get(2)).get("b")), "json escapes");
        check(Json.parse(Json.write(Map.of("k", "a\nb"))).equals(Map.of("k", "a\nb")), "json round trip");
        boolean threw = false; try { Json.parse("{\"a\":1} x"); } catch (IllegalArgumentException e) { threw = true; } check(threw, "json trailing data");
    }

    static void config() {
        Config c = Config.parse(Config.DEFAULT);
        check(c.apiUrl.equals("https://api.anx.run/v1") && !c.hasConfiguredToken() && c.telemetrySeconds == 30, "default config");
        Config d = Config.parse("""
                anx:
                  api_url: "http://127.0.0.1:9/v1/"   # comment
                  agent_token: 'abc'
                  telemetry_seconds: 5
                  rewards:
                    commands:
                      - "/give {player} diamond 1"
                modes:
                  survival: [survival-1, "survival-2"]
                  BAD KEY: [x]
                  lobby:
                    - lobby
                """);
        check(d.apiUrl.equals("http://127.0.0.1:9/v1") && d.hasConfiguredToken() && d.telemetrySeconds == 15, "custom config, clamped interval");
        check(d.rewardCommands.equals(List.of("give {player} diamond 1")), "reward commands");
        check(d.modes.equals(Map.of("survival", List.of("survival-1", "survival-2"), "lobby", List.of("lobby"))), "modes: " + d.modes);
    }

    static void telemetry() {
        Config c = Config.parse("anx:\n  include_servers: true\nmodes:\n  survival: [s1, s2]\n  lobby: [lobby]\n");
        UUID player = UUID.randomUUID();
        Platform.Stats s = new Platform.Stats(42, 100, List.of(player), Map.of("s1", 20, "s2", 10, "lobby", 12), Map.of("s1", true, "s2", false, "lobby", true), List.of("Velocity 3.4.0"));
        Map<String, Object> b = Telemetry.build("agt_x", "boot_1", 7, Instant.parse("2026-11-25T10:00:00Z"), s, c, 50);
        String json = Json.write(b);
        check(!json.contains(player.toString()), "no player ids in telemetry");
        Map<String, Object> n = Json.obj(b.get("network"));
        check(n.get("online_unique").equals(42) && n.get("bedrock_online").equals(42) && n.get("java_online").equals(0), "bedrock never above total: " + n);
        Map<String, Object> m = Json.obj(Json.arr(b.get("modes")).get(0));
        check(m.get("id").equals("survival") && m.get("online").equals(30) && m.get("status").equals("online"), "mode sums its servers");
        check(Json.arr(b.get("servers")).size() == 3, "internal servers by name");
        Map<String, Object> nob = Json.obj(Telemetry.build("agt_x", "boot_1", 8, Instant.now(), s, c, null).get("network"));
        check(!nob.containsKey("bedrock_online"), "no bedrock without floodgate");
    }

    static void queue() throws Exception {
        Path dir = Files.createTempDirectory("anx-q");
        Files.writeString(dir.resolve("config.yml"), "anx:\n  api_url: \"http://127.0.0.1:9/v1\"\n");
        Agent ag = new Agent(new FakePlatform(dir, new AtomicInteger(1), List.of()));
        for (int i = 1; i <= 15; i++) ag.enqueue(Map.of("sequence", (long) i));
        check(ag.queue.size() == Agent.QUEUE_MAX && ag.queue.peekFirst().seq() == 6, "queue keeps the newest 10");
    }

    static void agentRun() throws Exception {
        AtomicInteger mode = new AtomicInteger(0); // 0 ok, 1 outage, 2 revoked
        List<String> telemetry = new CopyOnWriteArrayList<>(), acks = new CopyOnWriteArrayList<>(), auth = new CopyOnWriteArrayList<>();
        AtomicInteger ackFailures = new AtomicInteger(1);
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/v1/", ex -> {
            String path = ex.getRequestURI().getPath(), body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String h = ex.getRequestHeaders().getFirst("Authorization"); if (h != null) auth.add(h);
            int code = 200; String out = "{}";
            if (mode.get() == 1 && !path.endsWith("/pair")) { ex.sendResponseHeaders(503, -1); ex.close(); return; }
            if (mode.get() == 2 && !path.endsWith("/pair")) code = 401;
            else if (path.endsWith("/agents/pair")) { code = Json.obj(Json.parse(body)).get("code").equals("ANX-AAAA-BBBB-CCCC") ? 201 : 404; out = code == 201 ? "{\"data\":{\"agent_id\":\"agt_test\",\"token\":\"1|secret-key\",\"network\":\"qa\"}}" : "{\"message\":\"no\"}"; }
            else if (path.endsWith("/agents/config")) out = "{\"data\":{\"agent_id\":\"agt_test\",\"motd_proofs\":[{\"motd_code\":\"anx-PROOFCODE123\"}],\"rewards\":{\"enabled\":true}}}";
            else if (path.endsWith("/agents/heartbeat")) out = "{\"data\":{\"ok\":true,\"verified_endpoints\":[]}}";
            else if (path.endsWith("/agents/telemetry")) { telemetry.add(body); code = 202; }
            else if (path.endsWith("/agents/votes")) out = "{\"data\":[{\"vote_id\":\"6f1c0a52-2a8e-4c3e-9a51-0f1f2a3b4c5d\",\"nickname\":\"Steve_1\",\"mode\":\"survival\"}]}";
            else if (path.contains("/ack")) { if (ackFailures.getAndDecrement() > 0) { ex.sendResponseHeaders(500, -1); ex.close(); return; } acks.add(path); }
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json"); ex.sendResponseHeaders(code, b.length); ex.getResponseBody().write(b); ex.close();
        });
        srv.start();
        try {
            Path dir = Files.createTempDirectory("anx-run");
            Files.writeString(dir.resolve("config.yml"), "anx:\n  api_url: \"http://127.0.0.1:" + srv.getAddress().getPort() + "/v1\"\n  rewards:\n    commands: [\"give {player} diamond 1\"]\nmodes:\n  survival: [s1]\n");
            List<String> commands = Collections.synchronizedList(new ArrayList<>());
            FakePlatform p = new FakePlatform(dir, new AtomicInteger(5), commands);
            Agent ag = new Agent(p);
            ag.start();
            check(ag.pair("ANX-ZZZZ-ZZZZ-ZZZZ").get().startsWith("Pairing failed"), "unknown code refused");
            String res = ag.pair("ANX-AAAA-BBBB-CCCC").get();
            check(res.startsWith("Paired as agt_test") && !res.contains("secret-key"), "paired without printing the key");
            check(Files.readString(dir.resolve("agent.key")).equals("1|secret-key"), "key stored in agent.key");
            check(!String.join("\n", p.log).contains("secret-key"), "key never logged");
            waitFor(() -> telemetry.size() >= 1, 15000, "first telemetry");
            waitFor(() -> ag.motdSuffix().contains("anx-PROOFCODE123"), 5000, "proof code served");
            Map<String, Object> first = Json.obj(Json.parse(telemetry.get(0)));
            check(first.get("agent_id").equals("agt_test") && ((Number) first.get("sequence")).longValue() >= 1, "telemetry fields");
            // reward: the first ack fails, the reward must still be given only once
            waitFor(() -> acks.size() >= 1, 70000, "vote acknowledged after a failed ack");
            check(commands.equals(List.of("give Steve_1 diamond 1")), "reward given once: " + commands);
            // outage: measurements queue up and are sent in order once anx.run is back
            mode.set(1);
            Thread.sleep(40000);
            int before = telemetry.size();
            mode.set(0);
            waitFor(() -> telemetry.size() > before + 1, 330000, "queued telemetry flushed after recovery");
            long prev = -1;
            for (String t : telemetry) { long s = ((Number) Json.obj(Json.parse(t)).get("sequence")).longValue(); check(s > prev, "sequence strictly increasing"); prev = s; }
            // revoked key: the agent stops instead of retrying
            mode.set(2);
            waitFor(() -> String.join("\n", ag.status()).contains("Sending stopped"), 40000, "stops on a revoked key");
            int calls = auth.size(); Thread.sleep(35000);
            check(auth.size() <= calls + 1, "no hammering after revocation");
            ag.stop();
        } finally { srv.stop(0); }
    }

    static void waitFor(java.util.function.BooleanSupplier c, long ms, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!c.getAsBoolean()) { if (System.currentTimeMillis() > end) throw new AssertionError("timeout: " + what); Thread.sleep(100); }
        passed++;
    }

    static final class FakePlatform implements Platform {
        final Path dir; final AtomicInteger online; final List<String> commands; final List<String> log = new CopyOnWriteArrayList<>();
        FakePlatform(Path dir, AtomicInteger online, List<String> commands) { this.dir = dir; this.online = online; this.commands = commands; }
        public String id() { return "velocity"; }
        public String software() { return "FakeProxy"; }
        public String softwareVersion() { return "1"; }
        public Path dataDir() { return dir; }
        public void info(String m) { log.add(m); }
        public void warn(String m) { log.add(m); }
        public void dispatchConsole(String c) { commands.add(c); }
        public CompletableFuture<Stats> stats() { return CompletableFuture.completedFuture(new Stats(online.get(), 100, List.of(UUID.randomUUID()), Map.of("s1", online.get()), Map.of("s1", true), List.of("FakeProxy 1"))); }
    }
}
