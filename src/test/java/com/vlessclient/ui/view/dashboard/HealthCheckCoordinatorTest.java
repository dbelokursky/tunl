package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.HealthCheckTarget;
import com.vlessclient.model.TunnelHealth;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ServiceReachabilityChecker;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.TestConfigStores;
import com.vlessclient.service.TunnelHealthState;
import com.vlessclient.testing.FxToolkitExtension;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.vlessclient.testing.FxTestSupport.flushFxEvents;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the health-check orchestration in {@link HealthCheckCoordinator}:
 * summary transitions, card visibility, reconnect banner, teardown, and
 * target-list editing.
 */
@ExtendWith(FxToolkitExtension.class)
class HealthCheckCoordinatorTest {

    @TempDir
    static Path tempDir;

    private static AppSettings priorSettings;
    private static ConfigStore priorStore;

    private VBox healthCard;
    private Label summaryLabel;
    private VBox statusList;
    private HBox banner;
    private Label bannerLabel;
    private FakeEngine engine;
    private TunnelHealthState healthState;

    /** Engine whose connection state the test controls directly. */
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

    /** Returns a canned result list instead of probing the network. */
    private static final class FakeChecker extends ServiceReachabilityChecker {
        private List<ProbeResult> results = List.of();

        @Override
        public CompletableFuture<List<ProbeResult>> checkAll(
                List<HealthCheckTarget> targets, int httpProxyPort) {
            return CompletableFuture.completedFuture(results);
        }
    }

    /** Leaves the probe outstanding until the test says otherwise. */
    private static final class BlockingChecker extends ServiceReachabilityChecker {
        private final CompletableFuture<List<ProbeResult>> pending = new CompletableFuture<>();

        @Override
        public CompletableFuture<List<ProbeResult>> checkAll(
                List<HealthCheckTarget> targets, int httpProxyPort) {
            return pending;
        }

        void complete(List<ProbeResult> results) {
            pending.complete(results);
        }
    }

    /** Supplies independently controlled probe futures in invocation order. */
    private static final class SequencedChecker extends ServiceReachabilityChecker {
        private final List<CompletableFuture<List<ProbeResult>>> pending = List.of(
                new CompletableFuture<>(), new CompletableFuture<>(), new CompletableFuture<>());
        private int calls;

        @Override
        public CompletableFuture<List<ProbeResult>> checkAll(
                List<HealthCheckTarget> targets, int httpProxyPort) {
            return pending.get(calls++);
        }

        void complete(int index, List<ProbeResult> results) {
            pending.get(index).complete(results);
        }

        int calls() {
            return calls;
        }
    }

    @BeforeAll
    static void rememberPriorServices() {
        priorSettings = tryGet(AppSettings.class);
        priorStore = tryGet(ConfigStore.class);
    }

    private static <T> T tryGet(Class<T> type) {
        try {
            return ServiceLocator.get(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @AfterAll
    static void restoreServices() {
        ServiceLocator.register(AppSettings.class,
                priorSettings != null ? priorSettings : new AppSettings());
        ServiceLocator.register(ConfigStore.class,
                priorStore != null ? priorStore : TestConfigStores.at(tempDir));
    }

    @BeforeEach
    void freshNodes() {
        healthCard = new VBox();
        summaryLabel = new Label("—");
        statusList = new VBox();
        banner = new HBox();
        bannerLabel = new Label();
        engine = new FakeEngine();
        healthState = new TunnelHealthState();
    }

    /** Replaced by the reconnect tests; a no-op everywhere else. */
    private Runnable reconnectAction = () -> { };

    private HealthCheckCoordinator coordinatorWith(ServiceReachabilityChecker checker) {
        return new HealthCheckCoordinator(
                new HealthCheckCoordinator.Controls(
                        healthCard, summaryLabel, statusList, banner, bannerLabel),
                checker,
                healthState,
                () -> engine,
                () -> { },
                () -> { },
                reconnectAction);
    }

    private static AppSettings healthSettings(boolean autoReconnect, HealthCheckTarget... targets) {
        AppSettings settings = new AppSettings();
        settings.setHealthCheckEnabled(true);
        settings.setHealthCheckAutoReconnect(autoReconnect);
        // Long timers so nothing fires while a test is running.
        settings.setHealthCheckIntervalSeconds(3600);
        settings.setHealthCheckDelaySeconds(3600);
        settings.setHealthCheckTargets(new ArrayList<>(List.of(targets)));
        ServiceLocator.register(AppSettings.class, settings);
        return settings;
    }

    private static ServiceReachabilityChecker.ProbeResult probe(String name, boolean reachable) {
        return new ServiceReachabilityChecker.ProbeResult(
                name, "https://" + name, reachable, reachable ? 12 : -1,
                reachable ? "HTTP 204" : "timeout");
    }

    private static void onFxAndWait(Runnable action) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private void connectAndCheck(HealthCheckCoordinator coordinator) throws InterruptedException {
        engine.state.set(ConnectionState.CONNECTED);
        onFxAndWait(() -> coordinator.onConnectionStateChanged(ConnectionState.CONNECTED));
        flushFxEvents();   // drain the whenComplete -> runLater hop
    }

    @Test
    void allReachableRendersRowsAndSummary() throws Exception {
        healthSettings(false,
                new HealthCheckTarget("a", "https://a"), new HealthCheckTarget("b", "https://b"));
        FakeChecker checker = new FakeChecker();
        checker.results = List.of(probe("a", true), probe("b", true));

        connectAndCheck(coordinatorWith(checker));

        assertThat(healthCard.isVisible()).isTrue();
        assertThat(statusList.getChildren()).hasSize(2);
        assertThat(summaryLabel.getText())
                .isEqualTo(I18n.get("dashboard.health.all.reachable"));
        assertThat(banner.isVisible()).isFalse();
    }

    @Test
    void partiallyReachableShowsCountSummary() throws Exception {
        healthSettings(false,
                new HealthCheckTarget("a", "https://a"), new HealthCheckTarget("b", "https://b"));
        FakeChecker checker = new FakeChecker();
        checker.results = List.of(probe("a", true), probe("b", false));

        connectAndCheck(coordinatorWith(checker));

        assertThat(summaryLabel.getText())
                .isEqualTo(I18n.get("dashboard.health.some.reachable", 1, 2));
    }

    @Test
    void allUnreachableWithAutoReconnectShowsBannerUntilCancelled() throws Exception {
        healthSettings(true, new HealthCheckTarget("a", "https://a"));
        FakeChecker checker = new FakeChecker();
        checker.results = List.of(probe("a", false));
        HealthCheckCoordinator coordinator = coordinatorWith(checker);

        connectAndCheck(coordinator);

        assertThat(summaryLabel.getText())
                .isEqualTo(I18n.get("dashboard.health.all.unreachable"));
        assertThat(banner.isVisible()).isTrue();
        assertThat(bannerLabel.getText()).isNotBlank();

        onFxAndWait(coordinator::cancelReconnectCountdown);
        assertThat(banner.isVisible()).isFalse();
    }

    @Test
    void disabledFeatureHidesCard() throws Exception {
        AppSettings settings = healthSettings(false, new HealthCheckTarget("a", "https://a"));
        settings.setHealthCheckEnabled(false);
        FakeChecker checker = new FakeChecker();

        connectAndCheck(coordinatorWith(checker));

        assertThat(healthCard.isVisible()).isFalse();
    }

    @Test
    void emptyTargetListKeepsCardWithHint() throws Exception {
        healthSettings(false);
        FakeChecker checker = new FakeChecker();

        connectAndCheck(coordinatorWith(checker));

        assertThat(healthCard.isVisible()).isTrue();
        assertThat(statusList.getChildren()).isEmpty();
        assertThat(summaryLabel.getText()).isEqualTo(I18n.get("health.no.targets"));
    }

    @Test
    void aRestartInProgressIsNotTreatedAsAUserDisconnect() throws Exception {
        AppSettings settings = healthSettings(true, new HealthCheckTarget("a", "https://a"));
        settings.setHealthCheckDelaySeconds(1);
        FakeChecker checker = new FakeChecker();
        checker.results = List.of(probe("a", false));

        // Stands in for a slow stop: SingBoxEngine waits out a SIGTERM grace
        // period and can force-kill after it, so DISCONNECTED can arrive
        // seconds into the restart. The old 700 ms gap had already cleared the
        // guard by then, and the coordinator tore its own reconnect down.
        CountDownLatch restartStarted = new CountDownLatch(1);
        CountDownLatch releaseRestart = new CountDownLatch(1);
        reconnectAction = () -> {
            restartStarted.countDown();
            try {
                releaseRestart.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        HealthCheckCoordinator coordinator = coordinatorWith(checker);

        connectAndCheck(coordinator);
        assertThat(banner.isVisible()).isTrue();
        assertThat(restartStarted.await(5, TimeUnit.SECONDS))
                .as("the countdown must reach the restart")
                .isTrue();

        // The engine reports the stop while the restart is still in flight.
        engine.state.set(ConnectionState.DISCONNECTED);
        onFxAndWait(() ->
                coordinator.onConnectionStateChanged(ConnectionState.DISCONNECTED));

        assertThat(healthCard.isVisible())
                .as("this DISCONNECTED is the app's own restart, not the user's")
                .isTrue();

        releaseRestart.countDown();
    }

    @Test
    void userDisconnectTearsTheCardDown() throws Exception {
        healthSettings(false, new HealthCheckTarget("a", "https://a"));
        FakeChecker checker = new FakeChecker();
        checker.results = List.of(probe("a", true));
        HealthCheckCoordinator coordinator = coordinatorWith(checker);

        connectAndCheck(coordinator);
        assertThat(healthCard.isVisible()).isTrue();

        engine.state.set(ConnectionState.DISCONNECTED);
        onFxAndWait(() ->
                coordinator.onConnectionStateChanged(ConnectionState.DISCONNECTED));

        assertThat(healthCard.isVisible()).isFalse();
        assertThat(statusList.getChildren()).isEmpty();
        assertThat(summaryLabel.getText()).isEqualTo("—");
        assertThat(banner.isVisible()).isFalse();
    }

    @Test
    void addTargetIgnoresExactUrlDuplicate() throws Exception {
        AppSettings settings = healthSettings(false, new HealthCheckTarget("a", "https://a"));
        ServiceLocator.register(ConfigStore.class,
                TestConfigStores.at(tempDir.resolve("health-store")));
        FakeChecker checker = new FakeChecker();
        HealthCheckCoordinator coordinator = coordinatorWith(checker);
        engine.state.set(ConnectionState.CONNECTED);

        onFxAndWait(() -> coordinator.addTarget(new HealthCheckTarget("dup", "https://a")));
        assertThat(settings.getHealthCheckTargets()).hasSize(1);

        onFxAndWait(() -> coordinator.addTarget(new HealthCheckTarget("b", "https://b")));
        assertThat(settings.getHealthCheckTargets()).hasSize(2);
    }

    // ===== the verdict published to the rest of the app =====

    @Test
    void allReachablePublishesHealthy() throws Exception {
        healthSettings(false,
                new HealthCheckTarget("a", "https://a"), new HealthCheckTarget("b", "https://b"));
        FakeChecker checker = new FakeChecker();
        checker.results = List.of(probe("a", true), probe("b", true));

        connectAndCheck(coordinatorWith(checker));

        assertThat(healthState.get()).isEqualTo(TunnelHealth.HEALTHY);
    }

    @Test
    void partiallyReachablePublishesDegraded() throws Exception {
        healthSettings(false,
                new HealthCheckTarget("a", "https://a"), new HealthCheckTarget("b", "https://b"));
        FakeChecker checker = new FakeChecker();
        checker.results = List.of(probe("a", true), probe("b", false));

        connectAndCheck(coordinatorWith(checker));

        assertThat(healthState.get()).isEqualTo(TunnelHealth.DEGRADED);
    }

    /** The reported bug: a core that started while nothing gets through. */
    @Test
    void allUnreachablePublishesBroken() throws Exception {
        healthSettings(false, new HealthCheckTarget("a", "https://a"));
        FakeChecker checker = new FakeChecker();
        checker.results = List.of(probe("a", false));

        connectAndCheck(coordinatorWith(checker));

        assertThat(healthState.get()).isEqualTo(TunnelHealth.BROKEN);
    }

    @Test
    void firstProbeOfAConnectionIsPublishedAsChecking() throws Exception {
        healthSettings(false, new HealthCheckTarget("a", "https://a"));
        BlockingChecker checker = new BlockingChecker();
        HealthCheckCoordinator coordinator = coordinatorWith(checker);

        engine.state.set(ConnectionState.CONNECTED);
        onFxAndWait(() -> coordinator.onConnectionStateChanged(ConnectionState.CONNECTED));

        assertThat(healthState.get())
                .as("a tunnel with no verdict yet must not look verified")
                .isEqualTo(TunnelHealth.CHECKING);

        checker.complete(List.of(probe("a", true)));
        flushFxEvents();
        assertThat(healthState.get()).isEqualTo(TunnelHealth.HEALTHY);
    }

    @Test
    void staleProbeCompletionDoesNotClearTheCurrentInFlightProbe() throws Exception {
        healthSettings(false, new HealthCheckTarget("a", "https://a"));
        SequencedChecker checker = new SequencedChecker();
        HealthCheckCoordinator coordinator = coordinatorWith(checker);

        engine.state.set(ConnectionState.CONNECTED);
        onFxAndWait(() -> coordinator.onConnectionStateChanged(ConnectionState.CONNECTED));

        engine.state.set(ConnectionState.DISCONNECTED);
        onFxAndWait(() -> coordinator.onConnectionStateChanged(ConnectionState.DISCONNECTED));
        engine.state.set(ConnectionState.CONNECTED);
        onFxAndWait(() -> coordinator.onConnectionStateChanged(ConnectionState.CONNECTED));
        assertThat(checker.calls()).isEqualTo(2);

        checker.complete(0, List.of(probe("stale", true)));
        flushFxEvents();
        onFxAndWait(coordinator::recheck);

        assertThat(checker.calls())
                .as("the stale callback must not make a newer probe look idle")
                .isEqualTo(2);

        checker.complete(1, List.of(probe("current", true)));
        flushFxEvents();
    }

    @Test
    void reProbeKeepsTheStandingVerdictInsteadOfBlinking() throws Exception {
        healthSettings(false, new HealthCheckTarget("a", "https://a"));
        FakeChecker settled = new FakeChecker();
        settled.results = List.of(probe("a", true));
        HealthCheckCoordinator coordinator = coordinatorWith(settled);
        connectAndCheck(coordinator);
        assertThat(healthState.get()).isEqualTo(TunnelHealth.HEALTHY);

        // A periodic re-check runs the same path again. With a 5s default
        // interval, flipping back to CHECKING here would strobe the tray icon.
        onFxAndWait(coordinator::recheck);
        assertThat(healthState.get()).isEqualTo(TunnelHealth.HEALTHY);
    }

    @Test
    void disconnectClearsTheVerdict() throws Exception {
        healthSettings(false, new HealthCheckTarget("a", "https://a"));
        FakeChecker checker = new FakeChecker();
        checker.results = List.of(probe("a", false));
        HealthCheckCoordinator coordinator = coordinatorWith(checker);

        connectAndCheck(coordinator);
        assertThat(healthState.get()).isEqualTo(TunnelHealth.BROKEN);

        engine.state.set(ConnectionState.DISCONNECTED);
        onFxAndWait(() ->
                coordinator.onConnectionStateChanged(ConnectionState.DISCONNECTED));

        assertThat(healthState.get()).isEqualTo(TunnelHealth.UNMONITORED);
    }

    @Test
    void disabledFeaturePublishesUnmonitored() throws Exception {
        AppSettings settings = healthSettings(false, new HealthCheckTarget("a", "https://a"));
        settings.setHealthCheckEnabled(false);

        connectAndCheck(coordinatorWith(new FakeChecker()));

        assertThat(healthState.get())
                .as("switching the checks off must not leave the user amber forever")
                .isEqualTo(TunnelHealth.UNMONITORED);
    }
}
