package com.vlessclient.service;

import com.vlessclient.app.I18n;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.outbound.OutboundTags;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

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

    /**
     * How many refused servers one connect leaves out before it gives up. Each
     * one costs another {@code sing-box check}, and the core names only the
     * first refusal it meets, so a list that is broken throughout is reported
     * rather than worked through server by server.
     */
    private static final int MAX_SKIPPED = 20;

    /** How a connect attempt ended. */
    public enum Outcome {
        /** The core was launched. */
        STARTED,
        /** The running core selected a different server without restarting. */
        SWITCHED,
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
     * A server the core refused to build, left out of the configuration so the
     * others could still connect.
     *
     * @param id     the server's id
     * @param name   the server's name, as the list shows it
     * @param reason what the core said about it
     */
    public record SkippedServer(String id, String name, String reason) {
    }

    /**
     * The result of a connect attempt.
     *
     * @param outcome what happened
     * @param server  the server the core was pointed at, or null when the
     *                attempt never got that far
     * @param skipped the servers the core refused and the attempt went ahead
     *                without; empty when there were none
     */
    public record ConnectAttempt(Outcome outcome, ServerConfig server,
                                 List<SkippedServer> skipped) {

        /** Keeps the list immutable and never null. */
        public ConnectAttempt {
            skipped = skipped == null ? List.of() : List.copyOf(skipped);
        }

        /** An attempt that left no server out. */
        public ConnectAttempt(Outcome outcome, ServerConfig server) {
            this(outcome, server, List.of());
        }

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
    private final ChangeListener<ConnectionState> stateListener;
    private volatile ProxyMode requestedMode;
    private volatile LiveSelector liveSelector;
    private ProxyMode runningMode;

    /** What the running core was started without, for the UI; changed on the FX thread. */
    private final ReadOnlyObjectWrapper<List<SkippedServer>> skippedServers =
            new ReadOnlyObjectWrapper<>(List.of());

    /**
     * The ids behind {@link #skippedServers}, readable from any thread: a live
     * switch compares against the configuration the core actually loaded.
     */
    private volatile Set<String> skippedIds = Set.of();


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
        this.stateListener = (obs, old, state) -> {
            // A notice about what the core was started without ends with it.
            if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
                skippedServers.set(List.of());
            }
            recovery.onConnectionState(state);
        };
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
                this.engine.connectionStateProperty().removeListener(stateListener);
            }
            this.engine = engine;
            if (engine != null) {
                engine.connectionStateProperty().addListener(stateListener);
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

    /**
     * The servers the running core was started without because it refused
     * their settings. Empty when there are none or no core is running; changes
     * on the JavaFX thread.
     *
     * @return the refused servers of the current session
     */
    public ReadOnlyObjectProperty<List<SkippedServer>> skippedServersProperty() {
        return skippedServers.getReadOnlyProperty();
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
     * generator includes them in the manual or automatic group. Any
     * previous core is waited out first, because {@code start} refuses while one
     * is alive — that is what makes the reconnect paths (server switch, health
     * auto-reconnect) work.</p>
     *
     * <p>A server the core refuses to build is left out and the start is tried
     * again without it, since one broken entry in a subscription used to block
     * connecting to every other server. The refusal of the active server still
     * fails the connect: connecting through a server the user did not pick is
     * not a fallback.</p>
     *
     * @param modeOverride proxy mode to use, or null to take it from settings
     * @return what happened, the server the core was pointed at, and the
     *     servers it was started without
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

        log.info("Connecting to server: {} ({})", active.getName(), mode);
        current.awaitStopped(STOP_WAIT);
        if (!allowed.getAsBoolean()) {
            return new ConnectAttempt(Outcome.CANCELLED, active);
        }
        List<ServerConfig> members = candidates;
        List<SkippedServer> skipped = new ArrayList<>();
        RoutingConfig routing = safeRoutingConfig();
        try {
            while (true) {
                LiveSelector prepared = new LiveSelector(
                        configGenerator.generate(members, active, settings, routing));
                LiveSelector previous = liveSelector;
                liveSelector = prepared;
                try {
                    current.start(prepared.config(), mode);
                    runningMode = mode;
                    break;
                } catch (ConfigRejectedException e) {
                    liveSelector = previous;
                    Refusal refusal = refusalOf(e, prepared.config(), members).orElse(null);
                    if (refusal == null) {
                        throw e;
                    }
                    ServerConfig refused = refusal.server();
                    if (refused.getId().equals(active.getId()) || skipped.size() >= MAX_SKIPPED) {
                        throw refusal.named(e);
                    }
                    log.warn("sing-box refused server '{}' ({}); connecting without it",
                            refused.getName(), refusal.detail());
                    skipped.add(new SkippedServer(
                            refused.getId(), refused.getName(), refusal.detail()));
                    members = members.stream()
                            .filter(server -> !server.getId().equals(refused.getId()))
                            .toList();
                    if (!allowed.getAsBoolean()) {
                        return new ConnectAttempt(Outcome.CANCELLED, active);
                    }
                } catch (IOException | IllegalStateException e) {
                    liveSelector = previous;
                    throw e;
                }
            }
        } catch (IllegalStateException e) {
            log.warn("sing-box already running: {}", e.getMessage());
            return new ConnectAttempt(Outcome.ALREADY_RUNNING, active);
        }
        publishSkipped(skipped);
        return new ConnectAttempt(Outcome.STARTED, active, skipped);
    }

    /**
     * Records what the core was just started without. The ids are set at once
     * for the live switch; the list reaches the UI through the FX queue, after
     * the not-started state each refused attempt queued, so that state cannot
     * clear it again.
     */
    private void publishSkipped(List<SkippedServer> skipped) {
        List<SkippedServer> snapshot = List.copyOf(skipped);
        skippedIds = snapshot.stream()
                .map(SkippedServer::id)
                .collect(Collectors.toUnmodifiableSet());
        try {
            Platform.runLater(() -> skippedServers.set(snapshot));
        } catch (IllegalStateException toolkitNotRunning) {
            skippedServers.set(snapshot);
        }
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
        liveSelector = null;
        skippedIds = Set.of();
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

    /** The group tag used by the current process, for live status queries. */
    public String getProxyGroupTag() {
        LiveSelector current = liveSelector;
        return current != null ? current.groupTag() : OutboundTags.PROXY;
    }

    /**
     * Applies a manual server selection through the core API when the loaded
     * configuration still matches; otherwise restarts with the latest config.
     *
     * @return the selected server and whether it was switched or restarted
     * @throws IOException if the fallback start fails
     */
    public ConnectAttempt switchToActiveServer() throws IOException {
        requireOffFxThread("switchToActiveServer");
        long request = recovery.connectionRequested();
        synchronized (operations) {
            if (!recovery.isWanted(request)) {
                return new ConnectAttempt(Outcome.CANCELLED, null);
            }
            List<ServerConfig> candidates = FxExecutor.get(
                    () -> List.copyOf(configStore.getServers()));
            ServerConfig active = candidates.stream().filter(ServerConfig::isActive)
                    .findFirst().orElse(null);
            if (active == null) {
                return new ConnectAttempt(Outcome.NO_ACTIVE_SERVER, null);
            }
            AppSettings settings = configStore.getSettings();
            ProxyMode mode = requestedMode != null ? requestedMode : settings.getProxyMode();
            // The loaded configuration left out the servers the core refused, so
            // compare against the same set. Picking one of those does not match
            // and restarts, where its refusal is reported by name.
            Set<String> skippedNow = skippedIds;
            List<ServerConfig> members = skippedNow.contains(active.getId())
                    ? candidates
                    : candidates.stream()
                            .filter(server -> !skippedNow.contains(server.getId()))
                            .toList();
            String generated = configGenerator.generate(members, active,
                    settings, safeRoutingConfig());
            LiveSelector selector = liveSelector;
            if (isRunning() && runningMode == mode && selector != null
                    && selector.accepts(generated)
                    && selector.select(OutboundTags.server(active))) {
                if (!recovery.isWanted(request)) {
                    return new ConnectAttempt(Outcome.CANCELLED, active);
                }
                return new ConnectAttempt(Outcome.SWITCHED, active);
            }
            if (!recovery.isWanted(request)) {
                return new ConnectAttempt(Outcome.CANCELLED, active);
            }
            stopCurrent();
            return connectInternal(requestedMode, () -> recovery.isWanted(request));
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
     * A group member as the core quotes it: "initialize outbound[3]: ",
     * "outbounds[3].", and the same for a WireGuard endpoint.
     */
    private static final Pattern MEMBER_POSITION =
            Pattern.compile("(?:initialize )?(outbound|endpoint)s?\\[(\\d+)\\](?::\\s*|\\.)");

    /**
     * A refusal traced back to the server behind it.
     *
     * @param server the server whose outbound or endpoint the core quoted
     * @param detail the core's reason without the position, e.g.
     *               {@code unsupported flow: xtls-rprx-direct}
     */
    private record Refusal(ServerConfig server, String detail) {

        /** The refusal as a message naming the server. */
        ConfigRejectedException named(ConfigRejectedException rejected) {
            return new ConfigRejectedException(
                    I18n.get("engine.config.rejected.server", server.getName(), detail),
                    rejected.reason());
        }
    }

    /**
     * Finds the server behind the member a refusal quotes. The core counts
     * outbounds and endpoints by position, which tells nobody which of forty
     * subscription servers is broken.
     *
     * @param rejected   the refusal as the engine reported it
     * @param configJson the configuration the core refused
     * @param servers    the servers that configuration was built from
     * @return the server and the core's reason, or empty when the refusal
     *     quotes no member or one that belongs to no server
     */
    private static Optional<Refusal> refusalOf(ConfigRejectedException rejected,
                                               String configJson,
                                               List<ServerConfig> servers) {
        Matcher position = MEMBER_POSITION.matcher(rejected.reason());
        if (!position.find()) {
            return Optional.empty();
        }
        String tag = memberTag(configJson, position.group(1) + "s", position.group(2));
        String detail = rejected.reason().substring(position.end());
        return servers.stream()
                .filter(server -> OutboundTags.server(server).equals(tag))
                .findFirst()
                .map(server -> new Refusal(server, detail));
    }

    /** The tag of the member at {@code index} in {@code section}, or "" when there is none. */
    private static String memberTag(String configJson, String section, String index) {
        try {
            return JsonMapper.builder().build().readTree(configJson)
                    .path(section).path(Integer.parseInt(index))
                    .path("tag").asString("");
        } catch (JacksonException | NumberFormatException e) {
            return "";
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
