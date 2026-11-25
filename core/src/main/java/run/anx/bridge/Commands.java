package run.anx.bridge;

import java.util.List;
import java.util.function.Consumer;

/** "anx status" and "anx pair <code>" shared by all platforms. Console or anx.admin only (checked by the adapter). */
public final class Commands {
    private Commands() {}

    public static void run(Agent agent, String[] args, Consumer<String> reply) {
        if (args.length >= 2 && args[0].equalsIgnoreCase("pair")) {
            if (!args[1].matches("(?i)ANX-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}")) { reply.accept("Expected a code like ANX-XXXX-XXXX-XXXX from your network settings on anx.run."); return; }
            reply.accept("Pairing with anx.run…");
            agent.pair(args[1]).thenAccept(reply);
            return;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) { agent.status().forEach(reply); return; }
        reply.accept("Usage: anx status | anx pair <code>");
    }

    public static List<String> complete(String[] args) { return args.length <= 1 ? List.of("status", "pair") : List.of(); }
}
