package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.testing.UiTest;
import java.util.Locale;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the property {@link ButtonLabels} exists for: a button that swaps
 * its label must not change size while doing so, in any language.
 *
 * <p>Measuring the button rather than asserting on a hard-coded number is
 * deliberate — a number here would only restate the one in the production
 * code, and would pass just as happily if both were wrong.</p>
 */
@UiTest
public class ButtonLabelsTest extends ApplicationTest {

    private Scene scene;
    private Group root;

    @AfterEach
    void resetLocale() {
        interact(() -> I18n.setLocale(Locale.ENGLISH));
    }

    @Override
    public void start(Stage stage) {
        root = new Group();
        scene = new Scene(root, 600, 200);
        scene.getStylesheets().addAll(ThemeCss.light());
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void widthHoldsWhenTheLabelSwaps() {
        Button button = boundButton();

        double idle = widthOf(button);
        interact(() -> ButtonLabels.show(button, "dashboard.copied"));
        double busy = widthOf(button);

        assertThat(busy)
                .withFailMessage("button resized when its label swapped: %.1f -> %.1f; "
                        + "the row beside it shifts under the pointer", idle, busy)
                .isEqualTo(idle);
    }

    @Test
    void widthFitsTheLongestLabelSoNothingIsClipped() {
        Button button = boundButton();

        // What the longest label needs on its own, with no pin applied.
        Button loose = new Button(I18n.get("dashboard.copied"));
        loose.getStyleClass().add("secondary-button");
        double needed = naturalWidth(loose);

        assertThat(widthOf(button))
                .withFailMessage("pinned to %.1f but \"%s\" needs %.1f — the label "
                                + "would render ellipsized",
                        widthOf(button), I18n.get("dashboard.copied"), needed)
                .isGreaterThanOrEqualTo(needed);
    }

    /**
     * The label set is measured, not hard-coded, so switching to a language
     * with longer words has to re-pin. Russian is the case that matters here:
     * "Скопировано!" runs some 50px past "Copied!".
     */
    @Test
    void widthGrowsForALongerLanguage() {
        Button button = boundButton();
        double english = widthOf(button);

        interact(() -> I18n.setLocale(Locale.of("ru")));
        double russian = widthOf(button);

        assertThat(russian)
                .withFailMessage("pin stayed at the English width %.1f after switching to "
                        + "Russian; the longer label is clipped", english)
                .isGreaterThan(english);

        // And it still holds steady across a swap in the new language.
        interact(() -> ButtonLabels.show(button, "dashboard.copied"));
        assertThat(widthOf(button)).isEqualTo(russian);
    }

    /**
     * The language can change while the view holding the button is off screen:
     * MainView swaps the whole content area, so every view but the visible one
     * has left the scene graph. The pin has to catch up when the view comes
     * back, or the button keeps the width of the language it was last measured
     * in — which is how the Routing save button came to render "Сох…".
     */
    @Test
    void widthCatchesUpOnALanguageSwitchMadeWhileTheViewWasAway() {
        Button button = boundButton();
        double english = widthOf(button);

        interact(() -> root.getChildren().clear());
        interact(() -> I18n.setLocale(Locale.of("ru")));
        interact(() -> root.getChildren().setAll(button));

        assertThat(widthOf(button))
                .withFailMessage("still %.1f wide after coming back to a Russian UI — the pin "
                        + "was left at the English width and the label is clipped", english)
                .isGreaterThan(english);
    }

    private Button boundButton() {
        Button button = new Button();
        button.getStyleClass().add("secondary-button");
        interact(() -> {
            root.getChildren().setAll(button);
            ButtonLabels.bind(button, "dashboard.copy", "dashboard.copied");
            root.applyCss();
            root.layout();
        });
        return button;
    }

    /** Laid-out width, which is what the neighbouring controls actually see. */
    private double widthOf(Button button) {
        final double[] width = new double[1];
        interact(() -> {
            root.applyCss();
            root.layout();
            width[0] = button.getWidth();
        });
        return width[0];
    }

    /** Width the button would take if nothing pinned it. */
    private double naturalWidth(Button button) {
        final double[] width = new double[1];
        interact(() -> {
            root.getChildren().add(button);
            root.applyCss();
            root.layout();
            width[0] = button.prefWidth(-1);
            root.getChildren().remove(button);
        });
        return width[0];
    }
}
