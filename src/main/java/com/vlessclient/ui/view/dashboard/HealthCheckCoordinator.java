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
import com.vlessclient.service.TunnelHealthState;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the Dashboard's service-availability loop: probes the configured
 * targets through the tunnel after connect and on a periodic interval,
 * renders the per-service rows and summary line, edits the target list, and
 * drives the cancelable auto-reconnect countdown when every service is
 * unreachable. Extracted from
 * {@link com.vlessclient.ui.view.DashboardViewController}, which stays the
 * FXML endpoint and hands its injected controls over via {@link Controls}.
 *
 * <p>All state is mutated only on the FX thread, matching the original
 * controller semantics.</p>
 *
 * <p>Every verdict this loop reaches is also published to
 * {@link TunnelHealthState}, because the health card is not the only thing
 * that must not claim a dead tunnel is fine: the menu-bar icon reads the same
 * signal, and it has no access to this view.</p>
 */
public final class HealthCheckCoordinator {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckCoordinator.class);

    /**
     * The FXML-injected controls the health card drives. They remain owned
     * (and declared) by the Dashboard controller; this record just carries
     * them.
     */
    public record Controls(
            VBox healthCard,
            Label healthSummaryLabel,
            VBox serviceStatusList,
            HBox reconnectBanner,
            Label reconnectBannerLabel) {
    }

    private final VBox healthCard;
    private final Label healthSummaryLabel;
    private final VBox serviceStatusList;
    private final HBox reconnectBanner;
    private final Label reconnectBannerLabel;

    private final ServiceReachabilityChecker reachabilityChecker;
    private final TunnelHealthState healthState;
    private final Supplier<SingBoxEngine> engineSupplier;
    private final Runnable connectAction;
    private final Runnable disconnectAction;
    private final Runnable reconnectAction;
    private final Supplier<AppSettings> settingsSupplier;
    private final Consumer<AppSettings> settingsSaver;

    // Health-check / auto-reconnect state. All mutated only on the FX thread.
    private PauseTransition reconnectDelay;
    // Schedules the next periodic reachability probe while the tunnel is up.
    private PauseTransition periodicCheckDelay;
    private final AtomicBoolean healthCheckInFlight = new AtomicBoolean();
    private final AtomicInteger healthGeneration = new AtomicInteger();
    private int reconnectAttempt;
    // True while we are tearing down and restarting the tunnel ourselves, so
    // the self-inflicted DISCONNECTED/ERROR transition is not mistaken for a
    // user disconnect that should cancel the health loop.
    private boolean suppressDisconnectHandling;
    // Whether this connection has produced a verdict yet. Gates the CHECKING
    // signal: with a 5s default interval, publishing it on every periodic
    // re-probe would flicker the tray icon and the hero card twelve times a
    // minute. Only the unproven window after a connect is worth showing.
    private final AtomicBoolean hasVerdict = new AtomicBoolean();

    /**
     * Creates the coordinator over the given controls. The engine is read
     * through {@code engineSupplier} on every use because the controller may
     * swap in a new {@link SingBoxEngine} after an in-app install; the
     * connect/disconnect actions are the controller's own, so a reconnect
     * behaves exactly like the user pressing the buttons. {@code healthState}
     * may be null, in which case verdicts are rendered here and nowhere else.
     */
    public HealthCheckCoordinator(
            Controls controls,
            ServiceReachabilityChecker reachabilityChecker,
            TunnelHealthState healthState,
            Supplier<SingBoxEngine> engineSupplier,
            Runnable connectAction,
            Runnable disconnectAction,
            Runnable reconnectAction) {
        this(controls, reachabilityChecker, healthState, engineSupplier, connectAction,
                disconnectAction, reconnectAction,
                () -> ServiceLocator.find(AppSettings.class).orElse(null),
                settings -> ServiceLocator.find(ConfigStore.class).ifPresentOrElse(
                        store -> store.saveSettings(settings),
                        () -> log.warn("ConfigStore not available; "
                                + "health-target change not persisted")));
    }

    /**
     * Creates the coordinator with explicit settings access, so a test can
     * hand it a settings instance and observe the saves without touching the
     * global locator; everything else was already injected.
     *
     * @param settingsSupplier the current settings, or null when unavailable
     * @param settingsSaver    persists an edited settings instance
     */
    public HealthCheckCoordinator(
            Controls controls,
            ServiceReachabilityChecker reachabilityChecker,
            TunnelHealthState healthState,
            Supplier<SingBoxEngine> engineSupplier,
            Runnable connectAction,
            Runnable disconnectAction,
            Runnable reconnectAction,
            Supplier<AppSettings> settingsSupplier,
            Consumer<AppSettings> settingsSaver) {
        this.settingsSupplier = settingsSupplier;
        this.settingsSaver = settingsSaver;
        this.healthCard = controls.healthCard();
        this.healthSummaryLabel = controls.healthSummaryLabel();
        this.serviceStatusList = controls.serviceStatusList();
        this.reconnectBanner = controls.reconnectBanner();
        this.reconnectBannerLabel = controls.reconnectBannerLabel();
        this.reachabilityChecker = reachabilityChecker;
        this.healthState = healthState;
        this.engineSupplier = engineSupplier;
        this.connectAction = connectAction;
        this.disconnectAction = disconnectAction;
        this.reconnectAction = reconnectAction;
    }

    private SingBoxEngine engine() {
        return engineSupplier.get();
    }

    /**
     * Reacts to connection-state changes for the health-check feature.
     * On CONNECTED we verify the tunnel actually carries traffic; on a
     * user-initiated DISCONNECTED/ERROR we tear the health loop down. A
     * self-inflicted transition during our own auto-reconnect restart is
     * ignored via {@link #suppressDisconnectHandling}.
     */
    public void onConnectionStateChanged(ConnectionState state) {
        if (healthCard == null) {
            return;
        }
        if (state == ConnectionState.CONNECTED) {
            runReachabilityCheck();
            return;
        }
        // Whatever the last verdict was, it described a tunnel that is no
        // longer up — including during our own auto-reconnect restart, where
        // the loop below is deliberately left standing. Drop it either way, so
        // the next connect is judged on its own probe rather than inheriting
        // the previous one's answer.
        hasVerdict.set(false);
        publishHealth(TunnelHealth.UNMONITORED);
        if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
            if (suppressDisconnectHandling) {
                return;
            }
            cancelHealthLoop();
        }
    }

    /**
     * Manual re-check, wired to the card's "Re-check" button. Supersedes any
     * pending auto-reconnect countdown; no-op while not connected.
     */
    public void recheck() {
        if (engine() == null
                || engine().connectionStateProperty().get() != ConnectionState.CONNECTED) {
            return;
        }
        // A manual re-check supersedes any pending auto-reconnect countdown.
        if (reconnectDelay != null) {
            reconnectDelay.stop();
            reconnectDelay = null;
        }
        runReachabilityCheck();
    }

    /**
     * Stops a pending auto-reconnect countdown and hides the banner, wired to
     * the banner's "Cancel" button.
     */
    public void cancelReconnectCountdown() {
        if (reconnectDelay != null) {
            reconnectDelay.stop();
            reconnectDelay = null;
        }
        reconnectAttempt = 0;
        hideReconnectBanner();
    }

    /**
     * Probes the configured services through the local proxy and renders the
     * results. Skips silently when the feature is disabled or prerequisites
     * are missing. Stale results (a newer check started, or the loop was
     * cancelled) are dropped via a generation token.
     */
    private void runReachabilityCheck() {
        // A check is starting now, so drop any pending periodic re-check; a new
        // one is scheduled once this probe completes.
        cancelPeriodicCheck();
        if (reachabilityChecker == null || engine() == null) {
            setHealthCardVisible(false);
            publishHealth(TunnelHealth.UNMONITORED);
            return;
        }
        AppSettings settings = settingsSupplier.get();
        if (settings == null) {
            return;
        }
        if (!settings.isHealthCheckEnabled()) {
            setHealthCardVisible(false);
            publishHealth(TunnelHealth.UNMONITORED);
            return;
        }
        List<HealthCheckTarget> targets = settings.getHealthCheckTargets();
        if (targets == null || targets.isEmpty()) {
            // Keep the card (and its "+" button) visible: hiding it would
            // leave no way to add a target back after removing the last one.
            setHealthCardVisible(true);
            if (serviceStatusList != null) {
                serviceStatusList.getChildren().clear();
            }
            healthSummaryLabel.setText(I18n.get("health.no.targets"));
            publishHealth(TunnelHealth.UNMONITORED);
            return;
        }
        if (!healthCheckInFlight.compareAndSet(false, true)) {
            return;
        }

        setHealthCardVisible(true);
        renderPendingRows(targets);
        healthSummaryLabel.setText(I18n.get("dashboard.health.checking"));
        if (!hasVerdict.get()) {
            publishHealth(TunnelHealth.CHECKING);
        }

        final int gen = healthGeneration.incrementAndGet();
        final int httpPort = settings.getHttpPort();

        reachabilityChecker.checkAll(targets, httpPort).whenComplete((results, err) ->
                Platform.runLater(() -> {
                    if (gen != healthGeneration.get()) {
                        return;   // superseded by a newer check or cancelled
                    }
                    healthCheckInFlight.set(false);
                    if (engine().connectionStateProperty().get()
                            != ConnectionState.CONNECTED) {
                        return;   // no longer connected
                    }
                    if (err != null) {
                        log.warn("Reachability check failed", err);
                        healthSummaryLabel.setText(I18n.get("dashboard.health.failed"));
                        // A failed batch is not a healthy tunnel and not a
                        // broken one — say so rather than leaving the last
                        // answer standing.
                        hasVerdict.set(true);
                        publishHealth(TunnelHealth.UNKNOWN);
                        return;
                    }
                    renderResultRows(results);
                    publishVerdict(results);
                    evaluateReconnect(results, settings);
                }));
    }

    /**
     * Turns a completed probe batch into the verdict the rest of the app sees.
     * Partial reachability counts as {@link TunnelHealth#DEGRADED} rather than
     * healthy: some services answering does not mean the route the user cares
     * about is working, and the indicators say "unproven" in amber instead of
     * claiming success.
     */
    private void publishVerdict(List<ServiceReachabilityChecker.ProbeResult> results) {
        if (results == null || results.isEmpty()) {
            publishHealth(TunnelHealth.UNMONITORED);
            return;
        }
        hasVerdict.set(true);
        long reachable = results.stream()
                .filter(ServiceReachabilityChecker.ProbeResult::reachable)
                .count();
        if (reachable == results.size()) {
            publishHealth(TunnelHealth.HEALTHY);
        } else if (reachable == 0) {
            publishHealth(TunnelHealth.BROKEN);
        } else {
            publishHealth(TunnelHealth.DEGRADED);
        }
    }

    private void publishHealth(TunnelHealth health) {
        if (healthState != null) {
            healthState.set(health);
        }
    }

    private void evaluateReconnect(List<ServiceReachabilityChecker.ProbeResult> results,
                                   AppSettings settings) {
        boolean broken = settings.isHealthCheckEnabled()
                && settings.isHealthCheckAutoReconnect()
                && ServiceReachabilityChecker.allUnreachable(results);
        if (broken) {
            scheduleReconnect(settings);
        } else {
            reconnectAttempt = 0;
            hideReconnectBanner();
            // Keep monitoring: re-probe after the configured interval. When the
            // reconnect path runs instead, the restart itself drives the next
            // check, so we don't double-schedule here.
            schedulePeriodicCheck(settings);
        }
    }

    /**
     * Schedules the next reachability probe {@code health_check_interval_seconds}
     * from now, so the tunnel keeps being monitored while connected rather than
     * only at connect time. No-op when the feature is disabled or the tunnel is
     * no longer up.
     */
    private void schedulePeriodicCheck(AppSettings settings) {
        cancelPeriodicCheck();
        if (!settings.isHealthCheckEnabled()) {
            return;
        }
        if (engine() == null
                || engine().connectionStateProperty().get() != ConnectionState.CONNECTED) {
            return;
        }
        int seconds = Math.max(1, settings.getHealthCheckIntervalSeconds());
        periodicCheckDelay = new PauseTransition(Duration.seconds(seconds));
        periodicCheckDelay.setOnFinished(e -> {
            periodicCheckDelay = null;
            runReachabilityCheck();
        });
        periodicCheckDelay.play();
    }

    private void cancelPeriodicCheck() {
        if (periodicCheckDelay != null) {
            periodicCheckDelay.stop();
            periodicCheckDelay = null;
        }
    }

    /**
     * Starts the (cancelable) countdown to the next reconnect. No-op if a
     * countdown is already running. The loop is unbounded by design — it
     * repeats every {@code health_check_delay_seconds} until a service becomes
     * reachable or the user disconnects/cancels.
     */
    private void scheduleReconnect(AppSettings settings) {
        if (reconnectDelay != null) {
            return;
        }
        int seconds = Math.max(1, settings.getHealthCheckDelaySeconds());
        reconnectAttempt++;
        showReconnectBanner(I18n.get("dashboard.reconnect.banner",
                String.valueOf(seconds), String.valueOf(reconnectAttempt)));
        reconnectDelay = new PauseTransition(Duration.seconds(seconds));
        reconnectDelay.setOnFinished(e -> performReconnect());
        reconnectDelay.play();
    }

    /**
     * Restarts the tunnel through {@code ConnectionService.reconnect}, which
     * waits for the old core to exit before starting the new one. The
     * DISCONNECTED that our own stop produces is suppressed for the whole
     * restart so it is not mistaken for a user disconnect. The subsequent
     * CONNECTED drives the next reachability check, continuing the loop.
     */
    private void performReconnect() {
        reconnectDelay = null;
        if (engine() == null
                || engine().connectionStateProperty().get() != ConnectionState.CONNECTED) {
            return;   // user disconnected or state changed during the wait
        }
        log.info("Auto-reconnect (attempt {}): all services unreachable, restarting tunnel",
                reconnectAttempt);
        hideReconnectBanner();
        suppressDisconnectHandling = true;
        // One restart, not disconnect-then-hope. The old 700 ms gap was shorter
        // than a stop can take — SingBoxEngine waits out a SIGTERM grace period
        // and can force-kill after it — so the suppress flag was already back
        // to false when DISCONNECTED finally arrived. onConnectionStateChanged
        // then treated the app's own restart as a user disconnect: attempt
        // counter reset, card hidden, health published as UNMONITORED, in the
        // middle of a reconnect this class had started. ConnectionService
        // .reconnect waits for the old core itself, and its javadoc names this
        // caller as the reason it exists.
        Thread.startVirtualThread(() -> {
            try {
                reconnectAction.run();
            } finally {
                // Held for the whole restart, and cleared on the FX thread
                // because that is where every other read of it happens.
                Platform.runLater(() -> suppressDisconnectHandling = false);
            }
        });
    }

    /**
     * Stops the health loop entirely: cancels any pending reconnect, drops any
     * in-flight probe, and hides the card. Invoked on a genuine (user or crash)
     * disconnect.
     */
    private void cancelHealthLoop() {
        if (reconnectDelay != null) {
            reconnectDelay.stop();
            reconnectDelay = null;
        }
        cancelPeriodicCheck();
        reconnectAttempt = 0;
        healthGeneration.incrementAndGet(); // invalidate any in-flight probe result
        healthCheckInFlight.set(false);
        hasVerdict.set(false);
        publishHealth(TunnelHealth.UNMONITORED);
        hideReconnectBanner();
        if (serviceStatusList != null) {
            serviceStatusList.getChildren().clear();
        }
        if (healthSummaryLabel != null) {
            healthSummaryLabel.setText("—");
        }
        setHealthCardVisible(false);
    }

    private void renderPendingRows(List<HealthCheckTarget> targets) {
        if (serviceStatusList == null) {
            return;
        }
        serviceStatusList.getChildren().clear();
        for (HealthCheckTarget t : targets) {
            String name = t.getName() != null && !t.getName().isBlank() ? t.getName() : t.getUrl();
            serviceStatusList.getChildren().add(buildServiceRow(name, t.getUrl(),
                    "status-circle-connecting", I18n.get("dashboard.health.checking"),
                    "service-pending"));
        }
    }

    private void renderResultRows(List<ServiceReachabilityChecker.ProbeResult> results) {
        if (serviceStatusList == null) {
            return;
        }
        serviceStatusList.getChildren().clear();
        int reachable = 0;
        for (ServiceReachabilityChecker.ProbeResult r : results) {
            boolean ok = r.reachable();
            if (ok) {
                reachable++;
            }
            String dotClass = ok ? "status-circle-connected" : "status-circle-error";
            String resultText =
                    ok ? r.latencyMs() + " ms" : I18n.get("dashboard.health.unreachable");
            String resultClass = ok ? "service-ok" : "service-fail";
            serviceStatusList.getChildren().add(
                    buildServiceRow(r.name(), r.url(), dotClass, resultText, resultClass));
        }
        healthSummaryLabel.setText(summarize(reachable, results.size()));
    }

    private static String summarize(int reachable, int total) {
        if (reachable == total) {
            return I18n.get("dashboard.health.all.reachable");
        }
        if (reachable == 0) {
            return I18n.get("dashboard.health.all.unreachable");
        }
        return I18n.get("dashboard.health.some.reachable", reachable, total);
    }

    private HBox buildServiceRow(String name, String url, String dotStyleClass,
                                 String resultText, String resultStyleClass) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);

        Circle dot = new Circle(5);
        dot.getStyleClass().setAll(dotStyleClass);

        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().setAll("service-name");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label resultLabel = new Label(resultText);
        resultLabel.getStyleClass().setAll(resultStyleClass);

        row.getChildren().addAll(dot, nameLabel, spacer, resultLabel);

        if (url != null && !url.isBlank()) {
            Button remove = new Button("✕");
            remove.getStyleClass().setAll("icon-button", "destructive");
            remove.setFocusTraversable(false);
            remove.setTooltip(new Tooltip(I18n.get("health.target.remove")));
            remove.setOnAction(e -> removeHealthTarget(url));
            row.getChildren().add(remove);
        }
        return row;
    }

    // ===== health-target list editing =====

    /**
     * Adds a target to the persisted list (silently ignoring an exact-URL
     * duplicate) and re-probes immediately.
     */
    public void addTarget(HealthCheckTarget target) {
        AppSettings settings = findSettings();
        if (settings == null) {
            return;
        }
        // Silently ignore an exact-URL duplicate — probing the same URL
        // twice adds noise, not information.
        boolean duplicate = settings.getHealthCheckTargets().stream()
                .anyMatch(t -> target.getUrl().equals(t.getUrl()));
        if (!duplicate) {
            settings.getHealthCheckTargets().add(target);
            saveSettingsQuietly(settings);
        }
        restartReachabilityCheck();
    }

    private void removeHealthTarget(String url) {
        AppSettings settings = findSettings();
        if (settings == null) {
            return;
        }
        settings.getHealthCheckTargets().removeIf(t -> url.equals(t.getUrl()));
        saveSettingsQuietly(settings);
        restartReachabilityCheck();
    }

    private AppSettings findSettings() {
        AppSettings settings = settingsSupplier.get();
        if (settings == null) {
            log.warn("AppSettings not available for health-target editing");
        }
        return settings;
    }

    private void saveSettingsQuietly(AppSettings settings) {
        settingsSaver.accept(settings);
    }

    /** Re-probes with the edited list, superseding any in-flight check. */
    private void restartReachabilityCheck() {
        if (engine() == null
                || engine().connectionStateProperty().get() != ConnectionState.CONNECTED) {
            return;
        }
        healthGeneration.incrementAndGet();
        healthCheckInFlight.set(false);
        runReachabilityCheck();
    }

    private void showReconnectBanner(String text) {
        if (reconnectBanner == null) {
            return;
        }
        if (reconnectBannerLabel != null) {
            reconnectBannerLabel.setText(text);
        }
        reconnectBanner.setVisible(true);
        reconnectBanner.setManaged(true);
    }

    private void hideReconnectBanner() {
        if (reconnectBanner == null) {
            return;
        }
        reconnectBanner.setVisible(false);
        reconnectBanner.setManaged(false);
    }

    private void setHealthCardVisible(boolean visible) {
        if (healthCard == null) {
            return;
        }
        healthCard.setVisible(visible);
        healthCard.setManaged(visible);
    }
}
