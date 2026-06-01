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
 * Log4j Filter that ACCEPTS only messages matching configured patterns
 * and DENIES everything else.  Used on the AuditAppender so only
 * high-risk commands get written to the audit file.
 *
 * This is the inverse of LogFilter (which DENIES matching messages
 * to suppress spam).
 */
public class AuditFilter extends AbstractFilter {

    private final List<String> patterns;

    public AuditFilter(List<String> patterns) {
        this.patterns = new ArrayList<>(patterns);
    }

    // ── Event-based (main pipeline path) ───────────────────────────────────

    @Override
    public Result filter(LogEvent event) {
        if (patterns.isEmpty()) {
            return Result.DENY; // nothing to match → don't write
        }

        Message message = event.getMessage();
        if (message == null) {
            return Result.DENY;
        }

        String formatted = message.getFormattedMessage();
        if (formatted == null) {
            return Result.DENY;
        }

        for (String pattern : patterns) {
            if (!pattern.isEmpty() && formatted.contains(pattern)) {
                return Result.ACCEPT; // matched → write to audit file
            }
        }

        return Result.DENY; // no match → skip
    }

    // ── Message-based overloads ────────────────────────────────────────────

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        if (patterns.isEmpty() || msg == null) {
            return Result.DENY;
        }
        String formatted = msg.getFormattedMessage();
        if (formatted == null) {
            return Result.DENY;
        }
        for (String pattern : patterns) {
            if (!pattern.isEmpty() && formatted.contains(pattern)) {
                return Result.ACCEPT;
            }
        }
        return Result.DENY;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
        if (patterns.isEmpty() || msg == null) {
            return Result.DENY;
        }
        for (String pattern : patterns) {
            if (!pattern.isEmpty() && msg.contains(pattern)) {
                return Result.ACCEPT;
            }
        }
        return Result.DENY;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
        if (patterns.isEmpty() || msg == null) {
            return Result.DENY;
        }
        String s = msg.toString();
        for (String pattern : patterns) {
            if (!pattern.isEmpty() && s.contains(pattern)) {
                return Result.ACCEPT;
            }
        }
        return Result.DENY;
    }

    // ── Runtime pattern updates ────────────────────────────────────────────

    public void updatePatterns(List<String> newPatterns) {
        patterns.clear();
        patterns.addAll(newPatterns);
    }

    public List<String> getPatterns() {
        return new ArrayList<>(patterns);
    }
}
