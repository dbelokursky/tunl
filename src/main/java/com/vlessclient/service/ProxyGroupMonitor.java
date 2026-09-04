package com.vlessclient.service;

import com.vlessclient.service.outbound.OutboundTags;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Follows which member of the proxy group the core is routing through.
 *
 * <p>In the automatic selection mode the dashboard named the <em>pinned</em>
 * server while the urltest group carried traffic through whichever member won
 * the last probe: the card claimed one destination and the tunnel used
 * another. Only the core knows the group's current pick, through the Clash
 * API's {@code GET /proxies/{tag}} answer ({@code "now"}), so this polls it
 * while the tunnel is up and publishes the member's tag as an FX property. A
 * pinned server is a group of one, so the answer is right in every mode.</p>
 */
public class ProxyGroupMonitor {

    private static final Logger log = LoggerFactory.getLogger(ProxyGroupMonitor.class);

    /**
     * How often the pick is re-read. urltest re-probes on its own interval
     * (minutes), so a few seconds keeps the card honest without hammering the
     * loopback API.
     */
    static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    private final HttpClient httpClient;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final ReadOnlyStringWrapper currentMemberTag = new ReadOnlyStringWrapper();
    private final Object lifecycleLock = new Object();
    private Thread poller;

    /** Uses a short-timeout client; the loopback API answers at once or not at all. */
    public ProxyGroupMonitor() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    /** Test seam. */
    ProxyGroupMonitor(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * The tag of the member the group currently routes through, or null while
     * it is unknown — not connected, or the core has not answered yet. Set on
     * the FX thread.
     *
     * @return the read-only tag property
     */
    public ReadOnlyStringProperty currentMemberTagProperty() {
        return currentMemberTag.getReadOnlyProperty();
    }

    /**
     * Starts polling the core. No-op while already running.
     *
     * @param port   the Clash API port
     * @param secret the API token, blank when the config has none
     */
    public void start(int port, String secret) {
        synchronized (lifecycleLock) {
            if (poller != null) {
                return;
            }
            String token = secret == null ? "" : secret;
            poller = Thread.ofVirtual().name("proxy-group-monitor").start(() -> poll(port, token));
        }
    }

    /** Stops polling and clears the published pick. Safe to call when idle. */
    public void stop() {
        Thread running;
        synchronized (lifecycleLock) {
            running = poller;
            poller = null;
        }
        if (running != null) {
            running.interrupt();
        }
        publish(null);
    }

    private void poll(int port, String secret) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                // Only a definite answer is published: a failed read (the API
                // still coming up, a transient error) keeps the last pick
                // rather than flickering the card back to "unknown".
                currentMember(port, secret, OutboundTags.PROXY).ifPresent(this::publish);
                Thread.sleep(POLL_INTERVAL);
            }
        } catch (InterruptedException e) {
            // stop(): nothing to unwind.
        }
    }

    /**
     * Asks the core once which member a group currently uses.
     *
     * @param port     the Clash API port
     * @param secret   the API token, blank when the config has none
     * @param groupTag the group's sing-box tag
     * @return the member's tag, or empty when the core is not running, does
     *         not know the group, or answered without a pick
     */
    public Optional<String> currentMember(int port, String secret, String groupTag) {
        if (groupTag == null || groupTag.isBlank() || port < 1) {
            return Optional.empty();
        }
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/proxies/"
                            + URLEncoder.encode(groupTag, StandardCharsets.UTF_8)))
                    .timeout(REQUEST_TIMEOUT)
                    .GET();
            if (secret != null && !secret.isBlank()) {
                request.header("Authorization", "Bearer " + secret);
            }
            HttpResponse<String> response =
                    httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("Group query for {} returned HTTP {}", groupTag, response.statusCode());
                return Optional.empty();
            }
            JsonNode now = mapper.readTree(response.body()).path("now");
            if (!now.isString() || now.asString().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(now.asString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Group query for {} failed: {}", groupTag, e.toString());
            return Optional.empty();
        }
    }

    private void publish(String tag) {
        Runnable set = () -> currentMemberTag.set(tag);
        try {
            if (Platform.isFxApplicationThread()) {
                set.run();
            } else {
                Platform.runLater(set);
            }
        } catch (IllegalStateException e) {
            // No toolkit (a service test): nothing observes the property from
            // another thread, so set it in place.
            set.run();
        }
    }
}
