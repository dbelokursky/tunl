package com.vlessclient.service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.collections.ObservableList;

/**
 * Reads an {@link InputStream} line by line in a daemon thread and hands the
 * lines to a JavaFX {@link ObservableList} in batches. Trims the list to a
 * maximum number of lines and detects the sing-box "started" message to signal
 * a successful connection.
 *
 * <p>Each line used to be its own FX task: an addition and, once the list was
 * full, a removal, and the Logs view reacted to both. At debug level the core
 * writes thousands of lines a second. One task now takes every line read by
 * the time it runs.</p>
 */
public class LogReader {

    /** Matches ANSI CSI SGR escape sequences (e.g. {@code \u001B[31m}, {@code \u001B[0m}). */
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\x1B\\[[\\d;]*[A-Za-z]");

    private final InputStream inputStream;
    private final ObservableList<String> logLines;
    private final int maxLines;
    private final Consumer<String> onStartedDetected;
    private volatile Thread readerThread;

    /** Lines read and not yet on the list, oldest first. */
    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();

    /** Set while an FX task that will take {@link #pending} is queued. */
    private final AtomicBoolean handOverQueued = new AtomicBoolean();

    /**
     * Creates a new LogReader.
     *
     * @param inputStream       the input stream to read from (typically process stdout)
     * @param logLines          the observable list to append log lines to
     * @param maxLines          maximum number of lines to retain (ring buffer behavior)
     * @param onStartedDetected callback invoked when a "started" message is detected in the output
     */
    public LogReader(InputStream inputStream,
                     ObservableList<String> logLines,
                     int maxLines,
                     Consumer<String> onStartedDetected) {
        this.inputStream = inputStream;
        this.logLines = logLines;
        this.maxLines = maxLines;
        this.onStartedDetected = onStartedDetected;
    }

    /**
     * Starts reading the input stream in a background daemon thread.
     * Lines are appended to the observable list on the JavaFX Application Thread.
     */
    public void start() {
        readerThread = new Thread(this::readLoop, "singbox-log-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * Interrupts the reader thread, causing it to stop.
     */
    public void stop() {
        Thread t = readerThread;
        if (t != null) {
            t.interrupt();
        }
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String logLine = stripAnsi(line);
                handOver(logLine);

                if (isStartedMessage(logLine)) {
                    onStartedDetected.accept(logLine);
                }
            }
        } catch (Exception e) {
            if (!(e instanceof InterruptedException)) {
                handOver("Log reader error: " + e.getMessage());
            }
        }
    }

    /** Removes ANSI color escape codes from {@code line}. */
    static String stripAnsi(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        return ANSI_ESCAPE.matcher(line).replaceAll("");
    }

    /** Queues a line for the list, and the FX task that takes it unless one is queued. */
    private void handOver(String line) {
        pending.add(line);
        if (handOverQueued.compareAndSet(false, true)) {
            Platform.runLater(this::appendPending);
        }
    }

    /**
     * Moves every queued line to the list in one addition, then trims the
     * oldest in one removal. The flag is cleared first, so a line queued while
     * this runs is either taken here or queues the next task.
     */
    private void appendPending() {
        handOverQueued.set(false);
        List<String> batch = new ArrayList<>();
        String line = pending.poll();
        while (line != null) {
            batch.add(line);
            line = pending.poll();
        }
        if (batch.isEmpty()) {
            return;
        }
        // Lines past the buffer's size would only be removed again.
        logLines.addAll(batch.subList(Math.max(0, batch.size() - maxLines), batch.size()));
        int excess = logLines.size() - maxLines;
        if (excess > 0) {
            logLines.remove(0, excess);
        }
    }

    /**
     * Detects whether a log line indicates that sing-box has successfully started.
     *
     * @param line the log line to check
     * @return true if the line contains a "started" indicator
     */
    private boolean isStartedMessage(String line) {
        String lower = line.toLowerCase();
        return lower.contains("sing-box started") || lower.contains("started");
    }
}
