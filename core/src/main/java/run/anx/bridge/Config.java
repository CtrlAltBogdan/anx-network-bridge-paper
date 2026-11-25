package run.anx.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** config.yml reader for the small YAML subset the default file uses (maps, scalars, [inline] and "- block" lists). */
public final class Config {
    public static final String DEFAULT = """
            # ANX Network Bridge — anx.run network agent. Documentation: https://api.anx.run/docs (tag "Agents").
            anx:
              api_url: "https://api.anx.run/v1"
              # Keep this placeholder and run "anx pair <code>" in the console: the key is then stored in agent.key,
              # never in this file and never in the log. A key for your own setup can also be pasted here.
              agent_token: "SET_YOUR_TOKEN"
              heartbeat_seconds: 30
              telemetry_seconds: 30
              include_modes: true
              include_bedrock_stats: true
              # Send internal server names with their state and player count (never addresses).
              include_servers: true
              # Add pending anx.run proof codes to this proxy's ping answer until the public address is proven.
              motd_proofs: true
              rewards:
                # Only used when rewards are enabled for this agent on anx.run. {player} and {mode} are replaced.
                commands: []
            # anx.run mode id -> backend servers (Velocity / BungeeCord names). On Paper list this server's own mode.
            modes:
              lobby: [lobby]
            """;

    public final String apiUrl, agentToken;
    public final int heartbeatSeconds, telemetrySeconds;
    public final boolean includeModes, includeBedrock, includeServers, motdProofs;
    public final List<String> rewardCommands;
    public final Map<String, List<String>> modes;

    private Config(Map<String, Object> root) {
        Map<String, Object> anx = Json.obj(root.get("anx"));
        apiUrl = trimSlash(str(anx.get("api_url"), "https://api.anx.run/v1"));
        agentToken = str(anx.get("agent_token"), "");
        heartbeatSeconds = clamp(num(anx.get("heartbeat_seconds"), 30), 15, 300);
        telemetrySeconds = clamp(num(anx.get("telemetry_seconds"), 30), 15, 300);
        includeModes = bool(anx.get("include_modes"), true);
        includeBedrock = bool(anx.get("include_bedrock_stats"), true);
        includeServers = bool(anx.get("include_servers"), true);
        motdProofs = bool(anx.get("motd_proofs"), true);
        List<String> cmds = new ArrayList<>();
        for (Object o : Json.arr(Json.obj(anx.get("rewards")).get("commands"))) if (o != null && !String.valueOf(o).isBlank()) cmds.add(String.valueOf(o).replaceFirst("^/", ""));
        rewardCommands = List.copyOf(cmds);
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : Json.obj(root.get("modes")).entrySet()) {
            List<String> servers = new ArrayList<>();
            for (Object o : Json.arr(e.getValue())) servers.add(String.valueOf(o));
            if (e.getKey().matches("[a-z0-9][a-z0-9_-]{0,31}")) m.put(e.getKey(), List.copyOf(servers));
        }
        modes = m;
    }

    public boolean hasConfiguredToken() { return !agentToken.isBlank() && !agentToken.equals("SET_YOUR_TOKEN"); }

    public static Config load(Path file) throws IOException {
        if (!Files.exists(file)) { Files.createDirectories(file.getParent()); Files.writeString(file, DEFAULT, StandardCharsets.UTF_8); }
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static Config parse(String yaml) { return new Config(Yaml.parse(yaml)); }

    private static String trimSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
    private static String str(Object v, String d) { return v == null ? d : String.valueOf(v); }
    private static int num(Object v, int d) { try { return v == null ? d : Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException e) { return d; } }
    private static boolean bool(Object v, boolean d) { return v == null ? d : Boolean.parseBoolean(String.valueOf(v)); }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Indentation based YAML subset. */
    static final class Yaml {
        private record Line(int indent, String text) {}

        static Map<String, Object> parse(String src) {
            List<Line> lines = new ArrayList<>();
            for (String raw : src.split("\\R")) {
                String t = stripComment(raw);
                if (t.isBlank()) continue;
                int ind = 0; while (ind < t.length() && t.charAt(ind) == ' ') ind++;
                lines.add(new Line(ind, t.trim()));
            }
            int[] pos = {0};
            return map(lines, pos, 0);
        }

        private static Map<String, Object> map(List<Line> lines, int[] pos, int indent) {
            Map<String, Object> m = new LinkedHashMap<>();
            while (pos[0] < lines.size() && lines.get(pos[0]).indent >= indent) {
                Line l = lines.get(pos[0]);
                if (l.indent > indent || l.text.startsWith("- ")) { pos[0]++; continue; }
                int c = l.text.indexOf(':');
                if (c < 0) { pos[0]++; continue; }
                String key = unquote(l.text.substring(0, c).trim()), rest = l.text.substring(c + 1).trim();
                pos[0]++;
                if (!rest.isEmpty()) { m.put(key, scalar(rest)); continue; }
                if (pos[0] < lines.size() && lines.get(pos[0]).indent > indent) {
                    int child = lines.get(pos[0]).indent;
                    if (lines.get(pos[0]).text.startsWith("- ")) {
                        List<Object> list = new ArrayList<>();
                        while (pos[0] < lines.size() && lines.get(pos[0]).indent == child && lines.get(pos[0]).text.startsWith("- ")) list.add(scalar(lines.get(pos[0]++).text.substring(2).trim()));
                        m.put(key, list);
                    } else m.put(key, map(lines, pos, child));
                } else m.put(key, null);
            }
            return m;
        }

        private static Object scalar(String v) {
            if (v.startsWith("[") && v.endsWith("]")) {
                List<Object> l = new ArrayList<>();
                String inner = v.substring(1, v.length() - 1).trim();
                if (!inner.isEmpty()) for (String part : splitList(inner)) l.add(unquote(part.trim()));
                return l;
            }
            return unquote(v);
        }

        private static List<String> splitList(String s) {
            List<String> out = new ArrayList<>(); StringBuilder cur = new StringBuilder(); char q = 0;
            for (char c : s.toCharArray()) {
                if (q != 0) { if (c == q) q = 0; cur.append(c); }
                else if (c == '"' || c == '\'') { q = c; cur.append(c); }
                else if (c == ',') { out.add(cur.toString()); cur.setLength(0); }
                else cur.append(c);
            }
            out.add(cur.toString());
            return out;
        }

        private static String unquote(String v) {
            if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) return v.substring(1, v.length() - 1);
            return v;
        }

        private static String stripComment(String line) {
            char q = 0;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (q != 0) { if (c == q) q = 0; }
                else if (c == '"' || c == '\'') q = c;
                else if (c == '#' && (i == 0 || line.charAt(i - 1) == ' ')) return line.substring(0, i);
            }
            return line;
        }
    }
}
