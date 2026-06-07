package cn.nskc.spawn.novanillalog;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Log4j Filter that suppresses log messages matching configured patterns.
 * Registered on the RootLogger so it intercepts ALL log output (Minecraft + plugins).
 */
public class LogFilter extends AbstractFilter {

    private final List<String> patterns;

    public LogFilter(List<String> patterns) {
        this.patterns = new ArrayList<>(patterns);
    }

    @Override
    public Result filter(LogEvent event) {
        if (patterns.isEmpty()) {
            return Result.NEUTRAL;
        }

        Message message = event.getMessage();
        if (message == null) {
            return Result.NEUTRAL;
        }

        String formatted = message.getFormattedMessage();
        if (formatted == null) {
            return Result.NEUTRAL;
        }

        for (String pattern : patterns) {
            if (!pattern.isEmpty() && containsIgnoreCase(formatted, pattern)) {
                return Result.DENY;
            }
        }

        return Result.NEUTRAL;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        if (patterns.isEmpty() || msg == null) {
            return Result.NEUTRAL;
        }
        String formatted = msg.getFormattedMessage();
        if (formatted == null) {
            return Result.NEUTRAL;
        }
        for (String pattern : patterns) {
            if (!pattern.isEmpty() && containsIgnoreCase(formatted, pattern)) {
                return Result.DENY;
            }
        }
        return Result.NEUTRAL;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
        if (patterns.isEmpty() || msg == null) {
            return Result.NEUTRAL;
        }
        for (String pattern : patterns) {
            if (!pattern.isEmpty() && containsIgnoreCase(msg, pattern)) {
                return Result.DENY;
            }
        }
        return Result.NEUTRAL;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
        if (patterns.isEmpty() || msg == null) {
            return Result.NEUTRAL;
        }
        String s = msg.toString();
        for (String pattern : patterns) {
            if (!pattern.isEmpty() && containsIgnoreCase(s, pattern)) {
                return Result.DENY;
            }
        }
        return Result.NEUTRAL;
    }

    private static boolean containsIgnoreCase(String text, String pattern) {
        return text.toLowerCase().contains(pattern.toLowerCase());
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
