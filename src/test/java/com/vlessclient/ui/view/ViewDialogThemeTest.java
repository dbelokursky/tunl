package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.RoutingRule;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ConnectionService;
import com.vlessclient.service.RoutingService;
import com.vlessclient.service.ServerBackupService;
import com.vlessclient.service.ShareLinkExporter;
import com.vlessclient.service.ShareLinkParser;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.SubscriptionService;
import com.vlessclient.service.TestConfigStores;
import com.vlessclient.service.TestRoutingServices;
import com.vlessclient.service.TestSubscriptionServices;
import com.vlessclient.service.TrafficHistoryStore;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.Contrast;
import com.vlessclient.testing.UiTest;
import com.vlessclient.ui.view.settings.TrafficHistorySettingsSection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every dialog a view opens belongs to the window the view is in, and so wears
 * its theme.
 *
 * <p>A dialog's scene gets stylesheets one way only: {@code Dialog.initOwner}
 * binds them to the owner scene's, when the owner is a Stage with a scene.
 * Shown without an owner, a dialog gets no app stylesheet at all. In the dark
 * theme those came up as stock light Modena over the dark window, beside owned
 * alerts that base.css themes, and centred on the screen rather than on the
 * window. Each test opens one dialog the way the user does, by a button or a
 * menu item fired or a key dispatched, and checks whom it belongs to and what
 * it is dressed in. The dialogs with fields of their own are also read text by
 * text in both themes: base.css styles the controls it knows, and Modena
 * derives the rest from its own light palette.</p>
 *
 * <p>Openers go through {@code Platform.runLater}, not {@code interact}: the
 * views show their dialogs with {@code showAndWait}, which returns only once
 * the dialog closes, so an {@code interact} around one would not return.
 * Buttons and items are fired rather than clicked, as in
 * {@code ServersImportRedactionTest}: Monocle's pointer does not reach every
 * popup and row on every runner.</p>
 */
@UiTest
public class ViewDialogThemeTest extends ApplicationTest {

    private static final String LINK = "vless://11111111-2222-3333-4444-555555555555"
            + "@198.51.100.7:443?type=tcp#Netherlands%2001";
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @TempDir
    static Path tempDir;

    private Stage stage;
    /** The window of every dialog the test opened, closed since or not. */
    private final List<Window> dialogs = new ArrayList<>();

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.setScene(new Scene(new StackPane(), 1000, 720));
        stage.show();
    }

    /**
     * Hides every dialog still open, which also ends the showAndWait a failed
     * assertion left open, and takes the focus to the root of every dialog the
     * test opened. Monocle keeps a window focused after it hides, so the field
     * focused in a dialog would go on blinking its caret: an animation that
     * keeps JavaFX pulsing for the rest of the fork, which
     * DashboardHiddenWindowTest counts. A form its own OK button closed blinks
     * on as well, though it has left Window.getWindows() and its field has
     * left its scene, so the dialogs come from {@link #open} too.
     */
    @AfterEach
    void closeTheDialogs() {
        interact(() -> {
            List<Window> windows = new ArrayList<>(dialogs);
            for (Window window : Window.getWindows()) {
                if (window != stage && !windows.contains(window)) {
                    windows.add(window);
                }
            }
            for (Window window : windows) {
                window.hide();
                if (window.getScene() != null) {
                    window.getScene().getRoot().requestFocus();
                }
            }
        });
    }

    // ===== Servers =====

    @Test
    void theServerDeleteConfirmation() {
        ConfigStore store = serverStore();
        mount("ServersView", "dark");
        interact(() -> store.addServer(server("Amsterdam 01")));
        ListView<?> list = lookup("#serverListView").queryAs(ListView.class);
        interact(() -> list.getSelectionModel().select(0));

        DialogPane confirm = open(() -> list.fireEvent(keyPress(KeyCode.DELETE)));

        assertThat(confirm.getHeaderText())
                .isEqualTo(I18n.get("servers.delete.header", "Amsterdam 01"));
        assertBelongsToTheWindow(confirm, "dark");
    }

    @Test
    void theBatchDeleteConfirmation() {
        ConfigStore store = serverStore();
        mount("ServersView", "dark");
        interact(() -> {
            store.addServer(server("Amsterdam 01"));
            store.addServer(server("Berlin 02"));
        });
        ListView<?> list = lookup("#serverListView").queryAs(ListView.class);
        interact(() -> list.getSelectionModel().selectAll());

        DialogPane confirm = open(() -> list.fireEvent(keyPress(KeyCode.DELETE)));

        assertThat(confirm.getHeaderText())
                .isEqualTo(I18n.get("servers.delete.many.header", 2));
        assertBelongsToTheWindow(confirm, "dark");
    }

    @ParameterizedTest
    @ValueSource(strings = {"light", "dark"})
    void theImportDialog(String theme) {
        serverStore();
        mount("ServersView", theme);

        DialogPane dialog = open(importMenuItem("importLinkItem")::fire);
        interact(() -> ((TextArea) dialog.lookup(".text-area")).setText(LINK));

        assertBelongsToTheWindow(dialog, theme);
        assertThat(assertReadsInTheTheme(dialog, theme))
                .contains(I18n.get("servers.import.header"), LINK);
    }

    @Test
    void theImportError() {
        serverStore();
        mount("ServersView", "dark");
        DialogPane form = open(importMenuItem("importLinkItem")::fire);
        interact(() -> ((TextArea) form.lookup(".text-area")).setText("not a link"));

        DialogPane error = open(((Button) form.lookupButton(ButtonType.OK))::fire);

        assertThat(error.getHeaderText()).isEqualTo(I18n.get("servers.import.error.header"));
        assertBelongsToTheWindow(error, "dark");
    }

    @Test
    void theShareLinkError() {
        ConfigStore store = serverStore();
        ServiceLocator.register(ShareLinkExporter.class, new ShareLinkExporter() {
            @Override
            public String export(ServerConfig config) {
                throw new IllegalStateException("nothing to share");
            }
        });
        mount("ServersView", "dark");
        interact(() -> store.addServer(server("Amsterdam 01")));

        DialogPane error = open(serverRowMenuItem(I18n.get("button.copy.share.link"))::fire);

        assertThat(error.getHeaderText()).isEqualTo(I18n.get("servers.export.error.header"));
        assertBelongsToTheWindow(error, "dark");
    }

    // ===== Routing =====

    @ParameterizedTest
    @ValueSource(strings = {"light", "dark"})
    void theAddRuleDialog(String theme) {
        ServiceLocator.register(RoutingService.class, TestRoutingServices.at(freshDir("routing")));
        mount("RoutingView", theme);

        DialogPane dialog = open(button("#addRuleButton")::fire);
        interact(() -> ((TextField) dialog.lookup(".text-field")).setText("example.com"));

        assertBelongsToTheWindow(dialog, theme);
        assertThat(assertReadsInTheTheme(dialog, theme))
                .as("the labels, the value typed and both combo boxes' values")
                .contains(I18n.get("form.type") + ":", "example.com",
                        I18n.get("routing.rule.type.domain.suffix"),
                        I18n.get("routing.action.proxy"));
    }

    @Test
    void theRuleDeleteConfirmation() {
        RoutingService routing = TestRoutingServices.at(freshDir("routing"));
        routing.addRule(new RoutingRule(RoutingRule.RuleType.DOMAIN_SUFFIX, "example.com",
                RoutingRule.RuleAction.DIRECT));
        ServiceLocator.register(RoutingService.class, routing);
        mount("RoutingView", "dark");

        DialogPane confirm = open(rowButton("#rulesListView", I18n.get("button.delete"))::fire);

        assertThat(confirm.getHeaderText()).contains("example.com");
        assertBelongsToTheWindow(confirm, "dark");
    }

    // ===== Subscriptions =====

    @ParameterizedTest
    @ValueSource(strings = {"light", "dark"})
    void theSubscriptionDialog(String theme) {
        ServiceLocator.register(SubscriptionService.class,
                TestSubscriptionServices.quiet(freshDir("subscriptions")));
        mount("SubscriptionsView", theme);

        DialogPane dialog = open(button("#addSubscriptionButton")::fire);
        // An http URL puts up the plaintext warning, the one line of the dialog
        // that picks a colour of its own.
        fillSubscription(dialog, "Provider", "http://provider.example/sub");

        assertBelongsToTheWindow(dialog, theme);
        assertThat(assertReadsInTheTheme(dialog, theme))
                .as("the values typed and the whole warning")
                .contains("Provider", "http://provider.example/sub",
                        I18n.get("subscriptions.http.warning"));
    }

    @Test
    void theSubscriptionDeleteConfirmation() {
        SubscriptionService service = TestSubscriptionServices.quiet(freshDir("subscriptions"));
        ServiceLocator.register(SubscriptionService.class, service);
        mount("SubscriptionsView", "dark");
        service.addSubscription("Provider", "https://provider.example/sub");

        DialogPane confirm = open(
                rowButton("#subscriptionListView", I18n.get("button.delete"))::fire);

        assertThat(confirm.getHeaderText())
                .isEqualTo(I18n.get("subscriptions.delete.confirm", "Provider"));
        assertBelongsToTheWindow(confirm, "dark");
    }

    /**
     * A failed add is reported whenever the service gives up, from another
     * thread, and by then the user may have gone to another page: MainView
     * takes the page out of the window, so a view that looked its window up
     * only when the failure landed would find none.
     */
    @Test
    void aFailureReportedAfterTheUserMovedOnStillBelongsToTheWindow() {
        CountDownLatch movedOn = new CountDownLatch(1);
        ServiceLocator.register(SubscriptionService.class,
                TestSubscriptionServices.failing(freshDir("subscriptions"), movedOn));
        mount("SubscriptionsView", "dark");
        DialogPane form = open(button("#addSubscriptionButton")::fire);
        fillSubscription(form, "Provider", "https://provider.example/sub");
        interact(((Button) form.lookupButton(ButtonType.OK))::fire);
        interact(() -> stage.getScene().setRoot(new StackPane()));

        DialogPane error = open(movedOn::countDown);

        assertThat(error.getHeaderText()).isEqualTo(I18n.get("subscriptions.add.failed"));
        assertBelongsToTheWindow(error, "dark");
    }

    // ===== Dashboard =====

    @ParameterizedTest
    @ValueSource(strings = {"light", "dark"})
    void theHealthTargetDialog(String theme) {
        mount("DashboardView", theme);

        DialogPane dialog = open(button("#addTargetButton")::fire);
        interact(() -> dialog.lookupAll(".text-field")
                .forEach(field -> ((TextField) field).setText("1.1.1.1")));

        assertBelongsToTheWindow(dialog, theme);
        assertThat(assertReadsInTheTheme(dialog, theme))
                .contains(I18n.get("health.target.name"), "1.1.1.1");
    }

    @Test
    void theConnectionError() {
        // Present but never started: the double fails the start before it would.
        ServiceLocator.register(SingBoxEngine.class,
                new SingBoxEngine(Path.of("target", "no-such-sing-box")));
        ServiceLocator.register(ConnectionService.class, new FailingConnectionService());
        DashboardViewController dashboard = mount("DashboardView", "dark");

        DialogPane error = open(dashboard::toggleConnection);

        assertThat(error.getHeaderText()).isEqualTo(I18n.get("dashboard.error.start.title"));
        assertBelongsToTheWindow(error, "dark");
    }

    // ===== Settings =====

    @Test
    void theClearHistoryConfirmation() {
        TrafficHistoryStore store =
                new TrafficHistoryStore(freshDir("history"), Clock.systemDefaultZone());
        store.record(server("Amsterdam 01"), 1_000, 4_000);
        Button clear = new Button();
        interact(() -> {
            Label summary = new Label();
            stage.getScene().setRoot(new VBox(summary, clear));
            stage.getScene().getStylesheets().setAll(ThemeCss.of("dark"));
            new TrafficHistorySettingsSection(store,
                    new TrafficHistorySettingsSection.Controls(summary, clear),
                    TrafficHistorySettingsSection::confirmWithDialog).init();
        });

        DialogPane confirm = open(clear::fire);

        assertThat(confirm.getHeaderText())
                .isEqualTo(I18n.get("settings.traffic.history.clear.confirm"));
        assertBelongsToTheWindow(confirm, "dark");
    }

    @Test
    void theTokenRegenerationConfirmation() {
        mount("SettingsView", "dark");
        Button regenerate = lookup("#mcpRegenButton").query();

        DialogPane confirm = open(regenerate::fire);

        assertThat(confirm.getHeaderText())
                .isEqualTo(I18n.get("settings.mcp.regenerate.confirm"));
        assertBelongsToTheWindow(confirm, "dark");
    }

    // ===== Logs =====

    @Test
    void theClearLogsConfirmation() {
        mount("LogsView", "dark");
        ListView<String> list = lookup("#logListView").query();
        Await.until("the log list to be live",
                () -> onFx(() -> list.getItems() instanceof FilteredList), PATIENCE);
        interact(() -> logSource(list).add("INFO[0000] sing-box started"));
        Button clear = lookup("#clearButton").query();

        DialogPane confirm = open(clear::fire);

        assertThat(confirm.getHeaderText()).isEqualTo(I18n.get("logs.clear.confirm"));
        assertBelongsToTheWindow(confirm, "dark");
    }

    /** The log's own list, behind the view's filtered one. */
    @SuppressWarnings("unchecked")
    private static ObservableList<String> logSource(ListView<String> list) {
        return (ObservableList<String>) ((FilteredList<String>) list.getItems()).getSource();
    }

    // ===== Opening and reading dialogs =====

    /** Puts a view in the window, dressed in {@code theme}, and returns its controller. */
    private <T> T mount(String view, String theme) {
        List<T> controller = new ArrayList<>(1);
        interact(() -> {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/" + view + ".fxml"));
            try {
                stage.getScene().setRoot(loader.load());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            stage.getScene().getStylesheets().setAll(ThemeCss.of(theme));
            controller.add(loader.getController());
        });
        return controller.get(0);
    }

    /**
     * Fires what opens a dialog and returns the dialog once it is on screen:
     * the first showing window that was not showing before, with a dialog pane
     * for its root. The window is kept for {@link #closeTheDialogs}.
     */
    private DialogPane open(Runnable opener) {
        List<Window> before = onFx(() -> List.copyOf(Window.getWindows()));
        Platform.runLater(opener);
        return Await.untilValue("a dialog to open", () -> onFx(() -> {
            for (Window window : Window.getWindows()) {
                if (!before.contains(window) && window.isShowing() && window.getScene() != null
                        && window.getScene().getRoot() instanceof DialogPane pane) {
                    dialogs.add(window);
                    return pane;
                }
            }
            return null;
        }), Objects::nonNull, PATIENCE);
    }

    /**
     * The dialog is owned by the window, which makes it modal to it, centres
     * it on it and hands it the window's stylesheets, and it is dressed in the
     * window's theme.
     */
    private void assertBelongsToTheWindow(DialogPane dialog, String theme) {
        Window owner = onFx(() -> ((Stage) dialog.getScene().getWindow()).getOwner());
        Color pane = onFx(() -> {
            dialog.applyCss();
            return Contrast.backdrop(dialog);
        });
        assertThat(owner)
                .withFailMessage("%s: the dialog's owner is %s, not the window, so it has none "
                        + "of the window's stylesheets and is centred on the screen", theme, owner)
                .isSameAs(stage);
        assertThat(Contrast.isDark(pane))
                .withFailMessage("%s: the dialog is painted %s, which is not the window's theme",
                        theme, pane)
                .isEqualTo("dark".equals(theme));
    }

    /**
     * Reads a dialog with fields of its own: each field is drawn in the theme,
     * and each piece of text on it (header, labels, what the fields hold, the
     * combo boxes' values, the buttons) reads on what is painted behind it.
     * Collected on the FX thread and asserted outside it, so one offender does
     * not hide the rest.
     *
     * @return the texts that were read, so a test can show the ones it put
     *     there were among them
     */
    private List<String> assertReadsInTheTheme(DialogPane dialog, String theme) {
        boolean dark = "dark".equals(theme);
        List<String> read = new ArrayList<>();
        List<String> offTheme = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        interact(() -> {
            dialog.applyCss();
            dialog.layout();
            for (String field : List.of(".text-field", ".text-area", ".combo-box")) {
                for (Node node : dialog.lookupAll(field)) {
                    Color fill = paintedBehind(node);
                    if (isShown(node) && Contrast.isDark(fill) != dark) {
                        offTheme.add(field + " on " + fill);
                    }
                }
            }
            for (Node node : dialog.lookupAll(".text")) {
                if (node instanceof Text text && isShown(text)
                        && text.getText() != null && !text.getText().isBlank()) {
                    Color fill = Contrast.flat(text.getFill());
                    Color behind = paintedBehind(text);
                    double ratio = Contrast.ratio(fill, behind);
                    read.add(text.getText());
                    if (ratio < Contrast.READABLE) {
                        unreadable.add(String.format(Locale.ROOT, "\"%s\" is %s on %s, %.2f:1",
                                text.getText(), fill, behind, ratio));
                    }
                }
            }
        });
        assertThat(offTheme)
                .withFailMessage("%s: fields drawn off the theme:%n  %s",
                        theme, String.join("\n  ", offTheme))
                .isEmpty();
        assertThat(unreadable)
                .withFailMessage("%s: text short of %.1f:1:%n  %s",
                        theme, Contrast.READABLE, String.join("\n  ", unreadable))
                .isEmpty();
        return read;
    }

    /** The nearest paint at or above a node that is not see-through. */
    private static Color paintedBehind(Node node) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (at instanceof Region region && region.getBackground() != null
                    && !region.getBackground().getFills().isEmpty()) {
                Color fill = Contrast.backdrop(region);
                if (fill.getOpacity() > 0) {
                    return fill;
                }
            }
        }
        throw new AssertionError("nothing is painted behind " + node);
    }

    private static boolean isShown(Node node) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (!at.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private Button button(String id) {
        return lookup(id).queryAs(Button.class);
    }

    /** The button reading {@code text} on a row of a list, once the list has drawn one. */
    private Button rowButton(String list, String text) {
        ListView<?> view = lookup(list).queryAs(ListView.class);
        return Await.untilValue("a row button reading " + text + " in " + list,
                () -> onFx(() -> view.lookupAll(".button").stream()
                        .filter(node -> node instanceof Button button
                                && text.equals(button.getText()))
                        .map(Button.class::cast)
                        .findFirst()
                        .orElse(null)),
                Objects::nonNull, PATIENCE);
    }

    /** The item reading {@code text} in the context menu of a drawn server row. */
    private MenuItem serverRowMenuItem(String text) {
        ListView<?> list = lookup("#serverListView").queryAs(ListView.class);
        return Await.untilValue("a server row whose menu has " + text,
                () -> onFx(() -> list.lookupAll(".list-cell").stream()
                        .filter(node -> node instanceof ListCell<?> cell
                                && cell.getItem() != null && cell.getContextMenu() != null)
                        .flatMap(node -> ((ListCell<?>) node).getContextMenu().getItems().stream())
                        .filter(item -> text.equals(item.getText()))
                        .findFirst()
                        .orElse(null)),
                Objects::nonNull, PATIENCE);
    }

    private MenuItem importMenuItem(String id) {
        MenuButton menu = lookup("#importMenuButton").queryAs(MenuButton.class);
        return menu.getItems().stream()
                .filter(item -> id.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the Import menu has no " + id));
    }

    private void fillSubscription(DialogPane dialog, String name, String url) {
        interact(() -> {
            for (Node node : dialog.lookupAll(".text-field")) {
                TextField field = (TextField) node;
                field.setText(I18n.get("subscriptions.name.prompt").equals(field.getPromptText())
                        ? name : url);
            }
        });
    }

    /** Runs {@code work} on the FX thread and hands back what it returned. */
    private <T> T onFx(Supplier<T> work) {
        List<T> result = new ArrayList<>(1);
        interact(() -> result.add(work.get()));
        return result.get(0);
    }

    /** A server store of the test's own, so nothing added here reaches the shared data dir. */
    private static ConfigStore serverStore() {
        ConfigStore store = TestConfigStores.at(freshDir("servers"));
        ServiceLocator.register(ConfigStore.class, store);
        ServiceLocator.register(ServerBackupService.class,
                new ServerBackupService(store, new ShareLinkParser()));
        return store;
    }

    private static Path freshDir(String name) {
        try {
            return Files.createDirectories(tempDir.resolve(name + "-" + System.nanoTime()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static KeyEvent keyPress(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    private static ServerConfig server(String name) {
        ServerConfig config = new ServerConfig();
        config.setName(name);
        config.setProtocol(Protocol.VLESS);
        config.setAddress("203.0.113.10");
        config.setPort(443);
        config.setUuid("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        return config;
    }

    /** Fails a start the way a sing-box that will not launch does. */
    private static final class FailingConnectionService extends ConnectionService {

        FailingConnectionService() {
            super(null, null, null, null);
        }

        @Override
        public ConnectAttempt connect(ProxyMode modeOverride) throws IOException {
            throw new IOException("sing-box exited with code 1");
        }
    }
}
