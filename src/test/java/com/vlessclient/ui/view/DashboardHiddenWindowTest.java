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
import com.vlessclient.testing.Await;
import com.vlessclient.testing.FxPulses;
import com.vlessclient.testing.UiTest;
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
 * <p>Stopping is measured where it costs. JavaFX pauses its pulse timer only
 * while no animation is running, so one transition left playing kept a hidden
 * app waking at the display refresh rate for as long as the tunnel stayed up.</p>
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
        // when the assertions look.
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

    private void connect() {
        interact(() -> engine.state.set(ConnectionState.CONNECTED));
        Await.until("the first health probe", () -> checker.calls.get() >= 1,
                Duration.ofSeconds(5));
        // The probe answers at once; the verdict, and with it the wait for the
        // next probe, arrives through runLater.
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static ServerConfig server(String id, String name) {
        ServerConfig config = new ServerConfig();
        config.setId(id);
        config.setName(name);
        return config;
    }
}
