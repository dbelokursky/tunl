package com.vlessclient.app;

import com.vlessclient.model.AppSettings;
import com.vlessclient.platform.Autostart;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.CountryResolver;
import com.vlessclient.service.GeoIpDatabase;
import com.vlessclient.service.LatencyTester;
import com.vlessclient.service.RoutingService;
import com.vlessclient.service.ServiceReachabilityChecker;
import com.vlessclient.service.ShareLinkExporter;
import com.vlessclient.service.ShareLinkParser;
import com.vlessclient.service.SingBoxConfigGenerator;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.SingBoxInstaller;
import com.vlessclient.service.SubscriptionService;
import com.vlessclient.service.ThemeManager;
import com.vlessclient.service.TrafficMonitor;
import com.vlessclient.service.UpdateManager;
import com.vlessclient.service.mcp.AppControlService;
import com.vlessclient.service.mcp.DefaultAppControlService;
import com.vlessclient.service.mcp.McpServerService;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple service locator providing manual dependency injection.
 * Holds singleton instances of core application services.
 */
public class ServiceLocator {

    private static final Logger log = LoggerFactory.getLogger(ServiceLocator.class);
    private static final Map<Class<?>, Object> services = new ConcurrentHashMap<>();
    private static String singBoxPath;

    private ServiceLocator() {
    }

    /**
     * Creates and registers all service instances.
     */
    public static void initialize() {
        SingBoxInstaller installer = new SingBoxInstaller();
        register(SingBoxInstaller.class, installer);

        // Before binary resolution: drop a cache left by a different app pin,
        // so the core this release ships takes over.
        installer.reconcileCacheWithPin();

        Optional<Path> existing = installer.findExisting();
        if (existing.isPresent()) {
            singBoxPath = existing.get().toString();
            log.info("sing-box binary path: {}", singBoxPath);
            register(SingBoxEngine.class, new SingBoxEngine(existing.get()));
        } else {
            singBoxPath = null;
            log.info("sing-box binary not found on disk; will be downloaded on startup");
        }

        ConfigStore configStore = new ConfigStore();
        register(ConfigStore.class, configStore);

        register(AppSettings.class, configStore.getSettings());

        ThemeManager themeManager = new ThemeManager();
        register(ThemeManager.class, themeManager);

        SingBoxConfigGenerator configGenerator = new SingBoxConfigGenerator();
        register(SingBoxConfigGenerator.class, configGenerator);

        ShareLinkParser shareLinkParser = new ShareLinkParser();
        register(ShareLinkParser.class, shareLinkParser);

        // Country flags: the database downloads in the background on first
        // run and every lookup is local, so a server list never leaves the
        // machine.
        GeoIpDatabase geoIp = new GeoIpDatabase();
        CountryResolver countryResolver = new CountryResolver(geoIp);
        countryResolver.warmUp();
        register(GeoIpDatabase.class, geoIp);
        register(CountryResolver.class, countryResolver);

        ShareLinkExporter shareLinkExporter = new ShareLinkExporter();
        register(ShareLinkExporter.class, shareLinkExporter);

        TrafficMonitor trafficMonitor = new TrafficMonitor();
        register(TrafficMonitor.class, trafficMonitor);

        LatencyTester latencyTester = new LatencyTester();
        register(LatencyTester.class, latencyTester);

        ServiceReachabilityChecker reachabilityChecker = new ServiceReachabilityChecker();
        register(ServiceReachabilityChecker.class, reachabilityChecker);

        RoutingService routingService = new RoutingService();
        register(RoutingService.class, routingService);

        SubscriptionService subscriptionService =
                new SubscriptionService(configStore, shareLinkParser);
        register(SubscriptionService.class, subscriptionService);
        subscriptionService.startAutoRefresh();

        UpdateManager updateManager = new UpdateManager();
        register(UpdateManager.class, updateManager);
        updateManager.startPeriodicCheck();

        register(Autostart.class, Autostart.current());

        // MCP control server: a facade over the services above, plus the server
        // that exposes it to agents. Started here so `mcp_enabled` takes effect
        // at launch; re-reconciled whenever settings are saved.
        SingBoxEngine engine = (SingBoxEngine) services.get(SingBoxEngine.class);
        DefaultAppControlService control = new DefaultAppControlService(
                configStore, trafficMonitor, subscriptionService, routingService,
                configGenerator, latencyTester, shareLinkParser, engine);
        register(AppControlService.class, control);
        McpServerService mcpServerService = new McpServerService(configStore, control);
        register(McpServerService.class, mcpServerService);
        if (engine != null) {
            mcpServerService.attachLogSource(engine);
        }
        mcpServerService.apply();

        log.info("ServiceLocator initialized");
    }

    /**
     * Retrieves a registered service instance.
     *
     * @param type the service class
     * @param <T>  the service type
     * @return the singleton instance
     * @throws IllegalArgumentException if no service of that type is registered
     */
    @SuppressWarnings("unchecked")
    public static <T> T get(Class<T> type) {
        Object service = services.get(type);
        if (service == null) {
            throw new IllegalArgumentException("No service registered for: " + type.getName());
        }
        return (T) service;
    }

    /**
     * Registers a service instance.
     */
    public static <T> void register(Class<T> type, T instance) {
        services.put(type, instance);
    }

    /**
     * Returns the resolved path to the sing-box binary, or null if not found.
     */
    public static String getSingBoxPath() {
        return singBoxPath;
    }

    /**
     * Cleans up resources on application shutdown.
     */
    public static void shutdown() {
        log.info("ServiceLocator shutting down");

        try {
            Object mcp = services.get(McpServerService.class);
            if (mcp instanceof McpServerService mcpServerService) {
                mcpServerService.stop();
            }
        } catch (Exception e) {
            log.error("Error stopping MCP server during shutdown", e);
        }

        try {
            Object updater = services.get(UpdateManager.class);
            if (updater instanceof UpdateManager updateManager) {
                updateManager.shutdown();
            }
        } catch (Exception e) {
            log.error("Error stopping UpdateManager during shutdown", e);
        }

        try {
            Object subService = services.get(SubscriptionService.class);
            if (subService instanceof SubscriptionService subscriptionService) {
                subscriptionService.stopAutoRefresh();
            }
        } catch (Exception e) {
            log.error("Error stopping SubscriptionService during shutdown", e);
        }

        try {
            Object monitor = services.get(TrafficMonitor.class);
            if (monitor instanceof TrafficMonitor trafficMonitor) {
                trafficMonitor.stop();
            }
        } catch (Exception e) {
            log.error("Error stopping TrafficMonitor during shutdown", e);
        }

        try {
            Object tester = services.get(LatencyTester.class);
            if (tester instanceof LatencyTester latencyTester) {
                latencyTester.shutdown();
            }
        } catch (Exception e) {
            log.error("Error stopping LatencyTester during shutdown", e);
        }

        try {
            Object checker = services.get(ServiceReachabilityChecker.class);
            if (checker instanceof ServiceReachabilityChecker reachabilityChecker) {
                reachabilityChecker.shutdown();
            }
        } catch (Exception e) {
            log.error("Error stopping ServiceReachabilityChecker during shutdown", e);
        }

        try {
            Object theme = services.get(ThemeManager.class);
            if (theme instanceof ThemeManager themeManager) {
                themeManager.stopWatching();
            }
        } catch (Exception e) {
            log.error("Error stopping ThemeManager during shutdown", e);
        }

        try {
            Object engine = services.get(SingBoxEngine.class);
            if (engine instanceof SingBoxEngine singBoxEngine && singBoxEngine.isRunning()) {
                log.info("Stopping sing-box engine");
                singBoxEngine.stop();
            }
        } catch (Exception e) {
            log.error("Error stopping SingBoxEngine during shutdown", e);
        }

        services.clear();
    }

    /**
     * Registers (or re-registers) the SingBoxEngine after the binary has been
     * downloaded at startup. Called by the installer flow in VlessClientApp.
     */
    public static void registerSingBoxEngine(Path binaryPath) {
        singBoxPath = binaryPath.toString();
        SingBoxEngine engine = new SingBoxEngine(binaryPath);
        register(SingBoxEngine.class, engine);
        // Point the MCP control facade and log bridge at the fresh engine.
        Object control = services.get(AppControlService.class);
        if (control instanceof DefaultAppControlService defaultControl) {
            defaultControl.setEngine(engine);
        }
        Object mcp = services.get(McpServerService.class);
        if (mcp instanceof McpServerService mcpServerService) {
            mcpServerService.attachLogSource(engine);
        }
        log.info("SingBoxEngine registered with binary: {}", singBoxPath);
    }
}
