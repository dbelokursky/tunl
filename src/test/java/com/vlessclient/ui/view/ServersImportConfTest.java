package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ServerBackupService;
import com.vlessclient.service.ShareLinkParser;
import com.vlessclient.service.TestConfigStores;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.UiTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * A WireGuard {@code .conf} gets in however it is at hand.
 *
 * <p>Only the link dialog read one, pasted in as text. Copied to the clipboard
 * and pasted onto the list, it was read line by line as share links and
 * nothing came in; kept as a file, it had no way in at all short of opening it
 * and copying the text out.</p>
 */
@UiTest
public class ServersImportConfTest extends ApplicationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    static final String CONF = """
            [Interface]
            PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
            Endpoint = wg.example:51820
            AllowedIPs = 0.0.0.0/0
            """;

    @TempDir
    static Path tempDir;

    private ConfigStore store;
    private ServersViewController controller;
    private Stage stage;

    @Override
    public void start(Stage stage) throws IOException {
        this.stage = stage;
        store = TestConfigStores.at(tempDir.resolve(UUID.randomUUID().toString()));
        ServiceLocator.register(ConfigStore.class, store);
        ServiceLocator.register(ShareLinkParser.class, new ShareLinkParser());
        ServiceLocator.register(ServerBackupService.class,
                new ServerBackupService(store, new ShareLinkParser()));
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ServersView.fxml"));
        Scene scene = new Scene(loader.load(), 900, 640);
        controller = loader.getController();
        scene.getStylesheets().addAll(ThemeCss.light());
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void closeDialogs() {
        interact(() -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window != stage) {
                    window.hide();
                }
            }
        });
    }

    @Test
    void aConfOnTheClipboardBecomesAWireguardServer() {
        interact(() -> controller.setClipboardText(() -> CONF));

        interact(() -> importMenuItem("importClipboardItem").fire());

        ServerConfig server = awaitServer(Protocol.WIREGUARD);
        assertThat(server.getAddress()).isEqualTo("wg.example");
        assertThat(server.getPort()).isEqualTo(51820);
    }

    @Test
    void aConfFileBecomesAWireguardServer() throws IOException {
        Path conf = Files.writeString(tempDir.resolve(UUID.randomUUID() + ".conf"), CONF);

        interact(() -> controller.importFile(conf));

        assertThat(awaitServer(Protocol.WIREGUARD).getAddress()).isEqualTo("wg.example");
    }

    @Test
    void aFileOfShareLinksBecomesItsServers() throws IOException {
        Path links = Files.writeString(tempDir.resolve(UUID.randomUUID() + ".txt"),
                "vless://11111111-2222-3333-4444-555555555555@one.example:443#One\n"
                        + "vless://66666666-7777-8888-9999-000000000000@two.example:443#Two\n");

        interact(() -> controller.importFile(links));

        Await.until("both servers", () -> store.getServers().size() == 2, TIMEOUT);
        assertThat(store.getServers()).extracting(ServerConfig::getAddress)
                .containsExactlyInAnyOrder("one.example", "two.example");
    }

    /** The empty list named neither subscriptions nor the paste shortcut. */
    @Test
    void theEmptyListSaysWhereSubscriptionsAndPastedLinksGo() {
        String paste = new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN)
                .getDisplayText();

        Label hint = lookup("#emptyStateHint").queryAs(Label.class);

        assertThat(hint.getText()).contains(paste)
                .isEqualTo(I18n.get("servers.empty.hint", paste));
    }

    private MenuItem importMenuItem(String id) {
        MenuButton menu = lookup("#importMenuButton").queryAs(MenuButton.class);
        return menu.getItems().stream()
                .filter(item -> id.equals(item.getId()))
                .findFirst()
                .orElseThrow();
    }

    private ServerConfig awaitServer(Protocol protocol) {
        return Await.untilValue("a " + protocol + " server",
                () -> store.getServers().stream()
                        .filter(server -> server.getProtocol() == protocol)
                        .findFirst()
                        .orElse(null),
                server -> server != null, TIMEOUT);
    }
}
