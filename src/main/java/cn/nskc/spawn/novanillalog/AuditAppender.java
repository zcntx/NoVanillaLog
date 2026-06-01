package cn.nskc.spawn.novanillalog;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Custom Log4j Appender that writes log events to a standalone audit file.
 *
 * Attached to the RootLogger with an {@link AuditFilter} that only passes
 * high-risk command messages.  The audit file is completely separate from
 * the main server log — it's a focused archive of give, economy, op, ban,
 * and other security-relevant operations.
 *
 * Rotation: when the file exceeds {@code maxFileSize} bytes, the current
 * file is renamed to {@code audit-YYYY-MM-DD-HH-mm-ss.log} and a fresh
 * {@code audit.log} is opened.
 */
public class AuditAppender extends AbstractAppender {

    private static final DateTimeFormatter ROTATION_SUFFIX =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
                    .withZone(ZoneId.systemDefault());

    private final Path logFile;
    private final Path logDir;
    private final long maxFileSize;
    private final Object writeLock = new Object();
    private BufferedWriter writer;

    /**
     * @param name        Appender name (must be unique in the logger config).
     * @param filter      The AuditFilter that decides which events reach us.
     * @param logDir      Directory for audit files (created if missing).
     * @param maxFileSize Rotation threshold in bytes (e.g. 10 * 1024 * 1024).
     */
    public AuditAppender(String name, Filter filter, Path logDir, long maxFileSize) {
        super(name, filter, PatternLayout.newBuilder()
                .withPattern("[%d{yyyy-MM-dd HH:mm:ss}] %msg%n")
                .build(), true, null);
        this.logDir = logDir;
        this.logFile = logDir.resolve("audit.log");
        this.maxFileSize = maxFileSize;
    }

    /** Path to the current audit log file. */
    public Path getLogFile() {
        return logFile;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public void start() {
        try {
            Files.createDirectories(logDir);
            openWriter();
        } catch (IOException e) {
            LOGGER.error("AuditAppender: cannot create log dir {}", logDir, e);
        }
        super.start();
    }

    @Override
    public void stop() {
        super.stop();
        synchronized (writeLock) {
            closeWriter();
        }
    }

    // ── Core append ────────────────────────────────────────────────────────

    @Override
    public void append(LogEvent event) {
        if (!isStarted() || event == null) {
            return;
        }

        Layout<? extends Serializable> layout = getLayout();
        if (layout == null) {
            return;
        }

        String line = new String(layout.toByteArray(event), StandardCharsets.UTF_8);

        synchronized (writeLock) {
            try {
                if (needsRotation()) {
                    rotate();
                }
                writer.write(line);
                writer.flush();
            } catch (IOException e) {
                LOGGER.error("AuditAppender: write failed", e);
            }
        }
    }

    // ── Rotation ───────────────────────────────────────────────────────────

    private boolean needsRotation() throws IOException {
        if (!Files.exists(logFile)) {
            return false;
        }
        return Files.size(logFile) >= maxFileSize;
    }

    private void rotate() throws IOException {
        closeWriter();

        // If there's already content, rename it with a timestamp suffix
        if (Files.exists(logFile) && Files.size(logFile) > 0) {
            String ts = ROTATION_SUFFIX.format(Instant.now());
            Path rotated = logDir.resolve("audit-" + ts + ".log");

            // Avoid collision: append counter if target already exists
            int counter = 1;
            while (Files.exists(rotated)) {
                rotated = logDir.resolve("audit-" + ts + "-" + counter + ".log");
                counter++;
            }

            Files.move(logFile, rotated);
        }

        openWriter();
    }

    // ── Writer helpers ─────────────────────────────────────────────────────

    private void openWriter() throws IOException {
        writer = Files.newBufferedWriter(
                logFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }

    private void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
            writer = null;
        }
    }
}
