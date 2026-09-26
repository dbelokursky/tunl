package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.TestConfigStores;
import com.vlessclient.testing.TestServers;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ListCell;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a click on a server row does to the active server. A plain click makes
 * that server the active one; a click that builds a selection or opens the
 * context menu must not, because activating a server restarts a live tunnel.
 *
 * <p>The clicks are synthetic MOUSE_CLICKED events fired at the row, as in
 * {@code DashboardTrafficDayPopoverTest}: a robot click lands where Monocle's
 * pointer happens to be, which differs between platforms.</p>
 */
@UiTest
public class ServerListClickTest extends ApplicationTest {

    @TempDir
    static Path tempDir;

    private ConfigStore store;

    @Override
    public void start(Stage stage) throws Exception {
        store = TestConfigStores.at(tempDir.resolve("data"));
        ServiceLocator.register(ConfigStore.class, store);

        // TestFX rebuilds the stage per test method, and a store rooted at the
        // same directory reloads what the previous method saved there.
        store.getServers().clear();
        store.addServer(server("Netherlands 01", "185.107.56.12"));
        store.addServer(server("Germany 02", "45.86.230.9"));

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ServersView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 980, 560));
        stage.show();
    }

    @Test
    void aPlainClickMakesTheRowsServerActive() {
        assertThat(activeName()).as("the first server added").isEqualTo("Netherlands 01");

        click("Germany 02", false, false);

        assertThat(activeName()).isEqualTo("Germany 02");
    }

    /**
     * Control-click is the context-menu click on macOS, where the shortcut key
     * is Command. It used to activate the row on the way to the menu, and so
     * restart a live tunnel onto that server.
     */
    @Test
    void aControlClickLeavesTheActiveServerAlone() {
        click("Germany 02", false, true);

        assertThat(activeName()).isEqualTo("Netherlands 01");
    }

    @Test
    void aShiftClickLeavesTheActiveServerAlone() {
        click("Germany 02", true, false);

        assertThat(activeName()).isEqualTo("Netherlands 01");
    }

    private void click(String name, boolean shiftDown, boolean controlDown) {
        ListCell<?> row = lookup(".list-cell").queryAll().stream()
                .map(node -> (ListCell<?>) node)
                .filter(cell -> cell.getItem() instanceof ServerConfig server
                        && name.equals(server.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row shows " + name));
        interact(() -> row.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED,
                0, 0, 0, 0, MouseButton.PRIMARY, 1,
                shiftDown, controlDown, false, false, true, false, false,
                false, false, true, null)));
    }

    private String activeName() {
        return store.getServers().stream()
                .filter(ServerConfig::isActive)
                .map(ServerConfig::getName)
                .findFirst()
                .orElse(null);
    }

    private static ServerConfig server(String name, String address) {
        return TestServers.server()
                .name(name)
                .address(address)
                .port(443)
                .protocol(Protocol.VLESS)
                .build();
    }
}
