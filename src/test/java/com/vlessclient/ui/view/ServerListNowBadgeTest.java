package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ProxyGroupMonitor;
import com.vlessclient.service.TestConfigStores;
import com.vlessclient.service.outbound.OutboundTags;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import java.util.List;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In the Fastest mode the core moves traffic between servers on its own, and
 * the list marked only the server the user had picked, calling it the active
 * one while the tunnel might be using another. The picked server is now the
 * selected one, and the server the core routes through carries a badge of its
 * own, following the core as it re-picks.
 */
@UiTest
public class ServerListNowBadgeTest extends ApplicationTest {

    /** Monitor whose published pick the test sets by hand; never polls. */
    private static final class FakeGroupMonitor extends ProxyGroupMonitor {
        private final SimpleStringProperty corePick = new SimpleStringProperty();

        @Override
        public ReadOnlyStringProperty corePickTagProperty() {
            return corePick;
        }
    }

    @TempDir
    static Path tempDir;

    private final FakeGroupMonitor monitor = new FakeGroupMonitor();
    private ProxyGroupMonitor priorMonitor;
    private ServerConfig germany;

    @Override
    public void start(Stage stage) throws Exception {
        priorMonitor = ServiceLocator.find(ProxyGroupMonitor.class).orElse(null);
        ServiceLocator.register(ProxyGroupMonitor.class, monitor);
        ConfigStore store = TestConfigStores.at(tempDir.resolve("data"));
        ServiceLocator.register(ConfigStore.class, store);
        store.getServers().clear();
        store.addServer(server("Netherlands 01", "185.107.56.12"));
        germany = server("Germany 02", "45.86.230.9");
        store.addServer(germany);

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ServersView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 980, 560));
        stage.show();
    }

    @AfterEach
    void restoreMonitor() {
        if (priorMonitor != null) {
            ServiceLocator.register(ProxyGroupMonitor.class, priorMonitor);
        }
    }

    @Test
    void theServerTheCorePickedCarriesABadgeOfItsOwnBesideTheSelectedOne() {
        assertThat(badges("Netherlands 01")).containsExactly(I18n.get("servers.active.badge"));
        assertThat(badges("Germany 02")).isEmpty();

        interact(() -> monitor.corePick.set(OutboundTags.server(germany)));

        assertThat(badges("Germany 02")).containsExactly(I18n.get("servers.now.badge"));
        assertThat(badges("Netherlands 01"))
                .as("the user's own pick stays marked as theirs")
                .containsExactly(I18n.get("servers.active.badge"));

        interact(() -> monitor.corePick.set(null));

        assertThat(badges("Germany 02"))
                .as("a disconnect, or the user's own selector, clears it")
                .isEmpty();
    }

    /** The texts of the selected and current badges on the row of {@code name}. */
    private List<String> badges(String name) {
        ListCell<?> row = lookup(".list-cell").queryAll().stream()
                .map(node -> (ListCell<?>) node)
                .filter(cell -> cell.getItem() instanceof ServerConfig server
                        && name.equals(server.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row shows " + name));
        return row.lookupAll(".label").stream()
                .filter(node -> node.getStyleClass().contains("active-badge")
                        || node.getStyleClass().contains("now-badge"))
                .map(node -> ((Label) node).getText())
                .toList();
    }

    private static ServerConfig server(String name, String address) {
        ServerConfig config = new ServerConfig();
        config.setName(name);
        config.setAddress(address);
        config.setPort(443);
        config.setProtocol(Protocol.VLESS);
        return config;
    }
}
