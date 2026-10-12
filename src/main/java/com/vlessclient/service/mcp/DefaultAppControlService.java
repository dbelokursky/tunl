package com.vlessclient.service.mcp;

import com.vlessclient.app.I18n;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.RoutingRule;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.Subscription;
import com.vlessclient.model.TunnelHealth;
import com.vlessclient.model.TunnelStatus;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ConnectionService;
import com.vlessclient.service.FxExecutor;
import com.vlessclient.service.LatencyTester;
import com.vlessclient.service.PersistenceState;
import com.vlessclient.service.ProxyGroupMonitor;
import com.vlessclient.service.Redact;
import com.vlessclient.service.RoutingService;
import com.vlessclient.service.ShareLinkParser;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.SubscriptionService;
import com.vlessclient.service.TrafficHistoryStore;
import com.vlessclient.service.TrafficMonitor;
import com.vlessclient.service.TunnelHealthState;
import com.vlessclient.service.outbound.OutboundTags;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;

/**
 * Production {@link AppControlService} backed by the real application services.
 *
 * <p>Dependencies are injected rather than pulled from the global
 * {@code ServiceLocator} so the class stays unit-testable and so a freshly
 * downloaded {@code SingBoxEngine} can be swapped in without rebuilding the MCP
 * layer (see {@link #setEngine}).</p>
 *
 * <p>Reads that touch JavaFX observable state (connection state, log buffer,
 * traffic counters, server list) are marshalled onto the FX thread via
 * {@link FxExecutor} to avoid torn reads and concurrent modification.</p>
 */
public final class DefaultAppControlService implements AppControlService {

    /** The shortest measure_latency waits, which one slow server can use up. */
    private static final Duration LATENCY_MIN_DEADLINE = Duration.ofSeconds(15);

    /**
     * What each wave of servers adds to the wait: the tester measures
     * {@link LatencyTester#MAX_CONCURRENT_TESTS} at a time, and one probe takes
     * up to 7 s through the core, or 5 s for a TCP connect.
     */
    private static final Duration LATENCY_PER_WAVE = Duration.ofSeconds(8);

    /** The longest it waits, however long the list: the agent's call has a timeout too. */
    private static final Duration LATENCY_MAX_DEADLINE = Duration.ofSeconds(60);

    private final ConfigStore configStore;
    private final TrafficMonitor trafficMonitor;
    private final SubscriptionService subscriptionService;
    private final RoutingService routingService;
    private final ConnectionService connectionService;
    private final LatencyTester latencyTester;
    private final ShareLinkParser shareLinkParser;
    private final SingBoxEngine engine;
    private final TunnelHealthState healthState;
    private final ProxyGroupMonitor groupMonitor;
    /** A test's deadline for measure_latency in place of {@link #latencyDeadline}. */
    private volatile Duration latencyDeadlineOverride;

    /**
     * Creates the service wired to the concrete application services.
     *
     * @param configStore the configuration store
     * @param trafficMonitor the live traffic monitor
     * @param subscriptionService the subscription service
     * @param routingService the routing service
     * @param connectionService the owner of the connect/disconnect flow
     * @param latencyTester the latency tester
     * @param shareLinkParser the share-link parser
     * @param engine the sing-box engine, which may have no core yet
     */
    public DefaultAppControlService(ConfigStore configStore,
                                    TrafficMonitor trafficMonitor,
                                    SubscriptionService subscriptionService,
                                    RoutingService routingService,
                                    ConnectionService connectionService,
                                    LatencyTester latencyTester,
                                    ShareLinkParser shareLinkParser,
                                    SingBoxEngine engine) {
        this(configStore, trafficMonitor, subscriptionService, routingService,
                connectionService, latencyTester, shareLinkParser, engine, null, null);
    }

    /** Creates the facade with the shared health verdict and the core's reported server pick. */
    public DefaultAppControlService(ConfigStore configStore,
                                    TrafficMonitor trafficMonitor,
                                    SubscriptionService subscriptionService,
                                    RoutingService routingService,
                                    ConnectionService connectionService,
                                    LatencyTester latencyTester,
                                    ShareLinkParser shareLinkParser,
                                    SingBoxEngine engine,
                                    TunnelHealthState healthState,
                                    ProxyGroupMonitor groupMonitor) {
        this.healthState = healthState;
        this.groupMonitor = groupMonitor;
        this.configStore = configStore;
        this.trafficMonitor = trafficMonitor;
        this.subscriptionService = subscriptionService;
        this.routingService = routingService;
        this.connectionService = connectionService;
        this.latencyTester = latencyTester;
        this.shareLinkParser = shareLinkParser;
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public String retrySaving() throws McpToolException {
        PersistenceState state = configStore.getPersistenceState();
        state.retry();
        reportUnsaved(toolFiles(state.failedFiles()), toolFiles(state.heldReasons().keySet()));
        return I18n.get("persistence.saved");
    }

    /**
     * Reports a change that lives in memory only, for the files the tool
     * wrote. Only those: every tool was blamed for every file, so a
     * routing.json that could not be opened at startup, or one failed save
     * of subscriptions.json, failed set_setting and add_server although what
     * they wrote was saved, and refused connect before it was tried.
     *
     * @param written the files the tool's change went to
     */
    private void ensureSaved(String... written) throws McpToolException {
        List<String> files = List.of(written);
        PersistenceState state = configStore.getPersistenceState();
        reportUnsaved(state.failedFiles().stream().filter(files::contains).toList(),
                state.heldReasons().keySet().stream().filter(files::contains).toList());
    }

    private static void reportUnsaved(List<String> failed, List<String> held)
            throws McpToolException {
        if (!failed.isEmpty()) {
            throw new McpToolException(
                    I18n.get("persistence.mcp.failed", String.join(", ", failed)));
        }
        // A file that could not be opened at startup is never saved over, so
        // a change to it lives only as long as this run: say so, as for a
        // failed save, rather than report it done.
        if (!held.isEmpty()) {
            throw new McpToolException(
                    I18n.get("persistence.mcp.held", String.join(", ", held)));
        }
    }

    /**
     * The files retry_saving reports on: all but the traffic history, which
     * no tool writes. Its failures are the window's to show; blaming them on
     * an added server told the agent a change that was saved had not been.
     */
    private static List<String> toolFiles(java.util.Collection<String> files) {
        return files.stream()
                .filter(file -> !TrafficHistoryStore.HISTORY_FILE.equals(file))
                .toList();
    }

    @Override
    public StatusInfo getStatus() {
        // One FX snapshot: the core state, verdict and routed member must not
        // come from different moments of a reconnect.
        return FxExecutor.get(() -> {
            AppSettings settings = configStore.getSettings();
            ConnectionState state = engine.connectionStateProperty().get();
            // The core's own line: it says why exactly, and in the same words
            // on every machine; the window's sentence is in the UI's language.
            String error = engine.errorDetailProperty().get();
            if (error == null || error.isBlank()) {
                error = engine.errorMessageProperty().get();
            }
            ServerConfig active = configStore.getServers().stream()
                    .filter(ServerConfig::isActive).findFirst().orElse(null);
            TunnelHealth health = state == ConnectionState.CONNECTED && healthState != null
                    ? healthState.get() : TunnelHealth.UNMONITORED;
            String tag = state == ConnectionState.CONNECTED && groupMonitor != null
                    ? groupMonitor.currentMemberTagProperty().get() : null;
            ServerConfig routed = tag == null ? null : configStore.getServers().stream()
                    .filter(server -> tag.equals(OutboundTags.server(server)))
                    .findFirst().orElse(null);
            // The settings' mode is what the next start uses; a mode changed
            // there does not change the running core's, and get_status said
            // the new one as if it were running.
            String runningMode = connectionService == null ? null
                    : connectionService.runningMode().map(ProxyMode::getValue).orElse(null);
            return new StatusInfo(
                    state.name(), state == ConnectionState.CONNECTED,
                    active != null ? active.getId() : null,
                    active != null ? active.getName() : null,
                    settings.getProxyMode().getValue(), settings.listenSocksPort(),
                    settings.listenHttpPort(), settings.listenClashApiPort(),
                    error != null ? error : "", health.name(),
                    TunnelStatus.of(state, health).name(),
                    routed != null ? routed.getId() : null,
                    routed != null ? routed.getName() : null,
                    runningMode);
        });
    }

    @Override
    public TrafficInfo getTraffic() {
        long up = FxExecutor.get(() -> trafficMonitor.uploadSpeedProperty().get());
        long down = FxExecutor.get(() -> trafficMonitor.downloadSpeedProperty().get());
        long totalUp = FxExecutor.get(() -> trafficMonitor.totalUploadProperty().get());
        long totalDown = FxExecutor.get(() -> trafficMonitor.totalDownloadProperty().get());
        return new TrafficInfo(
                up, down, totalUp, totalDown,
                TrafficMonitor.formatSpeed(up),
                TrafficMonitor.formatSpeed(down),
                TrafficMonitor.formatBytes(totalUp),
                TrafficMonitor.formatBytes(totalDown));
    }

    @Override
    public List<ServerSummary> listServers() {
        return FxExecutor.get(() -> configStore.getServers().stream()
                .map(s -> new ServerSummary(
                        s.getId(), s.getName(),
                        s.getProtocol() != null ? s.getProtocol().getValue() : null,
                        s.getAddress(), s.getPort(), s.isActive()))
                .toList());
    }

    @Override
    public List<String> getLogs(int limit, String filter) {
        List<String> snapshot = FxExecutor.get(() -> new ArrayList<>(engine.getLogLines()));
        if (filter != null && !filter.isBlank()) {
            // Locale.ROOT: under a Turkish locale "INFO" lowers to "ınfo", with
            // a dotless i, and the filter "info" matched none of the core's
            // INFO lines.
            String needle = filter.toLowerCase(Locale.ROOT);
            snapshot = snapshot.stream()
                    .filter(line -> line.toLowerCase(Locale.ROOT).contains(needle))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }
        int effectiveLimit = limit > 0 ? limit : snapshot.size();
        if (snapshot.size() > effectiveLimit) {
            snapshot = new ArrayList<>(
                    snapshot.subList(snapshot.size() - effectiveLimit, snapshot.size()));
        }
        // Same treatment as the diagnostics bundle and the SSE stream: a line
        // can quote a subscription URL, and the token in it is not something
        // every holder of the MCP token should get.
        snapshot.replaceAll(Redact::urlsIn);
        return snapshot;
    }

    @Override
    public List<SubscriptionSummary> listSubscriptions() {
        return FxExecutor.get(() -> subscriptionService.getSubscriptions().stream()
                .map(this::toSummary)
                .toList());
    }

    private SubscriptionSummary toSummary(Subscription sub) {
        int count = sub.getServerIds() != null ? sub.getServerIds().size() : 0;
        // Scheme and host only, as the Subscriptions tab shows it: the path or
        // query holds the account token, which get_logs and the event stream
        // already keep from agents, and this read tool works with changes off.
        return new SubscriptionSummary(sub.getId(), sub.getName(), Redact.url(sub.getUrl()),
                count, sub.getLastRefreshedAt());
    }

    @Override
    public RoutingInfo getRouting() {
        RoutingConfig config = routingService.getConfig();
        List<RoutingInfo.RuleInfo> rules = new ArrayList<>();
        if (config.getRules() != null) {
            config.getRules().forEach(r -> rules.add(new RoutingInfo.RuleInfo(
                    r.getId(),
                    r.getType() != null ? r.getType().getValue() : null,
                    r.getValue(),
                    r.getAction() != null ? r.getAction().getValue() : null)));
        }
        List<String> bypass = config.getBypassList() != null
                ? new ArrayList<>(config.getBypassList()) : List.of();
        return new RoutingInfo(config.getLegacyPreset(), bypass, rules);
    }

    @Override
    public SettingsInfo getSettings() {
        AppSettings s = configStore.getSettings();
        return new SettingsInfo(
                s.getTheme(), s.getLanguage(), s.isAutoConnect(),
                s.getProxyMode().getValue(),
                s.getSocksPort(), s.getHttpPort(), s.getClashApiPort(),
                dnsForAgents(s.getProxyDns()), dnsForAgents(s.getDirectDns()),
                s.getDnsStrategy(),
                s.getTunInterfaceName(), s.getCoreLogLevel().getValue(),
                s.isHealthCheckEnabled(),
                s.isMcpEnabled(), s.getMcpPort(), s.isMcpAllowMutations(),
                s.getServerSelection().getValue(), s.isSystemProxyAutoConfig(),
                s.getTunIpv4Address(), s.isTunIpv6Enabled());
    }

    /**
     * A resolver's address as an agent sees it. A private DNS-over-HTTPS
     * resolver keeps the account in its path ({@code https://dns.nextdns.io/ab12cd}),
     * which the diagnostics bundle redacts; get_settings, which works with
     * changes off, returned it whole. A bare address or {@code system} names
     * no account and is shown as it is.
     */
    private static String dnsForAgents(String address) {
        return address == null || (address.indexOf('/') < 0 && address.indexOf('@') < 0)
                ? address : Redact.url(address);
    }

    @Override
    public StatusInfo connect(String serverId, String mode, boolean confirm)
            throws McpToolException {
        if (!engine.hasBinary()) {
            throw new McpToolException("sing-box binary is not available; cannot connect.");
        }
        boolean serverGiven = serverId != null && !serverId.isBlank();
        boolean modeGiven = mode != null && !mode.isBlank();
        AppSettings settings = configStore.getSettings();
        ProxyMode effectiveMode = modeGiven ? parseMode(mode) : settings.getProxyMode();

        // A running tunnel is answered before anything changes. The pick and
        // the mode used to be saved first and the start refused after, as
        // "already running": the Dashboard meanwhile switched the live tunnel
        // to the saved pick, and a mode alone was saved for a core that went
        // on in the old one.
        Optional<ProxyMode> running = runningMode();
        if (running.isPresent()) {
            return alreadyConnected(serverGiven ? serverId : null,
                    modeGiven ? effectiveMode : null, running.get());
        }

        // Gate the proxy mode before doing any work: TUN triggers a macOS
        // admin password prompt and captures all traffic, so it needs an
        // explicit confirm.
        if (effectiveMode == ProxyMode.TUN && !confirm) {
            throw new McpToolException("Connecting in TUN mode shows a macOS admin password "
                    + "prompt and routes all traffic. Pass confirm:true to proceed.");
        }

        // Neither the pick nor the mode is checked for being saved, before or
        // after: this tool reports the tunnel, and a save that failed, or a
        // file no save may write until a restart, kept the tunnel down while
        // the Dashboard connected at once. The window's banner names what is
        // unsaved, and retry_saving reports it to an agent.
        if (serverGiven) {
            activate(serverId);
        }

        if (modeGiven && effectiveMode != settings.getProxyMode()) {
            // On the FX thread, as set_proxy_mode does: this runs on an HTTP
            // worker, and the dashboard reads the same instance from FX.
            FxExecutor.run(() -> {
                AppSettings live = configStore.getSettings();
                live.setProxyMode(effectiveMode);
                configStore.saveSettings(live);
            });
        }

        // The connect flow itself belongs to ConnectionService — including
        // passing every candidate to the generator, which is what keeps
        // automatic selection working on this path.
        ConnectionService.ConnectAttempt attempt;
        try {
            attempt = connectionService.connect(effectiveMode);
        } catch (IOException e) {
            throw new McpToolException("Failed to start sing-box: " + e.getMessage(), e);
        }
        if (attempt.outcome() == ConnectionService.Outcome.ALREADY_RUNNING
                && !serverGiven && !modeGiven && runningMode().isPresent()) {
            // Another request started a core since the check above: the
            // tunnel the agent asked for is up, as it would have been found.
            // Only while one does run: a refusal with none running, as for a
            // core still closing from a stop, was reported as a connect.
            return getStatus();
        }
        if (!attempt.started()) {
            // Anything but a start is an error for a tool call — including a
            // future outcome this facade has not been taught to report, which
            // must not read as a silent "connected".
            String reason = switch (attempt.outcome()) {
                case NO_CORE -> "sing-box binary is not available; cannot connect.";
                case NO_ACTIVE_SERVER ->
                        "No active server. Pass serverId or select one with select_server.";
                case ALREADY_RUNNING -> "sing-box is already running.";
                case CANCELLED -> "The connect was cancelled: the user dismissed the "
                        + "administrator prompt, or a newer request superseded it.";
                default -> "Unexpected connect outcome: " + attempt.outcome();
            };
            throw new McpToolException(reason);
        }
        return getStatus();
    }

    /**
     * The running core's mode, or empty when none runs. A core being stopped
     * counts as none: a connect waits for it to go and starts another.
     */
    private Optional<ProxyMode> runningMode() {
        if (connectionService == null || engine.isStopping()) {
            return Optional.empty();
        }
        return connectionService.runningMode();
    }

    /**
     * A connect while a core runs: the tunnel as it is when the agent asked
     * for nothing else, and otherwise a refusal that changes nothing and says
     * how to get what it asked for. A server is switched by select_server,
     * which a running tunnel follows; a mode only by a new start.
     */
    private StatusInfo alreadyConnected(String serverId, ProxyMode mode, ProxyMode runningMode)
            throws McpToolException {
        ServerConfig wanted = serverId == null ? null : configStore.getServerById(serverId)
                .orElseThrow(() -> new McpToolException("No server with id: " + serverId));
        if (mode != null && mode != runningMode) {
            throw new McpToolException("A tunnel is already running in "
                    + runningMode.getValue() + " mode, and nothing was changed. A new mode "
                    + "needs a new start: call disconnect, then connect with mode '"
                    + mode.getValue() + "'.");
        }
        if (wanted != null && !wanted.isActive()) {
            throw new McpToolException("A tunnel is already running, and nothing was changed. "
                    + "To move it to '" + wanted.getName() + "', call select_server: the "
                    + "running tunnel switches to the server it selects.");
        }
        return getStatus();
    }

    @Override
    public StatusInfo disconnect() {
        connectionService.disconnect();
        return getStatus();
    }

    @Override
    public ServerSummary selectServer(String serverId) throws McpToolException {
        ServerConfig s = activate(serverId);
        ensureSaved(ConfigStore.SERVERS_FILE);
        return new ServerSummary(s.getId(), s.getName(),
                s.getProtocol() != null ? s.getProtocol().getValue() : null,
                s.getAddress(), s.getPort(), true);
    }

    /**
     * Makes the server the active one. A connected tunnel switches to it,
     * as it does for a pick in the server list or the tray: the Dashboard
     * follows the list's active server.
     */
    private ServerConfig activate(String serverId) throws McpToolException {
        ServerConfig s = configStore.getServerById(serverId)
                .orElseThrow(() -> new McpToolException("No server with id: " + serverId));
        configStore.setActiveServer(serverId);
        return s;
    }

    @Override
    public List<LatencyResult> measureLatency(String serverId) throws McpToolException {
        List<ServerConfig> servers;
        if (serverId != null && !serverId.isBlank()) {
            ServerConfig one = configStore.getServerById(serverId)
                    .orElseThrow(() -> new McpToolException("No server with id: " + serverId));
            servers = List.of(one);
        } else {
            servers = new ArrayList<>(
                    FxExecutor.get(() -> new ArrayList<>(configStore.getServers())));
        }
        if (servers.isEmpty()) {
            throw new McpToolException("No servers configured to test.");
        }
        // One future per server, and a deadline that grows with the list. A
        // fixed 15 s for the whole list ran out at a few dozen blocked
        // servers, and the call failed with "Latency test failed: null", the
        // TimeoutException's message, losing the servers that had answered.
        List<CompletableFuture<LatencyTester.Result>> probes = servers.stream()
                .map(latencyTester::measure)
                .toList();
        Duration deadline = latencyDeadlineOverride != null
                ? latencyDeadlineOverride : latencyDeadline(servers.size());
        try {
            CompletableFuture.allOf(probes.toArray(new CompletableFuture<?>[0]))
                    .get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            // Told per server below: what came in, what failed, what did not.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            probes.forEach(probe -> probe.cancel(false));
            throw new McpToolException("Latency test interrupted.");
        }
        List<LatencyResult> out = new ArrayList<>();
        for (int i = 0; i < servers.size(); i++) {
            out.add(latencyOf(servers.get(i), probes.get(i)));
        }
        // A probe still waiting for the tester's ten threads is dropped from
        // their queue; left there, each call that ran out held up the next.
        probes.forEach(probe -> probe.cancel(false));
        return out;
    }

    private static LatencyResult latencyOf(ServerConfig s,
                                           CompletableFuture<LatencyTester.Result> probe) {
        if (!probe.isDone()) {
            return new LatencyResult(s.getId(), s.getName(), -1, LatencyResult.TIMED_OUT);
        }
        LatencyTester.Result r = probe.isCompletedExceptionally() ? null : probe.join();
        if (r == null || !r.measured()) {
            return new LatencyResult(s.getId(), s.getName(), -1, LatencyResult.NOT_MEASURED);
        }
        return r.reachable()
                ? new LatencyResult(s.getId(), s.getName(), r.millis(), LatencyResult.MEASURED)
                : new LatencyResult(s.getId(), s.getName(), -1, LatencyResult.UNREACHABLE);
    }

    /**
     * How long measure_latency waits for this many servers: a wave of probes
     * per {@link LatencyTester#MAX_CONCURRENT_TESTS} servers, within bounds.
     */
    static Duration latencyDeadline(int servers) {
        int waves = Math.ceilDiv(Math.max(servers, 1), LatencyTester.MAX_CONCURRENT_TESTS);
        Duration scaled = LATENCY_PER_WAVE.multipliedBy(waves);
        if (scaled.compareTo(LATENCY_MIN_DEADLINE) < 0) {
            return LATENCY_MIN_DEADLINE;
        }
        return scaled.compareTo(LATENCY_MAX_DEADLINE) > 0 ? LATENCY_MAX_DEADLINE : scaled;
    }

    /** Test seam: measure_latency waits {@code deadline} instead, whatever the list. */
    void setLatencyDeadline(Duration deadline) {
        this.latencyDeadlineOverride = deadline;
    }

    @Override
    public String refreshSubscription(String subscriptionId) throws McpToolException {
        Optional<Subscription> sub = subscription(subscriptionId);
        if (sub.isEmpty()) {
            throw new McpToolException("No subscription with id: " + subscriptionId);
        }
        subscriptionService.refreshSubscription(subscriptionId);
        ensureSaved(SubscriptionService.SUBSCRIPTIONS_FILE, ConfigStore.SERVERS_FILE);
        // The refresh has run by now, and records its failure on the
        // subscription rather than throwing: the tool said "Refresh triggered"
        // for a dead URL or an expired token alike.
        Subscription refreshed = subscription(subscriptionId).orElse(sub.get());
        if (refreshed.hasLastError()) {
            throw new McpToolException("Refreshing subscription '" + refreshed.getName()
                    + "' failed: " + SubscriptionService.failureText(refreshed));
        }
        return "Refreshed subscription '" + refreshed.getName() + "': "
                + refreshed.getServerIds().size() + " servers.";
    }

    /**
     * The subscription with this id, read on the FX thread as
     * {@link #listSubscriptions} reads it: the list is the FX thread's, and
     * streamed from this HTTP worker it could throw a
     * ConcurrentModificationException against an add there.
     */
    private Optional<Subscription> subscription(String id) {
        return FxExecutor.get(() -> subscriptionService.getSubscriptions().stream()
                .filter(s -> s.getId().equals(id)).findFirst());
    }

    @Override
    public ServerSummary addServer(String shareLink, String name) throws McpToolException {
        String cleanName = agentName(name);
        ServerConfig config = parseShareLink(shareLink);
        if (cleanName != null) {
            config.setName(cleanName);
        }
        config.setActive(false);
        configStore.addServer(config);
        ensureSaved(ConfigStore.SERVERS_FILE);
        return summaryOf(config);
    }

    @Override
    public ServerSummary updateServer(String id, String name, String shareLink)
            throws McpToolException {
        String cleanName = agentName(name);
        ServerConfig existing = configStore.getServerById(id)
                .orElseThrow(() -> new McpToolException("No server with id: " + id));
        ServerConfig updated;
        if (shareLink != null && !shareLink.isBlank()) {
            updated = parseShareLink(shareLink);
            updated.setId(existing.getId());
            updated.setActive(existing.isActive());
            // Kept as an edit in the server form keeps it: the subscription
            // the server came from. The fresh server from the link dropped
            // the stamp the subscription recovers its members by after a
            // crash between two saves. Fields a newer build wrote belong to
            // the link it replaces, and go, as on a subscription refresh.
            updated.setSubscriptionId(existing.getSubscriptionId());
            updated.setName(cleanName != null ? cleanName : existing.getName());
        } else {
            updated = existing;
            if (cleanName != null) {
                updated.setName(cleanName);
            }
        }
        configStore.updateServer(updated);
        ensureSaved(ConfigStore.SERVERS_FILE);
        return summaryOf(updated);
    }

    /**
     * A name an agent gives a server, cleaned as a link's own name is: it
     * went in as it came, and every connect logs the name, so a newline in
     * it forged a line in tunl.log, the file people attach to bug reports.
     *
     * @return the cleaned name, or null when none was given
     * @throws McpToolException when nothing printable is left of it
     */
    private static String agentName(String name) throws McpToolException {
        if (name == null || name.isBlank()) {
            return null;
        }
        String cleaned = ShareLinkParser.cleanName(name);
        if (cleaned.isEmpty()) {
            throw new McpToolException("The 'name' has nothing printable in it.");
        }
        return cleaned;
    }

    @Override
    public String deleteServer(String id, boolean confirm) throws McpToolException {
        if (!confirm) {
            throw new McpToolException("Deleting a server is irreversible; pass confirm:true.");
        }
        ServerConfig existing = configStore.getServerById(id)
                .orElseThrow(() -> new McpToolException("No server with id: " + id));
        configStore.removeServer(id);
        ensureSaved(ConfigStore.SERVERS_FILE);
        return "Deleted server '" + existing.getName() + "'.";
    }

    @Override
    public SettingsInfo setProxyMode(String mode) throws McpToolException {
        ProxyMode parsed = parseMode(mode);
        // Settings are mutated on the FX thread only, like the Settings view
        // does: this runs on an HTTP worker, and the dashboard reads the same
        // instance from the FX thread with nothing in between.
        FxExecutor.run(() -> {
            AppSettings settings = configStore.getSettings();
            settings.setProxyMode(parsed);
            configStore.saveSettings(settings);
        });
        ensureSaved(ConfigStore.SETTINGS_FILE);
        return getSettings();
    }

    @Override
    public SettingsInfo setSetting(String key, JsonNode value) throws McpToolException {
        if (key == null || value == null) {
            throw new McpToolException("Both 'key' and 'value' are required.");
        }
        McpToolException[] rejected = new McpToolException[1];
        FxExecutor.run(() -> {
            AppSettings s = configStore.getSettings();
            try {
                McpSettings.apply(s, key, value);
            } catch (McpToolException e) {
                rejected[0] = e;
                return;
            }
            configStore.saveSettings(s);
        });
        if (rejected[0] != null) {
            throw rejected[0];
        }
        ensureSaved(ConfigStore.SETTINGS_FILE);
        return getSettings();
    }

    @Override
    public RoutingInfo addRoutingRule(String type, String value, String action)
            throws McpToolException {
        if (value == null || value.isBlank()) {
            throw new McpToolException("Rule 'value' is required.");
        }
        RoutingRule rule = new RoutingRule(parseRuleType(type), value.strip(),
                parseRuleAction(action));
        try {
            routingService.addRule(rule);
        } catch (IllegalArgumentException refused) {
            throw new McpToolException(refused.getMessage());
        }
        ensureSaved(RoutingService.ROUTING_FILE);
        return getRouting();
    }

    @Override
    public RoutingInfo removeRoutingRule(String ruleId) throws McpToolException {
        boolean present = routingService.getConfig().getRules().stream()
                .anyMatch(r -> r.getId().equals(ruleId));
        if (!present) {
            throw new McpToolException("No routing rule with id: " + ruleId);
        }
        routingService.removeRule(ruleId);
        ensureSaved(RoutingService.ROUTING_FILE);
        return getRouting();
    }

    private ServerConfig parseShareLink(String shareLink) throws McpToolException {
        if (shareLink == null || shareLink.isBlank()) {
            throw new McpToolException("'shareLink' is required.");
        }
        try {
            ServerConfig config = shareLinkParser.parseForImport(shareLink.trim());
            if (config == null) {
                throw new McpToolException("Could not parse share link.");
            }
            return config;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpToolException("Invalid share link: " + e.getMessage(), e);
        }
    }

    private ServerSummary summaryOf(ServerConfig s) {
        return new ServerSummary(s.getId(), s.getName(),
                s.getProtocol() != null ? s.getProtocol().getValue() : null,
                s.getAddress(), s.getPort(), s.isActive());
    }




    private RoutingRule.RuleType parseRuleType(String type) throws McpToolException {
        if (type == null) {
            throw new McpToolException("Rule 'type' is required.");
        }
        for (RoutingRule.RuleType t : RoutingRule.RuleType.values()) {
            if (t.getValue().equalsIgnoreCase(type) || t.name().equalsIgnoreCase(type)) {
                return t;
            }
        }
        throw new McpToolException("Unknown rule type: " + type);
    }

    private RoutingRule.RuleAction parseRuleAction(String action) throws McpToolException {
        if (action == null) {
            throw new McpToolException("Rule 'action' is required.");
        }
        for (RoutingRule.RuleAction a : RoutingRule.RuleAction.values()) {
            if (a.getValue().equalsIgnoreCase(action) || a.name().equalsIgnoreCase(action)) {
                return a;
            }
        }
        throw new McpToolException("Unknown rule action: " + action + " (proxy|direct|block).");
    }

    private ProxyMode parseMode(String mode) throws McpToolException {
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case "tun" -> ProxyMode.TUN;
            case "system", "system_proxy" -> ProxyMode.SYSTEM_PROXY;
            default -> throw new McpToolException(
                    "Unknown mode '" + mode + "'. Use 'system_proxy' or 'tun'.");
        };
    }

}
