package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.testing.TestServers;
import com.vlessclient.testing.UiTest;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Tooltip;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * A fresh install used to greet the user with a disabled Connect button and
 * "Add a server to get started", with the way to do that two views away.
 * The hero card now links there, and only while there is nothing to connect
 * to.
 */
@UiTest
public class DashboardFirstRunLinkTest extends ApplicationTest {

    /** Removed after each test, so every test starts from the empty test data dir. */
    private ServerConfig added;

    @AfterEach
    void removeTheAddedServer() {
        if (added != null) {
            interact(() -> ServiceLocator.get(ConfigStore.class).removeServer(added.getId()));
            added = null;
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

        added = firstServer();
        interact(() -> store.addServer(added));

        assertThat(link.isVisible()).isFalse();
        assertThat(link.isManaged()).isFalse();
    }

    /**
     * A disabled Connect says why in a tooltip, on the holder around it: the
     * disabled button gets no mouse events. It is one tooltip that gets new
     * text: the refresh runs on every server-list change, and a new Tooltip
     * each time cost a popup control.
     */
    @Test
    void aDisabledConnectKeepsOneTooltipAcrossServerListChanges() {
        Node holder = lookup("#connectButtonHolder").query();
        ConfigStore store = ServiceLocator.get(ConfigStore.class);
        assertThat(store.getServers()).as("the test data dir starts empty").isEmpty();
        Tooltip hint = installedTooltip(holder);
        assertThat(hint).as("the tooltip of a disabled Connect").isNotNull();
        assertThat(hint.getText()).isEqualTo(I18n.get("dashboard.no.servers"));

        added = firstServer();
        interact(() -> store.addServer(added));
        assertThat(installedTooltip(holder)).as("the tooltip once a server is active").isNull();

        interact(() -> store.removeServer(added.getId()));
        added = null;
        assertThat(installedTooltip(holder))
                .as("the tooltip once the list is empty again")
                .isSameAs(hint);
        assertThat(hint.getText()).isEqualTo(I18n.get("dashboard.no.servers"));
    }

    /** The tooltip {@link Tooltip#install} put on a node, which is not a control. */
    private static Tooltip installedTooltip(Node node) {
        return (Tooltip) node.getProperties().get("javafx.scene.control.Tooltip");
    }

    private static ServerConfig firstServer() {
        return TestServers.server()
                .name("First")
                .protocol(Protocol.VLESS)
                .address("203.0.113.9")
                .port(443)
                .uuid("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
                .build();
    }
}
