package com.vlessclient.ui.view;

import com.vlessclient.app.AppVersion;
import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.platform.Autostart;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ThemeManager;
import com.vlessclient.service.mcp.McpServerService;
import com.vlessclient.ui.view.settings.UpdatesSection;
import java.io.IOException;
import java.util.Locale;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for the Settings view.
 * Manages theme, language, proxy, and other application settings.
 */
public class SettingsViewController implements ViewShownAware {

    private static final Logger log = LoggerFactory.getLogger(SettingsViewController.class);

    @FXML private Label titleLabel;
    @FXML private Label appearanceLabel;
    @FXML private Label themeLabel;
    @FXML private Label languageLabel;
    @FXML private Label connectionLabel;
    @FXML private Label proxyPortsLabel;
    @FXML private Label socksPortLabel;
    @FXML private Label httpPortLabel;
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
    @FXML private ComboBox<ProxyMode> proxyModeCombo;
    @FXML private CheckBox systemProxyAutoConfigCheck;
    @FXML private CheckBox storeSecretsCheck;
    @FXML private TextField proxyDnsField;
    @FXML private TextField directDnsField;
    @FXML private TextField tunInterfaceNameField;
    @FXML private TextField tunIpv4Field;

    @FXML private Label updatesLabel;
    @FXML private Button checkUpdatesButton;

    @FXML private VBox appUpdateRow;
    @FXML private Circle appUpdateDot;
    @FXML private Label appUpdateDetail;
    @FXML private Button downloadAppButton;

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
    private McpServerService mcpServerService;
    private boolean suppressLaunchAtLoginListener;

    /**
     * Resolves the settings-related services and builds every section of the
     * Settings view (theme, language, connection, health check, proxy mode,
     * advanced, about/updates), then binds the localized labels.
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
        initProxyModeCombo(settings);
        initSystemProxyAutoConfig(settings);
        initAdvancedSettings(settings);
        initMcpSettings(settings);
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
        systemProxyAutoConfigCheck.setSelected(settings.isSystemProxyAutoConfig());
        systemProxyAutoConfigCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            settings.setSystemProxyAutoConfig(newVal);
            saveSettings(settings);
        });
    }

    private void initAdvancedSettings(AppSettings settings) {
        proxyDnsField.setText(settings.getProxyDns());
        proxyDnsField.textProperty().addListener((obs, oldVal, newVal) -> {
            settings.setProxyDns(newVal == null ? "" : newVal.trim());
            saveSettings(settings);
        });

        directDnsField.setText(settings.getDirectDns());
        directDnsField.textProperty().addListener((obs, oldVal, newVal) -> {
            settings.setDirectDns(newVal == null ? "" : newVal.trim());
            saveSettings(settings);
        });

        tunInterfaceNameField.setText(settings.getTunInterfaceName());
        tunInterfaceNameField.textProperty().addListener((obs, oldVal, newVal) -> {
            settings.setTunInterfaceName(newVal == null ? "" : newVal.trim());
            saveSettings(settings);
        });

        storeSecretsCheck.setSelected(settings.isStoreSecretsSecurely());
        storeSecretsCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            settings.setStoreSecretsSecurely(newVal);
            saveSettings(settings);
        });

        tunIpv4Field.setText(settings.getTunIpv4Address());
        tunIpv4Field.textProperty().addListener((obs, oldVal, newVal) -> {
            settings.setTunIpv4Address(newVal == null ? "" : newVal.trim());
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
        // Normalize any previously stored legacy "system" value to "auto" so it
        // matches a combo item and displays correctly.
        themeCombo.setValue(ThemeManager.normalize(settings.getTheme()));

        themeCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
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
        languageCombo.setValue(settings.getLanguage());

        languageCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
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
        autoConnectCheck.setSelected(settings.isAutoConnect());
        autoConnectCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            settings.setAutoConnect(newVal);
            saveSettings(settings);
        });

        initLaunchAtLogin();

        socksPortField.setText(String.valueOf(settings.getSocksPort()));
        socksPortField.textProperty().addListener((obs, oldVal, newVal) -> {
            int port = parsePort(newVal, 1080);
            settings.setSocksPort(port);
            saveSettings(settings);
        });

        httpPortField.setText(String.valueOf(settings.getHttpPort()));
        httpPortField.textProperty().addListener((obs, oldVal, newVal) -> {
            int port = parsePort(newVal, 1081);
            settings.setHttpPort(port);
            saveSettings(settings);
        });

        // Restrict port fields to numeric input
        addNumericFilter(socksPortField);
        addNumericFilter(httpPortField);
    }

    private void initHealthCheckSettings(AppSettings settings) {
        healthCheckEnabledCheck.setSelected(settings.isHealthCheckEnabled());
        healthCheckEnabledCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            settings.setHealthCheckEnabled(newVal);
            saveSettings(settings);
        });

        healthCheckAutoReconnectCheck.setSelected(settings.isHealthCheckAutoReconnect());
        healthCheckAutoReconnectCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            settings.setHealthCheckAutoReconnect(newVal);
            saveSettings(settings);
        });

        healthCheckIntervalField.setText(String.valueOf(settings.getHealthCheckIntervalSeconds()));
        healthCheckIntervalField.textProperty().addListener((obs, oldVal, newVal) -> {
            settings.setHealthCheckIntervalSeconds(parseSeconds(newVal, 5));
            saveSettings(settings);
        });

        healthCheckReconnectDelayField.setText(
                String.valueOf(settings.getHealthCheckDelaySeconds()));
        healthCheckReconnectDelayField.textProperty().addListener((obs, oldVal, newVal) -> {
            settings.setHealthCheckDelaySeconds(parseSeconds(newVal, 10));
            saveSettings(settings);
        });

        addNumericFilter(healthCheckIntervalField);
        addNumericFilter(healthCheckReconnectDelayField);
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
        launchAtLoginCheck.setSelected(autostart.isEnabled());
        launchAtLoginCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
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
        proxyModeCombo.setValue(settings.getProxyMode());

        proxyModeCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal != oldVal) {
                settings.setProxyMode(newVal);
                saveSettings(settings);
            }
        });
    }

    /**
     * Fills the About block and hands the whole Updates block (app row, async
     * sing-box version detection) over to {@link UpdatesSection}, which drives
     * the controls listed here.
     */
    private void initAboutSection() {
        appVersionValue.setText(AppVersion.VERSION);
        updatesSection = new UpdatesSection(new UpdatesSection.Controls(
                singboxVersionValue,
                checkUpdatesButton,
                appUpdateRow,
                appUpdateDot,
                appUpdateDetail,
                downloadAppButton));
        updatesSection.init();
    }

    /** The cached view is re-shown, not re-initialized: refresh stale rows. */
    @Override
    public void onViewShown() {
        if (updatesSection != null) {
            updatesSection.refreshOnOpen();
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
        proxyModeLabel.textProperty().bind(I18n.binding("settings.proxy.mode"));
        systemProxyAutoConfigCheck.textProperty()
                .bind(I18n.binding("settings.proxy.autoconfig"));
        storeSecretsCheck.textProperty().bind(I18n.binding("settings.store.secrets"));
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
        updatesLabel.textProperty().bind(I18n.binding("settings.updates"));
        checkUpdatesButton.textProperty().bind(I18n.binding("settings.updates.check"));
        downloadAppButton.textProperty().bind(I18n.binding("settings.update.download"));
        advancedLabel.textProperty().bind(I18n.binding("settings.advanced"));
        proxyDnsLabel.textProperty().bind(I18n.binding("settings.proxy.dns"));
        directDnsLabel.textProperty().bind(I18n.binding("settings.direct.dns"));
        tunInterfaceNameLabel.textProperty().bind(I18n.binding("settings.tun.interface"));
        tunIpv4Label.textProperty().bind(I18n.binding("settings.tun.ipv4"));
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
        mcpEnabledCheck.setSelected(settings.isMcpEnabled());
        mcpEnabledCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            settings.setMcpEnabled(newVal);
            saveSettings(settings);
            applyMcp();
        });

        mcpPortField.setText(String.valueOf(settings.getMcpPort()));
        addNumericFilter(mcpPortField);
        mcpPortField.textProperty().addListener((obs, oldVal, newVal) -> {
            settings.setMcpPort(parsePort(newVal, 55555));
            saveSettings(settings);
            applyMcp();
        });

        mcpAllowMutationsCheck.setSelected(settings.isMcpAllowMutations());
        mcpAllowMutationsCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
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
        }
        refreshMcpCommand();
    }

    private void refreshMcpCommand() {
        if (mcpServerService == null) {
            mcpCommandArea.setText("");
            mcpStatusLabel.setText("");
            return;
        }
        mcpCommandArea.setText(mcpServerService.claudeAddCommand());
        mcpStatusLabel.setText(mcpServerService.isRunning() ? "● running" : "○ stopped");
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

    @FXML
    private void onRegenerateMcpToken() {
        if (mcpServerService == null) {
            return;
        }
        mcpServerService.regenerateToken();
        refreshMcpCommand();
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

    private void addNumericFilter(TextField field) {
        field.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.matches("\\d*")) {
                field.setText(oldVal);
            }
        });
    }
}
