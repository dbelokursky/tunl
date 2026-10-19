package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.platform.SecretSealers;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.mcp.AppControlService;
import com.vlessclient.service.mcp.McpServerService;
import com.vlessclient.testing.UiTest;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * The MCP card says why the server did not start in the language the window
 * is in.
 *
 * <p>The reason for a taken port was English text the service made when the
 * bind failed, so a Russian window read "○ ошибка: port 55555 is already in
 * use". A launch fails before the saved language is applied, and the line kept
 * the language it was written in after a switch until the page was shown
 * again.</p>
 */
@UiTest
public class SettingsMcpStatusLanguageTest extends ApplicationTest {

    @TempDir
    static Path tempDir;

    private ConfigStore store;
    private McpServerService service;
    private ViewShownAware controller;
    private ServerSocket blocker;

    @Override
    public void start(Stage stage) throws Exception {
        store = new ConfigStore(tempDir.resolve(UUID.randomUUID().toString()),
                SecretSealers.disabled());
        ServiceLocator.register(ConfigStore.class, store);
        // The real service rather than the graph's double, which never binds:
        // the failure is a bind on a port another socket holds.
        service = new McpServerService(store, ServiceLocator.get(AppControlService.class));
        ServiceLocator.register(McpServerService.class, service);
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/SettingsView.fxml"));
        Scene scene = new Scene(loader.load(), 700, 720);
        controller = loader.getController();
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown() throws IOException {
        service.stop();
        if (blocker != null) {
            blocker.close();
        }
        interact(() -> I18n.setLocale(Locale.ENGLISH));
    }

    /**
     * The server starts in {@code init()}, and {@code start()} applies the
     * saved language only after it: a reason worded when the bind failed was
     * English whatever language the window came up in.
     */
    @Test
    void aPortTakenAtLaunchIsReportedInTheLanguageOfTheWindow() throws IOException {
        int port = occupyTheMcpPort();
        AppSettings settings = store.getSettings();
        settings.setMcpEnabled(true);
        store.saveSettings(settings);

        service.applyAtLaunch();
        interact(() -> I18n.setLocale(Locale.of("ru")));
        interact(controller::onViewShown);

        assertThat(status())
                .as("the MCP status in a Russian window")
                .contains(String.valueOf(port))
                .doesNotContainPattern("\\p{IsLatin}");
    }

    @Test
    void theReasonFollowsALanguageSwitchMadeAfterTheFailure() throws IOException {
        interact(() -> I18n.setLocale(Locale.of("ru")));
        int port = occupyTheMcpPort();
        interact(controller::onViewShown);

        CheckBox enable = lookup("#mcpEnabledCheck").queryAs(CheckBox.class);
        interact(() -> enable.setSelected(true));

        assertThat(status())
                .as("the MCP status in a Russian window")
                .contains(String.valueOf(port))
                .doesNotContainPattern("\\p{IsLatin}");

        interact(() -> I18n.setLocale(Locale.ENGLISH));

        assertThat(status())
                .as("the status after a switch to English, the page still shown")
                .isEqualTo(I18n.get("settings.mcp.status.failed",
                        "port " + port + " is already in use"));
    }

    /** Holds a loopback port, as another program would, and points MCP at it. */
    private int occupyTheMcpPort() throws IOException {
        blocker = new ServerSocket();
        blocker.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        int port = blocker.getLocalPort();
        AppSettings settings = store.getSettings();
        settings.setMcpPort(port);
        store.saveSettings(settings);
        return port;
    }

    private String status() {
        return lookup("#mcpStatusLabel").queryAs(Label.class).getText();
    }
}
