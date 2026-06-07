package cn.nskc.spawn.novanillalog;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;

/**
 * Bukkit event listener that intercepts {@link AsyncChatEvent}
 * (Paper 1.21+) to suppress spam messages from appearing in players' in-game
 * chat.  Matching rules are shared with the console {@link LogFilter} via the
 * {@code filtered-patterns} config key.
 */
public class SystemChatFilter implements Listener {

    private final List<String> patterns;

    public SystemChatFilter(List<String> patterns) {
        this.patterns = new ArrayList<>(patterns);
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        if (patterns.isEmpty()) {
            return;
        }

        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (plain == null || plain.isEmpty()) {
            return;
        }

        String lower = plain.toLowerCase();
        for (String pattern : patterns) {
            if (!pattern.isEmpty() && lower.contains(pattern.toLowerCase())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * Replace the filter patterns at runtime (for /novanillalog reload).
     */
    public void updatePatterns(List<String> newPatterns) {
        patterns.clear();
        patterns.addAll(newPatterns);
    }

    /**
     * Return a defensive copy of current patterns.
     */
    public List<String> getPatterns() {
        return new ArrayList<>(patterns);
    }
}
