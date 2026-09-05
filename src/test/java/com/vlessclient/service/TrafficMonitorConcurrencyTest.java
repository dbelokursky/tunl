package com.vlessclient.service;

import com.vlessclient.testing.FxToolkitExtension;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Concurrency / state-machine tests for {@link TrafficMonitor}.
 *
 * <p>These tests don't need a real Clash API endpoint — we point the monitor
 * at an unused localhost port. The SSE connection will fail quickly, but
 * that's fine: we're verifying the lifecycle state machine, not the network
 * behavior.
 */
@ExtendWith(FxToolkitExtension.class)
class TrafficMonitorConcurrencyTest {

    // A port that is almost certainly not listening locally. The HTTP client
    // will fail to connect and the worker thread will exit shortly after.
    private static final int DUMMY_PORT = 1; // privileged port, refused on macOS

    @Test
    void stopBeforeStart_isNoOp() {
        TrafficMonitor monitor = new TrafficMonitor();

        assertThatCode(monitor::stop).doesNotThrowAnyException();

        // Calling stop() repeatedly without ever starting must remain a no-op.
        assertThatCode(monitor::stop).doesNotThrowAnyException();
    }

    @Test
    void doubleStart_secondCallIsIgnored() throws InterruptedException {
        TrafficMonitor monitor = new TrafficMonitor();

        try {
            assertThatCode(() -> monitor.start(DUMMY_PORT, "")).doesNotThrowAnyException();
            // Second call must not throw; per the implementation it logs and
            // returns without starting a second worker thread.
            assertThatCode(() -> monitor.start(DUMMY_PORT, "")).doesNotThrowAnyException();
        } finally {
            monitor.stop();
        }
    }

    @Test
    void rapidStartStopCycles_doNotCorruptState() throws InterruptedException {
        TrafficMonitor monitor = new TrafficMonitor();

        for (int i = 0; i < 20; i++) {
            monitor.start(DUMMY_PORT, "");
            monitor.stop();
        }

        // After the final stop the monitor must be in a clean state and a
        // fresh start/stop cycle must still succeed.
        assertThatCode(() -> {
            monitor.start(DUMMY_PORT, "");
            monitor.stop();
        }).doesNotThrowAnyException();

        // Drain any pending Platform.runLater tasks the SSE workers may have
        // posted so that the speed properties have been reset by stop().
        CountDownLatch drain = new CountDownLatch(1);
        Platform.runLater(drain::countDown);
        assertThat(drain.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(monitor.uploadSpeedProperty().get()).isEqualTo(0);
        assertThat(monitor.downloadSpeedProperty().get()).isEqualTo(0);
    }
}
