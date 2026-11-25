package run.anx.bridge;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** What a host platform (Velocity, BungeeCord/Waterfall, Paper) provides to the agent. */
public interface Platform {
    /** velocity | bungeecord | paper */
    String id();
    String software();
    String softwareVersion();
    Path dataDir();
    void info(String message);
    void warn(String message);

    /** Counts, collected on whatever thread the platform requires. Player ids are used locally only (Bedrock detection). */
    CompletableFuture<Stats> stats();

    /** Runs a console command on the platform's main thread. */
    void dispatchConsole(String command);

    record Stats(int online, Integer maxPlayers, Collection<UUID> playerIds, Map<String, Integer> serverPlayers, Map<String, Boolean> serverOnline, List<String> versions) {}
}
