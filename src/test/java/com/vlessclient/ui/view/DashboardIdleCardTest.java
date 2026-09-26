package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.testing.TestServers;
import com.vlessclient.testing.UiTest;
import java.util.UUID;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * The status card before any connect names the server Connect would use.
 *
 * <p>It read the server from a field set only by a connect, so every launch
 * greeted a user with a server already picked with "Add a server to get
 * started", beside an enabled Connect button; and after connecting to one
 * server, disconnecting and picking another, it still offered the first.</p>
 */
@UiTest
public class DashboardIdleCardTest extends ApplicationTest {

    private final ServerConfig tokyo = server("Tokyo", "203.0.113.9");
    private final ServerConfig frankfurt = server("Frankfurt", "203.0.113.10");
    private ConfigStore store;

    @Override
    public void start(Stage stage) throws Exception {
        // What a launch finds: servers an earlier run stored, one of them picked.
        store = ServiceLocator.get(ConfigStore.class);
        store.addServer(tokyo);
        store.addServer(frankfurt);
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/DashboardView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 640, 480));
        stage.show();
    }

    @AfterEach
    void removeTheServers() {
        interact(() -> {
            store.removeServer(tokyo.getId());
            store.removeServer(frankfurt.getId());
        });
    }

    @Test
    void theCardNamesTheServerPickedInAnEarlierRun() {
        Label subtitle = lookup("#statusLabel").query();

        assertThat(subtitle.getText()).isEqualTo(I18n.get("dashboard.status.ready", "Tokyo"));
    }

    @Test
    void pickingAnotherServerRenamesTheCard() {
        Label subtitle = lookup("#statusLabel").query();

        interact(() -> store.setActiveServer(frankfurt.getId()));

        assertThat(subtitle.getText())
                .isEqualTo(I18n.get("dashboard.status.ready", "Frankfurt"));
    }

    private static ServerConfig server(String name, String address) {
        return TestServers.server()
                .id(UUID.randomUUID().toString())
                .name(name)
                .protocol(Protocol.VLESS)
                .address(address)
                .port(443)
                .uuid("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
                .build();
    }
}
