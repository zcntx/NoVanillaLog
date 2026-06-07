package cn.nskc.spawn.novanillalog;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilterable;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class NoVanillaLogPlugin extends JavaPlugin {

    private Logger rootLogger;

    // ── Spam filter ────────────────────────────────────────────────────────
    private LogFilter spamFilter;

    // ── In-game system chat filter ─────────────────────────────────────────
    private SystemChatFilter systemChatFilter;
    private PacketFilter     packetFilter;
    private Listener         joinListener;
    private boolean          suppressInGame;

    // ── Config defaults ────────────────────────────────────────────────────

    /** Suppress these from the console / main log file AND in-game chat. */
    private static final List<String> DEFAULT_SPAM_PATTERNS = List.of(
            // English text (AdventureComponent packets)
            "已将",              // /effect (中文)
            "effect",            // /effect (英文)
            "Applied",           // /effect, /damage (英文)
            "Summoned",          // /summon (英文)
            "particle",          // /particle
            // Translation keys (NMS MutableComponent packets)
            "commands.effect",
            "commands.summon",
            "commands.damage",
            "commands.particle"
    );

    // ── Plugin lifecycle ───────────────────────────────────────────────────

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        getLogger().info("NoVanillaLog enabled — suppressing "
                + (spamFilter != null ? spamFilter.getPatterns().size() : 0)
                + " spam pattern(s) from console"
                + (suppressInGame ? " + in-game" : ""));
    }

    @Override
    public void onDisable() {
        unloadAll();
        getLogger().info("NoVanillaLog disabled.");
    }

    // ── Config loading ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void loadConfig() {
        reloadConfig();
        FileConfiguration cfg = getConfig();

        // ── defaults ───────────────────────────────────────────────────
        cfg.addDefault("filtered-patterns", DEFAULT_SPAM_PATTERNS);
        cfg.addDefault("suppress-in-game", true);
        cfg.options().copyDefaults(true);
        saveConfig();

        // Tear down old hooks
        unloadAll();

        rootLogger = (Logger) LogManager.getRootLogger();

        // ── Spam filter → attach to all EXISTING appenders
        List<String> spam = cfg.getStringList("filtered-patterns");
        if (spam == null || spam.isEmpty()) spam = DEFAULT_SPAM_PATTERNS;
        spamFilter = new LogFilter(spam);

        for (Appender app : rootLogger.getAppenders().values()) {
            if (app instanceof AbstractFilterable filterable) {
                filterable.addFilter(spamFilter);
            }
        }

        // ── In-game system chat filter ──────────────────────────────────
        suppressInGame = cfg.getBoolean("suppress-in-game", true);
        if (suppressInGame && !spam.isEmpty()) {
            // Packet-level interception via Paper's ChannelInitializeListenerHolder
            packetFilter = new PacketFilter(this, spam);
            packetFilter.register();
            getLogger().info("Packet-level filter registered for system messages.");

            // Inject handler for players who join after plugin load
            joinListener = new Listener() {
                @EventHandler(priority = EventPriority.LOWEST)
                public void onJoin(PlayerJoinEvent event) {
                    packetFilter.injectPlayer(event.getPlayer());
                }
            };
            Bukkit.getPluginManager().registerEvents(joinListener, this);
        }
    }

    private void unloadAll() {
        // Remove spam filter from all appenders that have it
        if (rootLogger != null && spamFilter != null) {
            for (Appender app : rootLogger.getAppenders().values()) {
                if (app instanceof AbstractFilterable filterable) {
                    filterable.removeFilter(spamFilter);
                }
            }
            spamFilter = null;
        }

        // Remove join listener
        if (joinListener != null) {
            HandlerList.unregisterAll(joinListener);
            joinListener = null;
        }

        // Remove packet filter (Netty channel handler)
        if (packetFilter != null) {
            packetFilter.unregister();
            packetFilter = null;
        }

        // Remove system chat filter
        if (systemChatFilter != null) {
            HandlerList.unregisterAll(systemChatFilter);
            systemChatFilter = null;
        }
        suppressInGame = false;

        rootLogger = null;
    }

    // ── /novanillalog command ──────────────────────────────────────────────

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (!sender.hasPermission("novanillalog.reload")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("reload")) {
            reloadCommand(sender);
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Unknown subcommand. Use "
                + ChatColor.YELLOW + "/novanillalog reload");
        return true;
    }

    // ── Reload ─────────────────────────────────────────────────────────────

    private void reloadCommand(CommandSender sender) {
        List<String> oldSpam = spamFilter != null ? spamFilter.getPatterns() : Collections.emptyList();

        loadConfig();

        List<String> newSpam = spamFilter != null ? spamFilter.getPatterns() : Collections.emptyList();

        sender.sendMessage(ChatColor.GREEN + "NoVanillaLog reloaded!");

        if (!newSpam.equals(oldSpam)) {
            sender.sendMessage("");
            sender.sendMessage(ChatColor.RED + "Suppressed from console"
                    + (suppressInGame ? " + in-game" : "") + " (" + newSpam.size() + "):");
            for (String p : newSpam) {
                String prefix = oldSpam.contains(p) ? ChatColor.GRAY + "  ✕ " : ChatColor.GREEN + "  + ";
                sender.sendMessage(prefix + ChatColor.WHITE + "\"" + p + "\"");
            }
            for (String p : oldSpam) {
                if (!newSpam.contains(p)) {
                    sender.sendMessage(ChatColor.RED + "  - " + ChatColor.WHITE + "\"" + p + "\"");
                }
            }
        } else {
            sender.sendMessage(ChatColor.GRAY + "(no changes detected)");
        }
    }

    // ── Tab complete ───────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
                                      @NotNull Command command,
                                      @NotNull String alias,
                                      @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
