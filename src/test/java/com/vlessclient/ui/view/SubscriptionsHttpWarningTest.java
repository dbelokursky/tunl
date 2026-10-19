package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.Subscription;
import com.vlessclient.service.SubscriptionService;
import com.vlessclient.service.TestSubscriptionServices;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.UiTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
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
import static org.assertj.core.api.Assertions.within;

/**
 * The plaintext-http warning in the subscription dialog reads whole whenever
 * it is shown, and the dialog gives its room back when it goes.
 *
 * <p>A dialog sizes its window to its content once, as it opens, and the
 * warning comes and goes with the URL's scheme after that. Typed into the
 * open dialog, an http URL got a warning of one line ending in an ellipsis,
 * with OK and Cancel pushed partly out of the window. Refitting the window
 * alone is not enough: a window refitted to its scene measures the dialog
 * without a width, which counts the wrapping warning as one line. And an http
 * subscription edited to https kept the warning's room as a blank band.</p>
 *
 * <p>Each test opens the dialog the way the user does, in a window of its
 * own, and reads it there: the warning's text node, OK against the window's
 * height, and the window against the height the dialog lays out to at its
 * width. As in {@link ViewDialogThemeTest}, openers go through
 * {@code Platform.runLater}: the view shows the dialog with
 * {@code showAndWait}, which an {@code interact} would wait on forever.</p>
 */
@UiTest
public class SubscriptionsHttpWarningTest extends ApplicationTest {

    private static final String HTTP_URL = "http://provider.example/sub";
    private static final String HTTPS_URL = "https://provider.example/sub";
    /** What subscriptions.json holds for a URL sealed in the macOS keychain. */
    private static final String SEALED_URL = "@sealed:keychain:v1";
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @TempDir
    static Path tempDir;

    private Stage stage;
    private SubscriptionService service;
    private SubscriptionsViewController controller;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.setScene(new Scene(new StackPane(), 1000, 720));
        stage.show();
    }

    /**
     * Hiding a dialog also ends the showAndWait a failed assertion left open.
     * Monocle keeps a hidden window focused, so the text field focused in the
     * dialog would go on blinking its caret: an animation that keeps JavaFX
     * pulsing for the rest of the fork, which DashboardHiddenWindowTest counts.
     * Focus taken to the window's root stops it.
     */
    @AfterEach
    void closeTheDialogsAndGoBackToEnglish() {
        interact(() -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window != stage) {
                    window.hide();
                    if (window.getScene() != null) {
                        window.getScene().getRoot().requestFocus();
                    }
                }
            }
            I18n.setLocale(Locale.ENGLISH);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "ru"})
    void anHttpUrlTypedIntoTheOpenDialogGetsTheWholeWarning(String language) {
        mountTheView(language);
        DialogPane dialog = open(addButton()::fire);

        typeUrl(dialog, HTTP_URL);

        assertTheWarningReadsWhole(dialog);
        assertTheWindowFits(dialog);
    }

    @Test
    void theWarningsRoomGoesOnceTheUrlIsNoLongerHttp() {
        mountTheView("en");
        DialogPane dialog = open(addButton()::fire);
        double opened = onFx(() -> dialog.getScene().getHeight());
        typeUrl(dialog, HTTP_URL);
        assertTheWindowFits(dialog);

        typeUrl(dialog, HTTPS_URL);

        assertThat(onFx(() -> dialog.getScene().getHeight()))
                .as("the window's height with the URL back on https")
                .isCloseTo(opened, within(0.5));
        assertTheWindowFits(dialog);
    }

    /** Filled in before the dialog opens, the URL has its warning sized in from the start. */
    @ParameterizedTest
    @ValueSource(strings = {"en", "ru"})
    void anHttpSubscriptionOpensForEditingWithTheWholeWarning(String language) {
        mountTheView(language);
        service.addSubscription("Provider", HTTP_URL);

        DialogPane dialog = open(rowButton(I18n.get("button.edit"))::fire);

        assertTheWarningReadsWhole(dialog);
        assertTheWindowFits(dialog);
    }

    @Test
    void anHttpSubscriptionEditedToHttpsLeavesNoBlankBand() {
        mountTheView("en");
        service.addSubscription("Provider", HTTP_URL);
        DialogPane dialog = open(rowButton(I18n.get("button.edit"))::fire);

        typeUrl(dialog, HTTPS_URL);

        assertThat(onFx(() -> dialog.lookup(".subscription-http-warning").isVisible()))
                .as("whether the warning shows for an https URL")
                .isFalse();
        assertTheWindowFits(dialog);
    }

    /**
     * A URL no refresh can read, one with a space in it, was taken: stored,
     * it failed every refresh, and the failure quoted the URL, token and all.
     * OK now waits for a URL a refresh can read, and the dialog says why.
     */
    @ParameterizedTest
    @ValueSource(strings = {"en", "ru"})
    void aUrlThatIsNotAnAddressHoldsOkAndSaysWhy(String language) {
        mountTheView(language);
        DialogPane dialog = open(addButton()::fire);

        typeUrl(dialog, "https://provider.example/sub/ token");

        assertThat(onFx(() -> dialog.lookupButton(ButtonType.OK).isDisabled()))
                .as("whether OK can be pressed").isTrue();
        assertThat(drawnProblem(dialog)).isEqualTo(I18n.get("subscriptions.url.invalid"));
        assertTheWindowFits(dialog);

        // A URI, but none the HTTP client takes: it names no host.
        typeUrl(dialog, "https:/provider.example/sub/token");

        assertThat(onFx(() -> dialog.lookupButton(ButtonType.OK).isDisabled()))
                .as("whether OK can be pressed for a URL with one slash").isTrue();
        assertThat(drawnProblem(dialog)).isEqualTo(I18n.get("subscriptions.url.invalid"));

        typeUrl(dialog, "https://provider.example/sub/%20token");

        assertThat(onFx(() -> dialog.lookupButton(ButtonType.OK).isDisabled())).isFalse();
        assertThat(drawnProblem(dialog)).as("the problem, once the URL is one").isNull();
        assertTheWindowFits(dialog);
    }

    /** A link opened from a page or another app, whose %20 decodes to a space, does the same. */
    @Test
    void aLinksUrlThatIsNotAnAddressOpensWithOkHeld() {
        mountTheView("en");

        DialogPane dialog = open(() -> controller.openAddSubscriptionDialog(
                "https://provider.example/sub/ token"));

        assertThat(onFx(() -> dialog.lookupButton(ButtonType.OK).isDisabled())).isTrue();
        assertThat(drawnProblem(dialog)).isEqualTo(I18n.get("subscriptions.url.invalid"));
        assertTheWindowFits(dialog);
    }

    /**
     * A subscription whose URL the keychain did not return at this start is
     * listed with its sealed tag, which comes back as the URL at a start that
     * has the keychain. Its edit dialog opened with the tag in the URL field,
     * a line calling it no valid web address, and OK held: the subscription
     * could not even be renamed without its URL pasted again. The tag left as
     * it is now goes through, and the dialog says why it is there.
     */
    @ParameterizedTest
    @ValueSource(strings = {"en", "ru"})
    void aUrlTheKeychainDidNotReturnIsKeptThroughARename(String language) {
        mountTheView(language);
        Subscription locked = new Subscription();
        locked.setName("Provider");
        locked.setUrl(SEALED_URL);
        interact(() -> service.getSubscriptions().add(locked));
        DialogPane dialog = open(rowButton(I18n.get("button.edit"))::fire);

        assertThat(onFx(() -> dialog.lookupButton(ButtonType.OK).isDisabled()))
                .as("whether OK can be pressed").isFalse();
        assertThat(drawnProblem(dialog)).as("the problem shown").isNull();
        assertThat(drawnNote(dialog)).isEqualTo(I18n.get("subscriptions.url.sealed"));
        assertTheWindowFits(dialog);

        typeUrl(dialog, "@sealed:dpapi:v1:AQAAANCMnd8BFdERjHoAwE");
        assertThat(onFx(() -> dialog.lookupButton(ButtonType.OK).isDisabled()))
                .as("whether OK can be pressed for a tag the subscription does not hold")
                .isTrue();
        assertThat(drawnNote(dialog)).isNull();
        typeUrl(dialog, HTTPS_URL);
        assertThat(drawnNote(dialog)).as("the note, for a URL entered again").isNull();
        assertThat(drawnProblem(dialog)).isNull();
        assertTheWindowFits(dialog);

        typeUrl(dialog, SEALED_URL);
        typeName(dialog, "Renamed");
        interact(() -> {
            // Off the name field first, as closeTheDialogsAndGoBackToEnglish
            // does for the windows it closes: Monocle keeps a closed window
            // focused, and the caret would blink on through the fork.
            dialog.getScene().getRoot().requestFocus();
            ((Button) dialog.lookupButton(ButtonType.OK)).fire();
        });

        Await.until("the rename saved", () -> onFx(() -> "Renamed".equals(locked.getName())),
                PATIENCE);
        assertThat(onFx(locked::getUrl)).as("the URL, kept as its tag").isEqualTo(SEALED_URL);
    }

    /** The problem under the URL as the dialog draws it, or null when none is shown. */
    private String drawnProblem(DialogPane dialog) {
        return onFx(() -> {
            dialog.applyCss();
            dialog.layout();
            Node problem = dialog.lookup(".subscription-error");
            return problem != null && problem.isVisible()
                    ? ((Text) problem.lookup(".text")).getText() : null;
        });
    }

    /** The note under the URL as the dialog draws it, or null when none is shown. */
    private String drawnNote(DialogPane dialog) {
        return onFx(() -> {
            dialog.applyCss();
            dialog.layout();
            Node note = dialog.lookup(".form-note");
            return note != null && note.isVisible()
                    ? ((Text) note.lookup(".text")).getText() : null;
        });
    }

    // ===== Opening and reading the dialog =====

    /**
     * Puts the view in the window over a subscription service of the test's
     * own, in {@code language} and the light theme: the dialog takes the
     * window's stylesheets, and with them the 11px type the warning wraps in.
     */
    private void mountTheView(String language) {
        service = TestSubscriptionServices.quiet(freshDir("subscriptions"));
        ServiceLocator.register(SubscriptionService.class, service);
        interact(() -> {
            I18n.setLocale(Locale.of(language));
            FXMLLoader loader =
                    new FXMLLoader(getClass().getResource("/fxml/SubscriptionsView.fxml"));
            try {
                stage.getScene().setRoot(loader.load());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            controller = loader.getController();
            stage.getScene().getStylesheets().setAll(ThemeCss.of("light"));
        });
    }

    /**
     * Fires what opens a dialog and returns the dialog once it is on screen:
     * the first showing window that was not showing before, with a dialog pane
     * for its root.
     */
    private DialogPane open(Runnable opener) {
        List<Window> before = onFx(() -> List.copyOf(Window.getWindows()));
        Platform.runLater(opener);
        return Await.untilValue("a dialog to open", () -> onFx(() -> {
            for (Window window : Window.getWindows()) {
                if (!before.contains(window) && window.isShowing() && window.getScene() != null
                        && window.getScene().getRoot() instanceof DialogPane pane) {
                    return pane;
                }
            }
            return null;
        }), Objects::nonNull, PATIENCE);
    }

    /** Puts {@code url} in the URL field: the field not prompting for a name. */
    private void typeUrl(DialogPane dialog, String url) {
        interact(() -> {
            for (Node node : dialog.lookupAll(".text-field")) {
                TextField field = (TextField) node;
                if (!I18n.get("subscriptions.name.prompt").equals(field.getPromptText())) {
                    field.setText(url);
                }
            }
        });
    }

    /** Puts {@code name} in the field prompting for a name. */
    private void typeName(DialogPane dialog, String name) {
        interact(() -> {
            for (Node node : dialog.lookupAll(".text-field")) {
                TextField field = (TextField) node;
                if (I18n.get("subscriptions.name.prompt").equals(field.getPromptText())) {
                    field.setText(name);
                }
            }
        });
    }

    /** The warning is up, and its text node holds the whole bundle string. */
    private void assertTheWarningReadsWhole(DialogPane dialog) {
        String drawn = onFx(() -> {
            dialog.applyCss();
            dialog.layout();
            Node warning = dialog.lookup(".subscription-http-warning");
            return warning.isVisible() ? ((Text) warning.lookup(".text")).getText() : null;
        });
        assertThat(drawn)
                .as("the warning as the dialog draws it")
                .isEqualTo(I18n.get("subscriptions.http.warning"));
    }

    /**
     * OK sits inside the window, and the window is as tall as the dialog lays
     * out to at its width: a shorter window cuts the buttons and the warning
     * off, a taller one leaves a blank band where the warning was.
     */
    private void assertTheWindowFits(DialogPane dialog) {
        Fit fit = onFx(() -> {
            dialog.applyCss();
            dialog.layout();
            Node ok = dialog.lookupButton(ButtonType.OK);
            return new Fit(dialog.getScene().getHeight(), dialog.prefHeight(dialog.getWidth()),
                    ok.localToScene(ok.getLayoutBounds()).getMaxY());
        });
        assertThat(fit.okBottom())
                .as("the bottom of OK, in a window %.0f px tall", fit.window())
                .isLessThanOrEqualTo(fit.window());
        assertThat(fit.window())
                .as("the window's height, for a dialog that lays out to %.0f px", fit.wanted())
                .isCloseTo(fit.wanted(), within(0.5));
    }

    /** Heights read off a shown dialog, in its window's coordinates. */
    private record Fit(double window, double wanted, double okBottom) {
    }

    /**
     * Looks up on the FX thread, as every lookup in this class does.
     *
     * <p>TestFX's own lookup walks the scene graph from the test thread. While
     * the list rebuilds its rows, that walk reads a children list mid-change:
     * on a Windows runner it came back as
     * {@code NullPointerException ... "javafx.scene.Node.lookupAll(...)"
     * because "<local7>" is null} from inside {@code Parent.lookupAll}, and
     * failed a test that has nothing to do with the change under review.</p>
     */
    private Button addButton() {
        return onFx(() -> lookup("#addSubscriptionButton").queryAs(Button.class));
    }

    /** The button reading {@code text} on a subscription row, once the list has drawn one. */
    private Button rowButton(String text) {
        ListView<?> list = onFx(() -> lookup("#subscriptionListView").queryAs(ListView.class));
        return Await.untilValue("a subscription row with a button reading " + text,
                () -> onFx(() -> list.lookupAll(".button").stream()
                        .filter(node -> node instanceof Button button
                                && text.equals(button.getText()))
                        .map(Button.class::cast)
                        .findFirst()
                        .orElse(null)),
                Objects::nonNull, PATIENCE);
    }

    /** Runs {@code work} on the FX thread and hands back what it returned. */
    private <T> T onFx(Supplier<T> work) {
        List<T> result = new ArrayList<>(1);
        interact(() -> result.add(work.get()));
        return result.get(0);
    }

    private static Path freshDir(String name) {
        try {
            return Files.createDirectories(tempDir.resolve(name + "-" + System.nanoTime()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
