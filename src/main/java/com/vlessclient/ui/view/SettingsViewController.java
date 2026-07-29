package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.LoginItemService;
import com.vlessclient.service.ThemeManager;
import com.vlessclient.service.mcp.McpServerService;
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
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Locale;

/**
 * Controller for the Settings view.
 * Manages theme, language, proxy, and other application settings.
 */
public class SettingsViewController {

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
    @FXML private Label appVersionLabel;
    @FXML private Label singboxVersionLabel;
    @FXML private Label appVersionValue;
    @FXML private Label singboxVersionValue;
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
    @FXML private ComboBox<ProxyMode> proxyModeCombo;
    @FXML private TextField proxyDnsField;
    @FXML private TextField directDnsField;
    @FXML private TextField tunInterfaceNameField;
    @FXML private TextField tunIpv4Field;

    @FXML private CheckBox mcpEnabledCheck;
    @FXML private TextField mcpPortField;
    @FXML private CheckBox mcpAllowMutationsCheck;
    @FXML private Label mcpStatusLabel;
    @FXML private TextArea mcpCommandArea;
    @FXML private Button mcpCopyButton;
    @FXML private Button mcpRegenButton;

    private ConfigStore configStore;
    private ThemeManager themeManager;
    private LoginItemService loginItemService;
    private McpServerService mcpServerService;
    private boolean suppressLaunchAtLoginListener;

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
            loginItemService = ServiceLocator.get(LoginItemService.class);
        } catch (IllegalArgumentException e) {
            log.warn("LoginItemService not available");
        }

        AppSettings settings = configStore.getSettings();

        try {
            mcpServerService = ServiceLocator.get(McpServerService.class);
        } catch (IllegalArgumentException e) {
            log.warn("McpServerService not available");
        }

        initThemeCombo(settings);
        initLanguageCombo(settings);
        initConnectionSettings(settings);
        initProxyModeCombo(settings);
        initAdvancedSettings(settings);
        initMcpSettings(settings);
        initAboutSection();
        bindLabels();
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

        tunIpv4Field.setText(settings.getTunIpv4Address());
        tunIpv4Field.textProperty().addListener((obs, oldVal, newVal) -> {
            settings.setTunIpv4Address(newVal == null ? "" : newVal.trim());
            saveSettings(settings);
        });
    }

    /**
     * Wires the Agent Control (MCP) section: enabling the server, its port and
     * the mutation toggle all persist and reconcile the running server via
     * {@link McpServerService#apply()}. The copy/regenerate buttons act on the
     * bearer token and the ready-to-run {@code claude mcp add} command.
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

    private void initThemeCombo(AppSettings settings) {
        themeCombo.getItems().addAll("system", "light", "dark");
        themeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String value) {
                if (value == null) {
                    return "";
                }
                return switch (value) {
                    case "system" -> I18n.get("settings.theme.system");
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
        themeCombo.setValue(settings.getTheme());

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
                    case "ru" -> "\u0420\u0443\u0441\u0441\u043a\u0438\u0439";
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

    /**
     * Wires the "Launch at login" checkbox to the macOS LaunchAgent. The
     * checkbox reflects whether the plist is actually installed (the source
     * of truth) rather than a saved setting, so it stays correct even if the
     * user removed the agent from System Settings. On a write failure the
     * checkbox reverts so it never claims a state that did not take effect.
     */
    private void initLaunchAtLogin() {
        if (loginItemService == null) {
            launchAtLoginCheck.setDisable(true);
            return;
        }
        launchAtLoginCheck.setSelected(loginItemService.isEnabled());
        launchAtLoginCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (suppressLaunchAtLoginListener) {
                return;
            }
            try {
                loginItemService.setEnabled(newVal);
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

    private void initAboutSection() {
        String appVersion = System.getProperty("app.version", "1.0.0");
        appVersionValue.setText(appVersion);

        String singboxVersion = detectSingBoxVersion();
        singboxVersionValue.setText(singboxVersion);
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
        aboutLabel.textProperty().bind(I18n.binding("settings.about"));
        appVersionLabel.textProperty().bind(I18n.binding("settings.app.version"));
        singboxVersionLabel.textProperty().bind(I18n.binding("settings.singbox.version"));
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

    private void addNumericFilter(TextField field) {
        field.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.matches("\\d*")) {
                field.setText(oldVal);
            }
        });
    }

    private String detectSingBoxVersion() {
        String singBoxPath = ServiceLocator.getSingBoxPath();
        if (singBoxPath == null) {
            return I18n.get("settings.version.unknown");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(singBoxPath, "version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output;
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                output = reader.readLine();
            }
            process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            return output != null ? output.trim() : I18n.get("settings.version.unknown");
        } catch (Exception e) {
            log.debug("Could not detect sing-box version", e);
            return I18n.get("settings.version.unknown");
        }
    }
}
