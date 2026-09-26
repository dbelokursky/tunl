package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ServerBackupService;
import com.vlessclient.service.ShareLinkParser;
import com.vlessclient.service.TestConfigStores;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.UiTest;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * What only a mouse reached, the keyboard reaches too.
 *
 * <p>A server's "Edit", "Duplicate" and "Copy link" were in a menu that only
 * a right-click opened: the menu key and Shift+F10 ask the list, and the
 * menu was the row's. And Tab in a text area typed a tab, or nothing in a
 * read-only one, so the keyboard could not leave it.</p>
 */
@UiTest
public class KeyboardReachTest extends ApplicationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    static Path tempDir;

    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.show();
    }

    @AfterEach
    void closeTheOtherWindows() {
        interact(() -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window != stage) {
                    window.hide();
                }
            }
        });
    }

    @Test
    void tabLeavesTheBypassListAndTheMcpCommand() {
        for (String view : List.of("/fxml/RoutingView.fxml", "/fxml/SettingsView.fxml")) {
            Parent root = show(view);
            List<TextArea> areas = new ArrayList<>();
            interact(() -> root.lookupAll(".text-area").forEach(node -> {
                if (node instanceof TextArea area) {
                    areas.add(area);
                }
            }));
            assertThat(areas).as("the text areas of %s", view).isNotEmpty();
            for (TextArea area : areas) {
                assertTabLeaves(area, false);
                assertTabLeaves(area, true);
            }
        }
    }

    @Test
    void tabLeavesTheLinkDialogsText() throws IOException {
        showServers();
        MenuButton importMenu = lookup("#importMenuButton").query();
        MenuItem linkItem = importMenu.getItems().stream()
                .filter(item -> "importLinkItem".equals(item.getId()))
                .findFirst().orElseThrow();
        Platform.runLater(linkItem::fire);
        DialogPane dialog = Await.untilValue("the link dialog", this::showingLinkDialog,
                Objects::nonNull, TIMEOUT);

        assertTabLeaves((TextArea) dialog.getContent(), false);
    }

    @Test
    void theMenuKeysOpenTheFocusedServersMenu() throws IOException {
        ListView<?> list = showServers();
        interact(() -> {
            list.requestFocus();
            list.getSelectionModel().clearAndSelect(1);
            list.getFocusModel().focus(1);
            list.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F10,
                    true, false, false, false));
        });
        assertRowMenuShowing(list, 1);

        closeTheOtherWindows();
        interact(() -> list.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                KeyCode.CONTEXT_MENU, false, false, false, false)));
        assertRowMenuShowing(list, 1);
    }

    /** The request Windows and Linux raise for the menu key goes to the list. */
    @Test
    void theKeyboardsMenuRequestOpensTheFocusedServersMenu() throws IOException {
        ListView<?> list = showServers();
        interact(() -> {
            list.requestFocus();
            list.getSelectionModel().clearAndSelect(0);
            list.getFocusModel().focus(0);
            Bounds bounds = list.localToScreen(list.getBoundsInLocal());
            list.fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                    10, 10, bounds.getMinX() + 10, bounds.getMinY() + 10, true, null));
        });
        assertRowMenuShowing(list, 0);
    }

    private void assertTabLeaves(TextArea area, boolean backwards) {
        String before = area.getText();
        interact(() -> {
            area.requestFocus();
            area.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB,
                    backwards, false, false, false));
        });
        String key = backwards ? "Shift+Tab" : "Tab";
        assertThat(area.isFocused()).as("%s on %s moved focus on", key, area.getId()).isFalse();
        assertThat(area.getText()).as("%s's text after %s", area.getId(), key).isEqualTo(before);
    }

    /**
     * The menu of row {@code index} is showing, with the server's actions in
     * it. A control's menu is shown against its window rather than the node,
     * so the row is told by the cell whose menu it is.
     */
    private void assertRowMenuShowing(ListView<?> list, int index) {
        ContextMenu menu = Await.untilValue("a row's menu", this::showingContextMenu,
                Objects::nonNull, TIMEOUT);
        AtomicReference<ContextMenu> rowMenu = new AtomicReference<>();
        interact(() -> list.lookupAll(".list-cell").forEach(node -> {
            if (node instanceof ListCell<?> cell && cell.getIndex() == index
                    && cell.isVisible()) {
                rowMenu.set(cell.getContextMenu());
            }
        }));
        assertThat(menu).as("the menu showing is row %d's", index).isSameAs(rowMenu.get());
        assertThat(menu.getItems()).extracting(MenuItem::getText)
                .contains(I18n.get("servers.menu.edit"), I18n.get("button.duplicate"),
                        I18n.get("button.copy.share.link"));
    }

    private Parent show(String view) {
        AtomicReference<Parent> root = new AtomicReference<>();
        interact(() -> {
            try {
                root.set(new FXMLLoader(getClass().getResource(view)).load());
            } catch (IOException e) {
                throw new IllegalStateException("could not load " + view, e);
            }
            stage.setScene(new Scene(root.get(), 900, 700));
        });
        return root.get();
    }

    /** The Servers page over a store of two servers. */
    private ListView<?> showServers() throws IOException {
        ConfigStore store = TestConfigStores.at(tempDir.resolve(UUID.randomUUID().toString()));
        ServiceLocator.register(ConfigStore.class, store);
        ServiceLocator.register(ShareLinkParser.class, new ShareLinkParser());
        ServiceLocator.register(ServerBackupService.class,
                new ServerBackupService(store, new ShareLinkParser()));
        interact(() -> {
            store.addServer(server("Netherlands 01", "198.51.100.7"));
            store.addServer(server("Germany 02", "198.51.100.8"));
        });
        Parent root = show("/fxml/ServersView.fxml");
        return (ListView<?>) root.lookup("#serverListView");
    }

    private static ServerConfig server(String name, String address) {
        ServerConfig server = new ServerConfig();
        server.setName(name);
        server.setAddress(address);
        server.setPort(443);
        server.setProtocol(Protocol.VLESS);
        server.setUuid(UUID.randomUUID().toString());
        return server;
    }

    private ContextMenu showingContextMenu() {
        AtomicReference<ContextMenu> found = new AtomicReference<>();
        interact(() -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof ContextMenu menu && menu.isShowing()) {
                    found.set(menu);
                }
            }
        });
        return found.get();
    }

    private DialogPane showingLinkDialog() {
        AtomicReference<DialogPane> found = new AtomicReference<>();
        interact(() -> {
            for (Window window : Window.getWindows()) {
                if (window != stage && window.isShowing() && window.getScene() != null) {
                    Node pane = window.getScene().getRoot().lookup(".dialog-pane");
                    if (pane instanceof DialogPane dialog
                            && dialog.getContent() instanceof TextArea) {
                        found.set(dialog);
                    }
                }
            }
        });
        return found.get();
    }
}
