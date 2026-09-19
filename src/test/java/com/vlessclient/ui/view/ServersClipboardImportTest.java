package com.vlessclient.ui.view;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ServerBackupService;
import com.vlessclient.service.ShareLinkParser;
import com.vlessclient.service.TestConfigStores;
import com.vlessclient.testing.UiTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Importing the share links on the clipboard, from the Import menu and from
 * the paste shortcut.
 *
 * <p>The clipboard is a fake handed to the controller before its
 * {@code initialize()} runs: headless JavaFX has no system clipboard to fill,
 * and a fake installed after the view is built could not see a read made while
 * building it. The store is this class's own and fresh for every method, so
 * nothing imported here reaches the shared test data dir.</p>
 *
 * <p>Menu items are fired and key events dispatched rather than clicked or
 * typed through the robot: Monocle's pointer reached popups and rows on macOS
 * and Linux x64 but not on every runner. That the item can be reached is
 * asserted on its own, by opening the menu.</p>
 */
@UiTest
public class ServersClipboardImportTest extends ApplicationTest {

    private static final String NETHERLANDS = "vless://11111111-2222-3333-4444-555555555555"
            + "@198.51.100.7:443?type=tcp#Netherlands%2001";
    private static final String GERMANY =
            "trojan://trojan-secret-password@198.51.100.8:8443#Germany%2002";
    private static final String FINLAND =
            "hysteria2://hy2-secret-password@198.51.100.9:443#Finland%2003";
    private static final String BROKEN = "vless://no-uuid-here#Broken";
    private static final String SUBSCRIPTION = "https://sub.example.com/link/aBcD1234token";

    private static final KeyCombination PASTE =
            new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);

    @TempDir
    static Path tempDir;

    private final AtomicInteger clipboardReads = new AtomicInteger();
    private volatile String clipboard;
    private ConfigStore store;
    private Stage stage;

    @Override
    public void start(Stage stage) throws IOException {
        this.stage = stage;
        store = TestConfigStores.at(tempDir.resolve(UUID.randomUUID().toString()));
        ServiceLocator.register(ConfigStore.class, store);
        ServiceLocator.register(ServerBackupService.class,
                new ServerBackupService(store, new ShareLinkParser()));
        Scene scene = new Scene(loadView(), 900, 640);
        scene.getStylesheets().addAll(ThemeCss.light());
        stage.setScene(scene);
        stage.show();
    }

    /**
     * Builds the view with the fake clipboard installed by the controller
     * factory, which runs before {@code initialize()}.
     */
    private Parent loadView() throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ServersView.fxml"));
        loader.setControllerFactory(type -> {
            ServersViewController controller = new ServersViewController();
            controller.setClipboardText(() -> {
                clipboardReads.incrementAndGet();
                return clipboard;
            });
            return controller;
        });
        return loader.load();
    }

    @AfterEach
    void closeReportsAndRestoreEnglish() {
        interact(() -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window != stage) {
                    window.hide();
                }
            }
            I18n.setLocale(Locale.ENGLISH);
        });
    }

    @Test
    void everyLinkOnTheClipboardIsImported() {
        importFromTheMenu(NETHERLANDS + "\n\n" + GERMANY + " " + FINLAND + "\r\n");

        assertThat(serverNames()).containsExactly("Netherlands 01", "Germany 02", "Finland 03");
        assertThat(report().getHeaderText())
                .isEqualTo(I18n.get("servers.import.clipboard.done", 3));
        assertThat(clipboardReads).hasValue(1);
    }

    @Test
    void aPartlyReadableClipboardSaysWhatWasSkipped() {
        importFromTheMenu("Your servers:\n" + NETHERLANDS + "\n" + BROKEN);

        assertThat(serverNames()).containsExactly("Netherlands 01");
        DialogPane report = report();
        assertThat(report.getHeaderText())
                .as("the words around the links are not links, so only the broken one counts")
                .isEqualTo(I18n.get("servers.import.clipboard.partial", 1, 1));
        assertThat(report.getContentText())
                .startsWith(I18n.get("servers.backup.import.skipped.list"))
                .contains("vless://");
    }

    @Test
    void anEmptyClipboardSaysThereIsNothingToImport() {
        importFromTheMenu(null);

        assertThat(serverNames()).isEmpty();
        assertNothingToImport(I18n.get("servers.import.clipboard.no.links"));
    }

    @Test
    void textWithoutLinksSaysThereIsNothingToImport() {
        importFromTheMenu("Call me when you are back, the meeting moved to 10:30");

        assertThat(serverNames()).isEmpty();
        assertNothingToImport(I18n.get("servers.import.clipboard.no.links"));
    }

    /**
     * No import here fetches a URL (the link dialog rejects one as an
     * unsupported scheme), so a subscription URL imports nothing, and the
     * report sends the user to the page that does fetch it.
     */
    @Test
    void aSubscriptionUrlPointsToTheSubscriptionsPage() {
        importFromTheMenu(SUBSCRIPTION + "\n");

        assertThat(serverNames()).isEmpty();
        assertNothingToImport(I18n.get("servers.import.clipboard.subscription"));
    }

    @Test
    void linksThatAreAllBrokenSayWhy() {
        importFromTheMenu(BROKEN);

        assertThat(serverNames()).isEmpty();
        DialogPane report = report();
        assertThat(report.getHeaderText())
                .isEqualTo(I18n.get("servers.import.clipboard.nothing"));
        assertThat(report.getContentText())
                .startsWith(I18n.get("servers.backup.import.skipped.list"));
    }

    @Test
    void thePasteShortcutOnTheServerListImports() {
        interact(() -> store.addServer(server("Already here")));
        clipboard = NETHERLANDS;

        ListView<?> list = lookup("#serverListView").query();
        interact(() -> list.fireEvent(pasteShortcut()));

        assertThat(serverNames()).containsExactly("Already here", "Netherlands 01");
        assertThat(report().getHeaderText())
                .isEqualTo(I18n.get("servers.import.clipboard.done", 1));
    }

    /**
     * With no servers the list is hidden behind the empty state and cannot
     * hold focus, which is exactly when a first link gets pasted. The header
     * controls around it still can.
     */
    @Test
    void thePasteShortcutWorksFromTheHeaderOfAnEmptyList() {
        clipboard = NETHERLANDS;

        MenuButton menu = lookup("#importMenuButton").query();
        interact(() -> menu.fireEvent(pasteShortcut()));

        assertThat(serverNames()).containsExactly("Netherlands 01");
    }

    @Test
    void thePasteShortcutInTheSearchFieldIsLeftToTheField() {
        interact(() -> store.addServer(server("Already here")));
        clipboard = NETHERLANDS;

        TextField search = lookup("#searchField").query();
        interact(() -> search.fireEvent(pasteShortcut()));

        assertThat(clipboardReads)
                .as("pasting into a text field is the field's paste, not an import")
                .hasValue(0);
        assertThat(serverNames()).containsExactly("Already here");
    }

    @Test
    void theClipboardIsLeftAloneUntilTheUserAsks() {
        interact(() -> store.addServer(server("Already here")));
        ListView<?> list = lookup("#serverListView").query();

        // What the view goes through with no import asked for: built, shown,
        // focused, a plain V typed, the language switched under it.
        interact(list::requestFocus);
        interact(() -> list.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "v", "v",
                KeyCode.V, false, false, false, false)));
        interact(() -> I18n.setLocale(Locale.of("ru")));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(clipboardReads).hasValue(0);
    }

    @Test
    void whatWasOnTheClipboardNeverReachesTheLog() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Logger serviceLog = (Logger) LoggerFactory.getLogger(ServerBackupService.class);
        Logger viewLog = (Logger) LoggerFactory.getLogger(ServersViewController.class);
        Level serviceLevel = serviceLog.getLevel();
        Level viewLevel = viewLog.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        // Everything both classes say, debug included, whatever the logging
        // configuration of the build would have let through.
        serviceLog.setLevel(Level.DEBUG);
        viewLog.setLevel(Level.DEBUG);
        try {
            importFromTheMenu(String.join(" ", NETHERLANDS, BROKEN, SUBSCRIPTION));
        } finally {
            serviceLog.setLevel(serviceLevel);
            viewLog.setLevel(viewLevel);
            root.detachAppender(appender);
        }

        List<String> logged = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(logged)
                .as("the import is logged, as counts")
                .anySatisfy(line -> assertThat(line).contains("1 added"));
        assertThat(logged).allSatisfy(line -> assertThat(line)
                .doesNotContain("11111111-2222")
                .doesNotContain("no-uuid-here")
                .doesNotContain("aBcD1234token"));
    }

    @Test
    void theImportMenuShowsTheClipboardItemInEveryLanguage() {
        List<String> labels = new ArrayList<>();
        for (Locale locale : List.of(Locale.ENGLISH, Locale.of("ru"))) {
            interact(() -> {
                I18n.setLocale(locale);
                // Rebuilt for each language, so the labels read below are the
                // ones a view built in it binds, not ones left from the last.
                try {
                    stage.getScene().setRoot(loadView());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            MenuButton menu = lookup("#importMenuButton").query();
            MenuItem item = clipboardItem();
            assertThat(menu.getText()).isEqualTo(I18n.get("servers.import"));
            assertThat(item.getText()).isEqualTo(I18n.get("servers.import.clipboard"));
            assertThat(item.isDisable()).isFalse();

            interact(menu::show);
            assertThat(openMenuLabels())
                    .as("the item is on screen in the open menu, not only in its item list")
                    .contains(I18n.get("servers.import.clipboard"));
            interact(menu::hide);
            labels.add(menu.getText() + "|" + item.getText());
        }

        assertThat(labels.get(1))
                .as("a Russian key missing from the bundle would fall back to English")
                .isNotEqualTo(labels.get(0));
    }

    private void importFromTheMenu(String clipboardText) {
        clipboard = clipboardText;
        MenuItem item = clipboardItem();
        interact(item::fire);
    }

    private MenuItem clipboardItem() {
        MenuButton menu = lookup("#importMenuButton").query();
        return menu.getItems().stream()
                .filter(item -> "importClipboardItem".equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the Import menu has no clipboard item"));
    }

    /** The one dialog the import opened to report what it did. */
    private DialogPane report() {
        List<DialogPane> open = new ArrayList<>();
        interact(() -> {
            for (Window window : Window.getWindows()) {
                if (window.isShowing() && window.getScene() != null
                        && window.getScene().getRoot().lookup(".dialog-pane")
                                instanceof DialogPane pane) {
                    open.add(pane);
                }
            }
        });
        assertThat(open).as("the import reports what it did in one dialog").hasSize(1);
        return open.get(0);
    }

    private void assertNothingToImport(String reason) {
        DialogPane report = report();
        assertThat(report.getHeaderText())
                .isEqualTo(I18n.get("servers.import.clipboard.nothing"));
        assertThat(report.getContentText()).isEqualTo(reason);
    }

    private List<String> openMenuLabels() {
        List<String> texts = new ArrayList<>();
        interact(() -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof PopupWindow && window.isShowing()) {
                    for (Node node : window.getScene().getRoot().lookupAll(".label")) {
                        if (node instanceof Label label && label.isVisible()) {
                            texts.add(label.getText());
                        }
                    }
                }
            }
        });
        return texts;
    }

    private List<String> serverNames() {
        return store.getServers().stream().map(ServerConfig::getName).toList();
    }

    /** Cmd+V on macOS, Ctrl+V elsewhere, asking JavaFX which one it expects. */
    private static KeyEvent pasteShortcut() {
        KeyEvent withMeta = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.V,
                false, false, false, true);
        return PASTE.match(withMeta) ? withMeta : new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                KeyCode.V, false, true, false, false);
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
}
