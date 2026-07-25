package com.vlessclient.app;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.platform.Autostart;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.CoreUpdateService;
import com.vlessclient.service.SingBoxConfigGenerator;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.SingBoxInstaller;
import com.vlessclient.service.ThemeManager;
import com.vlessclient.service.TrayIconService;
import com.vlessclient.ui.view.MainViewController;
import com.vlessclient.ui.view.SingBoxInstallerDialog;
import java.awt.Desktop;
import java.awt.Taskbar;
import java.awt.Toolkit;
import java.awt.desktop.QuitStrategy;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JavaFX {@link Application} entry point that wires up services, installs the
 * macOS Dock icon and quit handler, ensures sing-box is available, and shows
 * the main window. Kept alive in the system tray after the window is closed.
 */
public class VlessClientApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(VlessClientApp.class);

    private TrayIconService trayIconService;

    @Override
    public void init() {
        // Set the macOS Dock icon early, before any stage is shown, so the
        // generic "exec" icon never flashes. Must happen on a thread with an
        // AWT toolkit available. Safe no-op on platforms without Taskbar
        // support or when the ICON_IMAGE feature is unavailable.
        setDockIcon();
        installQuitHandler();
        ServiceLocator.initialize();
        clearStaleSystemProxy();
        refreshLoginItem();
    }

    /**
     * Undoes an OS proxy a previous run left behind after a hard crash (no
     * shutdown hook could restore it). Runs here — on the launch thread,
     * before {@code start()} wires auto-connect — so it can never race a
     * fresh connect re-registering the same endpoint. Skipped outside
     * SYSTEM_PROXY mode, where no OS proxy is ever set.
     */
    private void clearStaleSystemProxy() {
        try {
            AppSettings settings = ServiceLocator.get(AppSettings.class);
            if (settings.getProxyMode() != ProxyMode.SYSTEM_PROXY) {
                return;
            }
            ServiceLocator.get(SingBoxEngine.class)
                    .clearStaleSystemProxyOnStartup("127.0.0.1", settings.getHttpPort());
        } catch (IllegalArgumentException e) {
            log.debug("Skipping stale-proxy cleanup; services unavailable");
        }
    }

    /**
     * Rewrites the macOS LaunchAgent (when "Launch at login" is enabled) so it
     * points at the current application location. Harmless no-op otherwise.
     * Kept out of {@link ServiceLocator#initialize()} so headless UI tests,
     * which call that method directly, never touch the real LaunchAgents dir.
     */
    private void refreshLoginItem() {
        try {
            ServiceLocator.get(Autostart.class).refresh();
        } catch (IllegalArgumentException e) {
            log.debug("Autostart not available");
        }
    }

    /**
     * Registers a Desktop quit handler so Cmd+Q and the macOS app menu's
     * "Quit" both actually terminate the JVM. Without this, JavaFX's glass
     * bridge handles {@code applicationShouldTerminate:} on its own and —
     * combined with {@code Platform.setImplicitExit(false)} — leaves the
     * process running with a stranded Dock icon.
     *
     * <p>Belt-and-braces: also register a JVM shutdown hook that calls
     * {@link Runtime#halt(int)} as a last-resort killer, in case another
     * non-daemon thread is holding shutdown up.</p>
     */
    private void installQuitHandler() {
        Runnable forceKill = () -> {
            log.info("Forcing JVM termination (Runtime.halt)");
            Runtime.getRuntime().halt(0);
        };

        try {
            if (!Desktop.isDesktopSupported()) {
                log.debug("Desktop API not supported");
                return;
            }
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.APP_SUDDEN_TERMINATION)) {
                desktop.enableSuddenTermination();
                log.debug("Enabled sudden termination");
            }
            if (desktop.isSupported(Desktop.Action.APP_QUIT_STRATEGY)) {
                desktop.setQuitStrategy(QuitStrategy.CLOSE_ALL_WINDOWS);
                log.debug("Set quit strategy CLOSE_ALL_WINDOWS");
            }
            if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                desktop.setQuitHandler((event, response) -> {
                    log.info("Desktop quit handler fired — terminating");
                    // Don't let shutdown block us forever: kick a watchdog
                    // that force-halts after 2 seconds no matter what.
                    Thread killer = new Thread(() -> {
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        forceKill.run();
                    }, "vless-quit-killer");
                    killer.setDaemon(true);
                    killer.start();

                    try {
                        shutdown();
                    } catch (Exception e) {
                        log.debug("Shutdown during quit failed", e);
                    }
                    response.performQuit();
                    forceKill.run();
                });
                log.info("Desktop quit handler installed");
            } else {
                log.warn("APP_QUIT_HANDLER not supported — Cmd+Q may leak JVM");
            }
        } catch (Throwable e) {
            // Throwable: a poisoned AWT Toolkit (headless=false, no display)
            // surfaces as an Error here — must not abort startup.
            log.debug("Could not install Desktop quit handler: {}", e.toString());
        }
    }

    private void setDockIcon() {
        try {
            if (!Taskbar.isTaskbarSupported()) {
                return;
            }
            Taskbar taskbar = Taskbar.getTaskbar();
            if (!taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                return;
            }
            URL iconUrl = getClass().getResource("/icons/app-icon-512.png");
            if (iconUrl == null) {
                iconUrl = getClass().getResource("/icons/app-icon.png");
            }
            if (iconUrl == null) {
                log.debug("No app icon resource found for Dock");
                return;
            }
            java.awt.Image awtImage = Toolkit.getDefaultToolkit().getImage(iconUrl);
            taskbar.setIconImage(awtImage);
            log.info("Dock icon installed");
        } catch (UnsupportedOperationException | SecurityException e) {
            log.debug("Dock icon not supported on this platform: {}", e.getMessage());
        } catch (Throwable e) {
            // Throwable, not Exception: with -Djava.awt.headless=false on a
            // display-less/broken Linux host, the first AWT touch fails the
            // Toolkit's static init with an ExceptionInInitializerError (an
            // Error). Swallowing it keeps the app starting — just without a
            // Dock icon. Found by the desktop-VM QA scenario.
            log.warn("Failed to set Dock icon: {}", e.toString());
        }
    }

    /**
     * Whether an AWT system tray exists, defaulting to {@code false} if AWT
     * can't answer. {@code SystemTray.isSupported()} triggers Toolkit init;
     * with {@code -Djava.awt.headless=false} on a display-less/broken host
     * that fails with an {@link Error} (poisoned Toolkit) — which must not
     * abort startup, or the app never connects. Found by the desktop-VM QA.
     */
    private static boolean systemTraySupported() {
        try {
            return java.awt.SystemTray.isSupported();
        } catch (Throwable e) {
            log.warn("Could not query the system tray; assuming none: {}", e.toString());
            return false;
        }
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        ensureSingBoxAvailable();
        attachCoreTrialMonitor();

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainView.fxml"));
        Parent root = loader.load();

        // Compact default size that fits the new top-bar Dashboard layout
        // without scrolling; users can still resize freely above the minimum.
        Scene scene = new Scene(root, 820, 500);

        // Apply saved locale
        AppSettings settings = ServiceLocator.get(AppSettings.class);
        String lang = settings.getLanguage();
        Locale locale = "ru".equals(lang) ? Locale.of("ru") : Locale.ENGLISH;
        I18n.setLocale(locale);

        // Apply saved theme
        ThemeManager themeManager = ServiceLocator.get(ThemeManager.class);
        themeManager.setTheme(settings.getTheme());
        themeManager.applyTheme(scene);

        primaryStage.setTitle("Tunl");
        primaryStage.setMinWidth(760);
        primaryStage.setMinHeight(460);
        primaryStage.setScene(scene);

        loadAppIcon(primaryStage);

        // Keep the app alive when the main window is closed — it continues
        // running in the tray, where the user can reopen or quit it. On
        // desktops with no system tray (notably stock GNOME) hiding would
        // strand the app with no way back, so there closing the window quits.
        Platform.setImplicitExit(false);
        boolean trayAvailable = systemTraySupported();
        primaryStage.setOnCloseRequest(event -> {
            if (trayAvailable) {
                event.consume();
                primaryStage.hide();
            } else {
                log.info("No system tray on this desktop — window close quits the app");
                Platform.exit();
            }
        });

        primaryStage.show();
        log.info("Tunl started");

        installTrayIcon(primaryStage);

        // Connect now if the user enabled "Auto-connect on startup". Done
        // after the window is shown so any error dialog has a parent.
        MainViewController mainController = loader.getController();
        if (mainController != null) {
            mainController.triggerAutoConnect();
        }
    }

    @Override
    public void stop() {
        if (trayIconService != null) {
            try {
                trayIconService.uninstall();
            } catch (Exception e) {
                log.debug("Error uninstalling tray icon", e);
            }
            trayIconService = null;
        }
        shutdown();

        // JavaFX has stopped its event loop, but AWT (SystemTray, Taskbar,
        // Toolkit) keeps its non-daemon EventQueue thread alive, preventing
        // the JVM from terminating. Force the process to exit so the app
        // actually quits when the user picks Quit from the tray menu or
        // uses Cmd+Q.
        Runnable forceExit = () -> {
            log.info("Forcing JVM shutdown");
            Runtime.getRuntime().halt(0);
        };
        Thread killer = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            forceExit.run();
        }, "vless-jvm-exit");
        killer.setDaemon(true);
        killer.start();
        // In case the killer thread is somehow not enough, call System.exit
        // directly too. It waits for shutdown hooks (including our sing-box
        // stop) before handing control to `halt`.
        System.exit(0);
    }

    /**
     * If sing-box is not available yet, shows a modal installer dialog that
     * downloads and caches the pinned release before the main window appears.
     * On failure or user skip, the app continues without SingBoxEngine and the
     * Dashboard will show a brew-install hint.
     */
    private void ensureSingBoxAvailable() {
        boolean alreadyAvailable;
        try {
            ServiceLocator.get(SingBoxEngine.class);
            alreadyAvailable = true;
        } catch (IllegalArgumentException e) {
            alreadyAvailable = false;
        }
        if (alreadyAvailable) {
            return;
        }

        SingBoxInstaller installer;
        try {
            installer = ServiceLocator.get(SingBoxInstaller.class);
        } catch (IllegalArgumentException e) {
            log.warn("SingBoxInstaller not available; skipping auto-install");
            return;
        }

        SingBoxInstallerDialog dialog = new SingBoxInstallerDialog(installer);
        Optional<Path> installed = dialog.showAndWait();
        if (installed.isPresent()) {
            ServiceLocator.registerSingBoxEngine(installed.get());
            log.info("sing-box ready at {}", installed.get());
        } else {
            log.warn("User continued without sing-box; Connect will be unavailable");
        }
    }

    /**
     * Wires the core-update trial monitor to the engine: an in-app-updated
     * sing-box that errors on its first connect is rolled back automatically,
     * with a warning dialog telling the user which version was restored.
     */
    private void attachCoreTrialMonitor() {
        try {
            CoreUpdateService coreUpdateService = ServiceLocator.get(CoreUpdateService.class);
            SingBoxEngine engine = ServiceLocator.get(SingBoxEngine.class);
            coreUpdateService.attachToEngine(engine, restoredVersion ->
                    Platform.runLater(() -> {
                        javafx.scene.control.Alert alert =
                                new javafx.scene.control.Alert(
                                        javafx.scene.control.Alert.AlertType.WARNING);
                        alert.setTitle(I18n.get("core.trial.rolledback.title"));
                        alert.setHeaderText(null);
                        alert.setContentText(I18n.get("core.trial.rolledback",
                                restoredVersion != null ? restoredVersion : "?"));
                        alert.show();
                    }));
        } catch (IllegalArgumentException e) {
            log.debug("Core trial monitor not attached: {}", e.getMessage());
        }
    }

    private void installTrayIcon(Stage stage) {
        try {
            SingBoxEngine engine = null;
            try {
                engine = ServiceLocator.get(SingBoxEngine.class);
            } catch (IllegalArgumentException e) {
                log.debug("SingBoxEngine not available for tray icon");
            }
            ConfigStore configStore = ServiceLocator.get(ConfigStore.class);
            SingBoxConfigGenerator generator = ServiceLocator.get(SingBoxConfigGenerator.class);

            trayIconService = new TrayIconService(engine, configStore, generator, stage);
            ServiceLocator.register(TrayIconService.class, trayIconService);
            trayIconService.install();
        } catch (Throwable e) {
            // Throwable: SystemTray.isSupported()/AWT can fail the Toolkit
            // static init with an Error under headless=false without a
            // display — the app must still run, just without a tray icon.
            log.warn("Failed to install tray icon service: {}", e.toString());
        }
    }

    private void shutdown() {
        log.info("Shutting down Tunl");
        try {
            ServiceLocator.shutdown();
        } catch (Exception e) {
            log.error("Error during shutdown", e);
        }
    }

    private void loadAppIcon(Stage stage) {
        // Register multiple resolutions so the OS can pick the best fit for
        // the window title bar, Dock, and Cmd+Tab switcher.
        int[] sizes = {16, 32, 64, 128, 256, 512, 1024};
        int loaded = 0;
        for (int size : sizes) {
            String path = "/icons/app-icon-" + size + ".png";
            try (InputStream iconStream = getClass().getResourceAsStream(path)) {
                if (iconStream != null) {
                    stage.getIcons().add(new Image(iconStream));
                    loaded++;
                }
            } catch (Exception e) {
                log.debug("Failed to load icon {}", path);
            }
        }
        if (loaded == 0) {
            try (InputStream fallback = getClass().getResourceAsStream("/icons/app-icon.png")) {
                if (fallback != null) {
                    stage.getIcons().add(new Image(fallback));
                }
            } catch (Exception e) {
                log.debug("No application icon found, using default");
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
