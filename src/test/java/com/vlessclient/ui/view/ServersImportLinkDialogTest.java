package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.ThemeCss;
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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The link dialog reads what it is given the way the clipboard import does.
 *
 * <p>It used to parse the whole text as one link. Two pasted links became one
 * server whose name held the second link, credential included, and that name
 * went into the log on every connect. It also skipped the core's rules, so a
 * link the core refuses was stored; one with a REALITY short ID longer than
 * 16 hex digits made every connect fail.</p>
 */
@UiTest
public class ServersImportLinkDialogTest extends ApplicationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final String UUID_ONE = "11111111-2222-3333-4444-555555555555";
    private static final String UUID_TWO = "66666666-7777-8888-9999-000000000000";

    @TempDir
    static Path tempDir;

    private ConfigStore store;
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
    void twoLinksPastedTogetherBecomeTwoServers() {
        enterIntoTheLinkDialog(
                "vless://" + UUID_ONE + "@198.51.100.7:443#First\n"
                        + "vless://" + UUID_TWO + "@198.51.100.8:443#Second\n");
        closeReport("servers.import.clipboard.done", 2);

        assertThat(servers()).extracting(ServerConfig::getName)
                .containsExactly("First", "Second");
        assertThat(servers()).extracting(ServerConfig::getUuid)
                .containsExactly(UUID_ONE, UUID_TWO);
    }

    @Test
    void aLinkTheCoreWouldRefuseIsReportedAndNotStored() {
        enterIntoTheLinkDialog("vless://" + UUID_ONE + "@198.51.100.7:443"
                + "?security=reality&sni=example.com&fp=chrome"
                + "&pbk=WZaG00XCAiVCF2SP5fmSbKiuTbBB-lMDg_81rC8hR80"
                + "&sid=0123456789abcdef01#Too long");

        String report = closeReport("servers.import.clipboard.nothing");

        assertThat(report).contains(I18n.get("refusal.reality.short.id"));
        assertThat(report).doesNotContain(UUID_ONE);
        assertThat(servers()).isEmpty();
    }

    /** One link that went in needs no report, as before: the list shows it. */
    @Test
    void oneLinkIsStoredWithoutAReport() {
        enterIntoTheLinkDialog("vless://" + UUID_ONE + "@198.51.100.7:443#Only");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(servers()).extracting(ServerConfig::getName).containsExactly("Only");
        assertThat(showingDialog(pane -> true)).as("a dialog after the import").isNull();
    }

    private List<ServerConfig> servers() {
        AtomicReference<List<ServerConfig>> copy = new AtomicReference<>();
        interact(() -> copy.set(List.copyOf(store.getServers())));
        return copy.get();
    }

    /** Types {@code text} into the link dialog and confirms it. */
    private void enterIntoTheLinkDialog(String text) {
        MenuItem linkItem = menuItem("#importMenuButton", "importLinkItem");
        Platform.runLater(linkItem::fire);
        DialogPane input = awaitDialog("the link dialog",
                pane -> pane.getContent() instanceof TextArea);
        interact(() -> {
            ((TextArea) input.getContent()).setText(text);
            ((Button) input.lookupButton(ButtonType.OK)).fire();
        });
    }

    /**
     * Waits for the report whose header is {@code headerKey}, formatted with
     * {@code args}, closes it and returns what it said.
     */
    private String closeReport(String headerKey, Object... args) {
        String header = I18n.get(headerKey, args);
        DialogPane report = awaitDialog("the report \"" + header + "\"",
                pane -> header.equals(pane.getHeaderText()));
        String content = report.getContentText();
        interact(() -> ((Button) report.lookupButton(ButtonType.OK)).fire());
        WaitForAsyncUtils.waitForFxEvents();
        return content == null ? "" : content;
    }

    private DialogPane awaitDialog(String what, Predicate<DialogPane> matches) {
        return Await.untilValue(what, () -> showingDialog(matches), Objects::nonNull, TIMEOUT);
    }

    private DialogPane showingDialog(Predicate<DialogPane> matches) {
        AtomicReference<DialogPane> found = new AtomicReference<>();
        interact(() -> {
            for (Window window : Window.getWindows()) {
                if (window != stage && window.isShowing() && window.getScene() != null
                        && window.getScene().getRoot().lookup(".dialog-pane")
                                instanceof DialogPane pane
                        && matches.test(pane)) {
                    found.set(pane);
                }
            }
        });
        return found.get();
    }

    private MenuItem menuItem(String menuQuery, String itemId) {
        MenuButton menu = lookup(menuQuery).query();
        return menu.getItems().stream()
                .filter(item -> itemId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(menuQuery + " has no item " + itemId));
    }
}
