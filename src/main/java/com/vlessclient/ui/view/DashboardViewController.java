package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.ServerSelection;
import com.vlessclient.model.TunnelHealth;
import com.vlessclient.model.TunnelStatus;
import com.vlessclient.platform.SystemProxySupport;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ConnectionService;
import com.vlessclient.service.CountryResolver;
import com.vlessclient.service.LatencyTester;
import com.vlessclient.service.ProxyGroupMonitor;
import com.vlessclient.service.ServiceReachabilityChecker;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.SingBoxInstaller;
import com.vlessclient.service.TrafficMonitor;
import com.vlessclient.service.TunnelHealthState;
import com.vlessclient.service.outbound.OutboundTags;
import com.vlessclient.ui.view.dashboard.AddHealthTargetDialog;
import com.vlessclient.ui.view.dashboard.HealthCheckCoordinator;
import com.vlessclient.ui.view.dashboard.StatusPresenter;
import com.vlessclient.ui.view.dashboard.TrafficDisplayBinder;
import com.vlessclient.ui.view.dashboard.UpdateBannerSection;
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
import javafx.scene.control.Hyperlink;
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
public class DashboardViewController implements ViewShownAware {

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
    @FXML private Label modeLabel;
    @FXML private Label trafficSectionTitle;
    @FXML private Label healthSectionTitle;
    @FXML private Hyperlink addServerLink;
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

    /**
     * Set only while {@link #onViewShown()} copies persisted settings into the
     * combos. Both value listeners persist, and the server-selection one also
     * restarts a live tunnel — neither may fire for a change the user did not
     * make.
     */
    private boolean syncingFromSettings;

    /**
     * Host capability behind the SYSTEM_PROXY warning. A field so a test can
     * force either verdict; on macOS/Windows the real one is always true, so
     * the warning is Linux-only in practice.
     */
    private SystemProxySupport systemProxySupport = SystemProxySupport.current();

    /** Cached: probing shells out to {@code gsettings}, and a host cannot
     * grow a proxy store while the app runs. */
    private Boolean systemProxyCapable;

    private ServerConfig activeServer;
    private SingBoxEngine singBoxEngine;
    private ProxyGroupMonitor groupMonitor;

    // Extracted Dashboard collaborators; the controller stays the FXML
    // endpoint and hands each one the few controls it drives.
    private TrafficDisplayBinder trafficDisplay;
    private HealthCheckCoordinator healthChecks;
    private TunnelHealthState healthState;
    private UpdateBannerSection updateBannerSection;
    private StatusPresenter statusPresenter;

    /**
     * Wires up services, the connection-state listener, traffic/latency
     * readouts, and the initial UI state. Called by the FXML loader after
     * the view's nodes are injected.
     */
    @FXML
    public void initialize() {
        uploadCardIcon.setGraphic(Icons.chevronDoubleUp(16));
        downloadCardIcon.setGraphic(Icons.chevronDoubleDown(16));

        // The connect button's label is driven by the connection state below,
        // so its width is pinned from the four labels that state can produce
        // rather than bound here.
        ButtonLabels.pinWidth(connectButton, "button.connect", "button.disconnect",
                "button.cancel", "button.retry");

        // Titles, not readouts: they belong here rather than in
        // TrafficDisplayBinder, which only runs when a TrafficMonitor exists.
        uploadCardTitle.textProperty().bind(I18n.binding("dashboard.upload.speed"));
        downloadCardTitle.textProperty().bind(I18n.binding("dashboard.download.speed"));
        // Section headers and the two health-card buttons sat in the FXML as
        // English literals; in Russian the dashboard was half-translated.
        modeLabel.textProperty().bind(I18n.binding("dashboard.mode"));
        trafficSectionTitle.textProperty().bind(I18n.binding("dashboard.traffic.title"));
        healthSectionTitle.textProperty().bind(I18n.binding("dashboard.health.title"));
        ButtonLabels.bindStatic(recheckButton, "dashboard.health.recheck");
        ButtonLabels.bindStatic(cancelReconnectButton, "button.cancel");
        addServerLink.textProperty().bind(I18n.binding("dashboard.cta.add.server"));
        bindInstallBannerLabels();
        ButtonLabels.bind(updateBannerButton, "settings.update.restart");
        updateBannerSection = new UpdateBannerSection(new UpdateBannerSection.Controls(
                updateBanner, updateBannerTitle, updateBannerHint, updateBannerButton));
        updateBannerSection.init();

        // Every collaborator is optional: a missing one degrades the card
        // rather than failing the view, and the log says which.
        singBoxEngine = optional(SingBoxEngine.class,
                "SingBoxEngine not available; connect/disconnect will be disabled");
        TrafficMonitor trafficMonitor = optional(TrafficMonitor.class,
                "TrafficMonitor not available");
        LatencyTester latencyTester = optional(LatencyTester.class,
                "LatencyTester not available");
        groupMonitor = optional(ProxyGroupMonitor.class,
                "ProxyGroupMonitor not available; the card will name the pinned server");
        final ServiceReachabilityChecker reachabilityChecker = optional(
                ServiceReachabilityChecker.class,
                "ServiceReachabilityChecker not available; health check disabled");

        trafficDisplay = new TrafficDisplayBinder(trafficMonitor,
                uploadSpeedLabel, downloadSpeedLabel, totalUploadLabel, totalDownloadLabel,
                trafficSparkline);
        if (latencyTester != null) {
            // While connected, measure through the proxy instead of TCP-pinging
            // its address; the supplier returns null when the core is down.
            latencyTester.setApiEndpointSupplier(() -> {
                if (singBoxEngine == null || !singBoxEngine.isRunning()) {
                    return null;
                }
                return ServiceLocator.find(AppSettings.class)
                        .map(settings -> new LatencyTester.ApiEndpoint(
                                settings.getClashApiPort(), settings.getClashApiSecret()))
                        .orElse(null);
            });
        }
        statusPresenter = new StatusPresenter(
                new StatusPresenter.Controls(statusCircle, statusHalo, statusFlag,
                        statusTitle, statusLabel, serverNameLabel, connectButton),
                () -> activeServer,
                this::routedServer,
                this::currentHealth,
                () -> singBoxEngine,
                this::refreshConnectButtonAvailability);

        healthState = optional(TunnelHealthState.class,
                "TunnelHealthState not available; the status card will report process state only");

        // The engine is supplied lazily because onRetryInstallClicked can swap
        // in a new SingBoxEngine after an in-app install.
        healthChecks = new HealthCheckCoordinator(
                new HealthCheckCoordinator.Controls(healthCard, healthSummaryLabel,
                        serviceStatusList, reconnectBanner, reconnectBannerLabel),
                reachabilityChecker, healthState, () -> singBoxEngine,
                connectionService() != null ? connectionService().getRecoveryService() : null);

        // A verdict arriving does not change the process state, so the hero
        // card has to be repainted on its own signal — otherwise a tunnel that
        // stops carrying traffic keeps claiming it is connected.
        if (healthState != null) {
            healthState.healthProperty().addListener(
                    (obs, oldHealth, newHealth) -> updateUi(currentConnectionState()));
        }

        initProxyModeCombo();
        initServerSelectionCombo();
        trafficDisplay.initSparklines();

        if (trafficMonitor != null) {
            trafficDisplay.bindLabels();
        }

        ServiceLocator.find(ConfigStore.class).ifPresentOrElse(
                configStore -> configStore.getServers().addListener(
                        (javafx.collections.ListChangeListener<ServerConfig>) change -> {
                            refreshConnectButtonAvailability();
                            reconnectIfActiveServerChanged();
                        }),
                () -> log.debug("ConfigStore not available while wiring server-list listener"));

        if (groupMonitor != null) {
            // The pick can change under a live tunnel (urltest re-probes), so
            // the card repaints on its own signal, like the health verdict.
            groupMonitor.currentMemberTagProperty().addListener(
                    (obs, oldTag, newTag) -> updateUi(currentConnectionState()));
        }

        if (singBoxEngine != null) {
            bindEngine(singBoxEngine);
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
     * Wires an engine's signals into the card, the traffic readout, the health
     * loop and the group monitor, and paints its current state. Called once
     * at startup and again after an in-app core install swaps the engine; the
     * two sites used to carry their own copies of the same listeners.
     */
    private void bindEngine(SingBoxEngine engine) {
        engine.connectionStateProperty().addListener(
                (obs, oldState, newState) -> onEngineState(newState));
        engine.errorMessageProperty().addListener(
                (obs, oldMsg, newMsg) -> {
                    if (newMsg != null && !newMsg.isEmpty()) {
                        statusPresenter.showEngineError(newMsg);
                    }
                });
        onEngineState(engine.connectionStateProperty().get());
    }

    private void onEngineState(ConnectionState state) {
        updateUi(state);
        trafficDisplay.onConnectionStateChanged(state);
        healthChecks.onConnectionStateChanged(state);
        syncGroupMonitor(state);
    }

    /**
     * Follows the core's group pick in every mode so UI and MCP can distinguish
     * the configured selection from the server the running core actually uses.
     */
    private void syncGroupMonitor(ConnectionState state) {
        if (groupMonitor == null) {
            return;
        }
        if (state == ConnectionState.CONNECTED) {
            AppSettings settings = ServiceLocator.find(AppSettings.class).orElse(null);
            if (settings != null) {
                groupMonitor.start(settings.getClashApiPort(), settings.getClashApiSecret());
            }
        } else if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
            groupMonitor.stop();
        }
    }

    /**
     * The server traffic actually goes through: the proxy group's current
     * pick when the core has reported one, otherwise the pinned server.
     */
    private ServerConfig routedServer() {
        String tag = groupMonitor != null ? groupMonitor.currentMemberTagProperty().get() : null;
        if (tag != null) {
            ConfigStore configStore = ServiceLocator.find(ConfigStore.class).orElse(null);
            if (configStore != null) {
                for (ServerConfig server : configStore.getServers()) {
                    if (tag.equals(OutboundTags.server(server))) {
                        return server;
                    }
                }
            }
        }
        return activeServer;
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
        SingBoxInstaller installer = ServiceLocator.find(SingBoxInstaller.class).orElse(null);
        if (installer == null) {
            showError(I18n.get("dashboard.error.installer.title"),
                    I18n.get("dashboard.error.installer.body"));
            return;
        }

        com.vlessclient.ui.view.SingBoxInstallerDialog dialog =
                new com.vlessclient.ui.view.SingBoxInstallerDialog(installer);
        dialog.showAndWait().ifPresent(path -> {
            ServiceLocator.registerSingBoxEngine(path);
            singBoxEngine = ServiceLocator.find(SingBoxEngine.class).orElse(null);
            if (singBoxEngine != null) {
                bindEngine(singBoxEngine);
            } else {
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

        proxyModeCombo.setValue(ServiceLocator.find(AppSettings.class)
                .map(AppSettings::getProxyMode)
                .orElse(ProxyMode.SYSTEM_PROXY));

        updateProxyModeWarning(proxyModeCombo.getValue());

        proxyModeCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            updateProxyModeWarning(newVal);
            if (syncingFromSettings) {
                return;
            }
            if (!saveSetting(settings -> settings.setProxyMode(newVal))) {
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

        serverSelectionCombo.setValue(ServiceLocator.find(AppSettings.class)
                .map(AppSettings::getServerSelection)
                .orElse(ServerSelection.SINGLE));

        serverSelectionCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal == oldVal || syncingFromSettings) {
                return;
            }
            if (!saveSetting(settings -> settings.setServerSelection(newVal))) {
                log.warn("Could not save server selection setting");
                return;
            }
            if (singBoxEngine != null
                    && singBoxEngine.connectionStateProperty().get() == ConnectionState.CONNECTED) {
                log.info("Server selection changed while connected; restarting tunnel");
                // One restart on one thread, like reconnectIfActiveServerChanged:
                // a separate disconnect() plus connect() spawned two virtual
                // threads racing for the same core, and when the stop outlasted
                // the connect's wait the user ended up disconnected.
                reconnect();
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
        String warning = warningFor(mode);
        if (warning != null) {
            proxyModeWarning.setText(warning);
            proxyModeWarning.setVisible(true);
            proxyModeWarning.setManaged(true);
        } else {
            proxyModeWarning.setText("");
            proxyModeWarning.setVisible(false);
            proxyModeWarning.setManaged(false);
        }
    }

    /**
     * The line to show under the mode selector, or null when there is nothing
     * to say.
     *
     * <p>SYSTEM_PROXY is the default mode, and on a Linux box with no GNOME
     * proxy schema the generator drops {@code set_system_proxy} — the tunnel
     * comes up, the health card is green, and nothing is proxied OS-wide.
     * That silence is the failure this app exists to prevent, so it gets a
     * line telling the user where to point their apps instead.</p>
     */
    private String warningFor(ProxyMode mode) {
        if (mode == ProxyMode.TUN) {
            return I18n.get("settings.tun.warning");
        }
        if (mode == ProxyMode.SYSTEM_PROXY && !systemProxyCapable()) {
            AppSettings settings = ServiceLocator.find(AppSettings.class).orElse(null);
            if (settings == null) {
                log.warn("Could not read the HTTP port for the proxy warning");
                return null;
            }
            // As a String: MessageFormat would render an int through
            // NumberFormat and turn port 8080 into "8,080".
            return I18n.get("settings.proxy.system.unsupported",
                    String.valueOf(settings.getHttpPort()));
        }
        return null;
    }

    /** The service, or null with a warning: the dashboard degrades rather than fails. */
    private static <T> T optional(Class<T> type, String absentMessage) {
        return ServiceLocator.find(type).orElseGet(() -> {
            log.warn(absentMessage);
            return null;
        });
    }

    /**
     * Applies one change to the stored settings and persists it.
     *
     * @return false when there is no store to save into
     */
    private static boolean saveSetting(java.util.function.Consumer<AppSettings> change) {
        ConfigStore configStore = ServiceLocator.find(ConfigStore.class).orElse(null);
        if (configStore == null) {
            return false;
        }
        AppSettings settings = configStore.getSettings();
        change.accept(settings);
        configStore.saveSettings(settings);
        return true;
    }

    private boolean systemProxyCapable() {
        if (systemProxyCapable == null) {
            systemProxyCapable = systemProxySupport.canAutoConfigure();
        }
        return systemProxyCapable;
    }

    /** Test seam: force the host verdict and drop the cached answer. */
    void setSystemProxySupport(SystemProxySupport support) {
        this.systemProxySupport = support;
        this.systemProxyCapable = null;
    }

    /**
     * Re-reads the two persisted selectors every time the dashboard is shown.
     *
     * <p>{@code initialize()} runs once per app run, but the proxy mode and the
     * server-selection policy have three other writers: Settings, and the MCP
     * tools {@code set_proxy_mode} and {@code connect}. Without this the combo
     * kept the value it was born with — and because the TUN warning is only
     * refreshed from the change listener, a mode switched to TUN elsewhere was
     * displayed as System proxy with no warning, while the next Connect raised
     * an admin prompt and built a TUN device. Routing itself was always right:
     * {@code ConnectionService.connect} reads the mode live.</p>
     *
     * <p>It also removes a trap. Re-picking the displayed-but-stale value fired
     * no change event, so the click that looked like it fixed the mode wrote
     * nothing at all.</p>
     */
    @Override
    public void onViewShown() {
        AppSettings settings = ServiceLocator.find(AppSettings.class).orElse(null);
        if (settings == null) {
            log.warn("Could not re-read settings on view show");
            return;
        }
        syncingFromSettings = true;
        try {
            proxyModeCombo.setValue(settings.getProxyMode());
            serverSelectionCombo.setValue(settings.getServerSelection());
        } finally {
            syncingFromSettings = false;
        }
        // setValue is a no-op when the value already matches, so the listener
        // that normally maintains this may not have run.
        updateProxyModeWarning(proxyModeCombo.getValue());
    }

    /**
     * Toggles connection state: connects if disconnected, disconnects if connected.
     * Used by keyboard shortcuts.
     */
    public void toggleConnection() {
        onConnectClicked();
    }

    /**
     * The process state to render and act on: the engine's when there is one,
     * otherwise the local property the no-engine path drives.
     */
    private ConnectionState currentConnectionState() {
        return singBoxEngine != null
                ? singBoxEngine.connectionStateProperty().get()
                : connectionState.get();
    }

    @FXML
    private void onConnectClicked() {
        ConnectionState current = currentConnectionState();

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


    /**
     * Connects on startup when the user enabled "Auto-connect on startup" in
     * Settings. Silently skips when prerequisites are missing (no sing-box
     * binary, no active server) so a fresh install does not show an error
     * dialog at every launch. Invoked once after the main window is shown.
     */
    public void autoConnectIfEnabled() {
        AppSettings settings = ServiceLocator.find(AppSettings.class).orElse(null);
        if (settings == null || !settings.isAutoConnect()) {
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
                case CANCELLED -> log.debug("Connect superseded by a newer user request");
                // STARTED: the engine's state listener drives the UI from here.
                default -> Platform.runLater(() -> setActiveServer(attempt.server()));
            }
        } catch (IOException e) {
            log.error("Failed to start sing-box", e);
            Platform.runLater(() -> {
                statusLabel.setText(I18n.get("error.connection.failed", e.getMessage()));
                showError(I18n.get("dashboard.error.start.title"), e.getMessage());
            });
        } catch (RuntimeException e) {
            // An unexpected internal failure. It used to escape this virtual
            // thread entirely: the finally below still re-enabled the button,
            // so the user was left clicking a live Connect while the status
            // pill sat on CONNECTING and nothing reached tunl.log. No modal
            // here on purpose — a raw exception string is not actionable, and
            // showAndWait deadlocks a headless run.
            log.error("Unexpected failure while starting sing-box", e);
            String detail = e.getMessage() != null ? e.getMessage() : e.toString();
            Platform.runLater(() ->
                    statusLabel.setText(I18n.get("error.connection.failed", detail)));
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
        return ServiceLocator.find(ConnectionService.class).orElseGet(() -> {
            log.error("ConnectionService not available; connect is disabled");
            return null;
        });
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
    private void onAddServerLinkClicked() {
        ServiceLocator.find(MainViewController.class).ifPresent(MainViewController::showServers);
    }

    @FXML
    private void onCancelReconnectClicked() {
        healthChecks.cancelReconnectCountdown();
    }

    private ServerConfig findActiveServer() {
        ConfigStore configStore = ServiceLocator.find(ConfigStore.class).orElse(null);
        if (configStore == null) {
            log.warn("ConfigStore not available; keeping the last known active server");
            return activeServer;
        }
        return configStore.getServers().stream()
                .filter(ServerConfig::isActive)
                .findFirst()
                .orElse(null);
    }

    private void refreshConnectButtonAvailability() {
        ConfigStore configStore = ServiceLocator.find(ConfigStore.class).orElse(null);
        if (configStore == null) {
            log.debug("ConfigStore not available while refreshing connect button");
            return;
        }
        List<ServerConfig> servers = configStore.getServers();
        // A fresh install used to show a disabled Connect and a sentence,
        // with the way forward two views away. The link is that way.
        addServerLink.setVisible(servers.isEmpty());
        addServerLink.setManaged(servers.isEmpty());
        if (servers.isEmpty()) {
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
    }

    private TunnelHealth currentHealth() {
        return healthState != null ? healthState.get() : TunnelHealth.UNMONITORED;
    }

    /** Repaints the hero card for a process state. */
    private void updateUi(ConnectionState state) {
        statusPresenter.update(state);
    }

    /**
     * Sets the active server from the server list.
     */
    public void setActiveServer(ServerConfig server) {
        this.activeServer = server;
        statusPresenter.showActiveServerName(server);
    }
}
