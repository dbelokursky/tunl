package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.vlessclient.platform.InMemorySecretSealer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The device id a subscription fetch carries.
 *
 * <p>A panel that limits devices per plan (Remnawave) serves a client only
 * when it sends {@code x-hwid}; without it the answer is empty or a list of
 * messages. Tunl sent none, so such a subscription never worked. The id is
 * random per install, drawn once and kept, so a restart is not a new
 * device.</p>
 */
class SubscriptionDeviceIdTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final Map<String, String> received = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestHeaders().forEach((name, values) ->
                    received.put(name.toLowerCase(java.util.Locale.ROOT), values.getFirst()));
            byte[] body = ("vless://11111111-2222-3333-4444-555555555555@one.example:443"
                    + "?security=tls&type=tcp#One\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void aFetchCarriesThisInstallsDeviceId() {
        ConfigStore store = new ConfigStore(tempDir, new InMemorySecretSealer());
        SubscriptionService service = new SubscriptionService(store, new ShareLinkParser(),
                tempDir, HttpClient.newHttpClient());

        service.addSubscription("Provider",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/sub/token");

        String deviceId = store.getSettings().getDeviceId();
        assertThat(deviceId).as("this install's device id").isNotBlank();
        assertThat(received).containsEntry("x-hwid", deviceId);
        assertThat(received.get("x-device-os")).isIn("macOS", "Windows", "Linux");
        assertThat(received).containsEntry("x-ver-os", System.getProperty("os.version"));
    }

    @Test
    void theDeviceIdIsDrawnOnceAndKept() {
        String first = new ConfigStore(tempDir, new InMemorySecretSealer()).deviceId();
        String again = new ConfigStore(tempDir, new InMemorySecretSealer())
                .getSettings().getDeviceId();

        assertThat(first).isNotBlank().matches("[a-zA-Z0-9=-]{10,64}");
        assertThat(again).as("after a restart").isEqualTo(first);
    }
}
