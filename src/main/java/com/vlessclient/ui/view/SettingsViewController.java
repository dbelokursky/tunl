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
import com.vlessclient.service.mcp.McpServerService;
import com.vlessclient.ui.view.settings.UpdatesSection;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javafx.fxml.FXML;
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
    @FXML private TextField proxyDnsField;
    @FXML private TextField directDnsField;
    @FXML private TextField tunInterfaceNameField;
    @FXML private TextField tunIpv4Field;

    @FXML private Button checkUpdatesButton;

    @FXML private Button appUpdateButton;

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
    private McpServerService mcpServerService;
    private boolean updatingMcpControls;
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
        initCoreLogLevelCombo(settings);
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
        commitOnEditEnd(proxyDnsField, text -> {
            settings.setProxyDns(text);
            saveSettings(settings);
        });

        directDnsField.setText(settings.getDirectDns());
        commitOnEditEnd(directDnsField, text -> {
            settings.setDirectDns(text);
            saveSettings(settings);
        });

        tunInterfaceNameField.setText(settings.getTunInterfaceName());
        commitOnEditEnd(tunInterfaceNameField, text -> {
            settings.setTunInterfaceName(text);
            saveSettings(settings);
        });

        storeSecretsCheck.setSelected(settings.isStoreSecretsSecurely());
        storeSecretsCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            settings.setStoreSecretsSecurely(newVal);
            saveSettings(settings);
        });

        tunIpv4Field.setText(settings.getTunIpv4Address());
        commitOnEditEnd(tunIpv4Field, text -> {
            settings.setTunIpv4Address(text);
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

        restrictToDigits(socksPortField);
        socksPortField.setText(String.valueOf(settings.getSocksPort()));
        commitOnEditEnd(socksPortField, text -> {
            // An empty or out-of-range entry keeps the stored port rather than
            // resetting a customized one to the default.
            int port = parsePort(text, settings.getSocksPort());
            settings.setSocksPort(port);
            socksPortField.setText(String.valueOf(port));
            saveSettings(settings);
        });

        restrictToDigits(httpPortField);
        httpPortField.setText(String.valueOf(settings.getHttpPort()));
        commitOnEditEnd(httpPortField, text -> {
            int port = parsePort(text, settings.getHttpPort());
            settings.setHttpPort(port);
            httpPortField.setText(String.valueOf(port));
            saveSettings(settings);
        });
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

        restrictToDigits(healthCheckIntervalField);
        healthCheckIntervalField.setText(String.valueOf(settings.getHealthCheckIntervalSeconds()));
        commitOnEditEnd(healthCheckIntervalField, text -> {
            int seconds = parseSeconds(text, settings.getHealthCheckIntervalSeconds());
            settings.setHealthCheckIntervalSeconds(seconds);
            healthCheckIntervalField.setText(String.valueOf(seconds));
            saveSettings(settings);
        });

        restrictToDigits(healthCheckReconnectDelayField);
        healthCheckReconnectDelayField.setText(
                String.valueOf(settings.getHealthCheckDelaySeconds()));
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
        coreLogLevelCombo.setValue(settings.getCoreLogLevel());

        coreLogLevelCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
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
        proxyModeCombo.setValue(settings.getProxyMode());

        proxyModeCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal != oldVal) {
                settings.setProxyMode(newVal);
                saveSettings(settings);
            }
        });
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
        coreLogLevelLabel.textProperty().bind(I18n.binding("settings.core.log.level"));
        coreLogLevelHint.textProperty().bind(I18n.binding("settings.core.log.level.hint"));
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
        // The whole MCP block sat in the FXML in English.
        mcpSectionTitle.textProperty().bind(I18n.binding("settings.mcp.title"));
        mcpHintLabel.textProperty().bind(I18n.binding("settings.mcp.hint"));
        mcpEnabledCheck.textProperty().bind(I18n.binding("settings.mcp.enable"));
        mcpPortLabel.textProperty().bind(I18n.binding("settings.mcp.port"));
        mcpAllowMutationsCheck.textProperty().bind(I18n.binding("settings.mcp.allow.mutations"));
        mcpCommandLabel.textProperty().bind(I18n.binding("settings.mcp.command.label"));
        ButtonLabels.bindStatic(mcpCopyButton, "settings.mcp.copy");
        ButtonLabels.bindStatic(mcpRegenButton, "settings.mcp.regenerate");
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
        mcpEnabledCheck.setSelected(settings.isMcpEnabled());
        mcpEnabledCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (updatingMcpControls) {
                return;
            }
            settings.setMcpEnabled(newVal);
            saveSettings(settings);
            applyMcp();
        });

        restrictToDigits(mcpPortField);
        mcpPortField.setText(String.valueOf(settings.getMcpPort()));
        commitOnEditEnd(mcpPortField, text -> {
            int port = parsePort(text, settings.getMcpPort());
            settings.setMcpPort(port);
            mcpPortField.setText(String.valueOf(port));
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
        String[] lastCommitted = {trimmed(field.getText())};
        Runnable fire = () -> {
            String text = trimmed(field.getText());
            if (text.equals(lastCommitted[0])) {
                return;
            }
            commit.accept(text);
            lastCommitted[0] = trimmed(field.getText());
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
