package run.anx.bridge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds one telemetry body from platform counts. Aggregates only: no names, UUIDs or addresses of players. */
public final class Telemetry {
    private Telemetry() {}

    public static Map<String, Object> build(String agentId, String instanceId, long seq, Instant at, Platform.Stats s, Config c, Integer bedrock) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("agent_id", agentId); b.put("instance_id", instanceId); b.put("sequence", seq); b.put("observed_at", at.toString());
        Map<String, Object> n = new LinkedHashMap<>();
        int online = Math.max(0, s.online());
        n.put("online_unique", online);
        if (c.includeBedrock && bedrock != null) { int bed = Math.min(bedrock, online); n.put("bedrock_online", bed); n.put("java_online", online - bed); }
        if (s.maxPlayers() != null && s.maxPlayers() >= 0) n.put("max_players", s.maxPlayers());
        b.put("network", n);
        if (c.includeModes && !c.modes.isEmpty()) {
            List<Object> modes = new ArrayList<>();
            for (Map.Entry<String, List<String>> e : c.modes.entrySet()) {
                int players = 0; boolean any = false, up = false;
                for (String server : e.getValue()) {
                    Integer p = s.serverPlayers().get(server);
                    if (p != null) { players += p; any = true; }
                    if (Boolean.TRUE.equals(s.serverOnline().get(server))) up = true;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.getKey());
                m.put("online", any ? players : null);
                m.put("status", up || players > 0 ? "online" : (s.serverOnline().isEmpty() ? "unknown" : "offline"));
                modes.add(m);
            }
            b.put("modes", modes);
        }
        if (c.includeServers && !s.serverPlayers().isEmpty()) {
            List<Object> servers = new ArrayList<>();
            for (Map.Entry<String, Integer> e : s.serverPlayers().entrySet()) {
                if (!e.getKey().matches("[\\p{L}\\p{N} _.\\-]{1,48}") || servers.size() >= 128) continue;
                Map<String, Object> sv = new LinkedHashMap<>();
                sv.put("name", e.getKey()); sv.put("online", !Boolean.FALSE.equals(s.serverOnline().get(e.getKey()))); sv.put("players", e.getValue());
                servers.add(sv);
            }
            b.put("servers", servers);
        }
        Map<String, Object> proxy = new LinkedHashMap<>();
        proxy.put("software", trim(s.versions().isEmpty() ? "" : s.versions().get(0), 32));
        proxy.put("geyser", Bedrock.geyserPresent()); proxy.put("floodgate", Bedrock.floodgatePresent());
        b.put("proxy", proxy);
        return b;
    }

    private static String trim(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }
}
