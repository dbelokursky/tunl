package com.vlessclient.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
public class ClashApiDelayProbe implements AutoCloseable {

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
     * Releases the HTTP client. Each one keeps a selector thread, a virtual
     * one on this JDK, until it is closed or collected, and on Windows that
     * thread holds a carrier the whole time it waits for I/O.
     */
    @Override
    public void close() {
        httpClient.shutdownNow();
    }

    /** What the core answered about one proxy. */
    public sealed interface Answer {

        /**
         * A request went through the proxy.
         *
         * @param millis how long it took
         */
        record Delay(long millis) implements Answer {
        }

        /**
         * The core tried the proxy and it did not carry the request: sing-box
         * answers 504 when the test times out and 503 when it fails, the
         * handshake of a server whose credentials expired included.
         */
        record Failed() implements Answer {
        }

        /**
         * Nothing is known about the proxy: the core is not running, does not
         * know the tag (404), or the request was not understood.
         */
        record NoAnswer() implements Answer {
        }
    }

    /**
     * Measures the delay through one proxy.
     *
     * @param port   the Clash API port the core listens on
     * @param secret the API token, blank when the config has none
     * @param tag    the proxy's sing-box tag
     * @return the delay; {@link Answer.Failed} when the proxy was tried and does
     *         not work; {@link Answer.NoAnswer} when nothing is known about it,
     *         which is not an error to surface
     */
    public Answer measure(int port, String secret, String tag) {
        return measure(port, secret, tag, PROBE_URL);
    }

    /**
     * Measures the delay of a request to {@code target} through one proxy.
     *
     * @param port   the Clash API port the core listens on
     * @param secret the API token, blank when the config has none
     * @param tag    the proxy's (or group's) sing-box tag
     * @param target the URL the core requests through it
     * @return as {@link #measure(int, String, String)}
     */
    public Answer measure(int port, String secret, String tag, String target) {
        if (tag == null || tag.isBlank() || port < 1) {
            return new Answer.NoAnswer();
        }
        try {
            String url = "http://127.0.0.1:" + port + "/proxies/"
                    + URLEncoder.encode(tag, StandardCharsets.UTF_8)
                    + "/delay?timeout=" + PROBE_TIMEOUT_MS
                    + "&url=" + URLEncoder.encode(target, StandardCharsets.UTF_8);

            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(PROBE_TIMEOUT_MS + 2000))
                    .GET();
            if (secret != null && !secret.isBlank()) {
                request.header("Authorization", "Bearer " + secret);
            }

            HttpResponse<String> response =
                    httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 503 || status == 504) {
                // The proxy's own answer: it was tried and does not work. Read
                // as "no answer", it sent the caller to a TCP connect, which a
                // server with expired credentials passes in 40 ms.
                log.debug("Delay probe for {}: the proxy failed (HTTP {})", tag, status);
                return new Answer.Failed();
            }
            if (status != 200) {
                log.debug("Delay probe for {} returned HTTP {}", tag, status);
                return new Answer.NoAnswer();
            }

            JsonNode body = mapper.readTree(response.body());
            long delay = body.path("delay").asLong(-1);
            return delay >= 0 ? new Answer.Delay(delay) : new Answer.NoAnswer();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer.NoAnswer();
        } catch (Exception e) {
            log.debug("Delay probe for {} failed: {}", tag, e.toString());
            return new Answer.NoAnswer();
        }
    }
}
