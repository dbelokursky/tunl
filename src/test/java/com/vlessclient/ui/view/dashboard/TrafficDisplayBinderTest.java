package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.service.TrafficMonitor;
import com.vlessclient.ui.view.MirroredSparkline;
import com.vlessclient.testing.FxToolkitExtension;
import javafx.scene.control.Label;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the monitor start/stop glue in {@link TrafficDisplayBinder}.
 * Label binding itself rides on TrafficMonitor's formatting, which is covered
 * by TrafficMonitorTest; here we pin the lifecycle decisions.
 */
@ExtendWith(FxToolkitExtension.class)
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

    /** Counts clears, which is the one thing the binder does to the chart. */
    private static final class RecordingSparkline extends MirroredSparkline {
        private int clears;

        @Override
        public void clear() {
            clears++;
            super.clear();
        }
    }

    @BeforeAll
    static void rememberPriorSettings() {
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

    /**
     * Without a monitor the binder must leave the chart alone too: a
     * DISCONNECTED or ERROR with a live monitor clears the sparkline, so the
     * chart is the one observable thing a "no-op" can get wrong.
     */
    @Test
    void nullMonitorLeavesTheChartAndLabelsUntouched() {
        RecordingSparkline sparkline = new RecordingSparkline();
        Label download = new Label("42 KB/s");
        TrafficDisplayBinder binder = new TrafficDisplayBinder(null,
                new Label(), download, new Label(), new Label(), sparkline);

        for (ConnectionState state : ConnectionState.values()) {
            binder.onConnectionStateChanged(state);
        }
        assertThat(binder.awaitIdle(10_000)).isTrue();

        assertThat(sparkline.clears)
                .as("no monitor means nothing to stop, so nothing to clear either")
                .isZero();
        assertThat(download.getText()).isEqualTo("42 KB/s");
    }
}
