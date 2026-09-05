package com.vlessclient.app;

import com.vlessclient.model.AppSettings;
import com.vlessclient.platform.Autostart;
import com.vlessclient.platform.PlatformPaths;
import com.vlessclient.platform.SecretSealer;
import com.vlessclient.platform.SecretSealers;
import com.vlessclient.service.AppHttpClients;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ConnectionService;
import com.vlessclient.service.CountryResolver;
import com.vlessclient.service.DiagnosticsBundle;
import com.vlessclient.service.GeoIpDatabase;
import com.vlessclient.service.LatencyTester;
import com.vlessclient.service.ProxyGroupMonitor;
import com.vlessclient.service.RoutingService;
import com.vlessclient.service.ServerBackupService;
import com.vlessclient.service.ServiceReachabilityChecker;
import com.vlessclient.service.ShareLinkExporter;
import com.vlessclient.service.ShareLinkParser;
import com.vlessclient.service.SingBoxConfigGenerator;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.SingBoxInstaller;
import com.vlessclient.service.SubscriptionService;
import com.vlessclient.service.ThemeManager;
import com.vlessclient.service.TrafficMonitor;
import com.vlessclient.service.TrayIconService;
import com.vlessclient.service.TunnelHealthState;
import com.vlessclient.service.TunnelRecoveryService;
import com.vlessclient.service.UpdateManager;
import com.vlessclient.service.mcp.AppControlService;
import com.vlessclient.service.mcp.DefaultAppControlService;
import com.vlessclient.service.mcp.McpServerService;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
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

    /** Controls whether process-owned background services are activated. */
    enum StartupMode {
        APPLICATION,
        TEST
    }

    private ServiceLocator() {
    }

    /**
     * Creates and registers all service instances.
     */
    public static void initialize() {
        initialize(StartupMode.APPLICATION);
    }

    /**
     * Creates the application service graph, optionally leaving process-owned
     * background work dormant for headless UI tests.
     *
     * <p>The test graph deliberately contains the same service types as the
     * application graph so FXML controllers exercise their real wiring. Only
     * activation is suppressed here; test code can replace network-capable
     * services with deterministic doubles before loading a view.</p>
     */
    static void initialize(StartupMode mode) {
        Objects.requireNonNull(mode, "mode");
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

        // The test graph seals nothing: a headless UI test that adds a server
        // used to write through the developer's login Keychain (CLAUDE.md:
        // a test never touches the OS keychain).
        SecretSealer sealer = mode == StartupMode.TEST
                ? SecretSealers.disabled() : SecretSealers.forCurrentPlatform();
        ConfigStore configStore = new ConfigStore(PlatformPaths.current().dataDir(), sealer);
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
        register(GeoIpDatabase.class, geoIp);
        register(CountryResolver.class, countryResolver);

        ShareLinkExporter shareLinkExporter = new ShareLinkExporter();
        register(ShareLinkExporter.class, shareLinkExporter);

        TrafficMonitor trafficMonitor = new TrafficMonitor();
        register(TrafficMonitor.class, trafficMonitor);

        // Which member of the proxy group carries the traffic right now — the
        // only way the dashboard can name the server an automatic mode picked.
        register(ProxyGroupMonitor.class, new ProxyGroupMonitor());

        LatencyTester latencyTester = new LatencyTester();
        register(LatencyTester.class, latencyTester);

        ServiceReachabilityChecker reachabilityChecker = new ServiceReachabilityChecker();
        register(ServiceReachabilityChecker.class, reachabilityChecker);

        // Where that checker's verdict is published. The Dashboard's health
        // loop writes it; the tray icon reads it, which is the only way the
        // menu bar can tell "the core started" apart from "traffic gets out".
        register(TunnelHealthState.class, new TunnelHealthState());

        RoutingService routingService = new RoutingService();
        register(RoutingService.class, routingService);

        // Moving the server list off this machine, and assembling a bug
        // report from it. Both read the same store the views do, so they see
        // the list as the user last left it rather than as it was on disk.
        register(ServerBackupService.class,
                new ServerBackupService(configStore, shareLinkParser));
        register(DiagnosticsBundle.class,
                new DiagnosticsBundle(configStore, configGenerator, routingService));

        SubscriptionService subscriptionService =
                new SubscriptionService(configStore, shareLinkParser, sealer);
        register(SubscriptionService.class, subscriptionService);

        UpdateManager updateManager = new UpdateManager();
        register(UpdateManager.class, updateManager);

        register(Autostart.class, Autostart.current());

        // The one owner of the connect flow: the dashboard, the tray and the MCP
        // facade all drive the tunnel through it, so the three no longer carry
        // their own copies of resolve-generate-await-start.
        SingBoxEngine engine = (SingBoxEngine) services.get(SingBoxEngine.class);
        ConnectionService connectionService = new ConnectionService(
                configStore, configGenerator, routingService, engine);
        register(ConnectionService.class, connectionService);
        TunnelRecoveryService recovery = connectionService.getRecoveryService();
        register(TunnelRecoveryService.class, recovery);
        get(TunnelHealthState.class).healthProperty().addListener(
                (obs, old, health) -> recovery.onHealth(health));

        // MCP control server: a facade over the services above, plus the server
        // that exposes it to agents. Started here so `mcp_enabled` takes effect
        // at launch; re-reconciled whenever settings are saved.
        DefaultAppControlService control = new DefaultAppControlService(
                configStore, trafficMonitor, subscriptionService, routingService,
                connectionService, latencyTester, shareLinkParser, engine,
                get(TunnelHealthState.class), get(ProxyGroupMonitor.class));
        register(AppControlService.class, control);
        McpServerService mcpServerService = new McpServerService(configStore, control);
        register(McpServerService.class, mcpServerService);
        if (engine != null) {
            mcpServerService.attachLogSource(engine);
        }

        runStartupTasks(mode,
                () -> routeAppTrafficThroughTunnel(configStore),
                countryResolver::warmUp,
                subscriptionService::startAutoRefresh,
                updateManager::startPeriodicCheck,
                () -> attachUpdateCheckListener(engine, updateManager),
                mcpServerService::apply);

        log.info("ServiceLocator initialized in {} mode", mode);
    }

    /** Runs process-owned work only for the real application bootstrap. */
    static void runStartupTasks(StartupMode mode, Runnable... tasks) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(tasks, "tasks");
        if (mode == StartupMode.TEST) {
            log.info("Background services remain stopped for UI tests");
            return;
        }
        for (Runnable task : tasks) {
            Objects.requireNonNull(task, "task").run();
        }
    }

    /**
     * Sends the application's own HTTP requests through the tunnel whenever one
     * is carrying traffic.
     *
     * <p>Without this the updater, the subscription refresh, the country
     * database and the core release check all leave the machine directly, even
     * in SYSTEM_PROXY mode where sing-box has registered itself as the OS
     * proxy: {@code HttpClient} asks the JVM's default proxy selector, which
     * answers DIRECT for everything. On a network that blocks GitHub or the
     * subscription host — the reason this application exists — that made
     * "connected" and "the update check can succeed" two different moments.
     * TUN mode captured the JVM's traffic anyway, which is why the gap was
     * only ever visible in one of the two modes.</p>
     *
     * <p>The port is read per request, so the engine registered later by the
     * installer flow and a port changed in Settings are both picked up.</p>
     */
    private static void routeAppTrafficThroughTunnel(ConfigStore configStore) {
        AppHttpClients.followTunnel(
                () -> services.get(SingBoxEngine.class) instanceof SingBoxEngine engine
                        ? engine
                        : null,
                configStore::getSettings,
                get(TunnelHealthState.class));
    }

    private static void attachUpdateCheckListener(
            SingBoxEngine engine, UpdateManager updateManager) {
        if (engine == null) {
            return;
        }
        // A tunnel coming up is worth a check of its own. For a user whose
        // network throttles or blocks GitHub — the reason this app exists —
        // that is the moment a check can succeed at all, and no timer can know
        // it. UpdateManager throttles the trigger, so a flapping tunnel does
        // not turn into a flapping check.
        engine.connectionStateProperty().addListener((o, was, is) -> {
            if (is == com.vlessclient.model.ConnectionState.CONNECTED) {
                updateManager.checkAfterEvent();
            }
        });
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
     * Looks a service up without treating its absence as an error.
     *
     * <p>{@link #get} throws {@code IllegalArgumentException} for a missing
     * service, and the controllers wrapped every lookup in a catch of the
     * same type — the exception {@code ShareLinkParser} throws for bad input,
     * so a catch around a lookup and a parse could swallow the parse error.
     * Callers that can live without a service ask here instead.</p>
     *
     * @param type the service class
     * @param <T>  the service type
     * @return the registered instance, or empty when none is registered
     */
    @SuppressWarnings("unchecked")
    public static <T> Optional<T> find(Class<T> type) {
        return Optional.ofNullable((T) services.get(type));
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

        // Before anything else: a request started after this point must not be
        // handed the port of an engine that is about to stop.
        AppHttpClients.routeDirect();

        stopMcpServer();
        find(TunnelRecoveryService.class).ifPresent(TunnelRecoveryService::close);

        // Stop the engine early — right after MCP so no in-flight agent connect
        // survives it — and before the slow steps below. An in-flight
        // subscription refresh can hold stopAutoRefresh for its grace window,
        // and the Cmd+Q watchdog halts the JVM after 2s; halt() runs no
        // shutdown hooks, so a core still running at that point is orphaned,
        // keeping the SOCKS/HTTP ports and the OS proxy registration.
        try {
            Object engine = services.get(SingBoxEngine.class);
            if (engine instanceof SingBoxEngine singBoxEngine && singBoxEngine.isRunning()) {
                log.info("Stopping sing-box engine");
                singBoxEngine.stop();
            }
        } catch (Exception e) {
            log.error("Error stopping SingBoxEngine during shutdown", e);
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
                subscriptionService.shutdown();
            }
        } catch (Exception e) {
            log.error("Error stopping SubscriptionService during shutdown", e);
        }

        try {
            Object monitor = services.get(TrafficMonitor.class);
            if (monitor instanceof TrafficMonitor trafficMonitor) {
                trafficMonitor.shutdown();
            }
        } catch (Exception e) {
            log.error("Error stopping TrafficMonitor during shutdown", e);
        }

        try {
            Object groupMonitor = services.get(ProxyGroupMonitor.class);
            if (groupMonitor instanceof ProxyGroupMonitor monitor) {
                monitor.shutdown();
            }
        } catch (Exception e) {
            log.error("Error stopping ProxyGroupMonitor during shutdown", e);
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

        // Both were registered and never released: the geo database keeps a
        // memory-mapped file and the installer an HTTP client, one of each per
        // graph the UI test suite rebuilds.
        try {
            Object geo = services.get(GeoIpDatabase.class);
            if (geo instanceof GeoIpDatabase database) {
                database.shutdown();
            }
        } catch (Exception e) {
            log.error("Error closing GeoIpDatabase during shutdown", e);
        }

        try {
            Object installer = services.get(SingBoxInstaller.class);
            if (installer instanceof SingBoxInstaller singBoxInstaller) {
                singBoxInstaller.shutdown();
            }
        } catch (Exception e) {
            log.error("Error closing SingBoxInstaller during shutdown", e);
        }

        services.clear();
    }

    /**
     * Stops the process-owned MCP listener without changing the saved enable
     * preference. Safe to invoke from both the ordinary JavaFX lifecycle and
     * the JVM shutdown hook; {@link McpServerService#stop()} is idempotent.
     */
    public static void stopMcpServer() {
        try {
            Object mcp = services.get(McpServerService.class);
            if (mcp instanceof McpServerService mcpServerService) {
                mcpServerService.stop();
            }
        } catch (Exception e) {
            log.error("Error stopping MCP server during shutdown", e);
        }
    }

    /**
     * Registers (or re-registers) the SingBoxEngine after the binary has been
     * downloaded at startup. Called by the installer flow in VlessClientApp.
     */
    public static void registerSingBoxEngine(Path binaryPath) {
        singBoxPath = binaryPath.toString();
        SingBoxEngine engine = new SingBoxEngine(binaryPath);
        register(SingBoxEngine.class, engine);
        // Point the connect flow, the MCP control facade and the log bridge at
        // the fresh engine. Missing the first would leave every caller of
        // ConnectionService driving the engine that never had a binary.
        Object connection = services.get(ConnectionService.class);
        if (connection instanceof ConnectionService connectionService) {
            connectionService.setEngine(engine);
        }
        Object control = services.get(AppControlService.class);
        if (control instanceof DefaultAppControlService defaultControl) {
            defaultControl.setEngine(engine);
        }
        Object mcp = services.get(McpServerService.class);
        if (mcp instanceof McpServerService mcpServerService) {
            mcpServerService.attachLogSource(engine);
        }
        // The tray resolves the engine through a supplier, so its icon follows
        // the new one already — but a property listener is bound to one
        // instance, so it has to be moved across explicitly.
        Object tray = services.get(TrayIconService.class);
        if (tray instanceof TrayIconService trayIconService) {
            trayIconService.rebindEngineListener();
        }
        log.info("SingBoxEngine registered with binary: {}", singBoxPath);
    }
}
