package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.vlessclient.app.I18n;
import com.vlessclient.model.Subscription;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Why a refresh failed, in the app's words and the language of the UI.
 *
 * <p>The row showed the exception's message as it came, in English in every
 * language: "Connection refused (connect failed)", "HTTP 401 for URL: …",
 * and for a provider whose name does not resolve just
 * "java.net.ConnectException", since the JDK gives that one no message.</p>
 */
class SubscriptionFetchFailureWordsTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private SubscriptionService service() {
        return new SubscriptionService(new ConfigStore(tempDir), new ShareLinkParser(), tempDir,
                HttpClient.newHttpClient());
    }

    @Test
    void anAnswerOtherThan200IsWordedByWhatItMeans() {
        Map<Integer, String> expected = new LinkedHashMap<>();
        expected.put(401, "subscriptions.error.http.denied");
        expected.put(403, "subscriptions.error.http.denied");
        expected.put(404, "subscriptions.error.http.gone");
        expected.put(410, "subscriptions.error.http.gone");
        expected.put(429, "subscriptions.error.http.later");
        expected.put(503, "subscriptions.error.http.later");
        expected.put(418, "subscriptions.error.http");
        SubscriptionService service = service();
        status.set(401);
        service.addSubscription("Provider",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/sub?token=SECRET");
        Subscription sub = service.getSubscriptions().getFirst();

        expected.forEach((code, key) -> {
            status.set(code);
            service.refreshSubscription(sub.getId());
            assertThat(sub.getLastErrorKey()).as("HTTP %d", code).isEqualTo(key);
            assertThat(sub.getLastErrorArgs()).as("HTTP %d", code)
                    .containsExactly(String.valueOf(code));
            assertThat(sub.getLastError()).as("HTTP %d", code).isNull();
        });
    }

    @Test
    void aProviderThatRefusesTheConnectionIsSaidSo() throws IOException {
        int closed;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = probe.getLocalPort();
        }
        SubscriptionService service = service();

        service.addSubscription("Provider", "http://127.0.0.1:" + closed + "/sub");

        Subscription sub = service.getSubscriptions().getFirst();
        assertThat(sub.getLastErrorKey()).isEqualTo("subscriptions.error.connect");
        assertThat(sub.getLastError()).isNull();
    }

    /**
     * The chains the JDK's client throws, as it threw them for
     * provider.invalid and for a server that never answered: a name that does
     * not resolve comes as a ConnectException without a message around an
     * UnresolvedAddressException.
     */
    @Test
    void aNameThatDoesNotResolveATimeoutAndTlsAreWordedFromTheirCauses() {
        String url = "https://provider.example/api/sub?token=SECRET";
        Throwable unresolved = new ConnectException();
        unresolved.initCause(new ConnectException());
        unresolved.getCause().initCause(new UnresolvedAddressException());

        assertThat(SubscriptionService.wordedFailure(unresolved, url))
                .isEqualTo(new SubscriptionService.Declined("subscriptions.error.dns",
                        List.of("provider.example")));
        assertThat(SubscriptionService.wordedFailure(
                new HttpTimeoutException("request timed out"), url).key())
                .isEqualTo("subscriptions.error.timeout");
        assertThat(SubscriptionService.wordedFailure(
                new HttpConnectTimeoutException("HTTP connect timed out"), url).key())
                .isEqualTo("subscriptions.error.timeout");
        assertThat(SubscriptionService.wordedFailure(
                new SSLHandshakeException("PKIX path building failed"), url).key())
                .isEqualTo("subscriptions.error.tls");
        assertThat(SubscriptionService.wordedFailure(
                new IOException("Subscription body exceeds 10485760 bytes"), url))
                .as("a failure the app has no words for keeps its technical reason")
                .isNull();
    }

    @Test
    void theWordsAreInTheLanguageOfTheUi() {
        Locale before = I18n.getLocale();
        try {
            I18n.setLocale(Locale.of("ru"));
            for (String key : List.of("subscriptions.error.dns", "subscriptions.error.connect",
                    "subscriptions.error.timeout", "subscriptions.error.tls",
                    "subscriptions.error.tunnel", "subscriptions.error.http.denied",
                    "subscriptions.error.http.gone", "subscriptions.error.http.later",
                    "subscriptions.error.http")) {
                assertThat(I18n.get(key, "401")).as(key).containsPattern("\\p{IsCyrillic}");
            }
        } finally {
            I18n.setLocale(before);
        }
    }
}
