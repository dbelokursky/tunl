package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.RoutingRule;
import com.vlessclient.service.RoutingRuleCheck;
import com.vlessclient.service.RoutingService;
import com.vlessclient.service.SubscriptionService;
import com.vlessclient.service.TestRoutingServices;
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
import java.util.Objects;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The subscription form and the add-rule form keep OK disabled until they are
 * complete.
 *
 * <p>Both used to check their fields only after closing. OK on an incomplete
 * form closed it, put up a warning, and dropped what had been typed: a long
 * subscription URL pasted before the name, or the rule type and action
 * already chosen.</p>
 *
 * <p>As in {@link SubscriptionsHttpWarningTest}, the views show these dialogs
 * with {@code showAndWait}, so what opens them and OK go through
 * {@code Platform.runLater}: an {@code interact} would wait on them
 * forever. Whether a form is still open is asked of the window it opened in:
 * a closed dialog's pane no longer has a scene to lead back to it.</p>
 */
@UiTest
public class FormDialogsWaitForInputTest extends ApplicationTest {

    private static final String URL = "https://provider.example/sub?token=0123456789abcdef";
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @TempDir
    static Path tempDir;

    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.setScene(new Scene(new StackPane(), 1000, 720));
        stage.show();
    }

    /**
     * Closes what a test left open, the warning an incomplete form used to put
     * up included, and takes focus off the fields: Monocle keeps a hidden
     * window focused, and a focused field's caret would blink on into
     * DashboardHiddenWindowTest.
     */
    @AfterEach
    void closeTheDialogs() {
        interact(() -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window != stage) {
                    window.hide();
                    if (window.getScene() != null) {
                        window.getScene().getRoot().requestFocus();
                    }
                }
            }
        });
    }

    @Test
    void theSubscriptionFormStaysOpenWithAUrlAndNoName() {
        ServiceLocator.register(SubscriptionService.class,
                TestSubscriptionServices.quiet(freshDir("subscriptions")));
        mount("SubscriptionsView");
        DialogPane form = open(button("#addSubscriptionButton")::fire);
        Window window = windowOf(form);

        fillSubscription(form, "", URL);
        pressOk(form);

        assertThat(isShowing(window)).as("the form after OK with a URL and no name").isTrue();
        assertThat(subscriptionUrl(form)).as("the URL pasted into it").isEqualTo(URL);
        assertThat(okDisabled(form)).as("OK with a URL and no name").isTrue();

        fillSubscription(form, "Provider", "   ");
        assertThat(okDisabled(form)).as("OK with a name and a blank URL").isTrue();

        fillSubscription(form, "Provider", URL);
        assertThat(okDisabled(form)).as("OK with a name and a URL").isFalse();
    }

    @Test
    void theRuleFormStaysOpenWithoutAValueAndAddsTheRuleOnceItHasOne() {
        RoutingService routing = TestRoutingServices.at(freshDir("routing"));
        ServiceLocator.register(RoutingService.class, routing);
        mount("RoutingView");
        DialogPane form = open(button("#addRuleButton")::fire);
        Window window = windowOf(form);

        setRuleValue(form, "   ");
        pressOk(form);

        assertThat(isShowing(window)).as("the form after OK with a blank value").isTrue();
        assertThat(okDisabled(form)).as("OK with a blank value").isTrue();

        setRuleValue(form, "example.com");
        assertThat(okDisabled(form)).as("OK with a value").isFalse();
        pressOk(form);

        assertThat(isShowing(window)).as("the form after OK with a value").isFalse();
        Await.until("the rule to be added",
                () -> routing.getConfig().getRules().stream()
                        .map(RoutingRule::getValue)
                        .anyMatch("example.com"::equals),
                PATIENCE);
    }

    /**
     * A value the core would refuse is explained under the field, and OK
     * waits: sing-box refuses a whole configuration over one such rule, so it
     * stopped every server from connecting until it was found and deleted.
     */
    @Test
    void theRuleFormExplainsAValueTheCoreWouldRefuse() {
        RoutingService routing = TestRoutingServices.at(freshDir("routing"));
        ServiceLocator.register(RoutingService.class, routing);
        mount("RoutingView");
        DialogPane form = open(button("#addRuleButton")::fire);

        setRuleType(form, RoutingRule.RuleType.DOMAIN_REGEX);
        setRuleValue(form, "(");

        assertThat(okDisabled(form)).as("OK with an unclosed bracket").isTrue();
        assertThat(problemShown(form)).as("the reason under the value")
                .isEqualTo(RoutingRuleCheck.problem(RoutingRule.RuleType.DOMAIN_REGEX, "(")
                        .orElseThrow());
        assertThat(onFx(() -> {
            Label reason = (Label) form.lookup(".routing-rule-problem");
            return reason.getHeight() + 0.5 >= reason.prefHeight(reason.getWidth());
        })).as("the reason as tall as its lines, in the dialog it grew").isTrue();

        setRuleValue(form, "^(.+\\.)?example\\.com$");
        assertThat(okDisabled(form)).as("OK with an expression the core takes").isFalse();
        assertThat(problemShown(form)).as("the reason once the value is fine").isNull();
        pressOk(form);

        Await.until("the rule to be added",
                () -> routing.getConfig().getRules().stream()
                        .anyMatch(rule -> rule.getType() == RoutingRule.RuleType.DOMAIN_REGEX),
                PATIENCE);
    }

    private void setRuleType(DialogPane form, RoutingRule.RuleType type) {
        interact(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<RoutingRule.RuleType> types =
                    (ComboBox<RoutingRule.RuleType>) form.lookup(".combo-box");
            types.setValue(type);
        });
    }

    /** The reason shown under the rule's value, or null while none is shown. */
    private String problemShown(DialogPane form) {
        return onFx(() -> form.lookup(".routing-rule-problem") instanceof Label reason
                && reason.isVisible() ? reason.getText() : null);
    }

    /**
     * Puts {@code view} in the window over the services the test registered,
     * in the light theme, which the dialogs take from the window.
     */
    private void mount(String view) {
        interact(() -> {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/" + view + ".fxml"));
            try {
                stage.getScene().setRoot(loader.load());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
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

    /**
     * Presses OK as a click does: the click takes the focus to the button
     * first, and a disabled button takes neither. A field left focused would
     * blink its caret on after OK closed the form, since Monocle keeps a closed
     * window focused.
     */
    private void pressOk(DialogPane form) {
        Button ok = onFx(() -> (Button) form.lookupButton(ButtonType.OK));
        interact(ok::requestFocus);
        Platform.runLater(ok::fire);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private boolean okDisabled(DialogPane form) {
        return onFx(() -> form.lookupButton(ButtonType.OK).isDisabled());
    }

    /** The window a shown dialog is in, taken while it still is. */
    private Window windowOf(DialogPane form) {
        return onFx(() -> form.getScene().getWindow());
    }

    private boolean isShowing(Window window) {
        return onFx(window::isShowing);
    }

    /** Puts {@code name} in the field prompting for a name, and {@code url} in the other. */
    private void fillSubscription(DialogPane form, String name, String url) {
        interact(() -> form.lookupAll(".text-field").forEach(node -> {
            TextField field = (TextField) node;
            field.setText(isNameField(field) ? name : url);
        }));
    }

    private String subscriptionUrl(DialogPane form) {
        return onFx(() -> form.lookupAll(".text-field").stream()
                .map(TextField.class::cast)
                .filter(field -> !isNameField(field))
                .map(TextField::getText)
                .findFirst()
                .orElse(null));
    }

    private static boolean isNameField(TextField field) {
        return I18n.get("subscriptions.name.prompt").equals(field.getPromptText());
    }

    private void setRuleValue(DialogPane form, String value) {
        interact(() -> ((TextField) form.lookup(".text-field")).setText(value));
    }

    private Button button(String selector) {
        return lookup(selector).queryAs(Button.class);
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
