package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import tools.jackson.databind.json.JsonMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.Subscription;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Subscription URLs embed the account token, which is why
 * {@code serializableSubscriptions()} seals the {@code url} field before it
 * hits disk. These tests pin the other two ways that token used to escape:
 * the log line and the {@code lastError} field persisted right beside the
 * sealed URL.
 */
class SubscriptionServiceRedactionTest {

    /** The part of the URL that must never appear anywhere but the keychain. */
    private static final String TOKEN = "9f3caa1b2c3d4e5f";

    @TempDir
    Path tempDir;

    private HttpServer server;
    private int port;
    private int status = 401;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(status, -1);
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

    private String subscriptionUrl() {
        return "http://127.0.0.1:" + port + "/api/v1/subscribe?token=" + TOKEN;
    }

    @Test
    @DisplayName("a non-200 response reports the host without the token")
    void fetchContent_doesNotPutTheTokenInItsException() {
        SubscriptionService service = new SubscriptionService(
                new ConfigStore(tempDir), new ShareLinkParser(), tempDir,
                HttpClient.newHttpClient());

        assertThatThrownBy(() -> service.fetchContent(subscriptionUrl()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageContaining("127.0.0.1:" + port)
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    @DisplayName("the token never reaches lastError, sealed or not")
    void refreshSubscription_persistsARedactedError() throws IOException {
        SubscriptionService service = new SubscriptionService(
                new ConfigStore(tempDir), new ShareLinkParser(), tempDir,
                HttpClient.newHttpClient());

        // An expired token answering 401 is the ordinary failure, and the one
        // that used to write the whole URL into subscriptions.json.
        service.addSubscription("Provider", subscriptionUrl());

        Subscription sub = service.getSubscriptions().get(0);
        assertThat(sub.getLastError())
                .as("the failure is still recorded, just without the secret")
                .isNotBlank()
                .contains("HTTP 401")
                .doesNotContain(TOKEN);

        // Assert on the field, not the whole file: when secure storage is off
        // or no keychain backend is available, serializableSubscriptions()
        // deliberately keeps the url in plaintext ("a readable config always
        // wins over a lost URL"). lastError has no such excuse — it is a
        // derived string that never needed the token in the first place.
        JsonNode persisted = JsonMapper.builder().build()
                .readTree(Files.readString(tempDir.resolve("subscriptions.json")))
                .path("subscriptions").path(0);
        assertThat(persisted.path("lastError").asString())
                .as("the error persisted beside the url field")
                .contains("HTTP 401")
                .doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("a URL quoted by a JDK exception is scrubbed too")
    void refreshSubscription_scrubsMessagesItDidNotAuthor() {
        SubscriptionService service = new SubscriptionService(
                new ConfigStore(tempDir), new ShareLinkParser(), tempDir,
                HttpClient.newHttpClient()) {
            @Override
            String fetchContent(String url) {
                // Shaped like URI.create's complaint, which quotes the input
                // back verbatim — a message this class does not control.
                throw new IllegalArgumentException(
                        "Illegal character in query at index 40: " + url);
            }
        };

        service.addSubscription("Provider", subscriptionUrl());

        assertThat(service.getSubscriptions().get(0).getLastError())
                .contains("Illegal character in query")
                .doesNotContain(TOKEN);
    }

    /**
     * Not the token this time but a server's credential: every line of a
     * subscription is a share link, and one the parser rejects was logged with
     * the parser's message, which can quote the line back.
     */
    @Test
    @DisplayName("a line the parser rejects is logged without the link it quotes")
    void parseContent_scrubsTheRejectedLineOutOfTheDebugLog() {
        String credential = "0b7e5f2a-4c1d-4e8f-9a3b-6d2c1e0f9a8b";
        String link = "vless://" + credential + "@gateway.example:443?type=tcp#Quoted";
        SubscriptionService service = new SubscriptionService(
                new ConfigStore(tempDir), parserQuotingEveryLink(), tempDir,
                HttpClient.newHttpClient());

        Logger serviceLog = (Logger) LoggerFactory.getLogger(SubscriptionService.class);
        Level level = serviceLog.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLog.addAppender(appender);
        // Debug is the level a user is asked to switch on for a bug report.
        serviceLog.setLevel(Level.DEBUG);
        try {
            service.parseContent(link + "\n");
        } finally {
            serviceLog.setLevel(level);
            serviceLog.detachAppender(appender);
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(message -> assertThat(message).doesNotContain(credential))
                .contains("Skipping unparseable line: Cannot read vless://gateway.example:443/…");
    }

    /** A parser that rejects every line with a message quoting it, as a JDK one would. */
    private static ShareLinkParser parserQuotingEveryLink() {
        return new ShareLinkParser() {
            @Override
            public ServerConfig parse(String uri) {
                throw new IllegalArgumentException("Cannot read " + uri);
            }
        };
    }
}
