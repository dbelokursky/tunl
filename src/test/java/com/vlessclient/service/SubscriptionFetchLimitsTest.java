package com.vlessclient.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a subscription fetch will and will not accept from the far end.
 *
 * <p>Auto-refresh runs this on a timer, unattended, against a URL pasted from
 * a provider. Two properties matter there and neither was pinned: the response
 * must not be allowed to grow without limit (it was buffered whole by
 * {@code BodyHandlers.ofString()}), and a plaintext {@code http} subscription
 * must be called out — it is rewritable in transit, which for this app means
 * an attacker choosing which proxy the user routes through.</p>
 *
 * <p>Warned, not blocked: some providers only offer http, so refusing the URL
 * would take the app away from users who have no alternative.</p>
 */
class SubscriptionFetchLimitsTest {

    private static final String TOKEN = "1a2b3c4d5e6f7890";

    @TempDir
    Path tempDir;

    private HttpServer server;
    private int port;
    private byte[] body = "vless://nothing".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private SubscriptionService service() {
        return new SubscriptionService(new ConfigStore(tempDir), new ShareLinkParser(),
                tempDir, HttpClient.newHttpClient());
    }

    private String url() {
        return "http://127.0.0.1:" + port + "/api/v1/subscribe?token=" + TOKEN;
    }

    @Test
    @DisplayName("an oversized body is refused instead of being buffered whole")
    void anOversizedBodyIsRefused() {
        body = new byte[4 * 1024 * 1024 + 1];
        java.util.Arrays.fill(body, (byte) 'A');

        assertThatThrownBy(() -> service().fetchContent(url()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds")
                .as("the cap message is persisted as lastError next to a sealed URL")
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    @DisplayName("a body at the limit is still accepted")
    void aBodyAtTheLimitIsAccepted() {
        body = new byte[4 * 1024 * 1024];
        java.util.Arrays.fill(body, (byte) 'A');

        assertThatCode(() -> service().fetchContent(url())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a plaintext http fetch is logged by host, never with the token")
    void plaintextHttpIsWarnedAboutWithoutLeakingTheToken() throws Exception {
        Logger serviceLog = (Logger) LoggerFactory.getLogger(SubscriptionService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLog.addAppender(appender);
        try {
            service().fetchContent(url());

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(m -> assertThat(m)
                            .contains("plaintext http")
                            .contains("127.0.0.1")
                            .doesNotContain(TOKEN));
        } finally {
            serviceLog.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("only an http scheme counts as insecure")
    void theClassificationDoesNotOverreach() {
        assertThat(SubscriptionService.isInsecureHttpUrl("http://example.com/s")).isTrue();
        assertThat(SubscriptionService.isInsecureHttpUrl("HTTP://example.com/s")).isTrue();
        assertThat(SubscriptionService.isInsecureHttpUrl("  http://example.com/s ")).isTrue();

        assertThat(SubscriptionService.isInsecureHttpUrl("https://example.com/s")).isFalse();
        // Not a prefix match: "httpsx" and a bare host must not be flagged.
        assertThat(SubscriptionService.isInsecureHttpUrl("httpsx://example.com")).isFalse();
        assertThat(SubscriptionService.isInsecureHttpUrl("example.com/s")).isFalse();
        assertThat(SubscriptionService.isInsecureHttpUrl("")).isFalse();
        assertThat(SubscriptionService.isInsecureHttpUrl(null)).isFalse();
    }
}
