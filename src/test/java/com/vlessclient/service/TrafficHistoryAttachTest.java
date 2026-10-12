package com.vlessclient.service;

import com.vlessclient.model.ServerConfig;
import com.vlessclient.testing.FxToolkitExtension;
import com.vlessclient.testing.TestServers;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The path from the monitor's samples into the history, through
 * {@link TrafficHistoryStore#attach}. The store's own tests call record()
 * directly, so nothing covered how samples reach it: they were taken from
 * change events on the download speed, and a second with the same download
 * figure as the one before, upload included, never arrived.
 */
@ExtendWith(FxToolkitExtension.class)
class TrafficHistoryAttachTest {

    @Test
    void everySampleIsRecordedIncludingRepeatsAndUploadOnlyOnes(@TempDir Path dir)
            throws InterruptedException {
        Clock clock = Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneId.of("UTC"));
        TrafficHistoryStore store = new TrafficHistoryStore(dir, clock);
        TrafficMonitor monitor = new TrafficMonitor();
        ServerConfig server = TestServers.server().id("a").name("Amsterdam 01").build();
        try {
            store.attach(monitor, () -> server);

            // An upload the server answers only at the end (an HTTP PUT, a
            // backup): a minute of 1 MB up and nothing down...
            for (int i = 0; i < 60; i++) {
                monitor.processTrafficLine("{\"up\":1000000,\"down\":0}");
            }
            // ...then a steady download at a constant rate.
            for (int i = 0; i < 60; i++) {
                monitor.processTrafficLine("{\"up\":10000,\"down\":500000}");
            }
            awaitFx();

            TrafficHistoryStore.DayTotal today = store.lastDays(1).get(0);
            assertThat(today.upload())
                    .as("every second's upload, the repeated ones too")
                    .isEqualTo(60L * 1_000_000 + 60L * 10_000);
            assertThat(today.download())
                    .as("every second's download, the repeated ones too")
                    .isEqualTo(60L * 500_000);
            assertThat(today.total())
                    .as("the history agrees with what the monitor counted")
                    .isEqualTo(monitor.totalUploadProperty().get()
                            + monitor.totalDownloadProperty().get());
        } finally {
            monitor.shutdown();
            assertThat(store.awaitIdle(10_000)).isTrue();
        }
    }

    /** Lets the pending Platform.runLater work land. */
    private static void awaitFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertThat(latch.await(5, TimeUnit.SECONDS)).as("the FX thread caught up").isTrue();
    }
}
