package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ServerBackupService;
import com.vlessclient.service.ShareLinkParser;
import com.vlessclient.service.TestConfigStores;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.UiTest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * A subscription URL pasted on the Servers page goes on to the Subscriptions
 * page's form.
 *
 * <p>Nothing on the Servers page fetches a URL, so a subscription URL pasted
 * there imported nothing, and the report could only say that subscriptions
 * are added on the Subscriptions page: the user went there and pasted it
 * again (item 3.8 of the 2026-09-19 review). The report now offers to add it,
 * and the Subscriptions page opens its own form with the URL in it, so the
 * form's checks, the plain-http warning among them, still apply.</p>
 *
 * <p>The whole window is loaded, since the hand-off from one page to the
 * other is what is under test. The clipboard is the platform's: the headless
 * one keeps its content in memory, apart from the system's.</p>
 */
@UiTest
public class SubscriptionUrlHandOffTest extends ApplicationTest {

    private static final String URL =
            "https://sub.example.com/api/v1/client/subscribe?token=aBcD1234";
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @TempDir
    static Path tempDir;

    private Stage stage;
    private MainViewController main;
    /** The window of every dialog the test opened, closed since or not. */
    private final List<Window> dialogs = new ArrayList<>();

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        // A store of its own: a server imported here stays out of the one the
        // suite's other window tests share.
        ConfigStore store = TestConfigStores.at(tempDir.resolve(UUID.randomUUID().toString()));
        ServiceLocator.register(ConfigStore.class, store);
        ServiceLocator.register(ServerBackupService.class,
                new ServerBackupService(store, new ShareLinkParser()));
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainView.fxml"));
        stage.setScene(new Scene(loader.load(), 1024, 720));
        main = loader.getController();
        stage.show();
    }

    /**
     * Hides every dialog still open and takes the focus to the root of each:
     * the headless platform keeps a window focused after it hides, and the
     * caret of a field focused in the form would blink on into the tests
     * after this one.
     *
     * <p>The clipboard is emptied for the same reason. It belongs to the
     * platform, not to this class, and a URL left on it is typed into the
     * first text field another test pastes into — which starts a caret that
     * blinks through the rest of the fork.</p>
     */
    @AfterEach
    void closeTheDialogs() {
        interact(() -> {
            Clipboard.getSystemClipboard().clear();
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

    @Test
    void theReportHandsTheUrlToTheSubscriptionsForm() {
        DialogPane report = pasteOnTheServersPage(URL + "\n");
        Button add = onFx(() -> button(report, I18n.get("button.add.subscription")));
        assertThat(add).as("the report's button that adds the URL as a subscription")
                .isNotNull();

        DialogPane form = open(add::fire);

        assertThat(lookup("#btnSubscriptions").queryButton().getStyleClass())
                .as("the Subscriptions page is on screen")
                .contains("nav-button-active");
        assertThat(form.getHeaderText()).isEqualTo(I18n.get("subscriptions.add.header"));
        assertThat(onFx(() -> fieldTexts(form)))
                .as("the URL as it was pasted, its token included")
                .contains(URL);
    }

    /**
     * Another client's one-tap link wraps the URL. It was reported as a
     * server link that did not parse; the report now offers the URL inside,
     * and the form holds that URL rather than the wrapper.
     */
    @Test
    void anotherClientsLinkHandsOnTheUrlItWraps() {
        DialogPane report = pasteOnTheServersPage("happ://add/" + URL + "\n");
        assertThat(report.getContentText())
                .isEqualTo(I18n.get("servers.import.clipboard.subscription"));
        Button add = onFx(() -> button(report, I18n.get("button.add.subscription")));
        assertThat(add).as("the report's button that adds the wrapped URL").isNotNull();

        DialogPane form = open(add::fire);

        assertThat(onFx(() -> fieldTexts(form))).as("the URL the link wraps")
                .contains(URL)
                .noneMatch(text -> text.startsWith("happ://"));
    }

    /** Typed or pasted into the form itself, a wrapped link becomes the URL it holds. */
    @Test
    void theFormShowsTheUrlAWrappedLinkHolds() {
        interact(() -> lookup("#btnSubscriptions").queryButton().fire());
        DialogPane form = open(lookup("#addSubscriptionButton").queryButton()::fire);
        interact(() -> {
            for (Node node : form.lookupAll(".text-field")) {
                TextField field = (TextField) node;
                if (field.getPromptText().startsWith("https://")) {
                    field.setText("sing-box://import-remote-profile?url="
                            + URLEncoder.encode(URL, StandardCharsets.UTF_8)
                            + "#Provider");
                }
            }
        });

        assertThat(onFx(() -> fieldTexts(form))).as("the URL field")
                .contains(URL)
                .noneMatch(text -> text.startsWith("sing-box://"));
    }

    /** A link Happ encrypted for itself holds no URL: the report says to ask for it. */
    @Test
    void aLinkEncryptedForHappIsExplained() {
        DialogPane report = pasteOnTheServersPage("happ://crypt3/aBcDeFgHiJkLmNoP\n");

        assertThat(report.getContentText())
                .isEqualTo(I18n.get("subscriptions.link.happ.encrypted"));
        assertThat(onFx(() -> button(report, I18n.get("button.add.subscription"))))
                .isNull();
    }

    /** The link dialog reads its text the way the clipboard import does. */
    @Test
    void theLinkDialogOffersTheUrlToo() {
        interact(() -> lookup("#btnServers").queryButton().fire());
        DialogPane dialog = open(importMenuItem("importLinkItem")::fire);
        interact(() -> ((TextArea) dialog.lookup(".text-area")).setText(URL));

        DialogPane report = open(((Button) dialog.lookupButton(ButtonType.OK))::fire);

        assertThat(onFx(() -> button(report, I18n.get("button.add.subscription"))))
                .as("the report's button that adds the URL as a subscription")
                .isNotNull();
    }

    /** Two URLs are two subscriptions; the report does not pick one. */
    @Test
    void twoUrlsAreNotGuessedBetween() {
        DialogPane report = pasteOnTheServersPage(
                URL + "\nhttps://other.example.net/sub/xyz\n");

        assertThat(onFx(() -> button(report, I18n.get("button.add.subscription"))))
                .isNull();
        assertThat(report.getContentText())
                .isEqualTo(I18n.get("servers.import.clipboard.subscription"));
    }

    /** Server links pasted with the URL still go in, and nothing is offered. */
    @Test
    void aUrlAmongServerLinksIsReportedAsSkipped() {
        DialogPane report = pasteOnTheServersPage(URL + "\n"
                + "vless://11111111-2222-3333-4444-555555555555@198.51.100.7:443?type=tcp"
                + "#Netherlands%2001\n");

        assertThat(report.getHeaderText())
                .isEqualTo(I18n.get("servers.import.clipboard.partial", 1, 1));
        assertThat(onFx(() -> button(report, I18n.get("button.add.subscription"))))
                .isNull();
    }

    /** Puts {@code text} on the clipboard and imports it from the Servers page. */
    /**
     * A page's "Add to Tunl" button opens a tunl:// link, which opens the same
     * form with the URL in it: the user adds it, since any page can open a
     * link.
     */
    @Test
    void aTunlLinkOpensTheSubscriptionsFormWithItsUrl() {
        String link = "tunl://install-config?url="
                + java.net.URLEncoder.encode(URL, java.nio.charset.StandardCharsets.UTF_8)
                + "#Provider";

        DialogPane form = open(() -> main.openLink(link));

        assertThat(form.getHeaderText()).isEqualTo(I18n.get("subscriptions.add.header"));
        assertThat(onFx(() -> fieldTexts(form))).contains(URL);
    }

    @Test
    void aTunlLinkWithNoSubscriptionUrlSaysSo() {
        DialogPane alert = open(() -> main.openLink("tunl://something-else"));

        assertThat(alert.getHeaderText()).isEqualTo(I18n.get("links.unreadable.header"));
    }

    private DialogPane pasteOnTheServersPage(String text) {
        interact(() -> {
            Clipboard.getSystemClipboard().setContent(Map.of(DataFormat.PLAIN_TEXT, text));
            lookup("#btnServers").queryButton().fire();
        });
        return open(importMenuItem("importClipboardItem")::fire);
    }

    private MenuItem importMenuItem(String id) {
        MenuButton menu = lookup("#importMenuButton").queryAs(MenuButton.class);
        return menu.getItems().stream()
                .filter(item -> id.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the Import menu has no " + id));
    }

    /**
     * Runs {@code opener} on the FX thread without waiting for it, since the
     * form it opens waits for the user, and returns the dialog that appears.
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

    /** The dialog's button labelled {@code text}, or null. */
    private static Button button(DialogPane dialog, String text) {
        for (ButtonType type : dialog.getButtonTypes()) {
            if (dialog.lookupButton(type) instanceof Button button
                    && text.equals(button.getText())) {
                return button;
            }
        }
        return null;
    }

    private static List<String> fieldTexts(DialogPane dialog) {
        List<String> texts = new ArrayList<>();
        for (Node node : dialog.lookupAll(".text-field")) {
            texts.add(((TextField) node).getText());
        }
        return texts;
    }

    /** Runs {@code work} on the FX thread and hands back what it returned. */
    private <T> T onFx(Supplier<T> work) {
        List<T> result = new ArrayList<>(1);
        interact(() -> result.add(work.get()));
        return result.get(0);
    }
}
