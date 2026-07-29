package com.vlessclient.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streamable-HTTP transport for {@link McpServer}, hosted on the JDK's built-in
 * {@link HttpServer} — no servlet container, no extra dependency.
 *
 * <p>Hardening for a VPN control surface:</p>
 * <ul>
 *   <li>binds <strong>only</strong> to {@code 127.0.0.1} (loopback), never a
 *       routable interface;</li>
 *   <li>every request must carry {@code Authorization: Bearer &lt;token&gt;},
 *       compared in constant time;</li>
 *   <li>request bodies are capped to guard against abuse.</li>
 * </ul>
 *
 * <p>The single endpoint {@code POST /mcp} accepts a JSON-RPC message and
 * replies with {@code application/json} (or {@code 202} for notifications).
 * {@code GET /mcp} (server-initiated SSE stream) is not offered yet and returns
 * {@code 405}.</p>
 */
public class McpHttpServer {

    private static final Logger log = LoggerFactory.getLogger(McpHttpServer.class);

    private static final String ENDPOINT = "/mcp";
    private static final String LOOPBACK = "127.0.0.1";
    private static final long MAX_BODY_BYTES = 1_048_576; // 1 MiB
    private static final int STOP_DELAY_SECONDS = 1;

    private static final long SSE_POLL_MS = 15_000;
    private static final int MAX_SSE_STREAMS = 8;

    private final int port;
    private final String token;
    private final McpServer mcpServer;
    private final ObjectMapper mapper;
    private final McpNotifier notifier;

    private HttpServer httpServer;

    public McpHttpServer(int port, String token, McpServer mcpServer, ObjectMapper mapper) {
        this(port, token, mcpServer, mapper, null);
    }

    /**
     * Creates a server bound to {@code port} and guarded by {@code token}.
     *
     * @param port the loopback port to listen on (0 picks a free port)
     * @param token the bearer token every request must present
     * @param mcpServer the JSON-RPC handler backing the endpoint
     * @param mapper the JSON mapper used to read and write messages
     * @param notifier the log-stream notifier for SSE, or {@code null} to disable streaming
     */
    public McpHttpServer(int port, String token, McpServer mcpServer, ObjectMapper mapper,
                         McpNotifier notifier) {
        this.port = port;
        this.token = token;
        this.mcpServer = mcpServer;
        this.mapper = mapper;
        this.notifier = notifier;
    }

    /** Starts listening on loopback. Idempotent-safe: call {@link #stop()} first to restart. */
    public synchronized void start() throws IOException {
        if (httpServer != null) {
            return;
        }
        InetSocketAddress address = new InetSocketAddress(InetAddress.getByName(LOOPBACK), port);
        httpServer = HttpServer.create(address, 0);
        httpServer.createContext(ENDPOINT, this::handle);
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        httpServer.start();
        log.info("MCP server listening on http://{}:{}{}",
                LOOPBACK, httpServer.getAddress().getPort(), ENDPOINT);
    }

    /** Stops the server if running. */
    public synchronized void stop() {
        if (httpServer != null) {
            httpServer.stop(STOP_DELAY_SECONDS);
            httpServer = null;
            log.info("MCP server stopped");
        }
    }

    public synchronized boolean isRunning() {
        return httpServer != null;
    }

    /**
     * Returns the actually-bound port (useful when constructed with port 0).
     *
     * @return the bound port, or -1 if stopped
     */
    public synchronized int boundPort() {
        return httpServer != null ? httpServer.getAddress().getPort() : -1;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                handleSse(exchange);
                return;
            }
            if (!"POST".equalsIgnoreCase(method)) {
                sendPlain(exchange, 405, "Method Not Allowed");
                return;
            }
            if (!isAuthorized(exchange)) {
                sendPlain(exchange, 401, "Unauthorized");
                return;
            }
            byte[] body = readBody(exchange);
            JsonNode request;
            try {
                request = mapper.readTree(body);
            } catch (Exception parseError) {
                sendJson(exchange, 400, parseErrorResponse());
                return;
            }
            if (request == null || request.isNull() || request.isMissingNode()) {
                sendJson(exchange, 400, parseErrorResponse());
                return;
            }

            JsonNode response = mcpServer.handle(request);
            if (response == null) {
                // Notification(s) only — nothing to return.
                exchange.sendResponseHeaders(202, -1);
            } else {
                sendJson(exchange, 200, mapper.writeValueAsBytes(response));
            }
        } catch (Exception e) {
            log.warn("MCP request handling failed", e);
            try {
                sendPlain(exchange, 500, "Internal Server Error");
            } catch (IOException ignored) {
                // client gone
            }
        } finally {
            exchange.close();
        }
    }

    private void handleSse(HttpExchange exchange) throws IOException {
        if (notifier == null) {
            sendPlain(exchange, 405, "Method Not Allowed");
            return;
        }
        if (!isAuthorized(exchange)) {
            sendPlain(exchange, 401, "Unauthorized");
            return;
        }
        if (notifier.subscriberCount() >= MAX_SSE_STREAMS) {
            sendPlain(exchange, 503, "Too many concurrent log streams");
            return;
        }
        // Subscribe before headers go out so a frame pushed immediately after the
        // client connects is not missed.
        McpNotifier.Subscriber subscriber = notifier.subscribe();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0); // 0 => chunked, stream stays open

        OutputStream out = exchange.getResponseBody();
        try {
            // Flush a comment immediately so response headers reach the client
            // right away rather than waiting for the first log line / keep-alive.
            out.write(":connected\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (true) {
                String frame = subscriber.poll(SSE_POLL_MS);
                if (frame != null) {
                    out.write(("data: " + frame + "\n\n").getBytes(StandardCharsets.UTF_8));
                } else {
                    out.write(":keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                }
                out.flush();
            }
        } catch (IOException e) {
            // client disconnected — normal termination
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            notifier.unsubscribe(subscriber);
        }
    }

    private boolean isAuthorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        String presented = header.substring("Bearer ".length()).trim();
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return in.readNBytes((int) MAX_BODY_BYTES);
        }
    }

    private byte[] parseErrorResponse() {
        return ("{\"jsonrpc\":\"2.0\",\"id\":null,"
                + "\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private void sendJson(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void sendPlain(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
