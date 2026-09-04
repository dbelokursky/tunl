package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.UiTestServices;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import java.util.List;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Hyperlink;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * A fresh install used to greet the user with a disabled Connect button and
 * "Add a server to get started", with the way to do that two views away.
 * The hero card now links there, and only while there is nothing to connect
 * to.
 */
public class DashboardFirstRunLinkTest extends ApplicationTest {

    private static ServerConfig added;

    @BeforeAll
    static void setupHeadless() {
        System.setProperty("testfx.robot", "glass");
        System.setProperty("testfx.headless", "true");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        System.setProperty("java.awt.headless", "true");
        UiTestServices.initialize();
    }

    @AfterAll
    static void cleanUp() {
        if (added != null) {
            ServiceLocator.get(ConfigStore.class).applyServerBatch(List.of(), List.of(added.getId()));
        }
    }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/DashboardView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 640, 480));
        stage.show();
    }

    @Test
    void theLinkShowsOnlyWhileTheServerListIsEmpty() {
        Hyperlink link = lookup("#addServerLink").query();
        ConfigStore store = ServiceLocator.get(ConfigStore.class);
        assertThat(store.getServers()).as("the test data dir starts empty").isEmpty();
        assertThat(link.isVisible()).isTrue();
        assertThat(link.getText()).isNotBlank();

        added = new ServerConfig();
        added.setName("First");
        added.setProtocol(Protocol.VLESS);
        added.setAddress("203.0.113.9");
        added.setPort(443);
        added.setUuid("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        interact(() -> store.addServer(added));

        assertThat(link.isVisible()).isFalse();
        assertThat(link.isManaged()).isFalse();
    }
}
