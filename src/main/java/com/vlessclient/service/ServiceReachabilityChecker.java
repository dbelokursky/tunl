package com.vlessclient.service;

import com.vlessclient.model.HealthCheckTarget;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Probes whether well-known services (e.g. google.com, x.com) are reachable
 * <em>through the running tunnel</em>, by issuing HTTP requests via the local
 * proxy inbound that sing-box always exposes on {@code 127.0.0.1:<httpPort>}
 * (present in both SYSTEM_PROXY and TUN modes).
 *
 * <p>Modeled on {@link LatencyTester}: a small daemon thread pool, async
 * {@link CompletableFuture} results, and silent failures (a probe that throws
 * simply counts as unreachable). The real HTTP work sits behind the
 * {@link ProbeExecutor} seam so tests can run deterministically without
 * touching the network.</p>
 */
public class ServiceReachabilityChecker {

    private static final Logger log = LoggerFactory.getLogger(ServiceReachabilityChecker.class);

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int REQUEST_TIMEOUT_MS = 5000;
    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 1500;
    private static final int POOL_SIZE = 4;

    /** Port a bare IP / host target is probed on when none is given. */
    static final int DEFAULT_TCP_PORT = 443;

    // Matches a host:port or bare host/IP target (no scheme). IPv6 must be
    // bracketed to carry a port, e.g. [2606:4700:4700::1111]:53.
    private static final Pattern BRACKETED_IPV6 =
            Pattern.compile("^\\[(?<host>[0-9A-Fa-f:]+)](?::(?<port>\\d{1,5}))?$");
    private static final Pattern HOST_PORT =
            Pattern.compile("^(?<host>[^:/\\s]+)(?::(?<port>\\d{1,5}))?$");

    /**
     * Outcome of probing a single {@link HealthCheckTarget}.
     *
     * @param name      display name of the service
     * @param url       URL that was probed
     * @param reachable whether any HTTP response came back through the proxy
     * @param latencyMs round-trip time in ms, or -1 when unreachable
     * @param detail    short human-readable status (e.g. "HTTP 204", "timeout")
     */
    public record ProbeResult(
            String name, String url, boolean reachable, long latencyMs, String detail) {
    }

    /**
     * Seam over the act of probing one target through the proxy. The default
     * implementation does real HTTP; tests inject a deterministic stub.
     */
    @FunctionalInterface
    public interface ProbeExecutor {
        ProbeResult probe(HealthCheckTarget target, int httpProxyPort);
    }

    private final ExecutorService executor;
    private final ProbeExecutor probeExecutor;

    private volatile HttpClient cachedClient;
    private volatile int cachedPort = -1;

    public ServiceReachabilityChecker() {
        this(null);
    }

    /**
     * Creates a checker using the given probe implementation.
     *
     * @param probeExecutor custom probe implementation, or {@code null} to use
     *                      the real HTTP-through-proxy probe
     */
    public ServiceReachabilityChecker(ProbeExecutor probeExecutor) {
        this.executor = Executors.newFixedThreadPool(POOL_SIZE,
                DaemonThreads.factory("reachability-checker"));
        this.probeExecutor = probeExecutor != null ? probeExecutor : this::realProbe;
    }

    /**
     * Probes every target concurrently and completes with one
     * {@link ProbeResult} per target, in the same order as the input list.
     */
    public CompletableFuture<List<ProbeResult>> checkAll(
            List<HealthCheckTarget> targets, int httpProxyPort) {
        if (targets == null || targets.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        List<CompletableFuture<ProbeResult>> futures = targets.stream()
                .map(target -> CompletableFuture.supplyAsync(
                        () -> probeExecutor.probe(target, httpProxyPort), executor))
                .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<ProbeResult> results = new ArrayList<>(futures.size());
                    for (CompletableFuture<ProbeResult> future : futures) {
                        results.add(future.join());
                    }
                    return results;
                });
    }

    /**
     * True when there is at least one result and <em>every</em> result is
     * unreachable — the signal that the tunnel is broken and a reconnect is
     * warranted. An empty list returns false (nothing to conclude).
     */
    public static boolean allUnreachable(List<ProbeResult> results) {
        if (results == null || results.isEmpty()) {
            return false;
        }
        return results.stream().noneMatch(ProbeResult::reachable);
    }

    /** An IP/host[:port] target parsed into its host and probe port. */
    public record HostPort(String host, int port) {
    }

    /**
     * Parses a scheme-less target ("1.1.1.1", "1.1.1.1:53",
     * "[2606:4700:4700::1111]:53", "example.com:8443") into host + port,
     * defaulting to {@link #DEFAULT_TCP_PORT}. Returns null for an http(s)
     * URL (which is probed over HTTP instead) or an unparseable value.
     */
    public static HostPort parseHostPort(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty() || s.contains("://")) {
            return null;
        }
        var bracketed = BRACKETED_IPV6.matcher(s);
        if (bracketed.matches()) {
            return withPort(bracketed.group("host"), bracketed.group("port"));
        }
        // A bare (unbracketed) IPv6 literal has multiple colons and no port.
        if (s.chars().filter(c -> c == ':').count() > 1) {
            return s.matches("[0-9A-Fa-f:]+") ? new HostPort(s, DEFAULT_TCP_PORT) : null;
        }
        var hp = HOST_PORT.matcher(s);
        if (hp.matches()) {
            return withPort(hp.group("host"), hp.group("port"));
        }
        return null;
    }

    private static HostPort withPort(String host, String portGroup) {
        if (host == null || host.isBlank()) {
            return null;
        }
        int port = DEFAULT_TCP_PORT;
        if (portGroup != null) {
            try {
                port = Integer.parseInt(portGroup);
            } catch (NumberFormatException e) {
                return null;
            }
            if (port < 1 || port > 65535) {
                return null;
            }
        }
        return new HostPort(host, port);
    }

    private ProbeResult realProbe(HealthCheckTarget target, int httpProxyPort) {
        String name = target.getName() != null ? target.getName() : target.getUrl();
        String url = target.getUrl();
        if (url == null || url.isBlank()) {
            log.warn("Reachability probe skipped for '{}': url is null or blank", name);
            return new ProbeResult(name, url, false, -1, "no url");
        }

        // A scheme-less IP/host[:port] is checked with a TCP-connect probe
        // through the proxy — bare IPs usually aren't HTTP servers, so an
        // HTTP request would mislabel a perfectly reachable host as down.
        HostPort hostPort = parseHostPort(url);
        if (hostPort != null) {
            return tcpProbe(name, url, hostPort, httpProxyPort);
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            log.warn("Reachability probe skipped for '{}': invalid url '{}'", name, url);
            return new ProbeResult(name, url, false, -1, "invalid url");
        }

        HttpClient client = clientFor(httpProxyPort);
        String lastDetail = "unreachable";

        // Retry a couple of times so a freshly-established tunnel (the proxy /
        // clash listeners may still be warming up right after CONNECTED) is
        // not falsely reported as broken.
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                    .build();
            long start = System.nanoTime();
            try {
                HttpResponse<Void> response =
                        client.send(request, HttpResponse.BodyHandlers.discarding());
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                // Any HTTP status means we reached the service through the proxy.
                log.debug("Reachable: {} -> HTTP {} in {} ms", url, response.statusCode(), elapsed);
                return new ProbeResult(name, url, true, elapsed, "HTTP " + response.statusCode());
            } catch (Exception e) {
                lastDetail = describe(e);
                log.debug("Probe attempt {}/{} failed for {} ({})",
                        attempt, MAX_ATTEMPTS, url, lastDetail);
                if (attempt < MAX_ATTEMPTS && sleepBeforeRetry()) {
                    continue;
                }
            }
        }
        return new ProbeResult(name, url, false, -1, lastDetail);
    }

    /**
     * Probes a raw TCP endpoint through the tunnel by issuing an HTTP
     * {@code CONNECT host:port} to the local proxy: a {@code 200} means the
     * proxy established a connection to the target through sing-box.
     * Latency is the CONNECT round-trip.
     */
    private ProbeResult tcpProbe(
            String name, String display, HostPort hostPort, int httpProxyPort) {
        String target = hostPort.host() + ":" + hostPort.port();
        String connect = "CONNECT " + target + " HTTP/1.1\r\n"
                + "Host: " + target + "\r\n"
                + "Proxy-Connection: close\r\n\r\n";
        String lastDetail = "unreachable";

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long start = System.nanoTime();
            try (Socket socket = new Socket()) {
                socket.connect(
                        new InetSocketAddress("127.0.0.1", httpProxyPort), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(REQUEST_TIMEOUT_MS);
                OutputStream out = socket.getOutputStream();
                out.write(connect.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                String statusLine = readStatusLine(socket.getInputStream());
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                int status = parseStatusCode(statusLine);
                if (status == 200) {
                    log.debug("Reachable (TCP): {} via proxy in {} ms", target, elapsed);
                    return new ProbeResult(name, display, true, elapsed, "TCP " + hostPort.port());
                }
                // The proxy answered but couldn't reach the target (e.g. 502/504).
                lastDetail = statusLine != null && !statusLine.isBlank()
                        ? statusLine.strip() : "no proxy response";
                log.debug("TCP probe {}/{} for {}: {}", attempt, MAX_ATTEMPTS, target, lastDetail);
            } catch (IOException e) {
                lastDetail = describe(e);
                log.debug("TCP probe {}/{} failed for {} ({})",
                        attempt, MAX_ATTEMPTS, target, lastDetail);
            }
            if (attempt < MAX_ATTEMPTS && !sleepBeforeRetry()) {
                break;
            }
        }
        return new ProbeResult(name, display, false, -1, lastDetail);
    }

    /** Reads the first CRLF-terminated line (the HTTP status line). */
    private static String readStatusLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.append((char) c);
            }
            if (sb.length() > 512) {
                break;   // not a well-formed status line; stop reading
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Extracts the numeric status from "HTTP/1.1 200 Connection established". */
    private static int parseStatusCode(String statusLine) {
        if (statusLine == null) {
            return -1;
        }
        String[] parts = statusLine.strip().split("\\s+");
        if (parts.length < 2) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private boolean sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String describe(Exception e) {
        if (e instanceof java.net.http.HttpTimeoutException) {
            return "timeout";
        }
        String msg = e.getMessage();
        return msg != null && !msg.isBlank() ? msg : e.getClass().getSimpleName();
    }

    /**
     * Returns an {@link HttpClient} bound to the proxy at the given port,
     * rebuilding only when the port changes (the common case is a stable port,
     * so the client is reused across probes and reconnect cycles).
     */
    private synchronized HttpClient clientFor(int httpProxyPort) {
        if (cachedClient != null && cachedPort == httpProxyPort) {
            return cachedClient;
        }
        if (cachedClient != null) {
            cachedClient.close();
        }
        cachedClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", httpProxyPort)))
                .build();
        cachedPort = httpProxyPort;
        return cachedClient;
    }

    /**
     * Shuts down the probe thread pool and closes any cached HTTP client.
     */
    public void shutdown() {
        executor.shutdownNow();
        synchronized (this) {
            if (cachedClient != null) {
                cachedClient.close();
                cachedClient = null;
                cachedPort = -1;
            }
        }
    }
}
