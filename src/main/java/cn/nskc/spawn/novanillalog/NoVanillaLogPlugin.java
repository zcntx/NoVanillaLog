package cn.nskc.spawn.novanillalog;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilterable;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class NoVanillaLogPlugin extends JavaPlugin {

    private Logger rootLogger;

    // ── Spam filter ────────────────────────────────────────────────────────
    // Attached to CONSOLE / FILE appenders so it only suppresses output
    // there — the AuditAppender (which has its own filter) still receives
    // every event and decides independently.
    private LogFilter spamFilter;

    // ── Audit archive ──────────────────────────────────────────────────────
    private AuditFilter   auditFilter;
    private AuditAppender auditAppender;

    // ── In-game packet filter ──────────────────────────────────────────────
    private PacketFilter  packetFilter;
    private boolean       suppressInGame;

    private static final NamespacedKey PACKET_FILTER_KEY =
            new NamespacedKey("novanillalog", "packet_filter");

    // ── Config defaults ────────────────────────────────────────────────────

    /** Suppress these from the console / main log file AND in-game chat. */
    private static final List<String> DEFAULT_SPAM_PATTERNS = List.of(
            "Displaying particle",         // /particle command   (48,097 lines in luminol.old.log)
            "Changed the block",           // /setblock command   (19,564 lines)
            "Applied effect",              // /effect command     (12,573 lines)
            "Successfully filled",         // /fill command       ( 5,978 lines)
            "Target is invulnerable",      // /damage command     ( 1,562 lines)
            "Summoned ",                   // /summon command     (   380 lines)
            "Given ",                      // /give vanilla (< 1.21)
            "Gave ",                       // /give vanilla (1.21+)
            "Teleported "                  // /tp command         (     2 — pre-configured)
    );

    /** Archive these to the standalone audit file. */
    private static final List<String> DEFAULT_AUDIT_PATTERNS = List.of(
            // /give
            "issued server command: /give",
            "issued server command: /minecraft:give",
            // Economy — /money / playercurrency / gmp / ply
            "issued server command: /money give",
            "issued server command: /playercurrency",
            "issued server command: /gmp money",
            "issued server command: /ply give",
            // High-risk administration
            "issued server command: /op ",
            "issued server command: /deop ",
            "issued server command: /ban ",
            "issued server command: /ban-ip ",
            "issued server command: /kick ",
            "issued server command: /pardon ",
            "issued server command: /whitelist ",
            "issued server command: /gamemode "
    );

    private static final long DEFAULT_MAX_AUDIT_MB = 10;

    // ── Plugin lifecycle ───────────────────────────────────────────────────

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        String auditPath = auditAppender != null
                ? auditAppender.getLogFile().toString()
                : "(disabled)";

        getLogger().info("NoVanillaLog enabled — suppressing "
                + (spamFilter != null ? spamFilter.getPatterns().size() : 0)
                + " spam pattern(s) from console"
                + (suppressInGame ? " + in-game" : "")
                + ", archiving "
                + (auditFilter != null ? auditFilter.getPatterns().size() : 0)
                + " audit pattern(s) → " + auditPath);
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
        cfg.addDefault("audit.enabled", true);
        cfg.addDefault("audit.max-file-size-mb", (int) DEFAULT_MAX_AUDIT_MB);
        cfg.addDefault("audit.patterns", DEFAULT_AUDIT_PATTERNS);
        cfg.options().copyDefaults(true);
        saveConfig();

        // Tear down old hooks
        unloadAll();

        rootLogger = (Logger) LogManager.getRootLogger();

        // ── Spam filter → attach to all EXISTING appenders EXCEPT AuditAppender
        List<String> spam = cfg.getStringList("filtered-patterns");
        if (spam == null || spam.isEmpty()) spam = DEFAULT_SPAM_PATTERNS;
        spamFilter = new LogFilter(spam);

        for (Appender app : rootLogger.getAppenders().values()) {
            if (app instanceof AbstractFilterable filterable && !(app instanceof AuditAppender)) {
                filterable.addFilter(spamFilter);
            }
        }

        // ── In-game packet filter ──────────────────────────────────────
        suppressInGame = cfg.getBoolean("suppress-in-game", true);
        if (suppressInGame && !spam.isEmpty()) {
            packetFilter = new PacketFilter(spam, getLogger());
            packetFilter.register(PACKET_FILTER_KEY);
        }

        // ── Audit archive ──────────────────────────────────────────────
        boolean auditEnabled = cfg.getBoolean("audit.enabled", true);
        List<String> auditPatterns = cfg.getStringList("audit.patterns");
        if (auditPatterns == null || auditPatterns.isEmpty()) {
            auditPatterns = DEFAULT_AUDIT_PATTERNS;
        }

        if (auditEnabled && !auditPatterns.isEmpty()) {
            long maxMb = cfg.getLong("audit.max-file-size-mb", DEFAULT_MAX_AUDIT_MB);
            long maxBytes = Math.max(maxMb, 1) * 1024 * 1024;

            Path auditDir = getDataFolder().toPath().resolve("audit");
            auditFilter = new AuditFilter(auditPatterns);
            auditAppender = new AuditAppender("NoVanillaLog-Audit", auditFilter, auditDir, maxBytes);
            auditAppender.start();
            rootLogger.addAppender(auditAppender);
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

        // Remove packet filter
        if (packetFilter != null) {
            packetFilter.unregister(PACKET_FILTER_KEY);
            packetFilter = null;
        }
        suppressInGame = false;

        // Stop and remove audit appender
        if (rootLogger != null && auditAppender != null) {
            auditAppender.stop();
            rootLogger.removeAppender(auditAppender);
            auditAppender = null;
            auditFilter = null;
        }

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

        if (args.length == 0) {
            showStatus(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadCommand(sender);
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Unknown subcommand. Use "
                + ChatColor.YELLOW + "/novanillalog reload");
        return true;
    }

    // ── Status display ─────────────────────────────────────────────────────

    private void showStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "NoVanillaLog v" + getDescription().getVersion());

        List<String> spam = spamFilter != null ? spamFilter.getPatterns() : Collections.emptyList();
        sender.sendMessage("");
        sender.sendMessage(ChatColor.RED + "Suppressed from console" +
                (suppressInGame ? " + in-game" : "") + " (" + spam.size() + "):");
        for (String p : spam) {
            sender.sendMessage(ChatColor.GRAY + "  ✕ " + ChatColor.WHITE + "\"" + p + "\"");
        }

        List<String> audit = auditFilter != null ? auditFilter.getPatterns() : Collections.emptyList();
        String auditFile = auditAppender != null
                ? auditAppender.getLogFile().toAbsolutePath().toString()
                : ChatColor.RED + "(disabled)";
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "Archived to audit log (" + audit.size() + "):");
        for (String p : audit) {
            sender.sendMessage(ChatColor.GRAY + "  → " + ChatColor.WHITE + "\"" + p + "\"");
        }
        sender.sendMessage(ChatColor.GRAY + "  Audit file: " + ChatColor.WHITE + auditFile);

        sender.sendMessage("");
        sender.sendMessage(ChatColor.GRAY + "Edit " + ChatColor.YELLOW + "config.yml"
                + ChatColor.GRAY + " and run " + ChatColor.YELLOW + "/novanillalog reload"
                + ChatColor.GRAY + " to apply changes.");
    }

    // ── Reload ─────────────────────────────────────────────────────────────

    private void reloadCommand(CommandSender sender) {
        List<String> oldSpam  = spamFilter  != null ? spamFilter.getPatterns()  : Collections.emptyList();
        List<String> oldAudit = auditFilter != null ? auditFilter.getPatterns() : Collections.emptyList();

        loadConfig();

        List<String> newSpam  = spamFilter  != null ? spamFilter.getPatterns()  : Collections.emptyList();
        List<String> newAudit = auditFilter != null ? auditFilter.getPatterns() : Collections.emptyList();

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
        }

        if (!newAudit.equals(oldAudit)) {
            sender.sendMessage("");
            sender.sendMessage(ChatColor.GREEN + "Archived to audit log (" + newAudit.size() + "):");
            for (String p : newAudit) {
                String prefix = oldAudit.contains(p) ? ChatColor.GRAY + "  → " : ChatColor.GREEN + "  + ";
                sender.sendMessage(prefix + ChatColor.WHITE + "\"" + p + "\"");
            }
            for (String p : oldAudit) {
                if (!newAudit.contains(p)) {
                    sender.sendMessage(ChatColor.RED + "  - " + ChatColor.WHITE + "\"" + p + "\"");
                }
            }
        }

        if (newSpam.equals(oldSpam) && newAudit.equals(oldAudit)) {
            sender.sendMessage(ChatColor.GRAY + "(no changes detected)");
        }

        String auditPath = auditAppender != null
                ? auditAppender.getLogFile().toAbsolutePath().toString()
                : ChatColor.RED + "(disabled)";
        sender.sendMessage(ChatColor.GRAY + "Audit file: " + ChatColor.WHITE + auditPath);
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
