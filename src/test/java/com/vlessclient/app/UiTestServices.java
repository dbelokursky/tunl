package com.vlessclient.app;

import com.vlessclient.model.HealthCheckTarget;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.platform.SecretSealers;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ConnectionService;
import com.vlessclient.service.CountryResolver;
import com.vlessclient.service.GeoIpDatabase;
import com.vlessclient.service.LatencyTester;
import com.vlessclient.service.ProxyGroupMonitor;
import com.vlessclient.service.RoutingService;
import com.vlessclient.service.ServiceReachabilityChecker;
import com.vlessclient.service.ShareLinkParser;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.SingBoxInstaller;
import com.vlessclient.service.SubscriptionService;
import com.vlessclient.service.TrafficMonitor;
import com.vlessclient.service.UpdateManager;
import com.vlessclient.service.mcp.AppControlService;
import com.vlessclient.service.mcp.DefaultAppControlService;
import com.vlessclient.service.mcp.McpServerService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Builds the real UI service graph without granting it network access.
 *
 * <p>FXML controllers still receive the concrete service types used by the
 * application. Network entry points are replaced with deterministic doubles,
 * while {@link ServiceLocator.StartupMode#TEST} keeps background jobs and the
 * local MCP listener dormant.</p>
 */
public final class UiTestServices {

    private UiTestServices() {
    }

    /** Replaces any previous graph with a network-free graph for a UI test. */
    public static synchronized void initialize() {
        ServiceLocator.shutdown();
        ServiceLocator.initialize(ServiceLocator.StartupMode.TEST);

        ConfigStore configStore = ServiceLocator.get(ConfigStore.class);
        ShareLinkParser shareLinkParser = ServiceLocator.get(ShareLinkParser.class);

        NoNetworkGeoIpDatabase geoIp = new NoNetworkGeoIpDatabase();
        replace(GeoIpDatabase.class, geoIp, GeoIpDatabase::shutdown);
        ServiceLocator.register(CountryResolver.class, new NoNetworkCountryResolver(geoIp));

        NoNetworkTrafficMonitor trafficMonitor = new NoNetworkTrafficMonitor();
        replace(TrafficMonitor.class, trafficMonitor, TrafficMonitor::shutdown);
        replace(ProxyGroupMonitor.class, new NoNetworkProxyGroupMonitor(),
                ProxyGroupMonitor::shutdown);

        NoNetworkLatencyTester latencyTester = new NoNetworkLatencyTester();
        replace(LatencyTester.class, latencyTester, LatencyTester::shutdown);

        replace(ServiceReachabilityChecker.class, new NoNetworkReachabilityChecker(),
                ServiceReachabilityChecker::shutdown);

        NoNetworkSubscriptionService subscriptionService =
                new NoNetworkSubscriptionService(configStore, shareLinkParser);
        replace(SubscriptionService.class, subscriptionService, SubscriptionService::shutdown);

        replace(UpdateManager.class, new NoNetworkUpdateManager(), UpdateManager::shutdown);
        replace(SingBoxInstaller.class, new NoNetworkSingBoxInstaller(),
                SingBoxInstaller::shutdown);

        NoNetworkConnectionService connectionService = new NoNetworkConnectionService();
        ServiceLocator.register(ConnectionService.class, connectionService);
        RoutingService routingService = ServiceLocator.get(RoutingService.class);
        SingBoxEngine engine = ServiceLocator.get(SingBoxEngine.class);
        DefaultAppControlService control = new DefaultAppControlService(
                configStore, trafficMonitor, subscriptionService, routingService,
                connectionService, latencyTester, shareLinkParser, engine);
        ServiceLocator.register(AppControlService.class, control);
        ServiceLocator.register(McpServerService.class,
                new NoNetworkMcpServerService(configStore, control));
    }

    /**
     * Registers {@code replacement} in place of what the graph built, shutting
     * that down first. Replaced without it, every graph left its services'
     * HTTP clients open, each with a selector thread, a virtual one on this
     * JDK, that lives until the client is collected; on a Windows runner such
     * a thread holds a carrier while it waits, and after a hundred graphs none
     * was left for the work the UI hands off (six UI tests timed out at once).
     */
    private static <T> void replace(Class<T> type, T replacement, Consumer<T> shutdown) {
        ServiceLocator.find(type).ifPresent(original -> {
            shutdown.accept(original);
            Consumer<Object> keep = keepReplaced;
            if (keep != null) {
                keep.accept(original);
            }
        });
        ServiceLocator.register(type, replacement);
    }

    /**
     * Test seam: handed every service a double replaces, so a test can keep it
     * reachable the way the UI's controllers and listeners kept theirs, and see
     * that shutting it down, not collecting it, is what closes its clients.
     */
    static volatile Consumer<Object> keepReplaced;

    private static final class NoNetworkGeoIpDatabase extends GeoIpDatabase {

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public boolean ensureDownloaded() {
            return false;
        }

        @Override
        public Optional<String> lookup(String address) {
            return Optional.empty();
        }
    }

    private static final class NoNetworkCountryResolver extends CountryResolver {

        private NoNetworkCountryResolver(GeoIpDatabase database) {
            super(database);
        }

        @Override
        public void resolveAsync(ServerConfig server, Consumer<String> onResolved) {
            // Country badges are decoration; UI tests render them as unknown.
        }

        @Override
        public void warmUp() {
            // Never download the country database from a UI test.
        }
    }

    private static final class NoNetworkTrafficMonitor extends TrafficMonitor {

        @Override
        public void start(int clashApiPort, String secret) {
            // Leave observable counters at zero without opening the SSE stream.
        }
    }

    private static final class NoNetworkProxyGroupMonitor extends ProxyGroupMonitor {

        @Override
        public void start(int port, String secret, String groupTag) {
            // Never poll the loopback API from a UI test.
        }
    }

    private static final class NoNetworkLatencyTester extends LatencyTester {

        @Override
        public CompletableFuture<Result> measure(ServerConfig server) {
            return CompletableFuture.completedFuture(new Result(-1, false));
        }

        @Override
        public CompletableFuture<Map<String, Result>> testAll(List<ServerConfig> servers) {
            Map<String, Result> results = new LinkedHashMap<>();
            if (servers != null) {
                for (ServerConfig server : servers) {
                    if (server != null && server.getId() != null) {
                        results.put(server.getId(), new Result(-1, false));
                    }
                }
            }
            return CompletableFuture.completedFuture(results);
        }
    }

    private static final class NoNetworkReachabilityChecker
            extends ServiceReachabilityChecker {

        private NoNetworkReachabilityChecker() {
            super(NoNetworkReachabilityChecker::unreachable);
        }

        private static ProbeResult unreachable(HealthCheckTarget target, int httpProxyPort) {
            String url = target == null ? null : target.getUrl();
            String name = target == null || target.getName() == null
                    ? url
                    : target.getName();
            return new ProbeResult(name, url, false, -1, "network disabled in UI test");
        }
    }

    private static final class NoNetworkSubscriptionService extends SubscriptionService {

        private NoNetworkSubscriptionService(
                ConfigStore configStore, ShareLinkParser shareLinkParser) {
            // Never the platform sealer: a UI test must not reach the keychain.
            super(configStore, shareLinkParser, SecretSealers.disabled());
        }

        @Override
        public void refreshSubscription(String subscriptionId) {
            // Preserve the stored list; fetching is outside a UI test's scope.
        }

        @Override
        public void refreshAll() {
            // Preserve the stored list; fetching is outside a UI test's scope.
        }

        @Override
        public void startAutoRefresh() {
            // Never schedule subscription HTTP work from a UI test.
        }
    }

    private static final class NoNetworkUpdateManager extends UpdateManager {

        @Override
        public void startPeriodicCheck() {
            // Never schedule GitHub API calls from a UI test.
        }

        @Override
        public void checkAfterEvent() {
            // Connecting a test engine must not trigger a GitHub API call.
        }

        @Override
        public CheckResult checkForUpdates() {
            return CheckResult.UNREACHABLE;
        }

        @Override
        public void autoDownloadIfAllowed() {
            // No installer downloads in UI tests.
        }
    }

    private static final class NoNetworkSingBoxInstaller extends SingBoxInstaller {

        @Override
        public Path install(DoubleConsumer progress) throws IOException {
            throw new IOException("Network access is disabled in UI tests");
        }
    }

    private static final class NoNetworkConnectionService extends ConnectionService {

        private NoNetworkConnectionService() {
            // An engine of its own, which nothing starts: a binary a UI test
            // registers must never become executable here.
            super(null, null, null, SingBoxEngine.withoutCore());
        }

        @Override
        public boolean isRunning() {
            return false;
        }

        @Override
        public ConnectAttempt connect(com.vlessclient.model.ProxyMode modeOverride) {
            return new ConnectAttempt(Outcome.NO_CORE, null);
        }

        @Override
        public void disconnect() {
            // No process was started by this double.
        }

        @Override
        public ConnectAttempt reconnect(com.vlessclient.model.ProxyMode modeOverride) {
            return new ConnectAttempt(Outcome.NO_CORE, null);
        }

        @Override
        public ConnectAttempt switchToActiveServer() {
            return new ConnectAttempt(Outcome.NO_CORE, null);
        }
    }

    private static final class NoNetworkMcpServerService extends McpServerService {

        private NoNetworkMcpServerService(
                ConfigStore configStore, AppControlService control) {
            super(configStore, control);
        }

        @Override
        public synchronized void apply() {
            // Do not bind a loopback listener from a UI test.
        }

        @Override
        public synchronized boolean isRunning() {
            return false;
        }
    }
}
