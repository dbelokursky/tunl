package com.vlessclient.ui.view;

import com.vlessclient.app.AppVersion;
import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.CoreLogLevel;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.platform.Autostart;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ThemeManager;
import com.vlessclient.service.TrafficHistoryStore;
import com.vlessclient.service.mcp.McpServerService;
import com.vlessclient.ui.view.settings.TrafficHistorySettingsSection;
import com.vlessclient.ui.view.settings.UpdatesSection;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for the Settings view.
 * Manages theme, language, proxy, and other application settings.
 */
public class SettingsViewController implements ViewShownAware {

    /** Where a text field keeps the text it last committed, among its properties. */
    private static final Object COMMITTED = new Object();
    private static final Logger log = LoggerFactory.getLogger(SettingsViewController.class);

    @FXML private Label titleLabel;
    @FXML private Label appearanceLabel;
    @FXML private Label themeLabel;
    @FXML private Label languageLabel;
    @FXML private Label connectionLabel;
    @FXML private Label proxyPortsLabel;
    @FXML private Label socksPortLabel;
    @FXML private Label httpPortLabel;
    @FXML private Label coreLogLevelLabel;
    @FXML private Label coreLogLevelHint;
    @FXML private Label proxyModeLabel;
    @FXML private Label aboutLabel;
    @FXML private Label geoAttributionLabel;
    @FXML private Label appVersionLabel;
    @FXML private Label singboxVersionLabel;
    @FXML private Label appVersionValue;
    @FXML private Label singboxVersionValue;
    @FXML private Label healthCheckLabel;
    @FXML private Label healthCheckIntervalLabel;
    @FXML private Label healthCheckReconnectDelayLabel;
    @FXML private Label advancedLabel;
    @FXML private Label proxyDnsLabel;
    @FXML private Label directDnsLabel;
    @FXML private Label tunInterfaceNameLabel;
    @FXML private Label tunIpv4Label;

    @FXML private ComboBox<String> themeCombo;
    @FXML private ComboBox<String> languageCombo;
    @FXML private CheckBox autoConnectCheck;
    @FXML private CheckBox launchAtLoginCheck;
    @FXML private TextField socksPortField;
    @FXML private TextField httpPortField;
    @FXML private CheckBox healthCheckEnabledCheck;
    @FXML private CheckBox healthCheckAutoReconnectCheck;
    @FXML private TextField healthCheckIntervalField;
    @FXML private TextField healthCheckReconnectDelayField;
    @FXML private ComboBox<CoreLogLevel> coreLogLevelCombo;
    @FXML private ComboBox<ProxyMode> proxyModeCombo;
    @FXML private CheckBox systemProxyAutoConfigCheck;
    @FXML private CheckBox storeSecretsCheck;
    @FXML private Label deviceIdLabel;
    @FXML private Label deviceIdValue;
    @FXML private Button deviceIdResetButton;
    @FXML private Label deviceIdHint;
    @FXML private TextField proxyDnsField;
    @FXML private TextField directDnsField;
    @FXML private TextField tunInterfaceNameField;
    @FXML private TextField tunIpv4Field;
    @FXML private CheckBox tunIpv6Check;
    @FXML private Label tunIpv6Hint;

    @FXML private Button checkUpdatesButton;

    @FXML private Button appUpdateButton;

    @FXML private Label trafficHistoryLabel;
    @FXML private Label trafficHistoryRecordedLabel;
    @FXML private Label trafficHistorySummary;
    @FXML private Label trafficHistoryHint;
    @FXML private Button clearTrafficHistoryButton;

    @FXML private Label mcpSectionTitle;
    @FXML private Label mcpHintLabel;
    @FXML private Label mcpPortLabel;
    @FXML private Label mcpCommandLabel;
    @FXML private CheckBox mcpEnabledCheck;
    @FXML private TextField mcpPortField;
    @FXML private CheckBox mcpAllowMutationsCheck;
    @FXML private Label mcpStatusLabel;
    @FXML private TextArea mcpCommandArea;
    @FXML private Button mcpCopyButton;
    @FXML private Button mcpRegenButton;

    private ConfigStore configStore;
    private ThemeManager themeManager;
    private Autostart autostart;
    private UpdatesSection updatesSection;
    private TrafficHistorySettingsSection trafficHistorySection;
    private McpServerService mcpServerService;
    private boolean updatingMcpControls;
    private boolean suppressLaunchAtLoginListener;
    /** Set while {@link #showStored} fills the controls, so no listener acts on it. */
    private boolean showingStored;

    /**
     * Resolves the settings-related services and builds every section of the
     * Settings view (theme, language, connection, health check, proxy mode,
     * advanced, traffic history, about/updates), then binds the localized
     * labels.
     */
    @FXML
    public void initialize() {
        try {
            configStore = ServiceLocator.get(ConfigStore.class);
        } catch (IllegalArgumentException e) {
            log.warn("ConfigStore not available");
            return;
        }

        try {
            themeManager = ServiceLocator.get(ThemeManager.class);
        } catch (IllegalArgumentException e) {
            log.warn("ThemeManager not available");
        }

        try {
            autostart = ServiceLocator.get(Autostart.class);
        } catch (IllegalArgumentException e) {
            log.warn("Autostart not available");
        }

        try {
            mcpServerService = ServiceLocator.get(McpServerService.class);
        } catch (IllegalArgumentException e) {
            log.warn("McpServerService not available");
        }

        AppSettings settings = configStore.getSettings();

        initThemeCombo(settings);
        initLanguageCombo(settings);
        initConnectionSettings(settings);
        initHealthCheckSettings(settings);
        initCoreLogLevelCombo(settings);
        initProxyModeCombo(settings);
        initSystemProxyAutoConfig(settings);
        initAdvancedSettings(settings);
        initMcpSettings(settings);
        showStored();
        initTrafficHistorySection();
        initAboutSection();
        bindLabels();
    }

    /**
     * Wires the "Set system proxy automatically" toggle: in SYSTEM_PROXY mode
     * sing-box registers its http inbound as the OS proxy on connect and
     * restores the previous state on disconnect. Applies from the next
     * connect.
     */
    private void initSystemProxyAutoConfig(AppSettings settings) {
        onUserChange(systemProxyAutoConfigCheck.selectedProperty(), (oldVal, newVal) -> {
            settings.setSystemProxyAutoConfig(newVal);
            saveSettings(settings);
        });
    }

    private void initAdvancedSettings(AppSettings settings) {
        commitOnEditEnd(proxyDnsField, text -> {
            settings.setProxyDns(text);
            saveSettings(settings);
        });

        commitOnEditEnd(directDnsField, text -> {
            settings.setDirectDns(text);
            saveSettings(settings);
        });

        commitOnEditEnd(tunInterfaceNameField, text -> {
            settings.setTunInterfaceName(text);
            saveSettings(settings);
        });

        deviceIdValue.setText(configStore.deviceId());

        onUserChange(storeSecretsCheck.selectedProperty(), (oldVal, newVal) -> {
            settings.setStoreSecretsSecurely(newVal);
            saveSettings(settings);
        });

        commitOnEditEnd(tunIpv4Field, text -> {
            settings.setTunIpv4Address(text);
            saveSettings(settings);
        });

        onUserChange(tunIpv6Check.selectedProperty(), (oldVal, newVal) -> {
            settings.setTunIpv6Enabled(newVal);
            saveSettings(settings);
        });
    }

    private void initThemeCombo(AppSettings settings) {
        themeCombo.getItems().addAll("auto", "light", "dark");
        themeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String value) {
                if (value == null) {
                    return "";
                }
                return switch (value) {
                    case "auto", "system" -> I18n.get("settings.theme.auto");
                    case "light" -> I18n.get("settings.theme.light");
                    case "dark" -> I18n.get("settings.theme.dark");
                    default -> value;
                };
            }

            @Override
            public String fromString(String string) {
                return string;
            }
        });

        onUserChange(themeCombo.valueProperty(), (oldVal, newVal) -> {
            if (newVal != null && !newVal.equals(oldVal)) {
                settings.setTheme(newVal);
                if (themeManager != null) {
                    themeManager.setTheme(newVal);
                    if (themeCombo.getScene() != null) {
                        themeManager.applyTheme(themeCombo.getScene());
                    }
                }
                saveSettings(settings);
            }
        });
    }

    private void initLanguageCombo(AppSettings settings) {
        languageCombo.getItems().addAll("en", "ru");
        languageCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String value) {
                if (value == null) {
                    return "";
                }
                return switch (value) {
                    case "en" -> "English";
                    case "ru" -> "Русский";
                    default -> value;
                };
            }

            @Override
            public String fromString(String string) {
                return string;
            }
        });

        onUserChange(languageCombo.valueProperty(), (oldVal, newVal) -> {
            if (newVal != null && !newVal.equals(oldVal)) {
                settings.setLanguage(newVal);
                Locale newLocale = "ru".equals(newVal) ? Locale.of("ru") : Locale.ENGLISH;
                I18n.setLocale(newLocale);
                saveSettings(settings);
                refreshLabels();
            }
        });
    }

    private void initConnectionSettings(AppSettings settings) {
        onUserChange(autoConnectCheck.selectedProperty(), (oldVal, newVal) -> {
            settings.setAutoConnect(newVal);
            saveSettings(settings);
        });

        initLaunchAtLogin();

        restrictToDigits(socksPortField);
        commitOnEditEnd(socksPortField, text -> {
            // An empty or out-of-range entry keeps the stored port rather than
            // resetting a customized one to the default.
            int port = parsePort(text, settings.getSocksPort());
            settings.setSocksPort(port);
            socksPortField.setText(String.valueOf(port));
            saveSettings(settings);
        });

        restrictToDigits(httpPortField);
        commitOnEditEnd(httpPortField, text -> {
            int port = parsePort(text, settings.getHttpPort());
            settings.setHttpPort(port);
            httpPortField.setText(String.valueOf(port));
            saveSettings(settings);
        });
    }

    private void initHealthCheckSettings(AppSettings settings) {
        onUserChange(healthCheckEnabledCheck.selectedProperty(), (oldVal, newVal) -> {
            settings.setHealthCheckEnabled(newVal);
            saveSettings(settings);
        });

        onUserChange(healthCheckAutoReconnectCheck.selectedProperty(), (oldVal, newVal) -> {
            settings.setHealthCheckAutoReconnect(newVal);
            saveSettings(settings);
        });

        restrictToDigits(healthCheckIntervalField);
        commitOnEditEnd(healthCheckIntervalField, text -> {
            int seconds = parseSeconds(text, settings.getHealthCheckIntervalSeconds());
            settings.setHealthCheckIntervalSeconds(seconds);
            healthCheckIntervalField.setText(String.valueOf(seconds));
            saveSettings(settings);
        });

        restrictToDigits(healthCheckReconnectDelayField);
        commitOnEditEnd(healthCheckReconnectDelayField, text -> {
            int seconds = parseSeconds(text, settings.getHealthCheckDelaySeconds());
            settings.setHealthCheckDelaySeconds(seconds);
            healthCheckReconnectDelayField.setText(String.valueOf(seconds));
            saveSettings(settings);
        });
    }

    /**
     * Wires the "Launch at login" checkbox to the macOS LaunchAgent. The
     * checkbox reflects whether the plist is actually installed (the source
     * of truth) rather than a saved setting, so it stays correct even if the
     * user removed the agent from System Settings. On a write failure the
     * checkbox reverts so it never claims a state that did not take effect.
     */
    private void initLaunchAtLogin() {
        if (autostart == null) {
            launchAtLoginCheck.setDisable(true);
            return;
        }
        onUserChange(launchAtLoginCheck.selectedProperty(), (oldVal, newVal) -> {
            if (suppressLaunchAtLoginListener) {
                return;
            }
            try {
                autostart.setEnabled(newVal);
            } catch (IOException e) {
                log.error("Failed to {} launch at login", newVal ? "enable" : "disable", e);
                suppressLaunchAtLoginListener = true;
                launchAtLoginCheck.setSelected(oldVal);
                suppressLaunchAtLoginListener = false;
            }
        });
    }

    /**
     * Wires the core log level combo. The core reads its verbosity from the
     * config generated at connect time, so a change here reaches sing-box on
     * the next connect rather than the running process — which is what the
     * hint under the combo says out loud.
     */
    private void initCoreLogLevelCombo(AppSettings settings) {
        coreLogLevelCombo.getItems().addAll(CoreLogLevel.values());
        coreLogLevelCombo.setCellFactory(cb -> new ListCell<>() {
            @Override
            protected void updateItem(CoreLogLevel item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatCoreLogLevel(item));
            }
        });
        coreLogLevelCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(CoreLogLevel item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatCoreLogLevel(item));
            }
        });

        onUserChange(coreLogLevelCombo.valueProperty(), (oldVal, newVal) -> {
            if (newVal != null && newVal != oldVal) {
                settings.setCoreLogLevel(newVal);
                saveSettings(settings);
            }
        });
    }

    private void initProxyModeCombo(AppSettings settings) {
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

        onUserChange(proxyModeCombo.valueProperty(), (oldVal, newVal) -> {
            if (newVal != null && newVal != oldVal) {
                settings.setProxyMode(newVal);
                saveSettings(settings);
            }
        });
    }

    /**
     * Hands the Traffic history card to its section. The store is optional
     * like every collaborator here: without one the card says nothing is
     * recorded and its button stays off.
     */
    private void initTrafficHistorySection() {
        trafficHistorySection = new TrafficHistorySettingsSection(
                ServiceLocator.find(TrafficHistoryStore.class).orElse(null),
                new TrafficHistorySettingsSection.Controls(
                        trafficHistorySummary, clearTrafficHistoryButton),
                TrafficHistorySettingsSection::confirmWithDialog);
        trafficHistorySection.init();
    }

    /**
     * Hands the About block's two version rows and the Updates header over to
     * {@link UpdatesSection}, which drives the controls listed here. The app
     * version is set there too, since what the row says depends on what the
     * updater knows.
     */
    private void initAboutSection() {
        updatesSection = new UpdatesSection(new UpdatesSection.Controls(
                appVersionValue,
                singboxVersionValue,
                checkUpdatesButton,
                appUpdateButton));
        updatesSection.init();
    }

    /** The cached view is re-shown, not re-initialized: refresh stale rows. */
    @Override
    public void onViewShown() {
        showStored();
        refreshMcpCommand();
        if (updatesSection != null) {
            updatesSection.refreshOnOpen();
        }
        if (trafficHistorySection != null) {
            trafficHistorySection.refresh();
        }
    }

    private void bindLabels() {
        titleLabel.textProperty().bind(I18n.binding("settings.title"));
        appearanceLabel.textProperty().bind(I18n.binding("settings.appearance"));
        themeLabel.textProperty().bind(I18n.binding("settings.theme"));
        languageLabel.textProperty().bind(I18n.binding("settings.language"));
        connectionLabel.textProperty().bind(I18n.binding("settings.connection"));
        autoConnectCheck.textProperty().bind(I18n.binding("settings.auto.connect"));
        launchAtLoginCheck.textProperty().bind(I18n.binding("settings.launch.at.login"));
        proxyPortsLabel.textProperty().bind(I18n.binding("settings.proxy.ports"));
        socksPortLabel.textProperty().bind(I18n.binding("settings.socks.port"));
        httpPortLabel.textProperty().bind(I18n.binding("settings.http.port"));
        coreLogLevelLabel.textProperty().bind(I18n.binding("settings.core.log.level"));
        coreLogLevelHint.textProperty().bind(I18n.binding("settings.core.log.level.hint"));
        proxyModeLabel.textProperty().bind(I18n.binding("settings.proxy.mode"));
        systemProxyAutoConfigCheck.textProperty()
                .bind(I18n.binding("settings.proxy.autoconfig"));
        storeSecretsCheck.textProperty().bind(I18n.binding("settings.store.secrets"));
        deviceIdLabel.textProperty().bind(I18n.binding("settings.device.id"));
        deviceIdHint.textProperty().bind(I18n.binding("settings.device.id.hint"));
        ButtonLabels.bindStatic(deviceIdResetButton, "settings.device.id.reset");
        healthCheckLabel.textProperty().bind(I18n.binding("settings.health.check"));
        healthCheckEnabledCheck.textProperty().bind(I18n.binding("settings.health.check.enabled"));
        healthCheckAutoReconnectCheck.textProperty()
                .bind(I18n.binding("settings.health.check.auto.reconnect"));
        healthCheckIntervalLabel.textProperty()
                .bind(I18n.binding("settings.health.check.interval"));
        healthCheckReconnectDelayLabel.textProperty()
                .bind(I18n.binding("settings.health.check.reconnect.delay"));
        aboutLabel.textProperty().bind(I18n.binding("settings.about"));
        if (geoAttributionLabel != null) {
            geoAttributionLabel.textProperty().bind(I18n.binding("settings.geo.attribution"));
        }
        appVersionLabel.textProperty().bind(I18n.binding("settings.app.version"));
        singboxVersionLabel.textProperty().bind(I18n.binding("settings.singbox.version"));
        // Sized to its own label each, now that the pair shares one row: two
        // adjacent buttons at their natural widths read as two actions, which
        // is what they are. They were pinned to a shared width while they
        // stood a row apart, where unequal widths read as two different
        // controls instead of one used twice.
        ButtonLabels.bind(checkUpdatesButton, "settings.updates.check");
        ButtonLabels.bind(appUpdateButton, "settings.update.restart");
        advancedLabel.textProperty().bind(I18n.binding("settings.advanced"));
        proxyDnsLabel.textProperty().bind(I18n.binding("settings.proxy.dns"));
        directDnsLabel.textProperty().bind(I18n.binding("settings.direct.dns"));
        tunInterfaceNameLabel.textProperty().bind(I18n.binding("settings.tun.interface"));
        tunIpv4Label.textProperty().bind(I18n.binding("settings.tun.ipv4"));
        tunIpv6Check.textProperty().bind(I18n.binding("settings.tun.ipv6"));
        tunIpv6Hint.textProperty().bind(I18n.binding("settings.tun.ipv6.hint"));
        // The whole MCP block sat in the FXML in English.
        mcpSectionTitle.textProperty().bind(I18n.binding("settings.mcp.title"));
        mcpHintLabel.textProperty().bind(I18n.binding("settings.mcp.hint"));
        mcpEnabledCheck.textProperty().bind(I18n.binding("settings.mcp.enable"));
        mcpPortLabel.textProperty().bind(I18n.binding("settings.mcp.port"));
        mcpAllowMutationsCheck.textProperty().bind(I18n.binding("settings.mcp.allow.mutations"));
        mcpCommandLabel.textProperty().bind(I18n.binding("settings.mcp.command.label"));
        ButtonLabels.bindStatic(mcpCopyButton, "settings.mcp.copy");
        ButtonLabels.bindStatic(mcpRegenButton, "settings.mcp.regenerate");
        trafficHistoryLabel.textProperty().bind(I18n.binding("settings.traffic.history"));
        trafficHistoryRecordedLabel.textProperty()
                .bind(I18n.binding("settings.traffic.history.recorded"));
        trafficHistoryHint.textProperty().bind(I18n.binding("settings.traffic.history.hint"));
        ButtonLabels.bindStatic(clearTrafficHistoryButton, "settings.traffic.history.clear");
    }

    private void refreshLabels() {
        // Force theme combo to re-render display text
        String currentTheme = themeCombo.getValue();
        themeCombo.setValue(null);
        themeCombo.setValue(currentTheme);

        // Force proxy mode combo to re-render display text
        ProxyMode currentMode = proxyModeCombo.getValue();
        proxyModeCombo.setValue(null);
        proxyModeCombo.setValue(currentMode);

        // ...and the core log level combo
        CoreLogLevel currentLevel = coreLogLevelCombo.getValue();
        coreLogLevelCombo.setValue(null);
        coreLogLevelCombo.setValue(currentLevel);
    }

    private String formatCoreLogLevel(CoreLogLevel level) {
        return switch (level) {
            case DEBUG -> I18n.get("settings.core.log.level.debug");
            case INFO -> I18n.get("settings.core.log.level.info");
            case WARN -> I18n.get("settings.core.log.level.warn");
            case ERROR -> I18n.get("settings.core.log.level.error");
        };
    }

    private String formatProxyMode(ProxyMode mode) {
        return switch (mode) {
            case SYSTEM_PROXY -> I18n.get("settings.proxy.system");
            case TUN -> I18n.get("settings.proxy.tun");
        };
    }

    private void saveSettings(AppSettings settings) {
        if (configStore != null) {
            configStore.saveSettings(settings);
        }
    }

    /**
     * Wires the Agent Control (MCP) section: the enable toggle, port and
     * mutation checkbox persist and reconcile the running server via
     * {@link McpServerService#apply()}; the copy/regenerate buttons act on the
     * bearer token and the ready-to-run {@code claude mcp add} command.
     *
     * @param settings the settings instance to read and mutate
     */
    private void initMcpSettings(AppSettings settings) {
        onUserChange(mcpEnabledCheck.selectedProperty(), (oldVal, newVal) -> {
            if (updatingMcpControls) {
                return;
            }
            settings.setMcpEnabled(newVal);
            saveSettings(settings);
            applyMcp();
        });

        restrictToDigits(mcpPortField);
        commitOnEditEnd(mcpPortField, text -> {
            int port = parsePort(text, settings.getMcpPort());
            settings.setMcpPort(port);
            mcpPortField.setText(String.valueOf(port));
            saveSettings(settings);
            applyMcp();
        });

        onUserChange(mcpAllowMutationsCheck.selectedProperty(), (oldVal, newVal) -> {
            settings.setMcpAllowMutations(newVal);
            saveSettings(settings);
        });

        if (mcpServerService == null) {
            mcpEnabledCheck.setDisable(true);
            mcpPortField.setDisable(true);
            mcpAllowMutationsCheck.setDisable(true);
            mcpCopyButton.setDisable(true);
            mcpRegenButton.setDisable(true);
        }
        refreshMcpCommand();
    }

    private void applyMcp() {
        if (mcpServerService != null) {
            mcpServerService.apply();
            syncMcpEnabledCheck();
        }
        refreshMcpCommand();
    }

    private void syncMcpEnabledCheck() {
        boolean enabled = configStore.getSettings().isMcpEnabled();
        if (mcpEnabledCheck.isSelected() == enabled) {
            return;
        }
        updatingMcpControls = true;
        try {
            mcpEnabledCheck.setSelected(enabled);
        } finally {
            updatingMcpControls = false;
        }
    }

    private void refreshMcpCommand() {
        if (mcpServerService == null) {
            mcpCommandArea.setText("");
            mcpStatusLabel.setText("");
            return;
        }
        mcpCommandArea.setText(mcpServerService.claudeAddCommand());
        String startError = mcpServerService.getLastStartError();
        if (startError != null) {
            mcpStatusLabel.setText(I18n.get("settings.mcp.status.failed", startError));
        } else {
            mcpStatusLabel.setText(I18n.get(mcpServerService.isRunning()
                    ? "settings.mcp.status.running" : "settings.mcp.status.stopped"));
        }
    }

    @FXML
    private void onCopyMcpCommand() {
        if (mcpServerService == null) {
            return;
        }
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(mcpServerService.claudeAddCommand());
        clipboard.setContent(content);
    }

    /**
     * Draws a new device id, after asking: a provider that limits devices per
     * plan counts the new one as another device.
     */
    @FXML
    private void onResetDeviceId() {
        if (configStore == null || !Confirmations.confirmIrreversible(mcpDialogOwner(),
                I18n.get("settings.device.id.reset.title"),
                I18n.get("settings.device.id.reset.confirm"),
                I18n.get("settings.device.id.reset.content"),
                I18n.get("settings.device.id.reset.action"))) {
            return;
        }
        AppSettings settings = configStore.getSettings();
        settings.setDeviceId(ConfigStore.newDeviceId());
        saveSettings(settings);
        deviceIdValue.setText(settings.getDeviceId());
    }

    @FXML
    private void onRegenerateMcpToken() {
        // A new token takes effect at once, and every agent set up with the
        // old one loses access: it used to happen on one click.
        if (mcpServerService == null || !Confirmations.confirmIrreversible(mcpDialogOwner(),
                I18n.get("settings.mcp.regenerate.title"),
                I18n.get("settings.mcp.regenerate.confirm"),
                I18n.get("settings.mcp.regenerate.content"),
                I18n.get("settings.mcp.regenerate.action"))) {
            return;
        }
        mcpServerService.regenerateToken();
        refreshMcpCommand();
    }

    /** The window the MCP buttons are in, or null while the view is in none. */
    private Window mcpDialogOwner() {
        Scene scene = mcpRegenButton.getScene();
        return scene == null ? null : scene.getWindow();
    }

    private int parsePort(String text, int defaultPort) {
        if (text == null || text.isBlank()) {
            return defaultPort;
        }
        try {
            int port = Integer.parseInt(text.trim());
            if (port > 0 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        return defaultPort;
    }

    private int parseSeconds(String text, int defaultSeconds) {
        if (text == null || text.isBlank()) {
            return defaultSeconds;
        }
        try {
            int seconds = Integer.parseInt(text.trim());
            if (seconds >= 1) {
                return seconds;
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        return defaultSeconds;
    }

    /**
     * Fills every control from the stored settings: when the page is built,
     * and each time it is shown again. The page is cached while settings
     * change elsewhere, the mode on the Dashboard and anything an agent sets
     * through MCP, and it kept showing the values it was built with. Filling
     * the controls is not a change: no listener saves or applies anything
     * meanwhile, and a text field counts what it shows as committed.
     */
    private void showStored() {
        if (configStore == null) {
            return;
        }
        AppSettings settings = configStore.getSettings();
        showingStored = true;
        try {
            // A legacy stored "system" shows as "auto", the item it became.
            themeCombo.setValue(ThemeManager.normalize(settings.getTheme()));
            languageCombo.setValue(settings.getLanguage());
            autoConnectCheck.setSelected(settings.isAutoConnect());
            if (autostart != null) {
                launchAtLoginCheck.setSelected(autostart.isEnabled());
            }
            showCommitted(socksPortField, String.valueOf(settings.getSocksPort()));
            showCommitted(httpPortField, String.valueOf(settings.getHttpPort()));
            healthCheckEnabledCheck.setSelected(settings.isHealthCheckEnabled());
            healthCheckAutoReconnectCheck.setSelected(settings.isHealthCheckAutoReconnect());
            showCommitted(healthCheckIntervalField,
                    String.valueOf(settings.getHealthCheckIntervalSeconds()));
            showCommitted(healthCheckReconnectDelayField,
                    String.valueOf(settings.getHealthCheckDelaySeconds()));
            coreLogLevelCombo.setValue(settings.getCoreLogLevel());
            proxyModeCombo.setValue(settings.getProxyMode());
            systemProxyAutoConfigCheck.setSelected(settings.isSystemProxyAutoConfig());
            showCommitted(proxyDnsField, settings.getProxyDns());
            showCommitted(directDnsField, settings.getDirectDns());
            showCommitted(tunInterfaceNameField, settings.getTunInterfaceName());
            storeSecretsCheck.setSelected(settings.isStoreSecretsSecurely());
            showCommitted(tunIpv4Field, settings.getTunIpv4Address());
            tunIpv6Check.setSelected(settings.isTunIpv6Enabled());
            mcpEnabledCheck.setSelected(settings.isMcpEnabled());
            showCommitted(mcpPortField, String.valueOf(settings.getMcpPort()));
            mcpAllowMutationsCheck.setSelected(settings.isMcpAllowMutations());
        } finally {
            showingStored = false;
        }
    }

    /**
     * Runs {@code onChange} for a change the user made to a control, and not
     * for one {@link #showStored} made to show the stored value.
     */
    private <T> void onUserChange(ObservableValue<T> value, BiConsumer<T, T> onChange) {
        value.addListener((obs, oldVal, newVal) -> {
            if (!showingStored) {
                onChange.accept(oldVal, newVal);
            }
        });
    }

    /** Shows {@code text} in a field as already committed, so leaving it saves nothing. */
    private static void showCommitted(TextField field, String text) {
        field.setText(text);
        field.getProperties().put(COMMITTED, trimmed(field.getText()));
    }

    /**
     * Commits a text field when editing ends — focus leaving the field, or
     * Enter — rather than on every keystroke.
     *
     * <p>The per-keystroke listeners wrote settings.json (a temp file plus an
     * atomic rename) on every character, and the MCP port one also restarted
     * the listener: typing {@code 55556} rebound it on 5, 55, 555, 5555 and
     * 55556, with the momentarily empty field silently substituting the
     * default. Committing once per edit also means a half-typed value never
     * reaches the store.</p>
     *
     * @param field  the field to watch
     * @param commit receives the trimmed text; may normalize what the field
     *               shows (an invalid port is put back to the stored one)
     */
    private static void commitOnEditEnd(TextField field, Consumer<String> commit) {
        field.getProperties().put(COMMITTED, trimmed(field.getText()));
        Runnable fire = () -> {
            String text = trimmed(field.getText());
            if (text.equals(field.getProperties().get(COMMITTED))) {
                return;
            }
            commit.accept(text);
            field.getProperties().put(COMMITTED, trimmed(field.getText()));
        };
        field.setOnAction(event -> fire.run());
        field.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                fire.run();
            }
        });
    }

    private static String trimmed(String text) {
        return text == null ? "" : text.trim();
    }

    /**
     * Lets only digits into a field. A formatter filter rejects the change
     * before it lands, where the old text listener re-set the previous text
     * after the fact and re-entered every other listener on the field.
     */
    private static void restrictToDigits(TextField field) {
        field.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().matches("\\d*") ? change : null));
    }
}
