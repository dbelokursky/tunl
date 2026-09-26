package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.platform.SecretSealer;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.TestConfigStores;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import java.util.List;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A credential the keychain did not return on load is kept as its sealed tag,
 * so the server recovers once the keychain does. Nothing in the list said so:
 * the server looked like any other and failed at the connect.
 */
@UiTest
public class ServerListCredentialBadgeTest extends ApplicationTest {

    @TempDir
    static Path tempDir;

    @Override
    public void start(Stage stage) throws Exception {
        ConfigStore store = TestConfigStores.at(tempDir.resolve("data"));
        ServiceLocator.register(ConfigStore.class, store);
        store.getServers().clear();
        store.addServer(server("Netherlands 01", "vless-secret-uuid"));
        store.addServer(server("Locked 02", SecretSealer.SEAL_PREFIX + "keychain:v1"));

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ServersView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 980, 560));
        stage.show();
    }

    @Test
    void aServerWhoseCredentialWasNotReadSaysSo() {
        assertThat(badges("Locked 02")).contains(I18n.get("servers.badge.credential"));
        assertThat(badges("Netherlands 01")).doesNotContain(I18n.get("servers.badge.credential"));
    }

    /** The texts of the warning badges on the row of {@code name}. */
    private List<String> badges(String name) {
        ListCell<?> row = lookup(".list-cell").queryAll().stream()
                .map(node -> (ListCell<?>) node)
                .filter(cell -> cell.getItem() instanceof ServerConfig server
                        && name.equals(server.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row shows " + name));
        return row.lookupAll(".insecure-badge").stream()
                .map(node -> ((Label) node).getText())
                .toList();
    }

    private static ServerConfig server(String name, String credential) {
        ServerConfig config = new ServerConfig();
        config.setName(name);
        config.setAddress("185.107.56.12");
        config.setPort(443);
        config.setProtocol(Protocol.VLESS);
        config.setUuid(credential);
        return config;
    }
}
