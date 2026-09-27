package com.vlessclient.service;

import com.vlessclient.model.HealthCheckTarget;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Probes whether well-known services (e.g. google.com, x.com) are reachable
 * <em>through the running tunnel</em>, by way of the core's probe inbound on
 * {@code 127.0.0.1:<probePort>}. The route sends that inbound into the tunnel
 * ahead of every rule, so a probe tests the tunnel whatever the routing mode,
 * the bypass list or a direct rule would do with its target.
 *
 * <p>Every attempt opens a connection of its own through the inbound, a SOCKS5
 * one, where the core answers once the tunnel has carried the connection or
 * failed to; an http(s) target is then asked a {@code HEAD} on it, over TLS
 * for https. Two ways of asking passed through a dead tunnel. The core's HTTP
 * inbound answers a {@code CONNECT} with 200 before it dials, and a plain
 * request it could not dial with a 502 of its own. And a client kept between
 * rounds kept its HTTP/2 connection, which went on answering while the tunnel
 * could no longer open a new one: for ten minutes the services read reachable,
 * and were not.</p>
 *
 * <p>Modeled on {@link LatencyTester}: a small daemon thread pool, async
 * {@link CompletableFuture} results, and silent failures (a probe that throws
 * simply counts as unreachable). The real work sits behind the
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

    // RFC 1928 and RFC 1929, as much of them as a CONNECT needs.
    private static final int SOCKS_VERSION = 5;
    private static final int NO_AUTH = 0;
    private static final int PASSWORD_AUTH = 2;
    private static final int PASSWORD_AUTH_VERSION = 1;
    private static final int CONNECT = 1;
    private static final int IPV4 = 1;
    private static final int DOMAIN = 3;
    private static final int IPV6 = 4;

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
     * @param reachable whether the target answered through the tunnel
     * @param latencyMs round-trip time in ms, or -1 when unreachable
     * @param detail    short human-readable status (e.g. "HTTP 204", "timeout")
     */
    public record ProbeResult(
            String name, String url, boolean reachable, long latencyMs, String detail) {
    }

    /**
     * Seam over the act of probing one target through the probe inbound. The
     * default implementation goes through the core; tests inject a
     * deterministic stub.
     */
    @FunctionalInterface
    public interface ProbeExecutor {
        ProbeResult probe(HealthCheckTarget target, int probePort);
    }

    /** One try at a probe: says what answered, or throws when nothing did. */
    @FunctionalInterface
    private interface Attempt {
        String run() throws IOException;
    }

    private final ExecutorService executor;
    private final ProbeExecutor probeExecutor;
    private final SSLSocketFactory tls;

    public ServiceReachabilityChecker() {
        this(null);
    }

    /**
     * Creates a checker using the given probe implementation.
     *
     * @param probeExecutor custom probe implementation, or {@code null} to use
     *                      the real probe through the core
     */
    public ServiceReachabilityChecker(ProbeExecutor probeExecutor) {
        this(probeExecutor, null);
    }

    /**
     * Test seam: the TLS an https probe speaks, so that a test can trust a
     * site of its own.
     *
     * @param probeExecutor custom probe implementation, or {@code null}
     * @param tls           the TLS to speak, or {@code null} for the JDK's
     *                      default, which trusts the system's certificates
     */
    ServiceReachabilityChecker(ProbeExecutor probeExecutor, SSLContext tls) {
        this.executor = Executors.newFixedThreadPool(POOL_SIZE,
                DaemonThreads.factory("reachability-checker"));
        this.probeExecutor = probeExecutor != null ? probeExecutor : this::realProbe;
        this.tls = tls != null
                ? tls.getSocketFactory() : (SSLSocketFactory) SSLSocketFactory.getDefault();
    }

    /**
     * Probes every target concurrently and completes with one
     * {@link ProbeResult} per target, in the same order as the input list.
     *
     * @param targets   the targets
     * @param probePort the core's probe inbound, {@code AppSettings#listenProbePort()}
     * @return one result per target, in order
     */
    public CompletableFuture<List<ProbeResult>> checkAll(
            List<HealthCheckTarget> targets, int probePort) {
        if (targets == null || targets.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        List<CompletableFuture<ProbeResult>> futures = targets.stream()
                .map(target -> CompletableFuture.supplyAsync(
                        () -> probeExecutor.probe(target, probePort), executor))
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

    private ProbeResult realProbe(HealthCheckTarget target, int probePort) {
        String name = target.getName() != null ? target.getName() : target.getUrl();
        String url = target.getUrl();
        if (url == null || url.isBlank()) {
            log.warn("Reachability probe skipped for '{}': url is null or blank", name);
            return new ProbeResult(name, url, false, -1, "no url");
        }

        // A scheme-less IP/host[:port] is checked by opening a connection to
        // it through the tunnel: bare IPs usually aren't HTTP servers, so an
        // HTTP request would mislabel a perfectly reachable host as down.
        HostPort hostPort = parseHostPort(url);
        if (hostPort != null) {
            return attempt(name, url, () -> {
                throughTunnel(hostPort.host(), hostPort.port(), probePort).close();
                return "TCP " + hostPort.port();
            });
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            log.warn("Reachability probe skipped for '{}': invalid url '{}'", name, url);
            return new ProbeResult(name, url, false, -1, "invalid url");
        }
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
        if (uri.getHost() == null || !List.of("http", "https").contains(scheme)) {
            log.warn("Reachability probe skipped for '{}': invalid url '{}'", name, url);
            return new ProbeResult(name, url, false, -1, "invalid url");
        }
        return attempt(name, url, () -> headStatus(uri, scheme.equals("https"), probePort));
    }

    /**
     * Makes up to {@link #MAX_ATTEMPTS} attempts, so that a tunnel whose
     * listeners are still warming up right after CONNECTED is not reported
     * broken, and times the one that answered.
     */
    private ProbeResult attempt(String name, String url, Attempt attempt) {
        String lastDetail = "unreachable";
        for (int i = 1; i <= MAX_ATTEMPTS; i++) {
            long start = System.nanoTime();
            try {
                String detail = attempt.run();
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                log.debug("Reachable: {} ({}) in {} ms", url, detail, elapsed);
                return new ProbeResult(name, url, true, elapsed, detail);
            } catch (IOException e) {
                lastDetail = describe(e);
                log.debug("Probe attempt {}/{} failed for {} ({})",
                        i, MAX_ATTEMPTS, url, lastDetail);
            }
            if (i < MAX_ATTEMPTS && !sleepBeforeRetry()) {
                break;
            }
        }
        return new ProbeResult(name, url, false, -1, lastDetail);
    }

    /**
     * Sends a {@code HEAD} to an http(s) target over a connection of its own
     * through the tunnel, and gives the status its server answered with. Any
     * status is an answer: the service was reached.
     */
    private String headStatus(URI uri, boolean https, int probePort) throws IOException {
        String host = uri.getHost();
        String bareHost = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        int port = uri.getPort() > 0 ? uri.getPort() : https ? 443 : 80;
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty()
                ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        String head = "HEAD " + path + " HTTP/1.1\r\n"
                + "Host: " + host + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + "\r\n"
                + "Connection: close\r\n\r\n";
        Socket tunnelled = throughTunnel(bareHost, port, probePort);
        try (Socket socket = https ? overTls(tunnelled, bareHost, port) : tunnelled) {
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            int status = parseStatusCode(readStatusLine(socket.getInputStream()));
            if (status < 0) {
                throw new IOException("no HTTP answer");
            }
            return "HTTP " + status;
        }
    }

    /**
     * Speaks TLS to {@code host} over a connection the tunnel carried. The
     * certificate has to be valid for the host, as for a browser: a portal or
     * a filter that answers in the site's place does not pass for the site.
     */
    private Socket overTls(Socket tunnelled, String host, int port) throws IOException {
        try {
            SSLSocket socket = (SSLSocket) tls.createSocket(tunnelled, host, port, true);
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            return socket;
        } catch (IOException | RuntimeException e) {
            tunnelled.close();
            throw e;
        }
    }

    /**
     * Opens a connection to {@code host:port} through the probe inbound, with
     * this run's password, and returns it once the core has said that the
     * tunnel carried it.
     *
     * @throws IOException when the core said the tunnel did not, or said
     *     nothing in time
     */
    private static Socket throughTunnel(String host, int port, int probePort)
            throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), probePort),
                    CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(REQUEST_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // Both methods are offered: the inbound asks for the password.
            out.write(new byte[] {SOCKS_VERSION, 2, NO_AUTH, PASSWORD_AUTH});
            out.flush();
            if (in.readUnsignedByte() != SOCKS_VERSION) {
                throw new IOException("the probe port is not a SOCKS5 proxy");
            }
            int method = in.readUnsignedByte();
            if (method == PASSWORD_AUTH) {
                out.write(passwordRequest());
                out.flush();
                in.readUnsignedByte();   // the version of the password exchange
                if (in.readUnsignedByte() != 0) {
                    throw new IOException("the probe port refused the password");
                }
            } else if (method != NO_AUTH) {
                throw new IOException("the probe port accepts no method offered");
            }

            out.write(connectRequest(host, port));
            out.flush();
            in.readUnsignedByte();   // version
            int reply = in.readUnsignedByte();
            if (reply != 0) {
                throw new IOException("the tunnel did not carry it (SOCKS reply " + reply + ")");
            }
            in.readUnsignedByte();   // reserved
            int boundType = in.readUnsignedByte();
            int boundLength = switch (boundType) {
                case IPV4 -> 4;
                case IPV6 -> 16;
                case DOMAIN -> in.readUnsignedByte();
                default -> throw new IOException("unknown SOCKS address type " + boundType);
            };
            in.readFully(new byte[boundLength + 2]);   // the bound address and port
            return socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    private static byte[] passwordRequest() {
        byte[] user = LocalProxyCredentials.username().getBytes(StandardCharsets.UTF_8);
        byte[] password = LocalProxyCredentials.password().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(PASSWORD_AUTH_VERSION);
        request.write(user.length);
        request.writeBytes(user);
        request.write(password.length);
        request.writeBytes(password);
        return request.toByteArray();
    }

    /**
     * A CONNECT to {@code host:port}. A name goes as a name: the tunnel's far
     * end resolves it, as it does for the traffic the probe stands for.
     */
    private static byte[] connectRequest(String host, int port) throws IOException {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(SOCKS_VERSION);
        request.write(CONNECT);
        request.write(0);
        byte[] address;
        try {
            address = InetAddress.ofLiteral(host).getAddress();
            request.write(address.length == 4 ? IPV4 : IPV6);
        } catch (IllegalArgumentException notAnIpLiteral) {
            address = host.getBytes(StandardCharsets.UTF_8);
            if (address.length > 255) {
                throw new IOException("host name too long for SOCKS: " + host);
            }
            request.write(DOMAIN);
            request.write(address.length);
        }
        request.writeBytes(address);
        request.write(port >> 8);
        request.write(port & 0xff);
        return request.toByteArray();
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

    /** Extracts the numeric status from "HTTP/1.1 204 No Content". */
    private static int parseStatusCode(String statusLine) {
        if (statusLine == null || !statusLine.startsWith("HTTP/")) {
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
        if (e instanceof SocketTimeoutException) {
            return "timeout";
        }
        String msg = e.getMessage();
        return msg != null && !msg.isBlank() ? msg : e.getClass().getSimpleName();
    }

    /** Shuts down the probe thread pool. */
    public void shutdown() {
        executor.shutdownNow();
    }
}
