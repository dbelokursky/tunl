package com.vlessclient.service;

import static com.vlessclient.testing.FxTestSupport.flushFxEvents;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vlessclient.testing.FxToolkitExtension;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * What a caller of {@link FxExecutor} can rely on once it has given up waiting.
 */
@ExtendWith(FxToolkitExtension.class)
class FxExecutorTest {

    /**
     * A subscription refresh waits here while it holds the lock a delete on the
     * FX thread wants. It timed out, but its change stayed queued and ran after
     * the delete, putting back the servers the user had just removed.
     */
    @Test
    void aTaskThatHasNotStartedWhenTheCallerGivesUpNeverRuns() throws Exception {
        CountDownLatch fxBusy = new CountDownLatch(1);
        CountDownLatch releaseFx = new CountDownLatch(1);
        Platform.runLater(() -> {
            fxBusy.countDown();
            try {
                releaseFx.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(fxBusy.await(5, TimeUnit.SECONDS)).as("the FX thread is busy").isTrue();

        AtomicBoolean ran = new AtomicBoolean();
        try {
            assertThatThrownBy(() -> FxExecutor.get(() -> {
                ran.set(true);
                return null;
            }, Duration.ofMillis(200)))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Timed out");
        } finally {
            releaseFx.countDown();
        }
        flushFxEvents();

        assertThat(ran)
                .as("a change whose caller already gave up must not be applied afterwards")
                .isFalse();
    }

    @Test
    void aTaskThatAnswersInTimeReturnsItsValueFromTheFxThread() {
        assertThat(FxExecutor.get(Platform::isFxApplicationThread, Duration.ofSeconds(5)))
                .isTrue();
    }
}
