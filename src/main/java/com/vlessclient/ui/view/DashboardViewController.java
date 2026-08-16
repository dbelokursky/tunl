package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.ServerSelection;
import com.vlessclient.platform.UpdateApplier;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ConnectionService;
import com.vlessclient.service.CountryResolver;
import com.vlessclient.service.LatencyTester;
import com.vlessclient.service.ServiceReachabilityChecker;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.SingBoxInstaller;
import com.vlessclient.service.TrafficMonitor;
import com.vlessclient.service.UpdateManager;
import com.vlessclient.ui.view.dashboard.AddHealthTargetDialog;
import com.vlessclient.ui.view.dashboard.HealthCheckCoordinator;
import com.vlessclient.ui.view.dashboard.LatencyTestSession;
import com.vlessclient.ui.view.dashboard.TrafficDisplayBinder;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for the Dashboard view.
 * Displays connection status, real-time traffic stats, and latency testing.
 */
public class DashboardViewController {

    private static final Logger log = LoggerFactory.getLogger(DashboardViewController.class);

    @FXML private Circle statusCircle;
    @FXML private javafx.scene.layout.StackPane statusHalo;
    @FXML private javafx.scene.layout.StackPane statusFlag;
    @FXML private Label statusTitle;
    @FXML private Label statusLabel;
    @FXML private Label serverNameLabel;
    @FXML private MirroredSparkline trafficSparkline;
    @FXML private Button connectButton;
    @FXML private Label uploadSpeedLabel;
    @FXML private Label downloadSpeedLabel;
    @FXML private Label totalUploadLabel;
    @FXML private Label totalDownloadLabel;
    @FXML private Label uploadCardIcon;
    @FXML private Label downloadCardIcon;
    @FXML private Label totalUploadIcon;
    @FXML private Label totalDownloadIcon;
    @FXML private Button testLatencyButton;
    @FXML private VBox latencyResultList;
    @FXML private ComboBox<ProxyMode> proxyModeCombo;
    @FXML private Label serverSelectionLabel;
    @FXML private ComboBox<ServerSelection> serverSelectionCombo;
    @FXML private Label proxyModeWarning;
    @FXML private HBox singBoxMissingBanner;
    @FXML private Label uploadCardTitle;
    @FXML private Label downloadCardTitle;
    @FXML private Label singBoxMissingTitle;
    @FXML private Label singBoxMissingHint;
    @FXML private Label brewCommandLabel;
    @FXML private Button copyBrewButton;
    @FXML private Button retryInstallButton;
    @FXML private VBox healthCard;
    @FXML private Label healthSummaryLabel;
    @FXML private Button recheckButton;
    @FXML private VBox serviceStatusList;
    @FXML private HBox reconnectBanner;
    @FXML private Label reconnectBannerLabel;
    @FXML private Button cancelReconnectButton;
    @FXML private HBox updateBanner;
    @FXML private Label updateBannerTitle;
    @FXML private Label updateBannerHint;
    @FXML private Button updateBannerButton;

    private final ObjectProperty<ConnectionState> connectionState =
            new SimpleObjectProperty<>(ConnectionState.DISCONNECTED);

    private ServerConfig activeServer;
    private SingBoxEngine singBoxEngine;

    // Extracted Dashboard collaborators; the controller stays the FXML
    // endpoint and hands each one the few controls it drives.
    private TrafficDisplayBinder trafficDisplay;
    private LatencyTestSession latencySession;
    private HealthCheckCoordinator healthChecks;
    private UpdateManager updateManager;

    /**
     * Wires up services, the connection-state listener, traffic/latency
     * readouts, and the initial UI state. Called by the FXML loader after
     * the view's nodes are injected.
     */
    @FXML
    public void initialize() {
        uploadCardIcon.setGraphic(Icons.chevronDoubleUp(16));
        downloadCardIcon.setGraphic(Icons.chevronDoubleDown(16));
        totalUploadIcon.setGraphic(Icons.chevronDoubleUp(16));
        totalDownloadIcon.setGraphic(Icons.chevronDoubleDown(16));

        // The connect button's label is driven by the connection state below,
        // so its width is pinned from the four labels that state can produce
        // rather than bound here.
        ButtonLabels.pinWidth(connectButton, "button.connect", "button.disconnect",
                "button.cancel", "button.retry");

        // Titles, not readouts: they belong here rather than in
        // TrafficDisplayBinder, which only runs when a TrafficMonitor exists.
        uploadCardTitle.textProperty().bind(I18n.binding("dashboard.upload.speed"));
        downloadCardTitle.textProperty().bind(I18n.binding("dashboard.download.speed"));
        bindInstallBannerLabels();
        initUpdateBanner();

        try {
            singBoxEngine = ServiceLocator.get(SingBoxEngine.class);
        } catch (IllegalArgumentException e) {
            log.warn("SingBoxEngine not available; connect/disconnect will be disabled");
            singBoxEngine = null;
        }

        TrafficMonitor trafficMonitor;
        try {
            trafficMonitor = ServiceLocator.get(TrafficMonitor.class);
        } catch (IllegalArgumentException e) {
            log.warn("TrafficMonitor not available");
            trafficMonitor = null;
        }

        LatencyTester latencyTester;
        try {
            latencyTester = ServiceLocator.get(LatencyTester.class);
        } catch (IllegalArgumentException e) {
            log.warn("LatencyTester not available");
            latencyTester = null;
        }

        ServiceReachabilityChecker reachabilityChecker;
        try {
            reachabilityChecker = ServiceLocator.get(ServiceReachabilityChecker.class);
        } catch (IllegalArgumentException e) {
            log.warn("ServiceReachabilityChecker not available; health check disabled");
            reachabilityChecker = null;
        }

        trafficDisplay = new TrafficDisplayBinder(trafficMonitor,
                uploadSpeedLabel, downloadSpeedLabel, totalUploadLabel, totalDownloadLabel,
                trafficSparkline);
        latencySession = new LatencyTestSession(latencyTester, testLatencyButton,
                latencyResultList);
        // Picking a row makes that server active — and, if the tunnel is up,
        // the existing server-switch handling restarts it.
        latencySession.setOnServerChosen(server -> {
            try {
                ServiceLocator.get(ConfigStore.class).setActiveServer(server.getId());
                log.info("Active server set from latency results: {}", server.getName());
            } catch (IllegalArgumentException e) {
                log.warn("ConfigStore unavailable; cannot apply the latency result");
            }
        });
        if (latencyTester != null) {
            // While connected, measure through the proxy instead of TCP-pinging
            // its address; the supplier returns null when the core is down.
            latencyTester.setApiEndpointSupplier(() -> {
                if (singBoxEngine == null || !singBoxEngine.isRunning()) {
                    return null;
                }
                try {
                    AppSettings settings = ServiceLocator.get(AppSettings.class);
                    return new LatencyTester.ApiEndpoint(
                            settings.getClashApiPort(), settings.getClashApiSecret());
                } catch (IllegalArgumentException e) {
                    return null;
                }
            });
        }
        // The engine is supplied lazily because onRetryInstallClicked can swap
        // in a new SingBoxEngine after an in-app install.
        healthChecks = new HealthCheckCoordinator(
                new HealthCheckCoordinator.Controls(healthCard, healthSummaryLabel,
                        serviceStatusList, reconnectBanner, reconnectBannerLabel),
                reachabilityChecker, () -> singBoxEngine, this::connect, this::disconnect);

        initProxyModeCombo();
        initServerSelectionCombo();
        trafficDisplay.initSparklines();

        if (trafficMonitor != null) {
            trafficDisplay.bindLabels();
        }

        try {
            ConfigStore configStore = ServiceLocator.get(ConfigStore.class);
            configStore.getServers().addListener(
                    (javafx.collections.ListChangeListener<ServerConfig>) change -> {
                        refreshConnectButtonAvailability();
                        reconnectIfActiveServerChanged();
                    });
        } catch (IllegalArgumentException e) {
            log.debug("ConfigStore not available while wiring server-list listener");
        }

        if (singBoxEngine != null) {
            singBoxEngine.connectionStateProperty().addListener(
                    (obs, oldState, newState) -> {
                        updateUi(newState);
                        trafficDisplay.onConnectionStateChanged(newState);
                        healthChecks.onConnectionStateChanged(newState);
                    });

            singBoxEngine.errorMessageProperty().addListener(
                    (obs, oldMsg, newMsg) -> {
                        if (newMsg != null && !newMsg.isEmpty()) {
                            statusLabel.setText(newMsg);
                        }
                    });

            ConnectionState current = singBoxEngine.connectionStateProperty().get();
            updateUi(current);
            trafficDisplay.onConnectionStateChanged(current);
            healthChecks.onConnectionStateChanged(current);
        } else {
            connectionState.addListener((obs, oldState, newState) -> updateUi(newState));
            updateUi(ConnectionState.DISCONNECTED);
        }

        if (brewCommandLabel != null) {
            brewCommandLabel.setText(SingBoxInstaller.brewInstallCommand());
        }
        refreshSingBoxMissingBanner();
        refreshConnectButtonAvailability();
    }

    /**
     * The banner's own wording, which used to sit in the FXML in English
     * while the Copy button beside it answered in the user's language.
     * {@code brewCommandLabel} is left out on purpose — it holds a shell
     * command, and translating one would stop it working.
     */
    private void bindInstallBannerLabels() {
        singBoxMissingTitle.textProperty().bind(I18n.binding("dashboard.singbox.missing.title"));
        singBoxMissingHint.textProperty().bind(I18n.binding("dashboard.singbox.missing.hint"));
        ButtonLabels.bind(copyBrewButton, "dashboard.copy", "dashboard.copied");
        ButtonLabels.bind(retryInstallButton, "dashboard.singbox.retry");
    }

    /** What the dashboard should say about an update, if anything. */
    enum UpdateBannerState {

        /** Nothing newer exists, or there is no updater to ask. */
        HIDDEN,

        /** A newer release exists but this install updates through apt/AUR. */
        PACKAGE_MANAGER,

        /** Newer release seen; the background download has it in hand. */
        DOWNLOADING,

        /** Verified and staged — one restart away. */
        READY
    }

    /**
     * Chooses the banner's state. Kept as a pure function of the three facts
     * it depends on, so the combinations can be checked without a scene.
     *
     * @param updateAvailable whether a newer release was found
     * @param staged          whether its installer is already verified on disk
     * @param selfUpdates     whether this platform installs updates in-app
     * @return the state to render
     */
    static UpdateBannerState bannerState(
            boolean updateAvailable, boolean staged, boolean selfUpdates) {
        if (!updateAvailable) {
            return UpdateBannerState.HIDDEN;
        }
        if (!selfUpdates) {
            return UpdateBannerState.PACKAGE_MANAGER;
        }
        return staged ? UpdateBannerState.READY : UpdateBannerState.DOWNLOADING;
    }

    /**
     * Wires the update banner to the updater's own properties, so a release
     * found by the background check surfaces here without the user going
     * looking for it in Settings.
     */
    private void initUpdateBanner() {
        try {
            updateManager = ServiceLocator.get(UpdateManager.class);
        } catch (IllegalArgumentException e) {
            updateManager = null;
            refreshUpdateBanner();
            return;
        }
        ButtonLabels.bind(updateBannerButton, "settings.update.restart");
        updateManager.updateAvailableProperty()
                .addListener((o, was, is) -> refreshUpdateBanner());
        updateManager.latestVersionProperty()
                .addListener((o, was, is) -> refreshUpdateBanner());
        // The title carries a version number, so it cannot be a plain binding;
        // re-render instead when the language changes under it.
        I18n.localeProperty().addListener((o, was, is) -> refreshUpdateBanner());
        refreshUpdateBanner();
    }

    private void refreshUpdateBanner() {
        if (updateBanner == null) {
            return;
        }
        UpdateBannerState state = updateManager == null
                ? UpdateBannerState.HIDDEN
                : bannerState(updateManager.updateAvailableProperty().get(),
                        updateManager.hasStagedUpdate(),
                        UpdateApplier.current().selfUpdates());

        updateBanner.setVisible(state != UpdateBannerState.HIDDEN);
        updateBanner.setManaged(state != UpdateBannerState.HIDDEN);
        if (state == UpdateBannerState.HIDDEN) {
            return;
        }

        String version = updateManager.latestVersionProperty().get();
        updateBannerTitle.setText(state == UpdateBannerState.READY
                ? I18n.get("dashboard.update.ready", version)
                : I18n.get("dashboard.update.available", version));
        String hintKey = switch (state) {
            case READY -> "dashboard.update.hint.staged";
            case PACKAGE_MANAGER -> "dashboard.update.hint.packagemanager";
            default -> "dashboard.update.hint.downloading";
        };
        updateBannerHint.setText(I18n.get(hintKey));

        // Only the staged state has anything to press: the download runs on
        // its own, and a package-managed install is not ours to touch.
        boolean actionable = state == UpdateBannerState.READY;
        updateBannerButton.setVisible(actionable);
        updateBannerButton.setManaged(actionable);
    }

    @FXML
    private void onUpdateBannerClicked() {
        if (RestartToUpdate.start() == RestartToUpdate.Outcome.FAILED) {
            updateBannerHint.setText(I18n.get("settings.update.restart.failed"));
        }
    }

    private void refreshSingBoxMissingBanner() {
        if (singBoxMissingBanner == null) {
            return;
        }
        boolean missing = singBoxEngine == null;
        singBoxMissingBanner.setVisible(missing);
        singBoxMissingBanner.setManaged(missing);
    }

    @FXML
    private void onCopyBrewCommandClicked() {
        ClipboardContent content = new ClipboardContent();
        content.putString(SingBoxInstaller.brewInstallCommand());
        Clipboard.getSystemClipboard().setContent(content);
        if (copyBrewButton != null) {
            ButtonLabels.flash(copyBrewButton, "dashboard.copied");
        }
    }

    @FXML
    private void onRetryInstallClicked() {
        SingBoxInstaller installer;
        try {
            installer = ServiceLocator.get(SingBoxInstaller.class);
        } catch (IllegalArgumentException e) {
            showError(I18n.get("dashboard.error.installer.title"),
                    I18n.get("dashboard.error.installer.body"));
            return;
        }

        com.vlessclient.ui.view.SingBoxInstallerDialog dialog =
                new com.vlessclient.ui.view.SingBoxInstallerDialog(installer);
        dialog.showAndWait().ifPresent(path -> {
            ServiceLocator.registerSingBoxEngine(path);
            try {
                singBoxEngine = ServiceLocator.get(SingBoxEngine.class);
                singBoxEngine.connectionStateProperty().addListener(
                        (obs, oldState, newState) -> {
                            updateUi(newState);
                            trafficDisplay.onConnectionStateChanged(newState);
                            healthChecks.onConnectionStateChanged(newState);
                        });
                singBoxEngine.errorMessageProperty().addListener(
                        (obs, oldMsg, newMsg) -> {
                            if (newMsg != null && !newMsg.isEmpty()) {
                                statusLabel.setText(newMsg);
                            }
                        });
                updateUi(singBoxEngine.connectionStateProperty().get());
            } catch (IllegalArgumentException e) {
                log.warn("SingBoxEngine still unavailable after install");
            }
            refreshSingBoxMissingBanner();
            refreshConnectButtonAvailability();
        });
    }

    private void initProxyModeCombo() {
        proxyModeCombo.getItems().addAll(ProxyMode.values());
        proxyModeCombo.setCellFactory(cb -> new ListCell<>() {
            @Override
            protected void updateItem(ProxyMode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatProxyMode(item));
            }
        });
        proxyModeCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(ProxyMode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatProxyMode(item));
            }
        });

        try {
            AppSettings settings = ServiceLocator.get(AppSettings.class);
            proxyModeCombo.setValue(settings.getProxyMode());
        } catch (IllegalArgumentException e) {
            proxyModeCombo.setValue(ProxyMode.SYSTEM_PROXY);
        }

        updateProxyModeWarning(proxyModeCombo.getValue());

        proxyModeCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            updateProxyModeWarning(newVal);
            try {
                ConfigStore configStore = ServiceLocator.get(ConfigStore.class);
                AppSettings settings = configStore.getSettings();
                settings.setProxyMode(newVal);
                configStore.saveSettings(settings);
            } catch (IllegalArgumentException e) {
                log.warn("Could not save proxy mode setting");
            }
        });
    }

    /**
     * Wires the "which server" selector: pin the active one, or let the core
     * pick the fastest among all of them. Mirrors initProxyModeCombo so the
     * two controls behave identically — same persistence, same failure mode
     * when settings are unavailable.
     *
     * <p>Changing it while connected restarts the tunnel, for the same reason
     * switching servers does: otherwise the UI would claim one thing and the
     * live connection keep doing another.</p>
     */
    private void initServerSelectionCombo() {
        if (serverSelectionCombo == null) {
            return;
        }
        serverSelectionLabel.textProperty().bind(I18n.binding("dashboard.selection"));
        serverSelectionCombo.getItems().addAll(ServerSelection.values());
        serverSelectionCombo.setCellFactory(cb -> new ListCell<>() {
            @Override
            protected void updateItem(ServerSelection item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatSelection(item));
            }
        });
        serverSelectionCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(ServerSelection item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatSelection(item));
            }
        });

        try {
            serverSelectionCombo.setValue(
                    ServiceLocator.get(AppSettings.class).getServerSelection());
        } catch (IllegalArgumentException e) {
            serverSelectionCombo.setValue(ServerSelection.SINGLE);
        }

        serverSelectionCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal == oldVal) {
                return;
            }
            try {
                ConfigStore configStore = ServiceLocator.get(ConfigStore.class);
                AppSettings settings = configStore.getSettings();
                settings.setServerSelection(newVal);
                configStore.saveSettings(settings);
            } catch (IllegalArgumentException e) {
                log.warn("Could not save server selection setting");
                return;
            }
            if (singBoxEngine != null
                    && singBoxEngine.connectionStateProperty().get() == ConnectionState.CONNECTED) {
                log.info("Server selection changed while connected; restarting tunnel");
                disconnect();
                connect();
            }
        });
    }

    private String formatSelection(ServerSelection selection) {
        return switch (selection) {
            case SINGLE -> I18n.get("dashboard.selection.single");
            case AUTO_BEST -> I18n.get("dashboard.selection.auto");
        };
    }

    private String formatProxyMode(ProxyMode mode) {
        return switch (mode) {
            case SYSTEM_PROXY -> I18n.get("settings.proxy.system");
            case TUN -> I18n.get("settings.proxy.tun");
        };
    }

    private void updateProxyModeWarning(ProxyMode mode) {
        if (mode == ProxyMode.TUN) {
            proxyModeWarning.setText(I18n.get("settings.tun.warning"));
            proxyModeWarning.setVisible(true);
            proxyModeWarning.setManaged(true);
        } else {
            proxyModeWarning.setText("");
            proxyModeWarning.setVisible(false);
            proxyModeWarning.setManaged(false);
        }
    }

    /**
     * Toggles connection state: connects if disconnected, disconnects if connected.
     * Used by keyboard shortcuts.
     */
    public void toggleConnection() {
        onConnectClicked();
    }

    @FXML
    private void onConnectClicked() {
        ConnectionState current = singBoxEngine != null
                ? singBoxEngine.connectionStateProperty().get()
                : connectionState.get();

        if (current == ConnectionState.CONNECTED || current == ConnectionState.CONNECTING) {
            disconnect();
            return;
        }

        if (singBoxEngine == null) {
            showError(I18n.get("error.singbox.not.found"),
                    I18n.get("dashboard.error.singbox.body"));
            return;
        }

        connect();
    }

    private void showError(String header, String message) {
        log.error("{}: {}", header, message);
        try {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(I18n.get("dialog.error"));
            alert.setHeaderText(header);
            alert.setContentText(message);
            alert.showAndWait();
        } catch (Exception e) {
            log.error("Failed to show error dialog", e);
        }
    }

    @FXML
    private void onTestLatencyClicked() {
        latencySession.toggle();
    }

    /**
     * Connects on startup when the user enabled "Auto-connect on startup" in
     * Settings. Silently skips when prerequisites are missing (no sing-box
     * binary, no active server) so a fresh install does not show an error
     * dialog at every launch. Invoked once after the main window is shown.
     */
    public void autoConnectIfEnabled() {
        AppSettings settings;
        try {
            settings = ServiceLocator.get(AppSettings.class);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!settings.isAutoConnect()) {
            return;
        }
        if (singBoxEngine == null) {
            log.info("Auto-connect enabled but sing-box is unavailable; skipping");
            return;
        }
        if (findActiveServer() == null) {
            log.info("Auto-connect enabled but no active server; skipping");
            return;
        }
        log.info("Auto-connect enabled; connecting on startup");
        // Defer to a later pulse so the window finishes painting before the
        // connect work (which can briefly block for TUN privilege setup).
        Platform.runLater(this::connect);
    }

    /**
     * Starts the tunnel. The flow itself belongs to {@link ConnectionService};
     * what stays here is the UI half — remembering which server the dashboard
     * claims, disabling the button, and turning the outcome into a message.
     *
     * <p>The work runs off the FX thread because a start hashes the ~40 MB
     * binary and, for TUN, can raise a modal admin prompt with a 60-second
     * timeout: inline it froze the window during the one action users watch
     * most. The engine publishes its state through {@code
     * connectionStateProperty}, which already marshals to FX, so the rest of
     * the UI keeps updating itself.</p>
     */
    private void connect() {
        ConnectionService service = connectionService();
        if (service == null) {
            showError(I18n.get("dashboard.error.service.title"),
                    I18n.get("dashboard.error.service.body", "ConnectionService"));
            return;
        }
        // Tracked for the UI (flag, server name, server-switch detection); the
        // service resolves the active server again as the real source of truth.
        activeServer = findActiveServer();
        connectButton.setDisable(true);
        Thread.startVirtualThread(() -> runConnect(service, false));
    }

    /**
     * Restarts the tunnel, waiting for the old core to exit first. Used when the
     * active server changes while connected.
     */
    private void reconnect() {
        ConnectionService service = connectionService();
        if (service == null) {
            return;
        }
        activeServer = findActiveServer();
        connectButton.setDisable(true);
        Thread.startVirtualThread(() -> runConnect(service, true));
    }

    /** Runs a connect (or reconnect) off the FX thread and reports the result. */
    private void runConnect(ConnectionService service, boolean restart) {
        try {
            ConnectionService.ConnectAttempt attempt =
                    restart ? service.reconnect(null) : service.connect(null);
            switch (attempt.outcome()) {
                case NO_ACTIVE_SERVER -> Platform.runLater(() -> {
                    log.warn("No active server selected");
                    statusLabel.setText(I18n.get("dashboard.no.server"));
                    showError(I18n.get("dashboard.error.no.active.title"),
                            I18n.get("dashboard.error.no.active.body"));
                });
                case NO_ENGINE -> Platform.runLater(() ->
                        showError(I18n.get("error.singbox.not.found"),
                                I18n.get("dashboard.error.singbox.body")));
                case ALREADY_RUNNING -> log.warn("sing-box already running");
                // STARTED: the engine's state listener drives the UI from here.
                default -> Platform.runLater(() -> setActiveServer(attempt.server()));
            }
        } catch (IOException e) {
            log.error("Failed to start sing-box", e);
            Platform.runLater(() -> {
                statusLabel.setText(I18n.get("error.connection.failed", e.getMessage()));
                showError(I18n.get("dashboard.error.start.title"), e.getMessage());
            });
        } finally {
            Platform.runLater(this::refreshConnectButtonAvailability);
        }
    }

    private void disconnect() {
        ConnectionService service = connectionService();
        if (service == null || singBoxEngine == null) {
            connectionState.set(ConnectionState.DISCONNECTED);
            return;
        }
        // A stop waits out a SIGTERM grace period and can force-kill after it,
        // so it cannot run on the FX thread either.
        connectButton.setDisable(true);
        Thread.startVirtualThread(() -> {
            try {
                service.disconnect();
            } finally {
                Platform.runLater(this::refreshConnectButtonAvailability);
            }
        });
    }

    /** The connect-flow owner, or null when it is not registered. */
    private ConnectionService connectionService() {
        try {
            return ServiceLocator.get(ConnectionService.class);
        } catch (IllegalArgumentException e) {
            log.error("ConnectionService not available; connect is disabled", e);
            return null;
        }
    }

    /**
     * Restarts the tunnel when the active server changes while connected.
     *
     * <p>Switching servers used to move the flag and the tray checkmark while
     * traffic kept flowing through the <em>old</em> server: the UI claimed one
     * destination and the tunnel used another, with no warning. In a privacy
     * tool that is a trust bug, not a convenience one.</p>
     *
     * <p>Hangs off the server-list change event, so it covers both switch
     * paths (list and tray) — they both go through
     * {@code ConfigStore.setActiveServer}, which re-sets the elements and
     * therefore fires a change. The gap before reconnecting lets the old
     * process exit first, mirroring {@code HealthCheckCoordinator}'s
     * auto-reconnect.</p>
     */
    private void reconnectIfActiveServerChanged() {
        if (singBoxEngine == null
                || singBoxEngine.connectionStateProperty().get() != ConnectionState.CONNECTED) {
            return;
        }
        ServerConfig nowActive = findActiveServer();
        if (nowActive == null || activeServer == null
                || nowActive.getId().equals(activeServer.getId())) {
            return;
        }
        log.info("Active server changed while connected ({} -> {}); restarting tunnel",
                activeServer.getName(), nowActive.getName());
        // One restart on one thread: the service stops the old core and waits
        // for it to exit before starting the new one, which is exact rather
        // than a timed guess — and rules out the two racing threads a separate
        // disconnect() plus connect() used to spawn.
        reconnect();
    }

    // ===== Service availability / auto-reconnect =====
    // The probe/reconnect loop lives in HealthCheckCoordinator; only the FXML
    // entry points remain here.

    @FXML
    private void onAddTargetClicked() {
        new AddHealthTargetDialog().showAndWait().ifPresent(healthChecks::addTarget);
    }

    @FXML
    private void onRecheckClicked() {
        healthChecks.recheck();
    }

    @FXML
    private void onCancelReconnectClicked() {
        healthChecks.cancelReconnectCountdown();
    }

    private ServerConfig findActiveServer() {
        try {
            ConfigStore configStore = ServiceLocator.get(ConfigStore.class);
            if (configStore == null) {
                return activeServer;
            }
            List<ServerConfig> servers = configStore.getServers();
            if (servers == null || servers.isEmpty()) {
                return null;
            }
            Optional<ServerConfig> active = servers.stream()
                    .filter(ServerConfig::isActive)
                    .findFirst();
            return active.orElse(null);
        } catch (IllegalArgumentException e) {
            log.warn("ConfigStore not available: {}", e.getMessage());
            return activeServer;
        }
    }

    private void refreshConnectButtonAvailability() {
        try {
            ConfigStore configStore = ServiceLocator.get(ConfigStore.class);
            List<ServerConfig> servers = configStore != null ? configStore.getServers() : null;
            if (servers == null || servers.isEmpty()) {
                connectButton.setDisable(true);
                connectButton.setTooltip(new Tooltip(I18n.get("dashboard.no.servers")));
            } else if (findActiveServer() == null) {
                // Gate on activation, not list size: enabling Connect with no
                // active server turns a click into a modal error telling the
                // user to "mark it active" — a gesture the UI never offers.
                connectButton.setDisable(true);
                connectButton.setTooltip(new Tooltip(I18n.get("dashboard.no.server")));
            } else {
                connectButton.setDisable(false);
                connectButton.setTooltip(null);
            }
        } catch (IllegalArgumentException e) {
            log.debug("ConfigStore not available while refreshing connect button");
        }
    }

    /**
     * Shows the exit country beside the status while connected.
     *
     * <p>Hidden — and unmanaged, so it takes no space — whenever the country
     * is unknown or the tunnel is down. An empty slot next to "Connected"
     * would read as a missing flag rather than as missing information.</p>
     */
    private void showStatusFlag(ServerConfig server) {
        if (statusFlag == null) {
            return;
        }
        hideStatusFlag();
        if (server == null) {
            return;
        }
        CountryResolver resolver;
        try {
            resolver = ServiceLocator.get(CountryResolver.class);
        } catch (IllegalArgumentException e) {
            return;
        }
        resolver.countryOf(server).ifPresent(this::paintStatusFlag);
        resolver.resolveAsync(server, code -> Platform.runLater(() -> {
            // Only paint if this is still the server we are connected to.
            if (activeServer == server
                    && singBoxEngine != null
                    && singBoxEngine.connectionStateProperty().get() == ConnectionState.CONNECTED) {
                paintStatusFlag(code);
            }
        }));
    }

    private void paintStatusFlag(String isoCode) {
        statusFlag.getChildren().setAll(Flags.of(isoCode, 18));
        statusFlag.setVisible(true);
        statusFlag.setManaged(true);
    }

    private void hideStatusFlag() {
        statusFlag.getChildren().clear();
        statusFlag.setVisible(false);
        statusFlag.setManaged(false);
    }

    private void updateUi(ConnectionState state) {
        String haloClass;
        switch (state) {
            case CONNECTED -> {
                statusCircle.setFill(Color.web("#2e7d32"));
                statusCircle.getStyleClass().setAll("status-circle-connected");
                haloClass = "status-halo-connected";
                if (statusTitle != null) {
                    statusTitle.setText(I18n.get("state.connected"));
                    statusTitle.getStyleClass().setAll("status-title", "status-title-connected");
                }
                statusLabel.setText(activeServer != null
                        ? I18n.get("dashboard.status.routing.through", activeServer.getName())
                        : I18n.get("dashboard.status.routing"));
                showStatusFlag(activeServer);
                statusLabel.getStyleClass().setAll("status-subtitle");
                connectButton.setText(I18n.get("button.disconnect"));
                connectButton.setDisable(false);
                connectButton.getStyleClass().removeAll("connect-button");
                connectButton.getStyleClass().add("disconnect-button");
            }
            case CONNECTING -> {
                hideStatusFlag();
                statusCircle.setFill(Color.web("#ef6c00"));
                statusCircle.getStyleClass().setAll("status-circle-connecting");
                haloClass = "status-halo-connecting";
                if (statusTitle != null) {
                    statusTitle.setText(I18n.get("state.connecting"));
                    statusTitle.getStyleClass().setAll("status-title", "status-title-connecting");
                }
                statusLabel.setText(I18n.get("dashboard.status.establishing"));
                statusLabel.getStyleClass().setAll("status-subtitle");
                connectButton.setText(I18n.get("button.cancel"));
                connectButton.setDisable(false);
            }
            case ERROR -> {
                hideStatusFlag();
                statusCircle.setFill(Color.web("#c62828"));
                statusCircle.getStyleClass().setAll("status-circle-error");
                haloClass = "status-halo-error";
                if (statusTitle != null) {
                    statusTitle.setText(I18n.get("state.error"));
                    statusTitle.getStyleClass().setAll("status-title", "status-title-error");
                }
                if (!statusLabel.getText().startsWith("Process exited")) {
                    statusLabel.setText(I18n.get("dashboard.status.check.logs"));
                }
                statusLabel.getStyleClass().setAll("status-subtitle", "status-subtitle-error");
                connectButton.setText(I18n.get("button.retry"));
                connectButton.setDisable(false);
                connectButton.getStyleClass().removeAll("disconnect-button");
                connectButton.getStyleClass().add("connect-button");
            }
            default -> {
                statusCircle.setFill(Color.web("#9e9e9e"));
                statusCircle.getStyleClass().setAll("status-circle-disconnected");
                haloClass = "status-halo-disconnected";
                if (statusTitle != null) {
                    statusTitle.setText(I18n.get("state.disconnected"));
                    statusTitle.getStyleClass().setAll("status-title", "status-title-disconnected");
                }
                statusLabel.setText(activeServer != null
                        ? I18n.get("dashboard.status.ready", activeServer.getName())
                        : I18n.get("dashboard.status.add.server"));
                statusLabel.getStyleClass().setAll("status-subtitle");
                connectButton.setText(I18n.get("button.connect"));
                connectButton.setDisable(false);
                connectButton.getStyleClass().removeAll("disconnect-button");
                connectButton.getStyleClass().add("connect-button");
            }
        }

        if (statusHalo != null) {
            statusHalo.getStyleClass().removeAll(
                    "status-halo-connected", "status-halo-connecting",
                    "status-halo-error", "status-halo-disconnected");
            statusHalo.getStyleClass().add(haloClass);
        }

        // serverNameLabel is no longer rendered in the hero card (state
        // subtitle conveys that information), but update it for anyone
        // still observing the field.
        if (activeServer != null) {
            serverNameLabel.setText(activeServer.getName());
        } else {
            serverNameLabel.setText("");
        }

        if (state != ConnectionState.CONNECTED && state != ConnectionState.CONNECTING) {
            refreshConnectButtonAvailability();
        }
    }

    /**
     * Sets the active server from the server list.
     */
    public void setActiveServer(ServerConfig server) {
        this.activeServer = server;
        if (server != null) {
            serverNameLabel.setText(server.getName());
        } else {
            serverNameLabel.setText(I18n.get("dashboard.no.server"));
        }
    }

    public ObjectProperty<ConnectionState> connectionStateProperty() {
        return connectionState;
    }
}
