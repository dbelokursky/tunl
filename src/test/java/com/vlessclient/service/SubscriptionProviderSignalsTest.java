package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.Subscription;
import com.vlessclient.platform.InMemorySecretSealer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a provider says besides its server list, and what it must not cost.
 *
 * <p>A refresh deletes every stored server the answer leaves out, and a panel
 * that declines a request does not always say so with its status code. With
 * its device limit on, Remnawave answers a client that sends no device id
 * with HTTP 200 and an empty body, or with entries that only carry a message
 * ("App not supported") at {@code 0.0.0.0:1}; an expired or disabled account
 * gets such entries too. The refresh took either for the provider's new
 * list: it deleted the user's servers, or put the messages in their place,
 * and reported success, once an hour, unattended. The responses here are the
 * ones remnawave/backend builds ({@code subscription.service.ts},
 * {@code createFallbackHosts} in {@code resolve-proxy-config.service.ts}).</p>
 */
class SubscriptionProviderSignalsTest {

    private static final String TWO_SERVERS =
            "vless://11111111-2222-3333-4444-555555555555@one.example:443"
                    + "?security=tls&type=tcp#One\n"
                    + "vless://11111111-2222-3333-4444-555555555555@two.example:443"
                    + "?security=tls&type=tcp#Two\n";

    @TempDir
    Path tempDir;

    private HttpServer server;
    private volatile String body = TWO_SERVERS;
    private volatile int status = 200;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private ConfigStore store;
    private SubscriptionService service;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            synchronized (headers) {
                headers.forEach(exchange.getResponseHeaders()::add);
            }
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        store = new ConfigStore(tempDir, new InMemorySecretSealer());
        service = new SubscriptionService(store, new ShareLinkParser(), tempDir,
                HttpClient.newHttpClient());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** Adds the subscription while the provider still serves its two servers. */
    private Subscription subscribed() {
        service.addSubscription("Provider", "http://127.0.0.1:"
                + server.getAddress().getPort() + "/api/sub/token123");
        Subscription sub = service.getSubscriptions().get(0);
        assertThat(store.getServers()).hasSize(2);
        return sub;
    }

    /** The provider's next answer. */
    private void answer(String nextBody, String... headerPairs) {
        body = nextBody;
        synchronized (headers) {
            headers.clear();
            for (int i = 0; i < headerPairs.length; i += 2) {
                headers.put(headerPairs[i], headerPairs[i + 1]);
            }
        }
    }

    /** A Remnawave message entry: a VLESS link to 0.0.0.0:1 named after the message. */
    private static String messageEntry(String message) {
        return "vless://00000000-0000-0000-0000-000000000000@0.0.0.0:1"
                + "?encryption=none&type=tcp&security=none#"
                + java.net.URLEncoder.encode(message, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private void assertTheServersWereKept(Subscription sub, long refreshedBefore) {
        assertThat(store.getServers()).extracting(ServerConfig::getName)
                .containsExactly("[Provider] One", "[Provider] Two");
        assertThat(sub.getServerIds()).hasSize(2);
        assertThat(sub.getLastRefreshedAt())
                .as("a refresh that got no list is not a fresh one")
                .isEqualTo(refreshedBefore);
    }

    @Test
    void aDeviceLimitWithoutADeviceIdKeepsTheServers() {
        Subscription sub = subscribed();
        long refreshed = sub.getLastRefreshedAt();

        answer("", "x-hwid-not-supported", "true", "x-hwid-limit", "true");
        service.refreshSubscription(sub.getId());

        assertTheServersWereKept(sub, refreshed);
        assertThat(sub.getLastErrorKey()).isEqualTo("subscriptions.error.device.id");
    }

    @Test
    void aDeviceLimitReachedKeepsTheServersAndPassesTheProvidersWordsOn() {
        Subscription sub = subscribed();
        long refreshed = sub.getLastRefreshedAt();

        answer(base64(messageEntry("Device limit reached")),
                "x-hwid-active", "true",
                "x-hwid-max-devices-reached", "true",
                "x-hwid-limit", "true",
                "announce", "base64:" + base64("Remove a device in your account"));
        service.refreshSubscription(sub.getId());

        assertTheServersWereKept(sub, refreshed);
        assertThat(sub.getLastErrorKey()).isEqualTo("subscriptions.error.device.limit");
        assertThat(sub.getLastErrorArgs()).containsExactly("Remove a device in your account");
    }

    /**
     * 3x-ui (3.7 and later) refuses a device over the plan's limit with HTTP
     * 404 and X-Hwid-Max-Devices-Reached, where Remnawave answers 200. Only a
     * 200 had its headers read, so the row said the provider had no
     * subscription at this link — which reads as "delete it" to someone who
     * has paid for it and only needs to free a device.
     */
    @Test
    void aDeviceLimitRefusedWithA404IsNotReportedAsAGoneSubscription() {
        Subscription sub = subscribed();
        long refreshed = sub.getLastRefreshedAt();

        status = 404;
        answer("", "X-Hwid-Max-Devices-Reached", "true", "X-Hwid-Limit", "true");
        service.refreshSubscription(sub.getId());

        assertTheServersWereKept(sub, refreshed);
        assertThat(sub.getLastErrorKey()).isEqualTo("subscriptions.error.device.limit.plain");
    }

    /** A plain 404, with no device header, still says the link is gone. */
    @Test
    void aPlain404StillSaysTheSubscriptionIsGone() {
        Subscription sub = subscribed();

        status = 404;
        answer("");
        service.refreshSubscription(sub.getId());

        assertThat(sub.getLastErrorKey()).isEqualTo("subscriptions.error.http.gone");
    }

    /** An expired or disabled account: the entries carry the provider's message. */
    @Test
    void aListOfMessagesKeepsTheServersAndShowsTheMessage() {
        Subscription sub = subscribed();
        long refreshed = sub.getLastRefreshedAt();

        answer(base64(messageEntry("⌛ Subscription expired") + "\n"
                + messageEntry("Contact support")));
        service.refreshSubscription(sub.getId());

        assertTheServersWereKept(sub, refreshed);
        assertThat(sub.getLastErrorKey()).isEqualTo("subscriptions.error.message");
        assertThat(sub.getLastErrorArgs())
                .containsExactly("⌛ Subscription expired; Contact support");
    }

    /**
     * An empty answer from a subscription that has servers keeps them: a
     * provider that has really stopped is rarer than one declining a request
     * in its own way, and the user can delete the subscription.
     */
    @Test
    void anEmptyAnswerKeepsTheServers() {
        Subscription sub = subscribed();
        long refreshed = sub.getLastRefreshedAt();

        answer("");
        service.refreshSubscription(sub.getId());

        assertTheServersWereKept(sub, refreshed);
        assertThat(sub.getLastErrorKey()).isEqualTo("subscriptions.error.empty");
    }

    /** A message entry is never a server, even beside real ones. */
    @Test
    void aMessageEntryBesideRealServersIsLeftOut() {
        Subscription sub = subscribed();

        answer(TWO_SERVERS + messageEntry("Your plan ends in 3 days") + "\n");
        service.refreshSubscription(sub.getId());

        assertThat(store.getServers()).extracting(ServerConfig::getName)
                .containsExactly("[Provider] One", "[Provider] Two");
        assertThat(sub.hasLastError()).isFalse();
    }

    /** What a working answer from a panel with the limit on looks like. */
    @Test
    void anAnswerWithTheLimitActiveIsAnOrdinaryList() {
        Subscription sub = subscribed();

        answer(TWO_SERVERS + "vless://11111111-2222-3333-4444-555555555555@three.example:443"
                + "?security=tls&type=tcp#Three\n", "x-hwid-active", "true");
        service.refreshSubscription(sub.getId());

        assertThat(store.getServers()).extracting(ServerConfig::getName)
                .containsExactly("[Provider] One", "[Provider] Two", "[Provider] Three");
        assertThat(sub.hasLastError()).isFalse();
    }

    /**
     * The keys are stored and worded later, so the bundle check that follows
     * {@code I18n.get("…")} calls does not see them.
     */
    @Test
    void theErrorsReadInBothLanguages() {
        java.util.Locale before = com.vlessclient.app.I18n.getLocale();
        try {
            for (java.util.Locale locale : List.of(java.util.Locale.ENGLISH,
                    java.util.Locale.of("ru"))) {
                com.vlessclient.app.I18n.setLocale(locale);
                for (String key : List.of("subscriptions.error.device.id",
                        "subscriptions.error.device.limit",
                        "subscriptions.error.device.limit.plain",
                        "subscriptions.error.message", "subscriptions.error.empty")) {
                    assertThat(com.vlessclient.app.I18n.get(key)).as(locale + " " + key)
                            .isNotEqualTo(key);
                }
            }
        } finally {
            com.vlessclient.app.I18n.setLocale(before);
        }
    }
}
