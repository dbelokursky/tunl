package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * What the stores log about a data file they could not read. Jackson's
 * messages quote the token or the value they could not read, and in a file
 * edited by hand that is a credential which lost its quotes, or one pasted
 * into a field of another type: it went into tunl.log, the file users attach
 * to bug reports, beside a file that seals the same credential.
 */
class StoredJsonRedactionTest {

    private static final String SECRET = "Pw7SecretQx9";

    @TempDir
    Path dir;

    /** What {@code action} has {@code source} log, each event as the log file holds it. */
    private static List<String> logged(Class<?> source, Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(source);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().map(StoredJsonRedactionTest::rendered).toList();
    }

    /** The message, and the message of every exception in the chain a stack trace prints. */
    private static String rendered(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage());
        for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
            text.append('\n').append(t.getClassName()).append(": ").append(t.getMessage());
        }
        return text.toString();
    }

    @Test
    void aFileThatIsNotJsonIsLoggedByWhereItBroke() throws IOException {
        Files.writeString(dir.resolve("servers.json"), "{\"config_version\":1,\"servers\":[\n"
                + "  {\"id\":\"s-1\",\"name\":\"DE\",\"protocol\":\"trojan\","
                + "\"address\":\"tr.example.com\",\"port\":443,\"uuid\":" + SECRET + "}]}",
                StandardCharsets.UTF_8);

        List<String> lines = logged(ConfigStore.class, () -> new ConfigStore(dir));

        assertThat(lines)
                .anySatisfy(line -> assertThat(line).contains("servers.json").contains("line 2"))
                .allSatisfy(line -> assertThat(line).doesNotContain(SECRET.substring(0, 6)));
    }

    @Test
    void anEntryThatCannotBeReadIsLoggedByItsField() throws IOException {
        Files.writeString(dir.resolve("servers.json"), """
                {"config_version":1,"servers":[
                  {"id":"s-1","name":"DE","protocol":"trojan","address":"tr.example.com",
                   "port":"%s","uuid":"pw"}]}""".formatted(SECRET), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("subscriptions.json"), """
                {"config_version":1,"subscriptions":[
                  {"id":"a","name":"Work","url":"https://sub.example/a",
                   "lastRefreshedAt":"https://sub.example/api/%s"}]}""".formatted(SECRET),
                StandardCharsets.UTF_8);

        ConfigStore[] store = new ConfigStore[1];
        List<String> serverLines = logged(ConfigStore.class,
                () -> store[0] = new ConfigStore(dir));
        List<String> subscriptionLines = logged(SubscriptionService.class, () -> {
            try (HttpClient client = HttpClient.newHttpClient()) {
                new SubscriptionService(store[0], new ShareLinkParser(), dir, client);
            }
        });

        assertThat(serverLines)
                .anySatisfy(line -> assertThat(line).contains("port"))
                .allSatisfy(line -> assertThat(line).doesNotContain(SECRET.substring(0, 6)));
        assertThat(subscriptionLines)
                .anySatisfy(line -> assertThat(line).contains("lastRefreshedAt"))
                .allSatisfy(line -> assertThat(line).doesNotContain(SECRET.substring(0, 6)));
        assertThat(store[0].getPersistenceState().unreadableEntries())
                .as("still kept for the build that wrote them")
                .containsEntry("servers.json", 1).containsEntry("subscriptions.json", 1);
    }
}
