package com.vlessclient.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.SimpleLongProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Streams live upload/download traffic stats from the sing-box Clash API and
 * exposes them as JavaFX observable properties for UI binding.
 *
 * <p>Opens a server-sent-events connection to {@code /traffic} on a virtual
 * thread, reconnecting with a bounded back-off until the stream attaches or
 * {@link #stop()} is called.</p>
 */
public class TrafficMonitor {

    private static final Logger log = LoggerFactory.getLogger(TrafficMonitor.class);
    private static final ObjectMapper objectMapper = JsonMapper.builder().build();

    private static final long KB = 1024L;
    private static final long MB = 1024L * 1024L;
    private static final long GB = 1024L * 1024L * 1024L;

    // The clash_api server is started by sing-box only after routes (and any
    // remote rule_sets) finish loading, so the first few connect attempts can
    // hit "connection refused" before the listener is up. Reconnect with a
    // bounded back-off until the stream attaches or stop() is called.
    private static final long INITIAL_BACKOFF_MS = 300L;
    private static final long MAX_BACKOFF_MS = 5_000L;

    private final LongProperty uploadSpeed = new SimpleLongProperty(0);
    private final LongProperty downloadSpeed = new SimpleLongProperty(0);
    private final LongProperty totalUpload = new SimpleLongProperty(0);
    private final LongProperty totalDownload = new SimpleLongProperty(0);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object lifecycleLock = new Object();
    private volatile Thread sseThread;
    private volatile String clashApiSecret = "";

    // One client for the monitor's lifetime: the reconnect back-off loop can
    // make many attempts, and each HttpClient owns its own executor/selector.
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public ReadOnlyLongProperty uploadSpeedProperty() {
        return uploadSpeed;
    }

    public ReadOnlyLongProperty downloadSpeedProperty() {
        return downloadSpeed;
    }

    public ReadOnlyLongProperty totalUploadProperty() {
        return totalUpload;
    }

    public ReadOnlyLongProperty totalDownloadProperty() {
        return totalDownload;
    }

    /**
     * Starts streaming traffic stats from the Clash API on the given port.
     * Does nothing if a monitor is already running.
     *
     * @param clashApiPort the port the sing-box Clash API listens on
     * @param secret the clash_api auth token the core expects (may be blank
     *               for configs without one); sent as a Bearer header
     */
    public void start(int clashApiPort, String secret) {
        synchronized (lifecycleLock) {
            if (running.getAndSet(true)) {
                log.warn("TrafficMonitor is already running");
                return;
            }
            clashApiSecret = secret == null ? "" : secret;

            sseThread = Thread.ofVirtual().name("traffic-monitor").start(() -> {
                log.info("TrafficMonitor started, connecting to Clash API on port {}",
                        clashApiPort);
                try {
                    connectAndStream(clashApiPort);
                } catch (Exception e) {
                    if (running.get()) {
                        log.error("TrafficMonitor SSE stream failed", e);
                    }
                } finally {
                    running.set(false);
                    log.info("TrafficMonitor stopped");
                }
            });
        }
    }

    /** Puts the live readouts back to zero on the FX thread. */
    private void zeroSpeeds() {
        Platform.runLater(() -> {
            uploadSpeed.set(0);
            downloadSpeed.set(0);
        });
    }

    /**
     * Stops the monitor and resets the live speed properties to zero. Blocks
     * until the streaming thread has finished so callers can treat it as
     * synchronous. Safe to call when not running.
     */
    public void stop() {
        Thread thread;
        synchronized (lifecycleLock) {
            if (!running.getAndSet(false)) {
                // Already stopped - but not necessarily by us. The SSE thread
                // clears the flag itself when the stream ends, so a dropped
                // connection lands here and the early return used to leave the
                // last speeds frozen on the dashboard, reading as live traffic
                // over a tunnel that is gone.
                zeroSpeeds();
                return;
            }
            thread = sseThread;
            sseThread = null;
        }
        if (thread != null) {
            thread.interrupt();
            try {
                // Wait for the SSE thread to finish before returning so callers
                // can rely on stop() being synchronous.
                thread.join(Duration.ofSeconds(2).toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        zeroSpeeds();
        log.info("TrafficMonitor stop requested");
    }

    private void connectAndStream(int clashApiPort) throws Exception {
        long backoff = INITIAL_BACKOFF_MS;
        while (running.get()) {
            try {
                streamOnce(clashApiPort);
                return;
            } catch (java.net.ConnectException e) {
                if (!running.get()) {
                    return;
                }
                log.debug("Clash API not yet reachable on port {} ({}); "
                        + "retrying in {} ms", clashApiPort, e.getMessage(), backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
    }

    private void streamOnce(int clashApiPort) throws Exception {
        HttpRequest request = buildTrafficRequest(clashApiPort, clashApiSecret);

        HttpResponse<java.io.InputStream> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            log.error("Clash API returned status {}", response.statusCode());
            return;
        }

        log.info("TrafficMonitor connected to Clash API on port {}", clashApiPort);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body()))) {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                processTrafficLine(line);
            }
        }
    }

    /**
     * The {@code /traffic} request, carrying {@code Authorization: Bearer
     * <secret>} when the core's clash_api has a secret set. Without the header
     * a secured clash_api answers 401, so an eavesdropping local process can't
     * read the stream.
     */
    static HttpRequest buildTrafficRequest(int clashApiPort, String secret) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + clashApiPort + "/traffic"))
                .GET();
        if (secret != null && !secret.isBlank()) {
            b.header("Authorization", "Bearer " + secret);
        }
        return b.build();
    }

    void processTrafficLine(String line) {
        if (line.isBlank()) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(line);
            long up = node.path("up").asLong(0);
            long down = node.path("down").asLong(0);

            Platform.runLater(() -> {
                // Safety note: the read-modify-write on totalUpload/totalDownload is
                // not atomic in isolation, but all mutations to these JavaFX
                // properties are funneled through Platform.runLater, so they execute
                // serially on the JavaFX application thread. No additional
                // synchronization is required.
                uploadSpeed.set(up);
                downloadSpeed.set(down);
                totalUpload.set(totalUpload.get() + up);
                totalDownload.set(totalDownload.get() + down);
            });
        } catch (Exception e) {
            log.debug("Failed to parse traffic line: {}", line, e);
        }
    }

    /**
     * Formats a byte-per-second rate as a human-readable string (B/s, KB/s,
     * MB/s or GB/s). Negative values are treated as zero.
     *
     * @param bytesPerSec the rate in bytes per second
     * @return the formatted speed string
     */
    public static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec < 0) {
            return "0 B/s";
        }
        if (bytesPerSec < KB) {
            return bytesPerSec + " B/s";
        }
        if (bytesPerSec < MB) {
            return String.format(Locale.US, "%.1f KB/s", bytesPerSec / (double) KB);
        }
        if (bytesPerSec < GB) {
            return String.format(Locale.US, "%.1f MB/s", bytesPerSec / (double) MB);
        }
        return String.format(Locale.US, "%.2f GB/s", bytesPerSec / (double) GB);
    }

    /**
     * Formats a byte count as a human-readable string (B, KB, MB or GB).
     * Negative values are treated as zero.
     *
     * @param bytes the number of bytes
     * @return the formatted byte-count string
     */
    public static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "0 B";
        }
        if (bytes < KB) {
            return bytes + " B";
        }
        if (bytes < MB) {
            return String.format(Locale.US, "%.1f KB", bytes / (double) KB);
        }
        if (bytes < GB) {
            return String.format(Locale.US, "%.1f MB", bytes / (double) MB);
        }
        return String.format(Locale.US, "%.2f GB", bytes / (double) GB);
    }
}
