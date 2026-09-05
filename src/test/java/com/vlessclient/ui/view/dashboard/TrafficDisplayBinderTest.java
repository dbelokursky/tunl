package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.service.TrafficMonitor;
import com.vlessclient.testing.FxToolkitExtension;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
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
        return binderOver(monitor, new VBox());
    }

    private static TrafficDisplayBinder binderOver(TrafficMonitor monitor, VBox summary) {
        return new TrafficDisplayBinder(monitor,
                new TrafficDisplayBinder.Readout(new Label(), new Label()),
                new TrafficDisplayBinder.Readout(new Label(), new Label()),
                new Label(), summary);
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
     * The readout is hidden in the FXML and shown only while a tunnel is up:
     * there is no traffic to report otherwise, and the status row then looks
     * exactly as it did before the block was merged into it.
     */
    @Test
    void connectingAndDroppingTheTunnelShowsAndHidesTheReadout() {
        AppSettings settings = new AppSettings();
        ServiceLocator.register(AppSettings.class, settings);

        VBox summary = new VBox();
        summary.setVisible(false);
        summary.setManaged(false);
        TrafficDisplayBinder binder = binderOver(new RecordingMonitor(), summary);

        binder.onConnectionStateChanged(ConnectionState.CONNECTED);
        assertThat(binder.awaitIdle(10_000)).isTrue();
        assertThat(summary.isVisible()).as("a live tunnel has traffic to report").isTrue();
        assertThat(summary.isManaged())
                .as("visible but unmanaged would leave a hole in the row")
                .isTrue();

        binder.onConnectionStateChanged(ConnectionState.DISCONNECTED);
        assertThat(binder.awaitIdle(10_000)).isTrue();
        assertThat(summary.isVisible()).isFalse();
        assertThat(summary.isManaged()).isFalse();
    }

    /**
     * Without a monitor the binder must leave the readout alone too: with a
     * live monitor a DISCONNECTED or ERROR hides the block and zeroes its
     * labels, so those are the observable things a "no-op" can get wrong.
     */
    @Test
    void nullMonitorLeavesTheReadoutUntouched() {
        VBox summary = new VBox();
        Label download = new Label("42 KB/s");
        TrafficDisplayBinder binder = new TrafficDisplayBinder(null,
                new TrafficDisplayBinder.Readout(new Label(), new Label()),
                new TrafficDisplayBinder.Readout(new Label(), download),
                new Label(), summary);

        for (ConnectionState state : ConnectionState.values()) {
            binder.onConnectionStateChanged(state);
        }
        assertThat(binder.awaitIdle(10_000)).isTrue();

        assertThat(summary.isVisible())
                .as("no monitor means nothing to start, so nothing to reveal or hide")
                .isTrue();
        assertThat(download.getText()).isEqualTo("42 KB/s");
    }
}
