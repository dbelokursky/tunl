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
        ServiceLocator.register(GeoIpDatabase.class, geoIp);
        ServiceLocator.register(CountryResolver.class, new NoNetworkCountryResolver(geoIp));

        NoNetworkTrafficMonitor trafficMonitor = new NoNetworkTrafficMonitor();
        ServiceLocator.register(TrafficMonitor.class, trafficMonitor);
        ServiceLocator.register(ProxyGroupMonitor.class, new NoNetworkProxyGroupMonitor());

        NoNetworkLatencyTester latencyTester = new NoNetworkLatencyTester();
        ServiceLocator.register(LatencyTester.class, latencyTester);

        ServiceLocator.register(ServiceReachabilityChecker.class,
                new NoNetworkReachabilityChecker());

        NoNetworkSubscriptionService subscriptionService =
                new NoNetworkSubscriptionService(configStore, shareLinkParser);
        ServiceLocator.register(SubscriptionService.class, subscriptionService);

        ServiceLocator.register(UpdateManager.class, new NoNetworkUpdateManager());
        ServiceLocator.register(SingBoxInstaller.class, new NoNetworkSingBoxInstaller());

        NoNetworkConnectionService connectionService = new NoNetworkConnectionService();
        ServiceLocator.register(ConnectionService.class, connectionService);
        RoutingService routingService = ServiceLocator.get(RoutingService.class);
        SingBoxEngine engine = optionalEngine();
        DefaultAppControlService control = new DefaultAppControlService(
                configStore, trafficMonitor, subscriptionService, routingService,
                connectionService, latencyTester, shareLinkParser, engine);
        ServiceLocator.register(AppControlService.class, control);
        ServiceLocator.register(McpServerService.class,
                new NoNetworkMcpServerService(configStore, control));
    }

    private static SingBoxEngine optionalEngine() {
        try {
            return ServiceLocator.get(SingBoxEngine.class);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

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
        public void start(int port, String secret) {
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
            super(null, null, null, null);
        }

        @Override
        public void setEngine(SingBoxEngine engine) {
            // A binary registered by a UI test must never become executable here.
        }

        @Override
        public boolean isRunning() {
            return false;
        }

        @Override
        public ConnectAttempt connect(com.vlessclient.model.ProxyMode modeOverride) {
            return new ConnectAttempt(Outcome.NO_ENGINE, null);
        }

        @Override
        public void disconnect() {
            // No process was started by this double.
        }

        @Override
        public ConnectAttempt reconnect(com.vlessclient.model.ProxyMode modeOverride) {
            return new ConnectAttempt(Outcome.NO_ENGINE, null);
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
