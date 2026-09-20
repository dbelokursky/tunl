package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.Subscription;
import com.vlessclient.platform.InMemorySecretSealer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a provider calls its subscription, and what it has to tell the user.
 *
 * <p>Panels send the subscription's name in {@code profile-title} and a
 * message in {@code announce}, plain or base64 behind a {@code base64:}
 * prefix (Remnawave, Marzban, 3x-ui). Only the quota header was read: a
 * subscription could not be added without typing a name for it, and the
 * provider's message reached the user only when it declined a device.</p>
 */
class SubscriptionProviderTitleTest {

    private static final String ONE_SERVER =
            "vless://11111111-2222-3333-4444-555555555555@one.example:443"
                    + "?security=tls&type=tcp#One\n";

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private ConfigStore store;
    private SubscriptionService service;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = ONE_SERVER.getBytes(StandardCharsets.UTF_8);
            synchronized (headers) {
                headers.forEach(exchange.getResponseHeaders()::add);
            }
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        store = new ConfigStore(tempDir, new InMemorySecretSealer());
        service = newService();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private SubscriptionService newService() {
        return new SubscriptionService(store, new ShareLinkParser(), tempDir,
                HttpClient.newHttpClient());
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/sub/token123";
    }

    /** The headers of the provider's next answers. */
    private void answerWith(String... headerPairs) {
        synchronized (headers) {
            headers.clear();
            for (int i = 0; i < headerPairs.length; i += 2) {
                headers.put(headerPairs[i], headerPairs[i + 1]);
            }
        }
    }

    private Subscription add(String name) {
        service.addSubscription(name, url());
        return service.getSubscriptions().get(0);
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aSubscriptionAddedWithoutANameTakesTheProvidersTitle() {
        answerWith("profile-title", "base64:" + base64("Мой VPN"));

        Subscription sub = add("");

        assertThat(sub.getName()).isEqualTo("Мой VPN");
        assertThat(store.getServers()).extracting(ServerConfig::getName)
                .as("the servers take it as their prefix")
                .containsExactly("[Мой VPN] One");
    }

    @Test
    void aTitleMayComePlain() {
        answerWith("profile-title", "Acme VPN");

        assertThat(add("").getName()).isEqualTo("Acme VPN");
    }

    @Test
    void aNameTheUserGaveStays() {
        answerWith("profile-title", "Acme VPN");

        Subscription sub = add("Mine");
        service.refreshSubscription(sub.getId());

        assertThat(sub.getName()).isEqualTo("Mine");
        assertThat(store.getServers()).extracting(ServerConfig::getName)
                .containsExactly("[Mine] One");
    }

    /**
     * A URL with no host to read leaves the subscription unnamed. The shared
     * {@code hostOf} answers "?" for one, which belongs in an error line
     * rather than in the list.
     */
    @Test
    void aUrlWithNoHostNamesNothing() {
        Subscription sub = new Subscription();
        sub.setUrl("not a url at all");

        SubscriptionService.nameIfUnnamed(sub, HttpHeaders.of(Map.of(), (a, b) -> true));

        assertThat(sub.getName()).isNullOrEmpty();
    }

    @Test
    void withoutATitleASubscriptionIsNamedAfterItsHost() {
        answerWith();

        assertThat(add("").getName()).isEqualTo("127.0.0.1");
    }

    @Test
    void theProvidersAnnouncementIsKeptUntilItStopsSendingOne() {
        answerWith("announce", "base64:" + base64("Продлите подписку до 1 октября"));
        Subscription sub = add("Provider");

        assertThat(sub.getAnnounce()).isEqualTo("Продлите подписку до 1 октября");
        assertThat(newService().getSubscriptions().get(0).getAnnounce())
                .as("read back after a restart")
                .isEqualTo("Продлите подписку до 1 октября");

        answerWith();
        service.refreshSubscription(sub.getId());

        assertThat(sub.getAnnounce()).as("the provider stopped sending it").isEmpty();
    }
}
