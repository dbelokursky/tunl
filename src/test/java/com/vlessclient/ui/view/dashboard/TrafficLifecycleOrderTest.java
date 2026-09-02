package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.service.TrafficMonitor;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the traffic monitor's start and stop actually run.
 *
 * <p>{@code TrafficMonitor.stop()} joins its streaming thread for up to two
 * seconds, and this binder is driven from connection-state changes on the FX
 * thread — so a disconnect could freeze the window for as long as the core took
 * to let go.</p>
 *
 * <p>Handing the call to a fresh thread would trade that for something worse:
 * on a reconnect the pending stop can overtake the following start and kill the
 * monitor that just came up. Both properties are pinned here — off the caller's
 * thread, and still in order.</p>
 */
class TrafficLifecycleOrderTest {

    private AppSettings previousSettings;

    @BeforeEach
    void registerSettings() {
        try {
            previousSettings = ServiceLocator.get(AppSettings.class);
        } catch (IllegalArgumentException e) {
            previousSettings = null;
        }
        ServiceLocator.register(AppSettings.class, new AppSettings());
    }

    @AfterEach
    void restoreSettings() {
        if (previousSettings != null) {
            ServiceLocator.register(AppSettings.class, previousSettings);
        }
    }

    private static TrafficDisplayBinder binderOver(TrafficMonitor monitor) {
        return new TrafficDisplayBinder(monitor, null, null, null, null, null);
    }

    @Test
    @DisplayName("stop runs off the caller's thread and start still follows it")
    void lifecycleCallsAreQueuedNotInlined() throws Exception {
        RecordingMonitor monitor = new RecordingMonitor();
        TrafficDisplayBinder binder = binderOver(monitor);

        binder.onConnectionStateChanged(ConnectionState.DISCONNECTED);
        binder.onConnectionStateChanged(ConnectionState.CONNECTED);

        assertThat(monitor.both.await(10, TimeUnit.SECONDS))
                .as("both calls must actually happen")
                .isTrue();
        assertThat(monitor.calls)
                .as("a stop that overtakes the following start would kill the "
                        + "monitor the reconnect just brought up")
                .containsExactly("stop", "start");
        assertThat(monitor.stopThread.get())
                .as("stop joins a worker for up to 2s; doing that on the caller "
                        + "is the disconnect freeze this removes")
                .isNotEqualTo(Thread.currentThread().getName());
    }

    /** Records what was called, in what order, and from where. */
    private static final class RecordingMonitor extends TrafficMonitor {

        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final AtomicReference<String> stopThread = new AtomicReference<>("");
        private final CountDownLatch both = new CountDownLatch(2);

        @Override
        public void start(int clashApiPort, String secret) {
            calls.add("start");
            both.countDown();
        }

        @Override
        public void stop() {
            stopThread.set(Thread.currentThread().getName());
            calls.add("stop");
            both.countDown();
        }
    }
}
