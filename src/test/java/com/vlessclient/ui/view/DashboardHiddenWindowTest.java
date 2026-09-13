package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.HealthCheckTarget;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ServiceReachabilityChecker;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.TrafficHistoryStore;
import com.vlessclient.service.TrafficMonitor;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.FxPulses;
import com.vlessclient.testing.UiTest;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Closing the main window to the tray hides the stage and keeps the dashboard
 * with everything it runs. What it runs splits along what the window was for:
 * the health loop feeds the menu-bar icon and auto-reconnect and has to go on,
 * while work that exists to draw has to stop, and catch up once the window is
 * back.
 *
 * <p>Stopping is measured where it costs. While any animation plays, JavaFX
 * pulses at the display refresh rate whether or not a window is showing, and
 * without one every layout request still costs a pulse: one transition left
 * playing kept a hidden app pulsing sixty times a second, and a readout
 * repainted for every traffic sample bought a pulse per sample.</p>
 */
@UiTest
public class DashboardHiddenWindowTest extends ApplicationTest {

    private Stage stage;
    private FakeEngine engine;
    private CountingChecker checker;
    private TrafficHistoryStore store;
    private int animationsBeforeDashboard;
    private Region historyPanel;
    private Label historyServers;
    private Label uploadSpeed;

    /** An engine whose connection state the test sets directly. */
    private static final class FakeEngine extends SingBoxEngine {
        private final SimpleObjectProperty<ConnectionState> state =
                new SimpleObjectProperty<>(ConnectionState.DISCONNECTED);

        FakeEngine() {
            super(Path.of("sing-box-not-used-in-tests"));
        }

        @Override
        public ReadOnlyObjectProperty<ConnectionState> connectionStateProperty() {
            return state;
        }
    }

    /** Answers every probe as reachable and counts them. */
    private static final class CountingChecker extends ServiceReachabilityChecker {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public CompletableFuture<List<ProbeResult>> checkAll(
                List<HealthCheckTarget> targets, int httpProxyPort) {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(targets.stream()
                    .map(target -> new ProbeResult(target.getName(), target.getUrl(),
                            true, 12, "HTTP 204"))
                    .toList());
        }
    }

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        // Counted before the dashboard exists, so whatever it starts shows up.
        animationsBeforeDashboard = FxPulses.runningAnimations();

        AppSettings settings = new AppSettings();
        settings.setHealthCheckEnabled(true);
        settings.setHealthCheckAutoReconnect(false);
        // Long enough that a transition waiting it out would still be playing
        // when the assertions look, and that no probe lands inside a pulse count.
        settings.setHealthCheckIntervalSeconds(60);
        settings.setTrafficHistoryExpanded(true);
        ServiceLocator.register(AppSettings.class, settings);

        store = new TrafficHistoryStore(Files.createTempDirectory("hidden-window-history"),
                Clock.systemDefaultZone());
        store.record(server("amsterdam", "Amsterdam 01"), 1_000, 4_000);
        ServiceLocator.register(TrafficHistoryStore.class, store);

        checker = new CountingChecker();
        ServiceLocator.register(ServiceReachabilityChecker.class, checker);
        engine = new FakeEngine();
        ServiceLocator.register(SingBoxEngine.class, engine);

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/DashboardView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 900, 700));
        stage.show();
        historyPanel = (Region) root.lookup("#trafficHistoryPanel");
        historyServers = (Label) root.lookup("#trafficHistoryServers");
        uploadSpeed = (Label) root.lookup("#uploadSpeedLabel");
    }

    /**
     * TestFX reuses the stage, and a dashboard left connected would go on
     * probing for the rest of the run.
     */
    @AfterEach
    void disconnectAndShowAgain() {
        interact(() -> engine.state.set(ConnectionState.DISCONNECTED));
        interact(stage::show);
    }

    @Test
    void aConnectedDashboardInTheTrayLeavesNoAnimationRunning() {
        connect();
        assertThat(historyPanel.isVisible())
                .as("precondition: the history panel, which polls, is open")
                .isTrue();

        interact(stage::hide);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(FxPulses.runningAnimations())
                .as("with the window in the tray nothing may keep the pulse timer awake")
                .isEqualTo(animationsBeforeDashboard);
    }

    @Test
    void healthChecksGoOnWhileTheWindowIsInTheTray() {
        interact(() -> ServiceLocator.get(AppSettings.class).setHealthCheckIntervalSeconds(1));
        connect();

        interact(stage::hide);
        int probesWhenHidden = checker.calls.get();

        // The tray icon and auto-reconnect act on these verdicts.
        Await.until("two more probes with the window hidden",
                () -> checker.calls.get() >= probesWhenHidden + 2, Duration.ofSeconds(6));
    }

    @Test
    void theHistoryCatchesUpWhenTheWindowComesBack() {
        assertThat(historyServers.getText())
                .as("precondition: the open panel names the server on record")
                .contains("Amsterdam 01")
                .doesNotContain("Frankfurt 02");

        interact(stage::hide);
        interact(() -> store.record(server("frankfurt", "Frankfurt 02"), 2_000_000, 9_000_000));
        interact(stage::show);

        assertThat(historyServers.getText())
                .as("the panel reads the record again the moment it is back on screen")
                .contains("Frankfurt 02");
    }

    @Test
    void theSpeedsCatchUpWhenTheWindowComesBack() throws Exception {
        connect();
        String shownBeforeHiding = uploadSpeed.getText();
        String latest = TrafficMonitor.formatSpeed(123_456);
        assertThat(shownBeforeHiding).as("precondition").isNotEqualTo(latest);

        interact(stage::hide);
        pushTrafficSample(123_456, 654_321);
        assertThat(uploadSpeed.getText())
                .as("a sample arriving in the tray is remembered, not drawn")
                .isEqualTo(shownBeforeHiding);

        interact(stage::show);
        assertThat(uploadSpeed.getText())
                .as("the readout shows the latest sample the moment it is back on screen")
                .isEqualTo(latest);
    }

    /**
     * The tests above, counted as what they cost. Every label the readout
     * changes asks for a layout, and a layout request that reaches the scene
     * root asks the toolkit for a pulse whether or not the window is showing.
     */
    @Test
    void aConnectedDashboardInTheTrayStopsPulsingWhileTrafficFlows() throws Exception {
        connect();
        interact(stage::hide);
        // Past the pulses hiding the window asks for itself.
        Thread.sleep(500);

        long pulses;
        try (FxPulses.Counter counter = FxPulses.countPulses()) {
            // A readout repainted per sample costs a pulse per sample: about
            // thirty here, where the core would send one a second.
            for (int i = 0; i < 30; i++) {
                pushTrafficSample(20_000 + i, 70_000 + i);
                Thread.sleep(50);
            }
            pulses = counter.pulses();
        }

        assertThat(pulses)
                .as("pulses while thirty samples arrived for a dashboard in the tray")
                .isLessThanOrEqualTo(2);
    }

    private void connect() {
        interact(() -> engine.state.set(ConnectionState.CONNECTED));
        Await.until("the first health probe", () -> checker.calls.get() >= 1,
                Duration.ofSeconds(5));
        // The probe answers at once; the verdict, and with it the wait for the
        // next probe, arrives through runLater.
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** One {@code /traffic} line as the core streams it, through the monitor's own parser. */
    private static void pushTrafficSample(long up, long down) throws Exception {
        Method processLine = TrafficMonitor.class.getDeclaredMethod(
                "processTrafficLine", String.class);
        processLine.setAccessible(true);
        processLine.invoke(ServiceLocator.get(TrafficMonitor.class),
                "{\"up\":" + up + ",\"down\":" + down + "}");
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static ServerConfig server(String id, String name) {
        ServerConfig config = new ServerConfig();
        config.setId(id);
        config.setName(name);
        return config;
    }
}
