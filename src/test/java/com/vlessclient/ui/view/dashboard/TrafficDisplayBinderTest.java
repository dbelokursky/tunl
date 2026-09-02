package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.service.TrafficMonitor;
import javafx.application.Platform;
import javafx.scene.control.Label;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the monitor start/stop glue in {@link TrafficDisplayBinder}.
 * Label binding itself rides on TrafficMonitor's formatting, which is covered
 * by TrafficMonitorTest; here we pin the lifecycle decisions.
 */
class TrafficDisplayBinderTest {

    private static AppSettings priorSettings;

    /** Records lifecycle calls instead of opening real connections. */
    private static final class RecordingMonitor extends TrafficMonitor {
        private int startedPort = -1;
        private String startedSecret;
        private boolean stopped;

        @Override
        public void start(int clashApiPort, String secret) {
            startedPort = clashApiPort;
            startedSecret = secret;
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }

    @BeforeAll
    static void initJfx() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException ignored) {
            // Platform already started (e.g. from a previous test)
        }
        try {
            priorSettings = ServiceLocator.get(AppSettings.class);
        } catch (IllegalArgumentException e) {
            priorSettings = null;
        }
    }

    @AfterAll
    static void restoreServices() {
        ServiceLocator.register(AppSettings.class,
                priorSettings != null ? priorSettings : new AppSettings());
    }

    private static TrafficDisplayBinder binderOver(TrafficMonitor monitor) {
        return new TrafficDisplayBinder(monitor,
                new Label(), new Label(), new Label(), new Label(), null);
    }

    @Test
    void connectedStartsMonitorOnConfiguredClashApiPort() {
        AppSettings settings = new AppSettings();
        settings.setClashApiPort(19999);
        settings.setClashApiSecret("tok-xyz");
        ServiceLocator.register(AppSettings.class, settings);

        RecordingMonitor monitor = new RecordingMonitor();
        TrafficDisplayBinder binder = binderOver(monitor);
        binder.onConnectionStateChanged(ConnectionState.CONNECTED);
        assertThat(binder.awaitIdle(10_000))
                .as("the lifecycle call is queued off the caller now")
                .isTrue();

        assertThat(monitor.startedPort).isEqualTo(19999);
        assertThat(monitor.startedSecret).isEqualTo("tok-xyz");
        assertThat(monitor.stopped).isFalse();
    }

    @Test
    void disconnectedStopsMonitor() {
        RecordingMonitor monitor = new RecordingMonitor();
        TrafficDisplayBinder binder = binderOver(monitor);
        binder.onConnectionStateChanged(ConnectionState.DISCONNECTED);
        assertThat(binder.awaitIdle(10_000))
                .as("the lifecycle call is queued off the caller now")
                .isTrue();

        assertThat(monitor.stopped).isTrue();
        assertThat(monitor.startedPort).isEqualTo(-1);
    }

    @Test
    void errorStopsMonitor() {
        RecordingMonitor monitor = new RecordingMonitor();
        TrafficDisplayBinder binder = binderOver(monitor);
        binder.onConnectionStateChanged(ConnectionState.ERROR);
        assertThat(binder.awaitIdle(10_000))
                .as("the lifecycle call is queued off the caller now")
                .isTrue();

        assertThat(monitor.stopped).isTrue();
    }

    @Test
    void connectingChangesNothing() {
        RecordingMonitor monitor = new RecordingMonitor();
        TrafficDisplayBinder binder = binderOver(monitor);
        binder.onConnectionStateChanged(ConnectionState.CONNECTING);
        assertThat(binder.awaitIdle(10_000))
                .as("the lifecycle call is queued off the caller now")
                .isTrue();

        assertThat(monitor.startedPort).isEqualTo(-1);
        assertThat(monitor.stopped).isFalse();
    }

    @Test
    void nullMonitorIsNoOpForAllStates() {
        TrafficDisplayBinder binder = binderOver(null);
        for (ConnectionState state : ConnectionState.values()) {
            binder.onConnectionStateChanged(state);
        }
        // Reaching here without an exception is the contract.
    }
}
