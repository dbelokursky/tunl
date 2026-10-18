package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.TestConfigStores;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.TestServers;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * The user's own delete of the picked server, on the Servers page. The store
 * picks another in its place, and that pick is the user's: the running
 * tunnel follows it as it follows a pick in the list.
 *
 * <p>Every removal made the store's pick its own, as a subscription refresh
 * that withdrew the server makes it. Under a TUN core whose every launch
 * prompts (UAC on Windows), a running tunnel leaves such a pick to the user's
 * reconnect, so after the user deleted the server it ran on, confirmed and
 * irreversible, all traffic went on through that server while the list, the
 * tray and get_status named the one picked in its place.</p>
 */
@UiTest
public class ServerDeleteRepickTest extends ApplicationTest {

    @TempDir
    static Path tempDir;

    private ConfigStore store;
    private final ServerConfig tokyo = server("Tokyo", "203.0.113.9");
    private final ServerConfig osaka = server("Osaka", "203.0.113.10");
    private final ServerConfig nagoya = server("Nagoya", "203.0.113.12");

    @Override
    public void start(Stage stage) throws Exception {
        store = TestConfigStores.at(tempDir.resolve(UUID.randomUUID().toString()));
        ServiceLocator.register(ConfigStore.class, store);
        store.addServer(tokyo);
        store.addServer(osaka);
        store.addServer(nagoya);
        store.setActiveServer(tokyo.getId());

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ServersView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 980, 560));
        stage.show();
    }

    @AfterEach
    void closeTheDialogs() {
        interact(() -> List.copyOf(Window.getWindows()).forEach(window -> {
            if (window.getScene() != null
                    && window.getScene().getRoot() instanceof DialogPane) {
                window.hide();
            }
        }));
        store.awaitPendingWrites(5_000);
    }

    @Test
    void theUsersDeleteOfThePickedServerLeavesThePickInItsPlaceTheUsers() {
        deleteSelected(List.of(tokyo));

        assertThat(onFx(this::pickedName)).as("the pick in Tokyo's place").isEqualTo("Osaka");
        assertThat(onFx(store::isPickedByTheStore)).as("the store's own pick").isFalse();
    }

    @Test
    void theUsersDeleteOfSeveralServersLeavesThePickInTheirPlaceTheUsers() {
        deleteSelected(List.of(tokyo, osaka));

        assertThat(onFx(this::pickedName)).as("the pick in Tokyo's place").isEqualTo("Nagoya");
        assertThat(onFx(store::isPickedByTheStore)).as("the store's own pick").isFalse();
    }

    /** Selects the servers' rows, presses Delete and confirms, as the user does. */
    private void deleteSelected(List<ServerConfig> servers) {
        ListView<?> list = lookup("#serverListView").queryAs(ListView.class);
        interact(() -> {
            list.getSelectionModel().clearSelection();
            for (int i = 0; i < list.getItems().size(); i++) {
                if (servers.contains(list.getItems().get(i))) {
                    list.getSelectionModel().select(i);
                }
            }
        });
        // The confirmation waits in a nested loop: run the key press without
        // waiting for it, as the user's press is.
        Platform.runLater(() -> list.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                KeyCode.DELETE, false, false, false, false)));
        DialogPane confirm = Await.untilValue("the confirmation", this::openDialog,
                Objects::nonNull, Duration.ofSeconds(5));
        interact(() -> confirm.getButtonTypes().stream()
                .map(confirm::lookupButton)
                .filter(button -> button instanceof Button shown
                        && I18n.get("button.delete").equals(shown.getText()))
                .map(Button.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Delete button"))
                .fire());
        Await.until("the servers gone", () -> onFx(() -> servers.stream()
                .noneMatch(server -> store.getServerById(server.getId()).isPresent())),
                Duration.ofSeconds(5));
    }

    private String pickedName() {
        return store.getServers().stream().filter(ServerConfig::isActive)
                .map(ServerConfig::getName).findFirst().orElse(null);
    }

    private DialogPane openDialog() {
        return onFx(() -> {
            for (Window window : Window.getWindows()) {
                if (window.isShowing() && window.getScene() != null
                        && window.getScene().getRoot() instanceof DialogPane pane) {
                    return pane;
                }
            }
            return null;
        });
    }

    private static <T> T onFx(Supplier<T> read) {
        AtomicReference<T> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                seen.set(read.get());
            } finally {
                done.countDown();
            }
        });
        try {
            assertThat(done.await(5, TimeUnit.SECONDS)).as("the FX thread answered").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        return seen.get();
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
