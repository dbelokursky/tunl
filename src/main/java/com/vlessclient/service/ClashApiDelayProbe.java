package com.vlessclient.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks the running core how long a request through a given proxy actually
 * takes, via the Clash API's {@code /proxies/{tag}/delay} endpoint.
 *
 * <p><b>Why not a TCP connect.</b> Opening a socket to {@code host:port}
 * measures the path from this machine to that address and nothing more. It
 * says nothing about whether the proxy handshake succeeds, whether the
 * credentials are still valid, or whether the server is throttled or blocked —
 * a server that is reachable but unusable answers a TCP connect in 40 ms and
 * looks like the best one in the list. This probe sends a real request through
 * the proxy, so a failure means the proxy, not the route to it.</p>
 *
 * <p><b>What it cannot do.</b> The core only knows about proxies present in
 * the config it is running, so this works for servers in the active proxy
 * group and only while connected. Callers fall back to the TCP measurement
 * otherwise rather than showing nothing.</p>
 */
public class ClashApiDelayProbe {

    private static final Logger log = LoggerFactory.getLogger(ClashApiDelayProbe.class);

    /**
     * A 204-no-content target: small, unauthenticated and widely reachable, so
     * a failure points at the proxy rather than at the destination.
     */
    private static final String PROBE_URL = "https://www.gstatic.com/generate_204";
    private static final int PROBE_TIMEOUT_MS = 5000;

    private final HttpClient httpClient;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    /** Uses a short-timeout client; the endpoint answers in probe time. */
    public ClashApiDelayProbe() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build());
    }

    /** Test seam. */
    ClashApiDelayProbe(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Measures the delay through one proxy.
     *
     * @param port   the Clash API port the core listens on
     * @param secret the API token, blank when the config has none
     * @param tag    the proxy's sing-box tag
     * @return the delay in milliseconds, or empty when the core is not running,
     *         does not know the tag, or the probe failed — all of which mean
     *         "no usable answer" rather than an error to surface
     */
    public Optional<Long> measure(int port, String secret, String tag) {
        if (tag == null || tag.isBlank() || port < 1) {
            return Optional.empty();
        }
        try {
            String url = "http://127.0.0.1:" + port + "/proxies/"
                    + URLEncoder.encode(tag, StandardCharsets.UTF_8)
                    + "/delay?timeout=" + PROBE_TIMEOUT_MS
                    + "&url=" + URLEncoder.encode(PROBE_URL, StandardCharsets.UTF_8);

            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(PROBE_TIMEOUT_MS + 2000))
                    .GET();
            if (secret != null && !secret.isBlank()) {
                request.header("Authorization", "Bearer " + secret);
            }

            HttpResponse<String> response =
                    httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // A timing-out proxy answers with a non-200 and a message; that
                // is a real result ("unusable"), not a transport problem.
                log.debug("Delay probe for {} returned HTTP {}", tag, response.statusCode());
                return Optional.empty();
            }

            JsonNode body = mapper.readTree(response.body());
            long delay = body.path("delay").asLong(-1);
            return delay >= 0 ? Optional.of(delay) : Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Delay probe for {} failed: {}", tag, e.toString());
            return Optional.empty();
        }
    }
}
