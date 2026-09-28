package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.HealthCheckTarget;
import com.vlessclient.model.TunnelHealth;
import com.vlessclient.service.ConfigRejectedException;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.FxExecutor;
import com.vlessclient.service.ServiceReachabilityChecker;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.TestConfigStores;
import com.vlessclient.service.TestRejections;
import com.vlessclient.service.TunnelHealthState;
import com.vlessclient.service.TunnelRecoveryService;
import org.junit.jupiter.api.AfterEach;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.FxPulses;
import com.vlessclient.testing.FxToolkitExtension;
import com.vlessclient.testing.ManualPowerState;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
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
    private Button bannerButton;
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
        private final List<Integer> ports = new ArrayList<>();

        @Override
        public CompletableFuture<List<ProbeResult>> checkAll(
                List<HealthCheckTarget> targets, int probePort) {
            ports.add(probePort);
            return CompletableFuture.completedFuture(results);
        }
    }

    /** Leaves the probe outstanding until the test says otherwise. */
    private static final class BlockingChecker extends ServiceReachabilityChecker {
        private final CompletableFuture<List<ProbeResult>> pending = new CompletableFuture<>();

        @Override
        public CompletableFuture<List<ProbeResult>> checkAll(
                List<HealthCheckTarget> targets, int probePort) {
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
                List<HealthCheckTarget> targets, int probePort) {
            return pending.get(calls++);
        }

        void complete(int index, List<ProbeResult> results) {
            pending.get(index).complete(results);
        }

        int calls() {
            return calls;
        }
    }

    /** Answers every probe with the same result and counts them. */
    private static final class CountingChecker extends ServiceReachabilityChecker {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<ProbeResult> results;

        CountingChecker(List<ProbeResult> results) {
            this.results = results;
        }

        @Override
        public CompletableFuture<List<ProbeResult>> checkAll(
                List<HealthCheckTarget> targets, int probePort) {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(results);
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

    /**
     * The card draws only while it is on screen, so every test gets it laid
     * out as DashboardView.fxml has it, in a window that is showing.
     */
    @BeforeEach
    void freshNodes() throws InterruptedException {
        healthCard = new VBox();
        summaryLabel = new Label("—");
        statusList = new VBox();
        banner = new HBox();
        bannerLabel = new Label();
        bannerButton = new Button();
        banner.getChildren().setAll(bannerLabel);
        healthCard.getChildren().setAll(summaryLabel, statusList, banner);
        engine = new FakeEngine();
        healthState = new TunnelHealthState();
        onFxAndWait(() -> {
            window = new Stage();
            window.setScene(new Scene(healthCard, 400, 300));
            window.show();
        });
    }

    /** Replaced by the reconnect tests; a no-op everywhere else. */
    private Runnable reconnectAction = () -> { };
    private TunnelRecoveryService recovery;
    private Stage window;
    /** Awake unless a test puts it to sleep, so every test goes through the power seam. */
    private final ManualPowerState power = new ManualPowerState();
    private final AtomicBoolean networkUp = new AtomicBoolean(true);

    @AfterEach
    void stopRecovery() {
        if (recovery != null) {
            recovery.close();
        }
    }

    @AfterEach
    void closeWindow() throws InterruptedException {
        onFxAndWait(window::hide);
    }

    private HealthCheckCoordinator coordinatorWith(ServiceReachabilityChecker checker) {
        return coordinatorWith(checker, guard -> {
            reconnectAction.run();
            return true;
        });
    }

    private HealthCheckCoordinator coordinatorWith(ServiceReachabilityChecker checker,
                                                   TunnelRecoveryService.Attempt attempt) {
        recovery = new TunnelRecoveryService(
                () -> ServiceLocator.get(AppSettings.class), attempt, () -> false, power);
        recovery.connectionRequested();
        engine.state.addListener((obs, old, next) -> recovery.onConnectionState(next));
        healthState.healthProperty().addListener((obs, old, next) -> recovery.onHealth(next));
        return new HealthCheckCoordinator(
                new HealthCheckCoordinator.Controls(
                        healthCard, summaryLabel, statusList, banner, bannerLabel, bannerButton),
                checker,
                healthState,
                engine,
                recovery,
                power,
                networkUp::get);
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

    /**
     * The probes go in through this run's probe inbound, which the route sends
     * into the tunnel whatever the routing mode. They went in through the HTTP
     * port, and in the blocked-only mode the rules sent them direct: Google
     * answered past a dead server, and recovery never saw it.
     */
    @Test
    void theProbesGoInThroughThisRunsProbeInbound() throws Exception {
        AppSettings settings = healthSettings(false, new HealthCheckTarget("a", "https://a"));
        settings.listenOn(settings.getSocksPort(), settings.getHttpPort(),
                settings.getClashApiPort(), 47123);
        FakeChecker checker = new FakeChecker();
        checker.results = List.of(probe("a", true));

        connectAndCheck(coordinatorWith(checker));

        assertThat(checker.ports).containsExactly(47123);
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
    void coreCrashKeepsTheRecoveryBannerVisibleAndCancelable() throws Exception {
        healthSettings(true, new HealthCheckTarget("a", "https://a"));
        FakeChecker checker = new FakeChecker();
        checker.results = List.of(probe("a", true));
        HealthCheckCoordinator coordinator = coordinatorWith(checker);
        connectAndCheck(coordinator);
        onFxAndWait(() -> {
            engine.state.set(ConnectionState.ERROR);
            coordinator.onConnectionStateChanged(ConnectionState.ERROR);
        });
        assertThat(banner.isVisible()).isTrue();
        assertThat(healthCard.isVisible()).isTrue();
        onFxAndWait(coordinator::cancelReconnectCountdown);
        assertThat(banner.isVisible()).isFalse();
        assertThat(healthCard.isVisible()).isFalse();
    }

    /**
     * A restart the core refuses stops recovery, and the banner says why. It
     * used to count down to the same refused restart, again and again, under
     * a line blaming unreachable services.
     */
    @Test
    void aRestartTheCoreRefusesStopsRecoveryAndTheBannerSaysWhy() throws Exception {
        AppSettings settings = healthSettings(true, new HealthCheckTarget("a", "https://a"));
        settings.setHealthCheckDelaySeconds(1);
        ConfigRejectedException refusal = TestRejections.refusal("unknown field");
        FakeChecker checker = new FakeChecker();
        checker.results = List.of(probe("a", true));
        HealthCheckCoordinator coordinator = coordinatorWith(checker, guard -> {
            throw refusal;
        });
        connectAndCheck(coordinator);

        onFxAndWait(() -> {
            engine.state.set(ConnectionState.ERROR);
            coordinator.onConnectionStateChanged(ConnectionState.ERROR);
        });
        String expected = I18n.get("dashboard.reconnect.stopped", refusal.getMessage());
        Await.until("the banner to say why recovery stopped",
                () -> FxExecutor.get(() -> banner.isVisible()
                        && expected.equals(bannerLabel.getText())),
                Duration.ofSeconds(10));
        assertThat(healthCard.isVisible()).as("the card holding the banner").isTrue();
        assertThat(recovery.isTunnelWanted())
                .as("the user's request, which recovery stopping does not withdraw")
                .isTrue();
        assertThat(bannerButton.getText())
                .as("with no countdown to cancel, the button withdraws that request")
                .isEqualTo(I18n.get("button.disconnect"));

        onFxAndWait(coordinator::cancelReconnectCountdown);
        assertThat(banner.isVisible()).as("the banner once dismissed").isFalse();
        assertThat(recovery.isTunnelWanted()).as("the request once withdrawn").isFalse();
        assertThat(bannerButton.getText()).isEqualTo(I18n.get("button.cancel"));
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

    // ===== drawing the card =====

    /**
     * A probe comes round every few seconds for as long as the tunnel is up,
     * and each one used to build every row again, a new tooltip included: a
     * popup window with a scene of its own, per service per probe.
     */
    @Test
    void aReProbeUpdatesTheRowsInPlace() throws Exception {
        healthSettings(false,
                new HealthCheckTarget("a", "https://a"), new HealthCheckTarget("b", "https://b"));
        FakeChecker checker = new FakeChecker();
        checker.results = List.of(probe("a", true), probe("b", true));
        HealthCheckCoordinator coordinator = coordinatorWith(checker);
        connectAndCheck(coordinator);
        List<Node> rows = List.copyOf(statusList.getChildren());
        List<Tooltip> tooltips = removeButtons().stream().map(Button::getTooltip).toList();

        checker.results = List.of(probe("a", true), probe("b", false));
        onFxAndWait(coordinator::recheck);
        flushFxEvents();

        assertThat(resultTexts())
                .as("precondition: the second probe is drawn")
                .containsExactly(I18n.get("unit.ms", 12), I18n.get("dashboard.health.unreachable"));
        assertThat(statusList.getChildren())
                .as("the rows the first probe built, updated")
                .containsExactlyElementsOf(rows);
        assertThat(removeButtons().stream().map(Button::getTooltip).toList())
                .as("their tooltips, not new ones")
                .containsExactlyElementsOf(tooltips);
    }

    /**
     * Rows outlive the services they were built for, so a remove button has to
     * act on the service its row shows now, not the one it was built with.
     */
    @Test
    void aReusedRowRemovesTheServiceItShowsNow() throws Exception {
        AppSettings settings = healthSettings(false,
                new HealthCheckTarget("a", "https://a"), new HealthCheckTarget("b", "https://b"));
        ServiceLocator.register(ConfigStore.class,
                TestConfigStores.at(tempDir.resolve("reused-rows-store")));
        HealthCheckCoordinator coordinator = coordinatorWith(new EchoChecker());
        connectAndCheck(coordinator);

        onFxAndWait(() -> removeButtons().get(0).fire());
        flushFxEvents();
        assertThat(settings.getHealthCheckTargets())
                .extracting(HealthCheckTarget::getUrl)
                .containsExactly("https://b");
        assertThat(rowNames()).containsExactly("b");

        onFxAndWait(() -> removeButtons().get(0).fire());
        flushFxEvents();
        assertThat(settings.getHealthCheckTargets()).isEmpty();
        assertThat(statusList.getChildren()).isEmpty();
    }

    /**
     * Every row has a "✕", and a symbol gave a screen reader nothing to read.
     * Each names the service it removes, and Tab reaches it like any other
     * button.
     */
    @Test
    void eachRemoveButtonNamesItsServiceAndTakesTheFocus() throws Exception {
        healthSettings(false,
                new HealthCheckTarget("a", "https://a"), new HealthCheckTarget("b", "https://b"));
        HealthCheckCoordinator coordinator = coordinatorWith(new EchoChecker());
        connectAndCheck(coordinator);

        assertThat(removeButtons())
                .extracting(Button::getAccessibleText)
                .as("the names a screen reader reads for the rows' remove buttons")
                .containsExactly(I18n.get("health.target.remove.named", "a"),
                        I18n.get("health.target.remove.named", "b"));
        assertThat(removeButtons())
                .as("whether Tab reaches each remove button")
                .allMatch(Button::isFocusTraversable);
    }

    /**
     * A row's remove button is a trash can drawn as an icon, with no square
     * of its own until the pointer is over it. The "✕" it was is text, which
     * the font set on its own baseline, off the middle of its square.
     */
    @Test
    void eachRowsRemoveButtonIsABorderlessTrashCan() throws Exception {
        healthSettings(false, new HealthCheckTarget("a", "https://a"));
        HealthCheckCoordinator coordinator = coordinatorWith(new EchoChecker());
        connectAndCheck(coordinator);

        assertThat(removeButtons()).singleElement().satisfies(button -> {
            assertThat(button.getText()).as("a text glyph").isNullOrEmpty();
            assertThat(button.getGraphic()).as("an icon").isNotNull();
            assertThat(button.getStyleClass()).contains("icon-button", "ghost", "destructive");
        });
    }

    /**
     * The dashboard is cached, so another page takes the card out of the scene
     * while the probes go on. Their verdicts still reach the rest of the app;
     * the card shows the latest of them once it is back.
     */
    @Test
    void aCardOffScreenShowsTheLatestProbeOnceItIsBack() throws Exception {
        healthSettings(false, new HealthCheckTarget("a", "https://a"));
        FakeChecker checker = new FakeChecker();
        checker.results = List.of(probe("a", true));
        HealthCheckCoordinator coordinator = coordinatorWith(checker);
        connectAndCheck(coordinator);

        onFxAndWait(() -> window.getScene().setRoot(new VBox()));
        checker.results = List.of(probe("a", false));
        onFxAndWait(coordinator::recheck);
        flushFxEvents();

        assertThat(healthState.get())
                .as("the verdict is published with the card off screen")
                .isEqualTo(TunnelHealth.BROKEN);
        assertThat(summaryLabel.getText())
                .as("but not drawn")
                .isEqualTo(I18n.get("dashboard.health.all.reachable"));
        assertThat(resultTexts()).containsExactly(I18n.get("unit.ms", 12));

        onFxAndWait(() -> window.getScene().setRoot(healthCard));

        assertThat(summaryLabel.getText())
                .isEqualTo(I18n.get("dashboard.health.all.unreachable"));
        assertThat(resultTexts()).containsExactly(I18n.get("dashboard.health.unreachable"));
    }

    /** Answers for exactly the targets it is asked about, every one reachable. */
    private static final class EchoChecker extends ServiceReachabilityChecker {
        @Override
        public CompletableFuture<List<ProbeResult>> checkAll(
                List<HealthCheckTarget> targets, int probePort) {
            return CompletableFuture.completedFuture(targets.stream()
                    .map(target -> probe(target.getName(), true))
                    .toList());
        }
    }

    /** The service each row names, top to bottom. */
    private List<String> rowNames() {
        return rowLabels().stream()
                .filter(label -> label.getStyleClass().contains("service-name"))
                .map(Label::getText)
                .toList();
    }

    /** What each row says about its service, top to bottom. */
    private List<String> resultTexts() {
        return rowLabels().stream()
                .filter(label -> !label.getStyleClass().contains("service-name"))
                .map(Label::getText)
                .toList();
    }

    private List<Label> rowLabels() {
        return statusList.getChildren().stream()
                .flatMap(row -> ((HBox) row).getChildren().stream())
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .toList();
    }

    private List<Button> removeButtons() {
        return statusList.getChildren().stream()
                .flatMap(row -> ((HBox) row).getChildren().stream())
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .toList();
    }

    // ===== the wait between probes =====

    /**
     * The wait lasts as long as the tunnel does, window hidden to the tray
     * included. A PauseTransition waiting it out kept JavaFX pulsing at the
     * display refresh rate the whole time while drawing nothing.
     */
    @Test
    void theWaitForTheNextProbePlaysNoAnimation() throws Exception {
        AppSettings settings = healthSettings(false, new HealthCheckTarget("a", "https://a"));
        settings.setHealthCheckIntervalSeconds(1);
        CountingChecker checker = new CountingChecker(List.of(probe("a", true)));
        HealthCheckCoordinator coordinator = coordinatorWith(checker);
        int animationsBefore = FxPulses.runningAnimations();

        try {
            connectAndCheck(coordinator);
            assertThat(checker.calls).hasValue(1);
            assertThat(FxPulses.runningAnimations())
                    .as("waiting for the next probe must leave the pulse timer free to pause")
                    .isEqualTo(animationsBefore);

            Await.until("the periodic re-check", () -> checker.calls.get() >= 2,
                    Duration.ofSeconds(5));
        } finally {
            disconnect(coordinator);
        }
    }

    @Test
    void disconnectingDropsTheScheduledProbe() throws Exception {
        AppSettings settings = healthSettings(false, new HealthCheckTarget("a", "https://a"));
        settings.setHealthCheckIntervalSeconds(1);
        CountingChecker checker = new CountingChecker(List.of(probe("a", true)));
        HealthCheckCoordinator coordinator = coordinatorWith(checker);

        connectAndCheck(coordinator);
        disconnect(coordinator);
        Thread.sleep(1_500);
        flushFxEvents();

        assertThat(checker.calls).as("no probe once the tunnel is down").hasValue(1);
    }

    private void disconnect(HealthCheckCoordinator coordinator) throws InterruptedException {
        engine.state.set(ConnectionState.DISCONNECTED);
        onFxAndWait(() -> coordinator.onConnectionStateChanged(ConnectionState.DISCONNECTED));
    }

    // ===== the machine's sleep =====

    /**
     * The reported restarts: in a maintenance wake the probes time out through
     * a tunnel with nothing wrong with it, and each such verdict restarted it.
     * Outside the full wake no probe goes out, and the verdict stands.
     */
    @Test
    void outsideTheFullWakeACheckGivesNoVerdictAndRestartsNothing() throws Exception {
        healthSettings(true, new HealthCheckTarget("a", "https://a"));
        FakeChecker checker = new FakeChecker();
        checker.results = List.of(probe("a", true));
        HealthCheckCoordinator coordinator = coordinatorWith(checker);
        connectAndCheck(coordinator);

        power.sleep();
        checker.results = List.of(probe("a", false));
        onFxAndWait(coordinator::recheck);
        flushFxEvents();

        assertThat(checker.ports).as("probes sent outside the full wake").hasSize(1);
        assertThat(healthState.get()).as("the verdict").isEqualTo(TunnelHealth.HEALTHY);
        assertThat(recovery.retryProperty().get()).as("the restart scheduled").isNull();
        assertThat(banner.isVisible()).as("the countdown").isFalse();
    }

    /** A probe that went out awake and ran into the sleep found the sleep, not the tunnel. */
    @Test
    void aProbeThatRunsIntoASleepGivesNoVerdict() throws Exception {
        healthSettings(true, new HealthCheckTarget("a", "https://a"));
        BlockingChecker checker = new BlockingChecker();
        HealthCheckCoordinator coordinator = coordinatorWith(checker);
        engine.state.set(ConnectionState.CONNECTED);
        onFxAndWait(() -> coordinator.onConnectionStateChanged(ConnectionState.CONNECTED));

        power.sleep();
        checker.complete(List.of(probe("a", false)));
        flushFxEvents();

        assertThat(healthState.get()).as("the verdict").isEqualTo(TunnelHealth.CHECKING);
        assertThat(recovery.retryProperty().get()).as("the restart scheduled").isNull();
    }

    /** Nor once the machine is awake again: the probe timed out on the sleep in between. */
    @Test
    void aProbeThatSpannedASleepGivesNoVerdictOnTheWakeEither() throws Exception {
        healthSettings(true, new HealthCheckTarget("a", "https://a"));
        BlockingChecker checker = new BlockingChecker();
        HealthCheckCoordinator coordinator = coordinatorWith(checker);
        engine.state.set(ConnectionState.CONNECTED);
        onFxAndWait(() -> coordinator.onConnectionStateChanged(ConnectionState.CONNECTED));

        power.sleep();
        power.wakeUnannounced();
        checker.complete(List.of(probe("a", false)));
        flushFxEvents();

        assertThat(healthState.get()).as("the verdict").isEqualTo(TunnelHealth.CHECKING);
        assertThat(recovery.retryProperty().get()).as("the restart scheduled").isNull();
    }

    /**
     * The user is back: the tunnel is checked right away, once, and the
     * restart a failed check asks for starts at the first step. A verdict
     * from before the sleep counts for nothing, a broken one included: the
     * fresh check's is published anew, so recovery hears it.
     */
    @Test
    void theWakeChecksOnceRightAwayAndRecoveryStartsOver() throws Exception {
        healthSettings(true, new HealthCheckTarget("a", "https://a"));
        CountingChecker checker = new CountingChecker(List.of(probe("a", false)));
        HealthCheckCoordinator coordinator = coordinatorWith(checker);
        connectAndCheck(coordinator);
        assertThat(recovery.retryProperty().get())
                .as("precondition: a restart pending").isNotNull();

        power.sleep();
        power.wake();
        flushFxEvents();   // the wake reaches the FX thread
        flushFxEvents();   // and so does the probe's answer

        assertThat(checker.calls).as("probes: the connect's, then the wake's").hasValue(2);
        assertThat(healthState.get()).isEqualTo(TunnelHealth.BROKEN);
        assertThat(recovery.retryProperty().get())
                .as("the restart the check on the wake asks for")
                .isEqualTo(new TunnelRecoveryService.Retry(1, 3600));
    }

    /** Right after a wake the Wi-Fi is often still joining; the check waits for it. */
    @Test
    void theCheckOnTheWakeWaitsForTheNetwork() throws Exception {
        healthSettings(false, new HealthCheckTarget("a", "https://a"));
        CountingChecker checker = new CountingChecker(List.of(probe("a", true)));
        HealthCheckCoordinator coordinator = coordinatorWith(checker);
        connectAndCheck(coordinator);

        power.sleep();
        networkUp.set(false);
        power.wake();
        flushFxEvents();
        flushFxEvents();

        assertThat(checker.calls).as("probes with no network yet").hasValue(1);
        assertThat(summaryLabel.getText()).isEqualTo(I18n.get("dashboard.health.waiting.network"));
        assertThat(healthState.get())
                .as("the verdict from before the sleep, while the check waits")
                .isEqualTo(TunnelHealth.CHECKING);

        networkUp.set(true);
        Await.until("the check once the network is back", () -> checker.calls.get() == 2,
                Duration.ofSeconds(10));
        flushFxEvents();
        assertThat(healthState.get()).isEqualTo(TunnelHealth.HEALTHY);
    }

    /** A wake with the tunnel down has nothing to check. */
    @Test
    void theWakeChecksNothingWhileDisconnected() throws Exception {
        healthSettings(false, new HealthCheckTarget("a", "https://a"));
        CountingChecker checker = new CountingChecker(List.of(probe("a", true)));
        HealthCheckCoordinator coordinator = coordinatorWith(checker);
        connectAndCheck(coordinator);
        disconnect(coordinator);

        power.sleep();
        power.wake();
        flushFxEvents();
        flushFxEvents();

        assertThat(checker.calls).hasValue(1);
        assertThat(healthState.get()).isEqualTo(TunnelHealth.UNMONITORED);
    }

    /** Awake, a failed check is published and restarts the tunnel exactly as before. */
    @Test
    void awakeAFailedCheckIsPublishedAndRestartsAsBefore() throws Exception {
        healthSettings(true, new HealthCheckTarget("a", "https://a"));
        FakeChecker checker = new FakeChecker();
        checker.results = List.of(probe("a", false));

        connectAndCheck(coordinatorWith(checker));

        assertThat(healthState.get()).isEqualTo(TunnelHealth.BROKEN);
        assertThat(recovery.retryProperty().get())
                .isEqualTo(new TunnelRecoveryService.Retry(1, 3600));
        assertThat(banner.isVisible()).isTrue();
    }

    /**
     * Healthy verdicts in a row space the probes out; the first that is not
     * healthy, and a re-check by hand, bring them back to the configured
     * interval. At five seconds and two targets the probes made 24 tunnelled
     * TLS connections a minute for as long as the tunnel was up.
     */
    @Test
    void aTunnelThatKeepsAnsweringIsProbedLessOftenUntilAProbeFails() throws Exception {
        AppSettings settings = healthSettings(false,
                new HealthCheckTarget("a", "https://a"), new HealthCheckTarget("b", "https://b"));
        settings.setHealthCheckIntervalSeconds(5);
        FakeChecker checker = new FakeChecker();
        checker.results = List.of(probe("a", true), probe("b", true));
        HealthCheckCoordinator coordinator = coordinatorWith(checker);

        connectAndCheck(coordinator);
        List<Integer> waits = new ArrayList<>();
        onFxAndWait(() -> waits.add(coordinator.scheduledCheckSeconds()));
        for (int probe = 2; probe <= 12; probe++) {
            runTheScheduledCheck(coordinator);
            onFxAndWait(() -> waits.add(coordinator.scheduledCheckSeconds()));
        }
        assertThat(waits).as("the wait after each of twelve healthy verdicts")
                .containsExactly(5, 5, 10, 10, 10, 20, 20, 20, 40, 40, 40, 60);

        checker.results = List.of(probe("a", true), probe("b", false));
        runTheScheduledCheck(coordinator);
        assertWait(coordinator, 5);

        checker.results = List.of(probe("a", true), probe("b", true));
        for (int probe = 1; probe <= 6; probe++) {
            runTheScheduledCheck(coordinator);
        }
        assertWait(coordinator, 20);
        onFxAndWait(coordinator::recheck);
        flushFxEvents();
        assertWait(coordinator, 5);

        engine.state.set(ConnectionState.DISCONNECTED);
        onFxAndWait(() ->
                coordinator.onConnectionStateChanged(ConnectionState.DISCONNECTED));
        assertWait(coordinator, -1);
    }

    private static void runTheScheduledCheck(HealthCheckCoordinator coordinator)
            throws InterruptedException {
        onFxAndWait(coordinator::runScheduledCheckNow);
        flushFxEvents();   // drain the whenComplete -> runLater hop
    }

    private static void assertWait(HealthCheckCoordinator coordinator, int seconds)
            throws InterruptedException {
        AtomicInteger wait = new AtomicInteger();
        onFxAndWait(() -> wait.set(coordinator.scheduledCheckSeconds()));
        assertThat(wait.get()).isEqualTo(seconds);
    }
}
