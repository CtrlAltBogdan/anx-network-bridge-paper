package run.anx.bridge;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Paper adapter. Game state and command dispatch stay on the server thread. */
public final class PaperBridge extends JavaPlugin implements Platform, Listener {
    private Agent agent;

    @Override
    public void onEnable() {
        agent = new Agent(this);
        agent.start();
        getServer().getPluginManager().registerEvents(this, this);
        var command = getCommand("anx");
        if (command != null) {
            command.setExecutor((sender, ignored, label, args) -> {
                Commands.run(agent, args, message -> Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(message)));
                return true;
            });
            command.setTabCompleter((sender, ignored, label, args) -> Commands.complete(args));
        }
    }

    @Override public void onDisable() { if (agent != null) agent.stop(); }

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onPing(ServerListPingEvent event) {
        if (agent == null) return;
        String suffix = agent.motdSuffix();
        if (!suffix.isEmpty()) event.setMotd(event.getMotd() + suffix);
    }

    @Override public String id() { return "paper"; }
    @Override public String software() { return Bukkit.getName(); }
    @Override public String softwareVersion() { return Bukkit.getMinecraftVersion(); }
    @Override public Path dataDir() { return getDataFolder().toPath(); }
    @Override public void info(String message) { getLogger().info(message); }
    @Override public void warn(String message) { getLogger().warning(message); }
    @Override public void dispatchConsole(String command) {
        Bukkit.getScheduler().runTask(this, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    @Override
    public CompletableFuture<Stats> stats() {
        CompletableFuture<Stats> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(this, () -> {
            List<UUID> ids = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) ids.add(player.getUniqueId());
            future.complete(new Stats(ids.size(), Bukkit.getMaxPlayers(), ids, Map.of("local", ids.size()),
                    Map.of("local", true), List.of(software() + " " + softwareVersion())));
        });
        return future;
    }
}
