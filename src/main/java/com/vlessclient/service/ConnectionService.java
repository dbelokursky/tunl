package com.vlessclient.service;

import com.vlessclient.app.I18n;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.ServerSelection;
import com.vlessclient.platform.ElevationDeclinedException;
import com.vlessclient.platform.PowerState;
import com.vlessclient.service.outbound.CoreSettings;
import com.vlessclient.service.outbound.OutboundTags;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

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
     * How long a connect waits for a tunnel still closing: one an earlier
     * run's core left (SingBoxEngine#awaitEarlierTunnel), or one this run
     * stopped that outlasted the stop (SingBoxEngine#awaitStopped). One such
     * core took about half a minute after an update.
     */
    private static final Duration EARLIER_TUNNEL_WAIT = Duration.ofSeconds(45);

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
        /**
         * The running core took the pick without restarting: its selector
         * switched to the server, or the automatic selection, which a pick
         * does not change, went on as it was.
         */
        SWITCHED,
        /** No core is installed yet, so there is nothing to launch. */
        NO_CORE,
        /** Nothing is selected to connect to. */
        NO_ACTIVE_SERVER,
        /** A core was already running, so this attempt was refused. */
        ALREADY_RUNNING,
        /** A newer user request superseded this connection attempt. */
        CANCELLED,
        /**
         * A pick found no core running to move: the next start reads it,
         * the user's Connect or a recovery restart under way.
         */
        NOT_RUNNING,
        /**
         * A pick the store made itself, as a removal nobody asked the tunnel
         * to follow (a refresh's withdrawal, an agent's delete) took the
         * picked server, left the running core as it was: the restart it
         * needs would raise an elevation prompt nobody asked for, or start a
         * core that refuses the server picked, so it waits for the user's
         * reconnect.
         */
        DEFERRED
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

    /** The run's one engine, which has no core until one is installed. */
    private final SingBoxEngine engine;
    private final Object operations = new Object();
    private final TunnelRecoveryService recovery;
    private final ChangeListener<ConnectionState> stateListener;
    private volatile ProxyMode requestedMode;
    /**
     * The mode the last connect started in, or asked for: what recovery
     * restarts in. It took the saved mode, which a change on the Dashboard or
     * over MCP sets without touching the running core, so a dropped tunnel
     * came back in the other mode: a UAC or administrator prompt nobody asked
     * for, or a TUN tunnel silently turned into a system proxy.
     */
    private volatile ProxyMode restartMode;
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

    /** How often a TUN run with IPv6 on looks at the network's IPv6 again. */
    static final Duration NETWORK_CHECK = Duration.ofSeconds(15);

    /**
     * How the host's IPv6 changed since the running core started. The TUN
     * device takes IPv6 or not by the network it starts on, and a run keeps
     * that fact (see {@link HostFacts}), so a laptop that changed networks
     * sent its IPv6 around the tunnel, or drew apps to IPv6 that could not
     * leave, until a reconnect nothing offered.
     */
    public enum NetworkChange {

        /** As the run started. */
        NONE,

        /** The network gained IPv6, which goes around a device started without it. */
        IPV6_GAINED,

        /**
         * The network lost IPv6, which a device started with it still draws
         * apps to, so what the rules send direct does not open.
         */
        IPV6_LOST
    }

    private final ReadOnlyObjectWrapper<NetworkChange> networkChange =
            new ReadOnlyObjectWrapper<>(NetworkChange.NONE);
    /** Looks at the network while a TUN run with IPv6 on is up; null otherwise. */
    private ScheduledExecutorService networkWatch;

    /**
     * What a core was started from: the configuration it loaded, the mode, the
     * ports the user chose, which its own may have moved off, the ids of the
     * servers it was started without and what the core's check said of those
     * it refused, and the request a pick restarted it under, or
     * {@link #NOT_A_PICK}. Set as one, before the core starts: it reports
     * CONNECTED from its own thread, and a reader asking then got the mode and
     * the left-out servers of the start before.
     */
    private record Run(LiveSelector selector, ProxyMode mode, List<Integer> chosenPorts,
                       Set<String> leftOut, Map<String, Refused> refused, HostFacts host,
                       long pickedUnder) {
    }

    /**
     * A server the core's check refused as a run started, as it was then.
     *
     * @param content the server as the check refused it, but for whether it
     *                was picked: one that changed since is the core's to judge
     *                again
     * @param reason  what the core said about it
     */
    private record Refused(JsonNode content, String reason) {
    }

    /** The request of a start no pick made, which no request ever is. */
    private static final long NOT_A_PICK = -1;

    /**
     * A start is under way: a connect, a reconnect, a pick's restart or a
     * recovery restart holds the operations lock to bring a core up, from the
     * stop of the core before to the start's end, its waits and prompts
     * included.
     */
    private volatile boolean starting;


    /**
     * Creates the service, whose recovery takes the machine to be always
     * awake: for tests and tools, which have no sleep to wait out.
     *
     * @param configStore     source of the server list and the live settings
     * @param configGenerator builds the core configuration
     * @param routingService  supplies routing rules, or null for defaults
     * @param engine          the engine to drive, with or without a core
     */
    public ConnectionService(ConfigStore configStore,
                             SingBoxConfigGenerator configGenerator,
                             RoutingService routingService,
                             SingBoxEngine engine) {
        this(configStore, configGenerator, routingService, engine, PowerState.ALWAYS_AWAKE);
    }

    /**
     * Creates the service.
     *
     * @param configStore     source of the server list and the live settings
     * @param configGenerator builds the core configuration
     * @param routingService  supplies routing rules, or null for defaults
     * @param engine          the engine to drive, with or without a core
     * @param power           the machine's power state, which recovery asks
     *                        before it restarts the tunnel for a failed
     *                        health check
     */
    public ConnectionService(ConfigStore configStore,
                             SingBoxConfigGenerator configGenerator,
                             RoutingService routingService,
                             SingBoxEngine engine,
                             PowerState power) {
        this.configStore = configStore;
        this.configGenerator = configGenerator;
        this.routingService = routingService;
        this.engine = Objects.requireNonNull(engine, "engine");
        this.recovery = new TunnelRecoveryService(
                () -> configStore != null ? configStore.getSettings() : new AppSettings(),
                this::recover, this::restartNeedsTheUser, power);
        this.stateListener = (obs, old, state) -> {
            // A notice about what the core was started without ends with it.
            if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
                skippedServers.set(List.of());
                movedPorts.set(List.of());
            }
            recovery.onConnectionState(state);
            // A dismissed administrator prompt is the user cancelling the
            // connect, and the request for a tunnel goes with it: kept, it
            // held subscriptions back for a tunnel nobody wanted any more.
            // A pick's restart is no connect: it stopped the working tunnel
            // before the prompt, which leaves the reconnect to the user.
            if (state == ConnectionState.DISCONNECTED && engine.lastExitWasDeclined()) {
                Run declined = run;
                if (declined == null || !recovery.offerReconnect(declined.pickedUnder())) {
                    recovery.cancel();
                }
            }
            if (state == ConnectionState.CONNECTED) {
                followActiveServer();
                // Every start is a new comparison: a reconnect after a
                // change is what clears it.
                publishNetworkChange(NetworkChange.NONE);
                watchNetwork();
            } else if (state == ConnectionState.DISCONNECTED
                    || state == ConnectionState.ERROR) {
                stopWatchingNetwork();
            }
        };
        FxExecutor.run(() -> engine.connectionStateProperty().addListener(stateListener));
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
        if (configStore == null) {
            return false;
        }
        // The mode recover() would restart in.
        ProxyMode mode = restartMode;
        if (mode == null) {
            mode = requestedMode != null
                    ? requestedMode : configStore.getSettings().getProxyMode();
        }
        return mode == ProxyMode.TUN && engine.restartNeedsElevationPrompt();
    }

    /** The engine this service drives, which may have no core yet. */
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

    /**
     * How the network changed under the running core, for the Dashboard to
     * offer the reconnect that applies it. Set on the FX thread.
     *
     * @return the read-only change property
     */
    public ReadOnlyObjectProperty<NetworkChange> networkChangeProperty() {
        return networkChange.getReadOnlyProperty();
    }

    /**
     * Compares the network's IPv6 now with what the running TUN core started
     * on, and publishes the difference. Off the FX thread: it walks the
     * network interfaces. The watch calls it; package-private for a test.
     */
    void checkNetwork() {
        Run current = run;
        NetworkChange change = NetworkChange.NONE;
        if (current != null && current.mode() == ProxyMode.TUN && isRunning()
                && configStore != null && configStore.getSettings().isTunIpv6Enabled()) {
            boolean startedWith = current.host().ipv6Uplink();
            boolean now = configGenerator.hostFacts().ipv6Uplink();
            if (now != startedWith) {
                change = now ? NetworkChange.IPV6_GAINED : NetworkChange.IPV6_LOST;
            }
        }
        publishNetworkChange(change);
    }

    /**
     * Looks at the network every {@link #NETWORK_CHECK} while a TUN core with
     * IPv6 on runs, the only configuration the network's IPv6 shapes.
     */
    private synchronized void watchNetwork() {
        Run current = run;
        boolean shaped = current != null && current.mode() == ProxyMode.TUN
                && configStore != null && configStore.getSettings().isTunIpv6Enabled();
        if (!shaped) {
            stopWatchingNetwork();
            return;
        }
        if (networkWatch == null) {
            networkWatch = Executors.newSingleThreadScheduledExecutor(
                    DaemonThreads.factory("network-watch"));
            long period = NETWORK_CHECK.toMillis();
            networkWatch.scheduleWithFixedDelay(this::checkNetworkQuietly,
                    period, period, TimeUnit.MILLISECONDS);
        }
    }

    /** Whether the network watch runs; for a test. */
    synchronized boolean watchesNetwork() {
        return networkWatch != null;
    }

    private synchronized void stopWatchingNetwork() {
        if (networkWatch != null) {
            networkWatch.shutdownNow();
            networkWatch = null;
        }
        publishNetworkChange(NetworkChange.NONE);
    }

    private void checkNetworkQuietly() {
        try {
            checkNetwork();
        } catch (RuntimeException e) {
            // A failed look at the interfaces must not end the watch.
            log.debug("Could not look at the network: {}", e.toString());
        }
    }

    private void publishNetworkChange(NetworkChange change) {
        try {
            Platform.runLater(() -> networkChange.set(change));
        } catch (IllegalStateException toolkitNotRunning) {
            networkChange.set(change);
        }
    }

    /** Whether a core is running right now. Safe from any thread. */
    public boolean isRunning() {
        return engine.isRunning();
    }

    /**
     * Whether a start is under way, which may have no core to show for it
     * yet: while it waits for the core before it to close, while the core
     * checks the configuration, and while a setup prompt is up before
     * anything is launched. Safe from any thread.
     *
     * @return true while a connect, a reconnect or a restart is bringing a
     *     core up
     */
    public boolean isStarting() {
        return starting;
    }

    /**
     * The proxy mode the running core was started with. The settings may name
     * another: a mode changed there applies at the next start.
     *
     * @return the running core's mode, or empty when no core runs
     */
    public Optional<ProxyMode> runningMode() {
        Run current = run;
        return isRunning() && current != null ? Optional.of(current.mode()) : Optional.empty();
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
     * @throws IOException           if the core could not be started, or the one
     *                               being stopped was still closing after the wait
     * @throws IllegalStateException if called on the JavaFX thread
     */
    public ConnectAttempt connect(ProxyMode modeOverride) throws IOException {
        requireOffFxThread("connect");
        requestedMode = modeOverride;
        long request = recovery.connectionRequested();
        synchronized (operations) {
            ConnectAttempt attempt =
                    connectInternal(modeOverride, () -> recovery.isWanted(request), NOT_A_PICK);
            if (attempt.outcome() == Outcome.ALREADY_RUNNING) {
                // Nothing restarted, so no state change will tell recovery.
                recovery.keptUp(request);
            }
            return attempt;
        }
    }

    /**
     * Starts a core, under the operations lock, as the start under way.
     *
     * @param pickedUnder the request a pick restarts the core under, or
     *                    {@link #NOT_A_PICK}: what a dismissed elevation
     *                    prompt of the start leaves the tunnel to
     */
    private ConnectAttempt connectInternal(ProxyMode modeOverride, BooleanSupplier allowed,
                                           long pickedUnder) throws IOException {
        boolean started = false;
        starting = true;
        try {
            ConnectAttempt attempt = startCore(modeOverride, allowed, pickedUnder);
            started = attempt.outcome() == Outcome.STARTED;
            return attempt;
        } finally {
            starting = false;
            // A connect that waited for an earlier tunnel showed Connecting,
            // and one that failed after it without a start left it showing.
            engine.connectEnded();
            if (!started && !engine.isRunning()) {
                // The ports the attempt set for its run (AppSettings.listenOn)
                // when no core came to listen on them: refused, failed or
                // cancelled. Only a disconnect forgot them, so until the next
                // connect or disconnect what reads the run's ports (the
                // status an agent asks for, the port the Dashboard tells
                // apps to use, the app's own requests) read these, and not
                // the ones the user chose meanwhile.
                configStore.getSettings().endRun();
            }
        }
    }

    private ConnectAttempt startCore(ProxyMode modeOverride, BooleanSupplier allowed,
                                     long pickedUnder) throws IOException {
        if (!engine.hasBinary()) {
            return new ConnectAttempt(Outcome.NO_CORE, null);
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

        if (engine.isRunning() && !engine.isStopping()) {
            // Nothing is stopping this core, so waiting for it would only run
            // out STOP_WAIT under the lock a Disconnect needs, and the start
            // would be refused anyway.
            return new ConnectAttempt(Outcome.ALREADY_RUNNING, active);
        }
        restartMode = mode;
        log.info("Connecting to server: {} ({})", active.getName(), mode);
        // A TUN core may take longer to close than its stop waits, and the
        // stop returns with the launcher still running. This waited fifteen
        // seconds for it with nothing on screen, and the start was then
        // refused as "already running", which no caller showed: the click did
        // nothing, the tunnel stayed wanted and down, and an agent was told
        // it was up. It is waited for as an earlier run's tunnel is, showing
        // Connecting, and one that outlasts the wait is said.
        if (!engine.awaitStopped(EARLIER_TUNNEL_WAIT, allowed)) {
            if (!allowed.getAsBoolean()) {
                return new ConnectAttempt(Outcome.CANCELLED, active);
            }
            throw new IOException(I18n.get("engine.stop.still.closing"));
        }
        if (!allowed.getAsBoolean()) {
            return new ConnectAttempt(Outcome.CANCELLED, active);
        }
        // A tunnel an earlier run's core left may be closing still, after an
        // update or a quick restart: started beside it, the new one fought it
        // for the routes and the ports until it was gone. Waited for first,
        // so that its ports are free again, not moved aside.
        if (!engine.isRunning()
                && !engine.awaitEarlierTunnel(EARLIER_TUNNEL_WAIT, allowed)
                && !allowed.getAsBoolean()) {
            return new ConnectAttempt(Outcome.CANCELLED, active);
        }
        List<MovedPort> moved = List.of();
        if (!engine.isRunning()) {
            // Only once the previous core is gone: while it still ran, its own
            // ports would read as taken. What it leaves behind in TIME_WAIT
            // does not (see isFree), so a restart keeps the ports it had.
            moved = moveTakenListenPortsAside(settings, lastCorePorts);
            lastCorePorts = Set.copyOf(List.of(settings.listenSocksPort(),
                    settings.listenHttpPort(), settings.listenClashApiPort(),
                    settings.listenProbePort()));
            recordSessionHttpPort(settings, mode);
        }
        // A control secret for this core alone. One secret lasted the whole
        // run, and while a TUN start waits for the admin prompt the watchdog
        // sends it to whatever answers on the control port: a program
        // squatting there kept a token good for every later core. The
        // monitors read it again when the new core reaches CONNECTED.
        settings.setClashApiSecret(ConfigStore.newClashApiSecret());
        List<SkippedServer> skipped = new ArrayList<>();
        List<ServerConfig> members = withoutRefused(candidates, active, skipped);
        Map<String, Refused> refusedAtCheck = new LinkedHashMap<>();
        int refusedByTheCore = 0;
        RoutingConfig routing = safeRoutingConfig();
        // The run's own: comparing with the settings later reads the host as
        // it was now, and asks it nothing on the FX thread.
        HostFacts host = configGenerator.hostFacts();
        // The configuration is the mode's the core starts in, which a restart
        // may run in where the saved mode has changed since.
        AppSettings generatedFrom = inMode(settings, mode);
        try {
            while (true) {
                LiveSelector prepared = new LiveSelector(
                        configGenerator.generate(members, active, generatedFrom, routing, host));
                Run previous = run;
                String previousServer = appliedServerId;
                run = new Run(prepared, mode, chosenPorts(settings), idsOf(skipped),
                        Map.copyOf(refusedAtCheck), host, pickedUnder);
                // Set with the run, for the same reason: followActiveServer and
                // the Dashboard ask which server the core runs as it reports
                // Connected. Set only once the start had returned, it could
                // read as none on a first start, so a pick made while
                // connecting was not applied, and on a later start as the
                // server of the start before.
                appliedServerId = active.getId();
                try {
                    engine.start(prepared.config(), mode);
                    break;
                } catch (ConfigRejectedException e) {
                    run = previous;
                    appliedServerId = previousServer;
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
                    refusedAtCheck.put(refused.getId(),
                            new Refused(contentOf(refused), refusal.detail()));
                    members = members.stream()
                            .filter(server -> !server.getId().equals(refused.getId()))
                            .toList();
                    if (!allowed.getAsBoolean()) {
                        return new ConnectAttempt(Outcome.CANCELLED, active);
                    }
                } catch (ElevationDeclinedException declined) {
                    // The setup prompt dismissed: the connect is cancelled,
                    // not failed, and the request goes with it. A pick's
                    // restart had stopped the working tunnel before the
                    // prompt, which leaves the reconnect to the user.
                    run = previous;
                    appliedServerId = previousServer;
                    if (!recovery.offerReconnect(pickedUnder)) {
                        recovery.cancel();
                    }
                    log.info("TUN connect cancelled: {}", declined.getMessage());
                    return new ConnectAttempt(Outcome.CANCELLED, active);
                } catch (IOException | IllegalStateException e) {
                    run = previous;
                    appliedServerId = previousServer;
                    throw e;
                }
            }
        } catch (IllegalStateException e) {
            log.warn("sing-box already running: {}", e.getMessage());
            return new ConnectAttempt(Outcome.ALREADY_RUNNING, active);
        }
        if (mode == ProxyMode.TUN && settings.isTunIpv6Enabled()
                && !SingBoxConfigGenerator.tunTakesIpv6(generatedFrom, host)) {
            // Said once per start: the generator runs again on every dashboard
            // update to compare, and said it each time.
            log.info("TUN IPv6 is on, but no interface holds a global IPv6 address: "
                    + "the device stays IPv4-only so direct routes are not dialled over IPv6");
        }
        logReplacedFingerprints(members);
        publishSkipped(skipped);
        publishMoved(moved);
        return new ConnectAttempt(Outcome.STARTED, active, skipped);
    }

    private static final JsonMapper SETTINGS_COPIER = JsonMapper.builder().build();

    /** A server as a configuration is built from it, but for whether it is picked. */
    private static JsonNode contentOf(ServerConfig server) {
        ObjectNode content = SETTINGS_COPIER.valueToTree(server);
        content.remove("active");
        return content;
    }

    /**
     * {@code settings} in {@code mode}: themselves when that is their mode,
     * otherwise a copy, so a run in another mode leaves the saved one alone.
     * The copy keeps what is the run's and not saved: the control secret and
     * the ports the run listens on.
     */
    static AppSettings inMode(AppSettings settings, ProxyMode mode) {
        if (settings.getProxyMode() == mode) {
            return settings;
        }
        AppSettings copy = SETTINGS_COPIER.convertValue(settings, AppSettings.class);
        copy.setProxyMode(mode);
        copy.setClashApiSecret(settings.getClashApiSecret());
        copy.listenOn(settings.listenSocksPort(), settings.listenHttpPort(),
                settings.listenClashApiPort(), settings.listenProbePort());
        return copy;
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
     * Names the REALITY servers the core was given another uTLS fingerprint
     * than their links name, with both ({@link CoreSettings#realityReplacement}).
     * Said once per start, like the IPv6 note: the generator writes the
     * substitution into every configuration, and it writes one on every
     * dashboard update to compare.
     */
    private static void logReplacedFingerprints(List<ServerConfig> members) {
        String replaced = members.stream()
                .flatMap(server -> CoreSettings.realityReplacement(server.getTls()).stream()
                        .map(sent -> "'" + server.getName() + "' ("
                                + CoreSettings.fingerprint(server.getTls()) + " as " + sent + ")"))
                .collect(Collectors.joining(", "));
        if (!replaced.isEmpty()) {
            log.info("REALITY servers get a uTLS fingerprint whose hello newer servers accept "
                    + "in place of their link's: {}", replaced);
        }
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
        log.info("Disconnecting");
        engine.stop();
        // After the stop: while the core still ran, its requests went to it.
        configStore.getSettings().endRun();
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
            return restart(modeOverride, () -> recovery.isWanted(request), NOT_A_PICK);
        }
    }

    /**
     * Stops the running core and starts another, under the operations lock,
     * as one start under way: {@link #isStarting()} holds from the stop on.
     */
    private ConnectAttempt restart(ProxyMode mode, BooleanSupplier allowed, long pickedUnder)
            throws IOException {
        starting = true;
        try {
            stopCurrent();
            return connectInternal(mode, allowed, pickedUnder);
        } finally {
            starting = false;
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

    /**
     * The id of the server the running core was last pointed at, by a start
     * or a live switch, whoever asked for it: the Dashboard, the tray, an
     * agent over MCP, recovery, or {@link #followActiveServer}. The Dashboard
     * compares a new pick with this: its own copy knew only its own Connect,
     * so a pick under a core the tray had started was never applied.
     *
     * @return the server's id, or null before any start
     */
    public String appliedServerId() {
        return appliedServerId;
    }

    /**
     * Whether the user wants a tunnel now: asked for one and has not
     * disconnected since, whether or not the core is up at the moment.
     *
     * @return whether a tunnel is wanted
     */
    public boolean isTunnelWanted() {
        return recovery.isTunnelWanted();
    }

    /** The group tag used by the current process, for live status queries. */
    public String getProxyGroupTag() {
        Run current = run;
        return current != null ? current.selector().groupTag() : OutboundTags.PROXY;
    }

    /**
     * The server list and whether its pick is the store's own, read as one on
     * the FX thread.
     */
    private record Picked(List<ServerConfig> candidates, boolean byTheStore) {
    }

    /**
     * Moves the running core to the picked server: through the core API when
     * the loaded configuration still matches; otherwise by a restart with the
     * latest config, in the mode the running core runs in.
     *
     * <p>A pick moves the tunnel the user asked for, and asks for none
     * itself. It took a request of its own, before the lock, as a Connect
     * does: a Disconnect that withdrew the user's request was then outlived
     * by a switch already under way, by one the Dashboard started as the stop
     * ran and the card still read Connected, and by the one this service
     * starts for a pick made while connecting. Each found no core and started
     * one in the saved mode after the Disconnect, or moved the core the
     * Disconnect then stopped and left the tunnel wanted with nothing
     * running. A pick that met a recovery restart's stop called the restart
     * off the same way and started in the saved mode itself. With no core to
     * move a pick now changes nothing: the next start reads it. A pick that
     * moves the core starts recovery over, under the user's request
     * ({@link TunnelRecoveryService#startOver}).</p>
     *
     * @return the selected server, and whether it was switched or restarted,
     *     or left to the next start ({@link Outcome#NOT_RUNNING}) or to the
     *     user's reconnect ({@link Outcome#DEFERRED})
     * @throws ConfigRejectedException if the core refuses the picked server,
     *     which is said before anything is stopped for it
     * @throws IOException if the fallback start fails
     */
    public ConnectAttempt switchToActiveServer() throws IOException {
        requireOffFxThread("switchToActiveServer");
        long request = recovery.currentRequest();
        synchronized (operations) {
            if (!recovery.isWanted(request)) {
                return new ConnectAttempt(Outcome.CANCELLED, null);
            }
            Picked picked = FxExecutor.get(() -> new Picked(
                    List.copyOf(configStore.getServers()), configStore.isPickedByTheStore()));
            List<ServerConfig> candidates = picked.candidates();
            ServerConfig active = candidates.stream().filter(ServerConfig::isActive)
                    .findFirst().orElse(null);
            if (active == null) {
                return new ConnectAttempt(Outcome.NO_ACTIVE_SERVER, null);
            }
            Run current = run;
            if (!isRunning() || current == null) {
                return new ConnectAttempt(Outcome.NOT_RUNNING, active);
            }
            AppSettings settings = configStore.getSettings();
            // A pick changes the server, never the mode: it restarts in the
            // mode the core runs in, as recovery does. It restarted in the
            // saved mode, so a mode changed while connected took effect at the
            // next pick: an agent's set_proxy_mode to tun and a select_server
            // started TUN without the confirm a connect asks for, and a pick
            // in the list or the tray raised the administrator prompt, or
            // turned TUN into a system proxy that apps ignoring it go around.
            ProxyMode mode = current.mode();
            LiveSelector live = current.selector();
            String generated = generatedNow(candidates, active, inMode(settings, mode),
                    current.leftOut(), current.host());
            if (active.getId().equals(appliedServerId) && live.matches(generated)) {
                // The core runs the pick already: a start that read it, the
                // recovery restart this pick waited for among them.
                return new ConnectAttempt(Outcome.SWITCHED, active);
            }
            if (live.keepsAnyPick(generated)) {
                // The automatic selection holds every server whichever is
                // picked, so the pick changes nothing the core runs. It was
                // restarted all the same: every connection dropped, the TUN
                // device went down and came back, and on Windows each click
                // raised a UAC prompt.
                if (!recovery.isWanted(request)) {
                    return new ConnectAttempt(Outcome.CANCELLED, active);
                }
                recovery.keptUp(request);
                appliedServerId = active.getId();
                return new ConnectAttempt(Outcome.SWITCHED, active);
            }
            if (live.accepts(generated) && live.select(OutboundTags.server(active))) {
                // The traffic leaves through another server now, and the
                // countdown and backoff recovery had were the old one's.
                long moved = recovery.startOver(request);
                if (moved < 0) {
                    return new ConnectAttempt(Outcome.CANCELLED, active);
                }
                // The core kept running, so no state change will tell recovery.
                recovery.keptUp(moved);
                appliedServerId = active.getId();
                // Nor tell the engine that its log so far was another server's.
                engine.forgetRealityRefusal();
                return new ConnectAttempt(Outcome.SWITCHED, active);
            }
            // A server the core refuses was found refused only by the start
            // after the stop: the working tunnel went down for a pick that
            // could not run, and the refusal was known before it.
            ConfigRejectedException refused = refusalOfPick(active, current);
            if (picked.byTheStore()) {
                // A refresh that withdrew the picked server, or an agent's
                // delete, stopped the working core for a launch whose prompt
                // came up with nobody asking, and a dismissal left the VPN off.
                // The user's own delete is the user's pick (ConfigStore.Removal).
                // Recovery leaves such a restart to the user; so does this,
                // and so for a server picked in its place the core refuses.
                if (refused != null) {
                    log.info("The picked server was withdrawn, and the core cannot run '{}', "
                            + "picked in its place; the tunnel stays as it is", active.getName());
                    return new ConnectAttempt(Outcome.DEFERRED, active);
                }
                if (mode == ProxyMode.TUN && engine.restartNeedsElevationPrompt()) {
                    log.info("The picked server was withdrawn; the tunnel stays on the server "
                            + "it runs until a reconnect, which asks for administrator rights");
                    return new ConnectAttempt(Outcome.DEFERRED, active);
                }
            }
            if (refused != null) {
                putThePickBack(appliedServerId, active);
                throw refused;
            }
            // As for a live switch: the countdown of the server the restart
            // leaves would restart the one it brings up once more.
            long moved = recovery.startOver(request);
            if (moved < 0) {
                return new ConnectAttempt(Outcome.CANCELLED, active);
            }
            return restart(mode, () -> recovery.isWanted(moved), moved);
        }
    }

    /**
     * Why the running core cannot take {@code server} as a pick, as far as
     * that is known before anything is stopped for it: select_server said a
     * pick of a server the core refuses was made, while the switch it set
     * off stopped the tunnel and then failed.
     *
     * @param server the server a pick would make the active one
     * @return the refusal, naming the server, or empty when nothing says the
     *     core would refuse it or no core runs
     */
    public Optional<String> pickRefusal(ServerConfig server) {
        Run current = run;
        if (!isRunning() || current == null) {
            return Optional.empty();
        }
        ConfigRejectedException refused = refusalOfPick(server, current);
        return refused == null ? Optional.empty() : Optional.of(refused.getMessage());
    }

    /**
     * The refusal a start would meet for a pick of {@code server}, as far as
     * it is known without one: what {@link CoreSettings#refusal} finds, or
     * what the core's check said of the server as {@code current} started,
     * while it is as it was then.
     */
    private static ConfigRejectedException refusalOfPick(ServerConfig server, Run current) {
        String reason = CoreSettings.refusal(server).map(CoreSettings.Refusal::reason)
                .orElse(null);
        Refused atCheck = current.refused().get(server.getId());
        if (reason == null && atCheck != null && atCheck.content().equals(contentOf(server))) {
            reason = atCheck.reason();
        }
        return reason == null ? null : new ConfigRejectedException(
                I18n.get("engine.config.rejected.server", server.getName(), reason), reason);
    }

    /**
     * Puts the pick back on the server the core runs, after a pick it
     * refuses. Left on the refused one, the list and the tray named a server
     * the tunnel did not run, each later change of the list asked for the
     * same switch and its refusal again, and the next Connect failed on it.
     * Left as it is when the server the core runs has left the list, or the
     * pick moved on meanwhile.
     */
    private void putThePickBack(String running, ServerConfig pick) {
        if (running == null) {
            return;
        }
        String pickId = pick.getId();
        FxExecutor.run(() -> {
            boolean stillPicked = configStore.getServers().stream()
                    .anyMatch(server -> server.isActive() && server.getId().equals(pickId));
            boolean runningListed = configStore.getServers().stream()
                    .anyMatch(server -> server.getId().equals(running));
            if (stillPicked && runningListed) {
                configStore.setActiveServer(running);
            }
        });
    }

    /**
     * Whether the running core was built from the settings, rules and servers
     * as they are now, but for which server is picked, which switches live.
     * A change of DNS, ports, TUN options, a routing rule or the mode applies
     * only at the next start, and nothing said so.
     *
     * <p>Compared with what a reconnect from the Dashboard would start: the
     * saved mode, and the chosen ports as well as the configuration, since a
     * port the run moved stands in for the chosen one until the run ends.
     * A REALITY server's short ID and server name do not count: its panel
     * picks them anew each time a subscription is refreshed, and the ones the
     * core runs with work as well. An hourly refresh put up the reconnect
     * banner most hours, though nothing had changed.</p>
     *
     * @return true when no core runs, or when a restart would run as the
     *     core does
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
                && current.selector().runsAs(generatedNow(
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
            // Restarted as it ran: the saved mode applies at the next start
            // the user makes, which is what the Dashboard and MCP promise.
            ProxyMode mode = restartMode;
            return restart(mode != null ? mode : requestedMode, allowed, NOT_A_PICK).started();
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
     *
     * <p>The health checks' inbound gets its port here too, one none of the
     * others is about to take. Nobody chose it, so it is no move to report,
     * and the system's proxy never points at it, so {@link SessionPorts} does
     * not record it.</p>
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
        settings.listenOn(listen[0], listen[1], listen[2], freeProbePort(reserved));
        List<MovedPort> moved = new ArrayList<>();
        for (int i = 0; i < chosen.length; i++) {
            if (listen[i] != chosen[i]) {
                moved.add(new MovedPort(what[i], chosen[i], listen[i]));
            }
        }
        return List.copyOf(moved);
    }

    /**
     * A loopback port for the health checks' inbound: one the system has
     * free, and none of {@code reserved}, which the other inbounds are about
     * to take.
     *
     * <p>The system's pick rather than a walk up from a fixed port, as the
     * chosen ports take: nothing dials this one but the app, and a walk can
     * end in a range the system keeps for itself (Hyper-V's, on Windows),
     * where a port nobody chose would stop the core from starting.</p>
     *
     * @param reserved the ports this run's other inbounds listen on
     * @return the port, or 0 when the system gives none, which leaves the
     *     pick to the core and the checks without a way in
     */
    static int freeProbePort(Set<Integer> reserved) {
        for (int attempt = 0; attempt < 8; attempt++) {
            try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                if (!reserved.contains(probe.getLocalPort())) {
                    return probe.getLocalPort();
                }
            } catch (IOException e) {
                log.warn("No free loopback port for the health checks: {}", e.getMessage());
                return 0;
            }
        }
        return 0;
    }

    /**
     * Keeps this run's HTTP port for the next start when the core points the
     * system's proxy at it: a run that dies leaves the proxy there, and the
     * next start clears it. A run that does not set the proxy leaves nothing
     * to clear, and a start with nothing recorded skips the check.
     */
    private void recordSessionHttpPort(AppSettings settings, ProxyMode mode) {
        if (mode == ProxyMode.SYSTEM_PROXY
                && settings.isSystemProxyAutoConfig()) {
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
