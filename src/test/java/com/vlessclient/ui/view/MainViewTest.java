package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.TestServers;
import com.vlessclient.testing.ThreadDump;
import com.vlessclient.testing.UiTest;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Headless TestFX smoke tests for MainView: the FXML loads and sidebar
 * navigation marks the selected button active.
 */
@UiTest
public class MainViewTest extends ApplicationTest {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 1024, 720));
        stage.show();
    }

    /**
     * The sidebar text must come from the bundle, not FXML literals. It used to
     * be hardcoded English, so the primary Russian audience saw an English
     * navigation while the sidebar.* translations sat unused — and a language
     * change did not move it.
     */
    @Test
    void sidebarLabelsComeFromTheMessageBundle() {
        // Every nav label must be bound to the bundle rather than carrying an
        // FXML literal — that is what left the navigation in English for the
        // primary Russian audience while sidebar.* sat translated and unused.
        // Asserting the binding (not a re-translated value) keeps this robust:
        // I18n's locale is process-wide static state shared by the whole
        // headless suite, so flipping it here would race the other classes.
        for (String id : new String[] {"#btnDashboard", "#btnServers", "#btnSubscriptions",
                                       "#btnRouting", "#btnLogs", "#btnSettings"}) {
            Button button = lookup(id).queryButton();
            assertThat(button.textProperty().isBound())
                    .as("%s must take its text from the message bundle", id)
                    .isTrue();
            assertThat(button.getText()).isNotBlank();
        }

        Button dashboard = lookup("#btnDashboard").queryButton();
        assertThat(dashboard.getText())
                .isEqualTo(com.vlessclient.app.I18n.get("sidebar.dashboard"));
    }

    @Test
    void sidebarButtonsExist() {
        assertThat(lookup("#btnDashboard").tryQuery()).isPresent();
        assertThat(lookup("#btnServers").tryQuery()).isPresent();
        assertThat(lookup("#btnSubscriptions").tryQuery()).isPresent();
        assertThat(lookup("#btnRouting").tryQuery()).isPresent();
        assertThat(lookup("#btnLogs").tryQuery()).isPresent();
        assertThat(lookup("#btnSettings").tryQuery()).isPresent();
    }

    @Test
    void clickingDashboardSwitchesView() {
        fireNav("#btnDashboard");
        Button dashboard = lookup("#btnDashboard").query();
        assertThat(dashboard.getStyleClass()).contains("nav-button-active");
    }

    @Test
    void clickingServersSwitchesView() {
        fireNav("#btnServers");
        Button servers = lookup("#btnServers").query();
        assertThat(servers.getStyleClass()).contains("nav-button-active");
    }

    /**
     * Shortcut+F takes the keyboard to the search field of the page on screen,
     * its text selected so typing replaces it. The Servers and Logs pages each
     * have one, and only the pointer reached it.
     *
     * <p>The shortcut is taken from the window's accelerators and run, as the
     * scene runs it for the key. The focus is read as the scene's focus owner:
     * {@code isFocused()} also needs a focused window, which a headless one may
     * not be.</p>
     */
    @Test
    void theFindShortcutFocusesTheSearchFieldOfThePageOnScreen() {
        Button dashboard = lookup("#btnDashboard").queryButton();
        ConfigStore store = ServiceLocator.get(ConfigStore.class);
        ServerConfig server = server("Amsterdam 01");
        try {
            // With no servers the Servers page hides its search, since there
            // is nothing to narrow, and the shortcut leaves the focus alone.
            fireNav("#btnServers");
            Button servers = lookup("#btnServers").queryButton();
            interact(servers::requestFocus);
            interact(findShortcut(servers.getScene()));
            assertThat(servers.getScene().getFocusOwner())
                    .as("the focus after Shortcut+F on a Servers page with no servers")
                    .isSameAs(servers);

            interact(() -> store.addServer(server));
            for (String page : List.of("#btnServers", "#btnLogs")) {
                fireNav(page);
                TextField search = lookup("#searchField").queryAs(TextField.class);
                Scene scene = search.getScene();
                Button nav = lookup(page).queryButton();
                interact(() -> {
                    search.setText("vless");
                    nav.requestFocus();
                });
                assertThat(scene.getFocusOwner())
                        .as("precondition: the focus on the sidebar button of %s", page)
                        .isSameAs(nav);

                interact(findShortcut(scene));

                assertThat(scene.getFocusOwner())
                        .as("the focus after Shortcut+F on %s", page)
                        .isSameAs(search);
                assertThat(search.getSelectedText())
                        .as("the search text, selected so typing replaces it")
                        .isEqualTo("vless");
                interact(() -> {
                    search.clear();
                    nav.requestFocus();
                });
            }
        } finally {
            // A field left with the focus blinks its caret on once TestFX hides
            // the window; a sidebar button has no caret.
            interact(() -> {
                store.removeServer(server.getId());
                dashboard.requestFocus();
            });
        }
    }

    /** The main window's Shortcut+F, as the scene runs it for the key. */
    private static Runnable findShortcut(Scene scene) {
        Runnable find = scene.getAccelerators().get(KeyCombination.keyCombination("Shortcut+F"));
        assertThat(find).as("the main window's Shortcut+F").isNotNull();
        return find;
    }

    private static ServerConfig server(String name) {
        return TestServers.server()
                .name(name)
                .protocol(Protocol.VLESS)
                .address("203.0.113.10")
                .port(443)
                .uuid("b1c2d3e4-f5a6-7890-abcd-ef1234567890")
                .build();
    }

    /**
     * Fires the nav button's action directly instead of a robot clickOn. The
     * glass robot can miss its target under load when several TestFX suites
     * run together (headless focus contention), which made these assertions
     * flaky; firing the handler exercises the same navigation deterministically.
     */
    private void fireNav(String id) {
        Button button = lookup(id).query();
        interact(button::fire);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Entries a newer build wrote are skipped rather than failing the file,
     * which only helps if the user learns of it: the next save rewrites the
     * file without them, so a rollback that was still recoverable stops being
     * recoverable the moment they add a server.
     */
    @Test
    void entriesANewerBuildWroteAnnounceThemselvesInTheirOwnBanner() {
        var persistence = com.vlessclient.app.ServiceLocator
                .get(com.vlessclient.service.ConfigStore.class).getPersistenceState();
        try {
            interact(() -> persistence.couldNotRead("servers.json", 2));

            javafx.scene.layout.HBox banner = lookup("#unreadableBanner").queryAs(
                    javafx.scene.layout.HBox.class);
            javafx.scene.control.Label message = lookup("#unreadableMessage").queryAs(
                    javafx.scene.control.Label.class);
            interact(() -> {
                banner.getScene().getRoot().applyCss();
                banner.getScene().getRoot().layout();
            });

            assertThat(banner.isVisible()).isTrue();
            assertThat(banner.isManaged()).isTrue();
            assertThat(message.getText()).contains("servers.json");
        } finally {
            interact(() -> persistence.saved("servers.json"));
        }
    }

    /**
     * A damaged file set aside at startup looks like lost data unless the
     * window says where it went; once read, the notice can be put away.
     */
    @Test
    void aFileSetAsideIsNamedWithWhereItWentUntilDismissed() {
        var persistence = com.vlessclient.app.ServiceLocator
                .get(com.vlessclient.service.ConfigStore.class).getPersistenceState();
        String where = "/data/servers.json.corrupt-1727000000000";
        try {
            interact(() -> persistence.setAside("servers.json", where));

            javafx.scene.layout.HBox banner = lookup("#fileNoticeBanner").queryAs(
                    javafx.scene.layout.HBox.class);
            javafx.scene.control.Label message = lookup("#fileNoticeMessage").queryAs(
                    javafx.scene.control.Label.class);
            Button dismiss = lookup("#dismissFileNoticeButton").queryButton();
            interact(() -> {
                banner.getScene().getRoot().applyCss();
                banner.getScene().getRoot().layout();
                assertThat(banner.isVisible()).isTrue();
                assertThat(banner.isManaged()).isTrue();
                assertThat(message.getText()).contains("servers.json").contains(where);
                assertThat(dismiss.isVisible()).isTrue();
                assertThat(message.getBoundsInParent().getMaxX())
                        .isLessThanOrEqualTo(dismiss.getBoundsInParent().getMinX());
                assertThat(dismiss.getBoundsInParent().getMaxX())
                        .isLessThanOrEqualTo(banner.getWidth());
                // Fired, as Retry is: a click through the pointer missed the
                // button on the Linux runner and the banner stayed.
                dismiss.fire();
            });

            assertThat(banner.isVisible()).isFalse();
            assertThat(banner.isManaged()).isFalse();
        } finally {
            interact(persistence::dismissSetAside);
        }
    }

    /**
     * A held file is news for the whole run: every save of it is skipped, so
     * the banner names it and tells the user what would let it be saved.
     */
    @Test
    void aHeldFileIsNamedWithWhatWouldLetItBeSaved() {
        String notice = MainViewController.fileNotice(
                java.util.Map.of("servers.json", "AccessDeniedException"),
                java.util.Map.of("routing.json", "/data/routing.json.corrupt-1"));

        assertThat(notice.split("\n")).containsExactly(
                com.vlessclient.app.I18n.get("persistence.held", "servers.json"),
                com.vlessclient.app.I18n.get("persistence.set.aside", "routing.json",
                        "/data/routing.json.corrupt-1"));
    }

    @Test
    void failedSaveShowsAFittingBannerAndRetryClearsIt() {
        var persistence = com.vlessclient.app.ServiceLocator
                .get(com.vlessclient.service.ConfigStore.class).getPersistenceState();
        java.util.concurrent.atomic.AtomicBoolean retried = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            interact(() -> persistence.failed("test.json", () -> {
                retried.set(true);
                persistence.saved("test.json");
            }));
            assertThat(lookup("#persistenceBanner").tryQuery()).isPresent();
            javafx.scene.layout.HBox banner = lookup("#persistenceBanner").queryAs(
                    javafx.scene.layout.HBox.class);
            Button retry = lookup("#retrySavingButton").queryButton();
            javafx.scene.control.Label message = lookup("#persistenceMessage").queryAs(
                    javafx.scene.control.Label.class);
            interact(() -> {
                banner.getScene().getRoot().applyCss();
                banner.getScene().getRoot().layout();
                assertThat(banner.isVisible()).isTrue();
                assertThat(banner.isManaged()).isTrue();
                assertThat(message.getBoundsInParent().getMaxX())
                        .isLessThanOrEqualTo(retry.getBoundsInParent().getMinX());
                assertThat(retry.getBoundsInParent().getMaxX()).isLessThanOrEqualTo(banner.getWidth());
                assertThat(message.textProperty().isBound()).isTrue();
                retry.fire();
            });
            // Two waits, so a failure says which side stalled: the retry runs on
            // a virtual thread, and the banner hides on the FX thread. Twice on
            // the Windows runner a single 5 s wait ran out with nothing said
            // about which.
            awaitOrDumpThreads("the retry ran", retried::get,
                    () -> "the retry button is disabled: " + retry.isDisabled());
            awaitOrDumpThreads("successful retry hides the banner",
                    () -> com.vlessclient.service.FxExecutor.get(() -> !banner.isVisible()),
                    () -> "files still failing: " + persistence.failedFiles());
        } finally {
            interact(() -> persistence.saved("test.json"));
        }
    }

    /**
     * Waits for {@code condition}. On a timeout it prints every thread first,
     * virtual ones included, and adds what {@code state} reads then.
     */
    private static void awaitOrDumpThreads(String what, BooleanSupplier condition,
                                           Supplier<String> state) {
        try {
            Await.until(what, condition, Duration.ofSeconds(30));
        } catch (AssertionError timedOut) {
            System.err.println("Threads when the wait for " + what + " ran out:\n"
                    + ThreadDump.ofAllThreads());
            throw new AssertionError(timedOut.getMessage() + "; " + state.get(), timedOut);
        }
    }
}
