package com.vlessclient.service;

import com.vlessclient.app.I18n;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.ServerSelection;
import com.vlessclient.service.outbound.CoreSettings;
import com.vlessclient.service.outbound.OutboundTags;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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

    /** A loopback connect answers at once either way; this only bounds a firewall's silence. */
    private static final int PORT_PROBE_TIMEOUT_MS = 250;

    /**
     * How many servers the core refuses before one connect gives up. Each one
     * costs another {@code sing-box check}, and the core names only the first
     * refusal it meets, so a list that is broken throughout is reported rather
     * than worked through server by server. Servers left out before the check,
     * which cost nothing, do not count.
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
    /** What the running core was started from, or null when none was. */
    private volatile Run run;
    /** The ports the last core listened on, which a restart may find in its TIME_WAIT. */
    private volatile Set<Integer> lastCorePorts = Set.of();
    /** The server the running core was last pointed at, by a start or a live switch. */
    private volatile String appliedServerId;

    /** What the running core was started without, for the UI; changed on the FX thread. */
    private final ReadOnlyObjectWrapper<List<SkippedServer>> skippedServers =
            new ReadOnlyObjectWrapper<>(List.of());

    /** Ports the running core moved off because another program held them; FX thread. */
    private final ReadOnlyObjectWrapper<List<MovedPort>> movedPorts =
            new ReadOnlyObjectWrapper<>(List.of());

    /**
     * What a core was started from: the configuration it loaded, the mode, the
     * ports the user chose, which its own may have moved off, and the ids of
     * the servers it was started without. Set as one, before the core starts:
     * it reports CONNECTED from its own thread, and a reader asking then got
     * the mode and the left-out servers of the start before.
     */
    private record Run(LiveSelector selector, ProxyMode mode, List<Integer> chosenPorts,
                       Set<String> leftOut, HostFacts host) {
    }


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
                this::recover, this::restartNeedsTheUser);
        this.stateListener = (obs, old, state) -> {
            // A notice about what the core was started without ends with it.
            if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
                skippedServers.set(List.of());
                movedPorts.set(List.of());
            }
            recovery.onConnectionState(state);
            if (state == ConnectionState.CONNECTED) {
                followActiveServer();
            }
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

    /**
     * Whether a tunnel that dropped waits for the user's reconnect, for the
     * Dashboard; see {@link TunnelRecoveryService#isReconnectNeeded()}.
     *
     * @return the observable offer
     */
    public javafx.beans.property.ReadOnlyBooleanProperty reconnectNeededProperty() {
        return recovery.reconnectNeededProperty();
    }

    /**
     * Whether recovering the tunnel would raise an elevation prompt: the restart
     * runs in TUN mode and the core's last launch went through a prompt that
     * every launch raises again.
     */
    boolean restartNeedsTheUser() {
        SingBoxEngine current = engine;
        if (current == null || configStore == null) {
            return false;
        }
        // The mode recover() would restart in.
        ProxyMode mode = requestedMode != null
                ? requestedMode : configStore.getSettings().getProxyMode();
        return mode == ProxyMode.TUN && current.restartNeedsElevationPrompt();
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

    /**
     * A local port the running core listens on in place of the chosen one,
     * which another program held.
     *
     * @param inbound what listens there: {@code SOCKS}, {@code HTTP} or
     *                {@code control}
     * @param chosen  the port the user chose
     * @param used    the port the run listens on
     */
    public record MovedPort(String inbound, int chosen, int used) {
    }

    /**
     * The ports the running core moved off, for the Dashboard to say so: a
     * program set to the chosen port reaches nothing this session. Empty when
     * none moved or no core runs; changes on the JavaFX thread.
     *
     * @return the moved ports of the current session
     */
    public ReadOnlyObjectProperty<List<MovedPort>> movedPortsProperty() {
        return movedPorts.getReadOnlyProperty();
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
     * generator includes them in the manual or automatic group. A previous core
     * that is being stopped is waited out first, because {@code start} refuses
     * while one is alive — that is what makes the reconnect paths (server
     * switch, health auto-reconnect) work. A core that is running with no stop
     * under way is reported as {@link Outcome#ALREADY_RUNNING} at once.</p>
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
            ConnectAttempt attempt =
                    connectInternal(modeOverride, () -> recovery.isWanted(request));
            if (attempt.outcome() == Outcome.ALREADY_RUNNING) {
                // Nothing restarted, so no state change will tell recovery.
                recovery.keptUp(request);
            }
            return attempt;
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

        if (current.isRunning() && !current.isStopping()) {
            // Nothing is stopping this core, so waiting for it would only run
            // out STOP_WAIT under the lock a Disconnect needs, and the start
            // would be refused anyway.
            return new ConnectAttempt(Outcome.ALREADY_RUNNING, active);
        }
        log.info("Connecting to server: {} ({})", active.getName(), mode);
        current.awaitStopped(STOP_WAIT);
        if (!allowed.getAsBoolean()) {
            return new ConnectAttempt(Outcome.CANCELLED, active);
        }
        List<MovedPort> moved = List.of();
        if (!current.isRunning()) {
            // Only once the previous core is gone: while it still ran, its own
            // ports would read as taken. What it leaves behind in TIME_WAIT
            // does not (see isFree), so a restart keeps the ports it had.
            moved = moveTakenListenPortsAside(settings, lastCorePorts);
            lastCorePorts = Set.copyOf(List.of(settings.listenSocksPort(),
                    settings.listenHttpPort(), settings.listenClashApiPort()));
            recordSessionHttpPort(settings);
        }
        // A control secret for this core alone. One secret lasted the whole
        // run, and while a TUN start waits for the admin prompt the watchdog
        // sends it to whatever answers on the control port: a program
        // squatting there kept a token good for every later core. The
        // monitors read it again when the new core reaches CONNECTED.
        settings.setClashApiSecret(ConfigStore.newClashApiSecret());
        List<SkippedServer> skipped = new ArrayList<>();
        List<ServerConfig> members = withoutRefused(candidates, active, skipped);
        int refusedByTheCore = 0;
        RoutingConfig routing = safeRoutingConfig();
        // The run's own: comparing with the settings later reads the host as
        // it was now, and asks it nothing on the FX thread.
        HostFacts host = configGenerator.hostFacts();
        try {
            while (true) {
                LiveSelector prepared = new LiveSelector(
                        configGenerator.generate(members, active, settings, routing, host));
                Run previous = run;
                run = new Run(prepared, mode, chosenPorts(settings), idsOf(skipped), host);
                try {
                    current.start(prepared.config(), mode);
                    break;
                } catch (ConfigRejectedException e) {
                    run = previous;
                    Refusal refusal = refusalOf(e, prepared.config(), members).orElse(null);
                    if (refusal == null) {
                        throw e;
                    }
                    ServerConfig refused = refusal.server();
                    if (refused.getId().equals(active.getId())
                            || refusedByTheCore >= MAX_SKIPPED) {
                        throw refusal.named(e);
                    }
                    refusedByTheCore++;
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
                    run = previous;
                    throw e;
                }
            }
        } catch (IllegalStateException e) {
            log.warn("sing-box already running: {}", e.getMessage());
            return new ConnectAttempt(Outcome.ALREADY_RUNNING, active);
        }
        if (mode == ProxyMode.TUN && settings.isTunIpv6Enabled()
                && !SingBoxConfigGenerator.tunTakesIpv6(settings, host)) {
            // Said once per start: the generator runs again on every dashboard
            // update to compare, and said it each time.
            log.info("TUN IPv6 is on, but no interface holds a global IPv6 address: "
                    + "the device stays IPv4-only so direct routes are not dialled over IPv6");
        }
        appliedServerId = active.getId();
        publishSkipped(skipped);
        publishMoved(moved);
        return new ConnectAttempt(Outcome.STARTED, active, skipped);
    }

    /**
     * The servers the core can build; the others go into {@code skipped}.
     *
     * <p>{@link CoreSettings#refusal} knows some servers the core cannot build
     * before the core does, and for some of those the core never says which
     * server it was: a REALITY short ID longer than 16 hex digits makes
     * {@code sing-box check} panic, a panic quotes no member, and every
     * connect failed over one spare server, whichever server was picked.
     * Import refuses such a server, but an older build, the server form or a
     * restored backup may still have stored one.</p>
     *
     * @throws ConfigRejectedException if the active server is one of them:
     *     connecting through a server the user did not pick is no fallback
     */
    private static List<ServerConfig> withoutRefused(List<ServerConfig> candidates,
            ServerConfig active, List<SkippedServer> skipped) throws ConfigRejectedException {
        CoreSettings.Refusal own = CoreSettings.refusal(active).orElse(null);
        if (own != null) {
            throw new ConfigRejectedException(
                    I18n.get("engine.config.rejected.server", active.getName(), own.reason()),
                    own.reason());
        }
        List<ServerConfig> members = new ArrayList<>();
        for (ServerConfig server : candidates) {
            CoreSettings.Refusal refusal = CoreSettings.refusal(server).orElse(null);
            if (refusal == null) {
                members.add(server);
                continue;
            }
            log.warn("sing-box cannot build server '{}' ({}); connecting without it",
                    server.getName(), refusal.feature());
            skipped.add(new SkippedServer(server.getId(), server.getName(), refusal.reason()));
        }
        return members;
    }

    /**
     * Hands what the core was just started without to the UI, through the FX
     * queue, after the not-started state each refused attempt queued, so that
     * state cannot clear it again. The live switch reads the ids from the run.
     */
    private void publishSkipped(List<SkippedServer> skipped) {
        List<SkippedServer> snapshot = List.copyOf(skipped);
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
        run = null;
        // A stopped core put the system's proxy back itself.
        SessionPorts.forget(configStore.getDataDir());
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

    /**
     * Applies a server picked while the core was still starting.
     *
     * <p>A pick switches the running core, but only once it runs: one made
     * while the tunnel was connecting, by the user or during a recovery
     * restart, changed the list and the tray while the traffic stayed on the
     * server the start had begun with, and no banner said so. Called as the
     * core reaches CONNECTED; the automatic selection has nothing pinned to
     * follow.</p>
     */
    void followActiveServer() {
        String applied = appliedServerId;
        if (applied == null || configStore == null
                || configStore.getSettings().getServerSelection() != ServerSelection.SINGLE) {
            return;
        }
        ServerConfig active = FxExecutor.get(() -> configStore.getServers().stream()
                .filter(ServerConfig::isActive).findFirst().orElse(null));
        if (active == null || active.getId().equals(applied)) {
            return;
        }
        log.info("The active server changed while connecting; applying {}", active.getName());
        Thread.startVirtualThread(() -> {
            try {
                switchToActiveServer();
            } catch (IOException | RuntimeException e) {
                log.warn("Could not apply the server picked while connecting", e);
            }
        });
    }

    /** The server the running core was last pointed at, or null before any start. */
    String appliedServerId() {
        return appliedServerId;
    }

    /** The group tag used by the current process, for live status queries. */
    public String getProxyGroupTag() {
        Run current = run;
        return current != null ? current.selector().groupTag() : OutboundTags.PROXY;
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
            Run current = run;
            if (isRunning() && current != null && current.mode() == mode
                    && current.selector().accepts(generatedNow(
                            candidates, active, settings, current.leftOut(), current.host()))
                    && current.selector().select(OutboundTags.server(active))) {
                if (!recovery.isWanted(request)) {
                    return new ConnectAttempt(Outcome.CANCELLED, active);
                }
                // The core kept running, so no state change will tell recovery.
                recovery.keptUp(request);
                appliedServerId = active.getId();
                return new ConnectAttempt(Outcome.SWITCHED, active);
            }
            if (!recovery.isWanted(request)) {
                return new ConnectAttempt(Outcome.CANCELLED, active);
            }
            stopCurrent();
            return connectInternal(requestedMode, () -> recovery.isWanted(request));
        }
    }

    /**
     * Whether the running core was built from the settings, rules and servers
     * as they are now, but for which server is picked, which switches live.
     * A change of DNS, ports, TUN options, a routing rule or the mode applies
     * only at the next start, and nothing said so.
     *
     * <p>Compared with what a reconnect from the Dashboard would start: the
     * saved mode, and the chosen ports as well as the configuration, since a
     * port the run moved stands in for the chosen one until the run ends.</p>
     *
     * @return true when no core runs, or when a restart would load the same
     *     configuration
     */
    public boolean runsCurrentSettings() {
        Run current = run;
        if (!isRunning() || current == null) {
            return true;
        }
        List<ServerConfig> candidates = FxExecutor.get(
                () -> List.copyOf(configStore.getServers()));
        ServerConfig active = candidates.stream().filter(ServerConfig::isActive)
                .findFirst().orElse(null);
        if (active == null) {
            return true;
        }
        AppSettings settings = configStore.getSettings();
        return current.mode() == settings.getProxyMode()
                && current.chosenPorts().equals(chosenPorts(settings))
                && current.selector().matches(generatedNow(
                        candidates, active, settings, current.leftOut(), current.host()));
    }

    /**
     * The configuration the current settings make. The loaded one left out
     * the servers the core refused, so it leaves them out too; picking one of
     * those does not match and restarts, where its refusal is reported by
     * name. The host is taken as the run found it.
     */
    private String generatedNow(List<ServerConfig> candidates, ServerConfig active,
                                AppSettings settings, Set<String> leftOut, HostFacts host) {
        List<ServerConfig> members = leftOut.contains(active.getId())
                ? candidates
                : candidates.stream()
                        .filter(server -> !leftOut.contains(server.getId()))
                        .toList();
        return configGenerator.generate(members, active, settings, safeRoutingConfig(), host);
    }

    /** The ports the user chose, which a run's own may have moved off. */
    private static List<Integer> chosenPorts(AppSettings settings) {
        return List.of(settings.getSocksPort(), settings.getHttpPort(),
                settings.getClashApiPort());
    }

    private static Set<String> idsOf(List<SkippedServer> skipped) {
        return skipped.stream()
                .map(SkippedServer::id)
                .collect(Collectors.toUnmodifiableSet());
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
     * Moves each local listening port another program holds to the next free
     * one, for this run.
     *
     * <p>The core refuses to start on a port it cannot bind, and recovery then
     * repeated that refusal for as long as the other program lived, with a
     * notification every time. The ports move for the run only
     * ({@link AppSettings#listenOn}): the chosen ones, which are what gets
     * saved, stay as they are, so the next start tries them again, and what
     * listens, connects or shows a port reads the run's.</p>
     */
    private static List<MovedPort> moveTakenListenPortsAside(AppSettings settings,
                                                             Set<Integer> lastCores) {
        int[] chosen = {settings.getSocksPort(), settings.getHttpPort(),
            settings.getClashApiPort()};
        String[] what = {"SOCKS", "HTTP", "control"};
        // The chosen ports that are free are kept first, so a port that moves
        // cannot land on one: a taken 1080 used to move SOCKS onto 1081, the
        // HTTP port, and HTTP on to 1082.
        Set<Integer> reserved = new LinkedHashSet<>();
        boolean[] kept = new boolean[chosen.length];
        for (int i = 0; i < chosen.length; i++) {
            kept[i] = !reserved.contains(chosen[i]) && isFree(chosen[i], lastCores);
            if (kept[i]) {
                reserved.add(chosen[i]);
            }
        }
        int[] listen = chosen.clone();
        for (int i = 0; i < chosen.length; i++) {
            if (!kept[i]) {
                listen[i] = freePortFrom(chosen[i], what[i], reserved, lastCores);
            }
        }
        settings.listenOn(listen[0], listen[1], listen[2]);
        List<MovedPort> moved = new ArrayList<>();
        for (int i = 0; i < chosen.length; i++) {
            if (listen[i] != chosen[i]) {
                moved.add(new MovedPort(what[i], chosen[i], listen[i]));
            }
        }
        return List.copyOf(moved);
    }

    /**
     * Keeps this run's HTTP port for the next start when it is not the chosen
     * one: a run that dies leaves the system's proxy pointing at it, and the
     * next start looks for such a proxy on the chosen port.
     */
    private void recordSessionHttpPort(AppSettings settings) {
        if (settings.listenHttpPort() != settings.getHttpPort()) {
            SessionPorts.record(configStore.getDataDir(), settings.listenHttpPort());
        } else {
            SessionPorts.forget(configStore.getDataDir());
        }
    }

    /** Hands the ports this run moved off to the UI, after the states the start queued. */
    private void publishMoved(List<MovedPort> moved) {
        try {
            Platform.runLater(() -> movedPorts.set(moved));
        } catch (IllegalStateException toolkitNotRunning) {
            movedPorts.set(moved);
        }
    }

    /**
     * The configured port when it is free, else the next free one above it.
     * Ports already handed out in this pass count as taken, so two inbounds
     * cannot be moved onto the same one.
     */
    private static int freePortFrom(int configured, String what, Set<Integer> alreadyTaken,
                                    Set<Integer> lastCores) {
        for (int port = configured; port <= 65535 && port < configured + 64; port++) {
            if (alreadyTaken.contains(port) || !isFree(port, lastCores)) {
                continue;
            }
            if (port != configured) {
                log.warn("The {} port {} is held by another program; this run listens on {}",
                        what, configured, port);
            }
            alreadyTaken.add(port);
            return port;
        }
        // Nothing free nearby: keep the choice and let the core say why it
        // cannot start, rather than listening somewhere nobody expects.
        log.error("No free {} port near {}; starting on it anyway", what, configured);
        alreadyTaken.add(configured);
        return configured;
    }

    /**
     * Whether the next core can have {@code port}.
     *
     * <p>A core stopped a moment ago leaves its closed connections in
     * TIME_WAIT for half a minute, and when that core ran as root (TUN), macOS
     * refuses the app's own bind while they last, though the next root core
     * binds the port fine: a quick restart reported the control port "held by
     * another program" and walked it from 9099 to 9100 to 9101. So a port the
     * last core listened on is free when nothing listens on it now, whatever
     * the bind says; any other port is free when it binds.</p>
     *
     * @param port       the port to ask about
     * @param lastCores  the ports the last core listened on
     */
    static boolean isFree(int port, Set<Integer> lastCores) {
        try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort() == port;
        } catch (IOException refused) {
            return lastCores.contains(port) && !someoneListens(port);
        }
    }

    private static boolean someoneListens(int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                    PORT_PROBE_TIMEOUT_MS);
            // Dialled from the very port it dials, a socket reaches itself (TCP
            // simultaneous open); macOS hands out ephemeral ports in sequence,
            // which makes that likely for a port in its range.
            return probe.getLocalPort() != port;
        } catch (IOException nothingListens) {
            return false;
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
