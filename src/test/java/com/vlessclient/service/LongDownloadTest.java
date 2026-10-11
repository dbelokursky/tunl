package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A download is bounded by how long it stalls, never by how long it takes.
 * The request timeout it replaced ended the whole transfer on this JDK, so a
 * slow link lost every large download at the same minute, mid-body.
 */
class LongDownloadTest {

    private static final Duration STALL = Duration.ofMillis(400);

    private HttpServer server;
    private HttpClient client;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // Ten chunks 100 ms apart: a second in all, far past the stall limit,
        // but never quiet for longer than a quarter of it.
        server.createContext("/trickle", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 10; i++) {
                    out.write(new byte[1000]);
                    out.flush();
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.createContext("/stall", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(new byte[1000]);
                out.flush();
                release.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                // The client gave up on us, which is the point.
            }
        });
        server.createContext("/silent", exchange -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        client.shutdownNow();
        server.stop(0);
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort() + path)).GET().build();
    }

    @Test
    void aSlowBodyThatKeepsMovingArrivesWhole() throws Exception {
        HttpResponse<InputStream> response = LongDownload.send(client, get("/trickle"));

        try (InputStream in = LongDownload.guard(response.body(), STALL)) {
            assertThat(in.readAllBytes()).hasSize(10_000);
        }
    }

    @Test
    void aBodyThatStopsFailsAsAStallSoonAfterTheLimit() throws Exception {
        HttpResponse<InputStream> response = LongDownload.send(client, get("/stall"));
        long start = System.nanoTime();

        try (InputStream in = LongDownload.guard(response.body(), STALL)) {
            assertThatThrownBy(in::readAllBytes).isInstanceOf(HttpTimeoutException.class);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .as("given up on after the stall limit, not after the server's ten seconds")
                .isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void aServerThatNeverAnswersFailsWithinTheHeaderWait() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> LongDownload.send(client, get("/silent"), STALL))
                .isInstanceOf(HttpTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }
}
