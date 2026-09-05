package com.vlessclient.service;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.ServerConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the tunnel lifecycle: resolve the active server and its candidates,
 * build the core configuration, and start or stop {@link SingBoxEngine}.
 *
 * <p>This flow used to exist in three independent copies — the dashboard
 * controller, the tray menu and the MCP control facade — and they had drifted
 * apart in user-visible ways: the tray ran the whole thing on the JavaFX
 * thread (freezing the window for as long as a TUN connect took), and the MCP
 * path built its config from the active server alone, silently collapsing
 * automatic selection to a one-member group. Keeping the flow here means a fix
 * or a new step lands once for every caller.</p>
 *
 * <h2>Threading</h2>
 *
 * <p><strong>Call {@link #connect}, {@link #disconnect} and {@link #reconnect}
 * off the JavaFX thread.</strong> They wait out a stop (a SIGTERM grace period
 * and possibly a force-kill) and a start (which hashes the ~40 MB binary and
 * can raise a modal admin prompt for TUN). {@code connect} enforces this
 * rather than documenting it: the freeze it prevents is invisible in review but
 * obvious to a user, so it must fail loudly in a test instead. Reads of the
 * FX-owned server list are marshalled internally through {@link FxExecutor}.</p>
 */
public class ConnectionService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);

    /**
     * How long a connect waits for a previous core to exit before giving up and
     * letting the engine reject the start. Generous: a stop is bounded by the
     * engine's own SIGTERM grace plus force-kill.
     */
    private static final Duration STOP_WAIT = Duration.ofSeconds(15);

    /** How a connect attempt ended. */
    public enum Outcome {
        /** The core was launched. */
        STARTED,
        /** No sing-box binary is available yet, so there is nothing to launch. */
        NO_ENGINE,
        /** Nothing is selected to connect to. */
        NO_ACTIVE_SERVER,
        /** A core was already running, so this attempt was refused. */
        ALREADY_RUNNING,
        /** A newer user request superseded this connection attempt. */
        CANCELLED
    }

    /**
     * The result of a connect attempt.
     *
     * @param outcome what happened
     * @param server  the server the core was pointed at, or null when the
     *                attempt never got that far
     */
    public record ConnectAttempt(Outcome outcome, ServerConfig server) {

        /** Whether the core was actually launched. */
        public boolean started() {
            return outcome == Outcome.STARTED;
        }
    }

    private final ConfigStore configStore;
    private final SingBoxConfigGenerator configGenerator;
    private final RoutingService routingService;

    /**
     * Null until the binary is installed: the app starts without one and the
     * installer registers an engine afterwards, so this is replaced at runtime
     * (see {@link #setEngine}) and read as a single volatile snapshot per call.
     */
    private volatile SingBoxEngine engine;
    private final Object operations = new Object();
    private final TunnelRecoveryService recovery;
    private final ChangeListener<ConnectionState> recoveryListener;
    private volatile ProxyMode requestedMode;


    /**
     * Creates the service.
     *
     * @param configStore     source of the server list and the live settings
     * @param configGenerator builds the core configuration
     * @param routingService  supplies routing rules, or null for defaults
     * @param engine          the engine to drive, or null until one exists
     */
    public ConnectionService(ConfigStore configStore,
                             SingBoxConfigGenerator configGenerator,
                             RoutingService routingService,
                             SingBoxEngine engine) {
        this.configStore = configStore;
        this.configGenerator = configGenerator;
        this.routingService = routingService;
        this.recovery = new TunnelRecoveryService(
                () -> configStore != null ? configStore.getSettings() : new AppSettings(),
                this::recover);
        this.recoveryListener = (obs, old, state) -> recovery.onConnectionState(state);
        bindEngine(engine);
    }

    /**
     * Points the service at a new engine — used when the sing-box binary is
     * downloaded after startup and a fresh engine is registered.
     *
     * @param engine the engine to drive from now on
     */
    public void setEngine(SingBoxEngine engine) {
        bindEngine(engine);
    }

    private void bindEngine(SingBoxEngine engine) {
        FxExecutor.run(() -> {
            if (this.engine != null) {
                this.engine.connectionStateProperty().removeListener(recoveryListener);
            }
            this.engine = engine;
            if (engine != null) {
                engine.connectionStateProperty().addListener(recoveryListener);
            }
        });
    }

    /** The application-owned recovery loop, shared with health reporting and the UI. */
    public TunnelRecoveryService getRecoveryService() {
        return recovery;
    }

    /** The engine currently driven, or null when no binary is available. */
    public SingBoxEngine getEngine() {
        return engine;
    }

    /** Whether a core is running right now. Safe from any thread. */
    public boolean isRunning() {
        SingBoxEngine current = engine;
        return current != null && current.isRunning();
    }

    /**
     * Connects using the proxy mode from the current settings.
     *
     * @return what happened, and the server the core was pointed at
     * @throws IOException if the core could not be started
     */
    public ConnectAttempt connect() throws IOException {
        return connect(null);
    }

    /**
     * Connects to the active server.
     *
     * <p>Every configured server is passed to the generator as a candidate; the
     * generator uses them only when the selection mode is automatic. Any
     * previous core is waited out first, because {@code start} refuses while one
     * is alive — that is what makes the reconnect paths (server switch, health
     * auto-reconnect) work.</p>
     *
     * @param modeOverride proxy mode to use, or null to take it from settings
     * @return what happened, and the server the core was pointed at
     * @throws IOException           if the core could not be started
     * @throws IllegalStateException if called on the JavaFX thread
     */
    public ConnectAttempt connect(ProxyMode modeOverride) throws IOException {
        requireOffFxThread("connect");
        requestedMode = modeOverride;
        long request = recovery.connectionRequested();
        synchronized (operations) {
            return connectInternal(modeOverride, () -> recovery.isWanted(request));
        }
    }

    private ConnectAttempt connectInternal(ProxyMode modeOverride, BooleanSupplier allowed)
            throws IOException {
        SingBoxEngine current = engine;
        if (current == null) {
            return new ConnectAttempt(Outcome.NO_ENGINE, null);
        }

        // One marshalled read of the FX-owned list: the candidates and the
        // active server must come from the same view, or the config could name
        // a server that is no longer in the group it was built from.
        List<ServerConfig> candidates =
                FxExecutor.get(() -> List.copyOf(configStore.getServers()));
        ServerConfig active = candidates.stream()
                .filter(ServerConfig::isActive)
                .findFirst()
                .orElse(null);
        if (active == null) {
            return new ConnectAttempt(Outcome.NO_ACTIVE_SERVER, null);
        }

        AppSettings settings = configStore.getSettings();
        ProxyMode mode = modeOverride != null ? modeOverride : settings.getProxyMode();
        final String configJson =
                configGenerator.generate(candidates, active, settings, safeRoutingConfig());

        log.info("Connecting to server: {} ({})", active.getName(), mode);
        current.awaitStopped(STOP_WAIT);
        if (!allowed.getAsBoolean()) {
            return new ConnectAttempt(Outcome.CANCELLED, active);
        }
        try {
            current.start(configJson, mode);
        } catch (IllegalStateException e) {
            log.warn("sing-box already running: {}", e.getMessage());
            return new ConnectAttempt(Outcome.ALREADY_RUNNING, active);
        }
        return new ConnectAttempt(Outcome.STARTED, active);
    }

    /**
     * Stops the running core. No-op when nothing is running.
     *
     * @throws IllegalStateException if called on the JavaFX thread
     */
    public void disconnect() {
        requireOffFxThread("disconnect");
        recovery.cancel();
        synchronized (operations) {
            stopCurrent();
        }
    }

    private void stopCurrent() {
        SingBoxEngine current = engine;
        if (current != null) {
            log.info("Disconnecting");
            current.stop();
        }
    }

    /**
     * Stops the running core and connects again — the server-switch restart and
     * the health-check auto-reconnect. No timed gap is needed: the connect waits
     * for the stop to finish, which is exact rather than a guess.
     *
     * @param modeOverride proxy mode to use, or null to take it from settings
     * @return what happened, and the server the core was pointed at
     * @throws IOException           if the core could not be restarted
     * @throws IllegalStateException if called on the JavaFX thread
     */
    public ConnectAttempt reconnect(ProxyMode modeOverride) throws IOException {
        requireOffFxThread("reconnect");
        requestedMode = modeOverride;
        long request = recovery.connectionRequested();
        synchronized (operations) {
            stopCurrent();
            return connectInternal(modeOverride, () -> recovery.isWanted(request));
        }
    }

    private boolean recover(BooleanSupplier allowed) throws IOException {
        synchronized (operations) {
            if (!allowed.getAsBoolean()) {
                return false;
            }
            stopCurrent();
            return connectInternal(requestedMode, allowed).started();
        }
    }

    /**
     * Routing rules, or null when they cannot be read. Routing is an
     * enhancement, not a precondition: all three former copies of this flow
     * fell back to the default route rather than refusing to connect, and a
     * connect that fails because a rule file is unreadable would be worse.
     */
    private RoutingConfig safeRoutingConfig() {
        if (routingService == null) {
            log.debug("RoutingService not available; using default route");
            return null;
        }
        try {
            return routingService.getConfig();
        } catch (RuntimeException e) {
            log.warn("Could not read routing rules; using default route", e);
            return null;
        }
    }

    /**
     * Guards the threading contract. Blocking here would freeze the window for
     * the whole connect — the exact bug the tray path shipped with.
     */
    private static void requireOffFxThread(String action) {
        if (Platform.isFxApplicationThread()) {
            throw new IllegalStateException(
                    "ConnectionService." + action + " must not run on the JavaFX thread: "
                            + "it blocks for the core's start/stop and would freeze the UI");
        }
    }
}
