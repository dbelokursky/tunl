package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The app's own requests fall back to a direct connection while the core is
 * connected but the tunnel has been declared broken, so the updater keeps
 * working on hostile networks. A subscription URL carries the account token,
 * and the fetch used to take that same fallback: the token, and the user's
 * real address, went straight to the provider at the one moment the user
 * believed they were tunneled.
 */
class SubscriptionTunnelGuardTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] body = "vless://uuid@host.example:443?security=tls#A\n"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void restore() {
        AppHttpClients.setTunnelBrokenProbe(null);
        server.stop(0);
    }

    private SubscriptionService service() {
        return new SubscriptionService(new ConfigStore(dir), new ShareLinkParser(), dir,
                HttpClient.newHttpClient());
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/sub?token=account-secret";
    }

    @Test
    void aBrokenTunnelFailsTheFetchInsteadOfSendingTheTokenDirectly() {
        AppHttpClients.setTunnelBrokenProbe(() -> true);

        assertThatThrownBy(() -> service().fetchContent(url()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not carrying traffic");
        assertThat(hits.get()).as("nothing left the machine").isZero();
    }

    @Test
    void otherwiseTheFetchProceeds() throws Exception {
        AppHttpClients.setTunnelBrokenProbe(() -> false);

        assertThat(service().fetchContent(url())).contains("vless://");
        assertThat(hits.get()).isEqualTo(1);
    }
}
