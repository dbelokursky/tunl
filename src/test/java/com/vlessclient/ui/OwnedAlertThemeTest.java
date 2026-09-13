package com.vlessclient.ui;

import com.vlessclient.app.ThemeCss;
import com.vlessclient.testing.Contrast;
import com.vlessclient.testing.UiTest;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An alert that belongs to the main window has to be readable in both themes.
 *
 * <p>An owned dialog is a window of its own, and {@code Dialog.initOwner} binds
 * its scene's stylesheets to the owner scene's. The DialogPane is that scene's
 * root, so base.css reaches it through {@code .root} and the theme's tokens
 * resolve on it, while Modena goes on deriving every colour base.css does not
 * state from its own light {@code -fx-background}. Until base.css styled
 * {@code .dialog-pane}, the dark theme painted the pane #1e1e1e and Modena wrote
 * the content text on it in #333, 1.32:1, under a header band that stayed
 * light. Every owned alert in the app looked like that, and the light theme
 * looked fine.</p>
 *
 * <p>Colours come from the computed style, as in
 * {@code ThemeTokenResolutionTest}: the property is exact, where a rendered
 * pixel beside antialiased glyphs is not.</p>
 */
@UiTest
public class OwnedAlertThemeTest extends ApplicationTest {

    private Stage owner;
    private Alert alert;

    @Override
    public void start(Stage stage) {
        owner = stage;
        stage.setScene(new Scene(new StackPane(), 480, 320));
        stage.show();
    }

    @AfterEach
    void closeAlert() {
        if (alert != null) {
            interact(alert::close);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"light", "dark"})
    void contentTextReadsOnThePane(String theme) {
        DialogPane pane = showOwnedAlert(theme);

        Label content = (Label) pane.lookup(".content.label");
        assertReadable(theme, "the content text", content.getTextFill(), Contrast.backdrop(pane));
    }

    /**
     * The band has to belong to the same theme as the pane under it, not only
     * carry readable text: Modena's light band kept its dark text readable on
     * the dark theme and was still a light slab across a dark dialog.
     */
    @ParameterizedTest
    @ValueSource(strings = {"light", "dark"})
    void headerBandFollowsTheTheme(String theme) {
        DialogPane pane = showOwnedAlert(theme);

        Region band = (Region) pane.lookup(".header-panel");
        Color bandFill = Contrast.backdrop(band);
        Color paneFill = Contrast.backdrop(pane);
        assertThat(Contrast.isDark(bandFill))
                .withFailMessage("%s: the header band is %s on a %s pane, so it kept Modena's "
                        + "colours instead of the theme's", theme, bandFill, paneFill)
                .isEqualTo(Contrast.isDark(paneFill));
        Label header = (Label) band.lookup(".label");
        assertReadable(theme, "the header text", header.getTextFill(), bandFill);
    }

    @ParameterizedTest
    @ValueSource(strings = {"light", "dark"})
    void buttonLabelsReadOnTheirButtons(String theme) {
        DialogPane pane = showOwnedAlert(theme);

        for (ButtonType type : pane.getButtonTypes()) {
            Button button = (Button) pane.lookupButton(type);
            assertReadable(theme, "the " + type.getText() + " button",
                    button.getTextFill(), Contrast.backdrop(button));
        }
    }

    /**
     * A confirmation owned by a window dressed the way ThemeManager dresses the
     * main one. Shown with {@code show}: {@code showAndWait} would not return
     * until the dialog closed.
     */
    private DialogPane showOwnedAlert(String theme) {
        interact(() -> {
            owner.getScene().getStylesheets().setAll(ThemeCss.of(theme));
            alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.initOwner(owner);
            alert.setHeaderText("The file will contain your credentials in plain text");
            alert.setContentText("Save it somewhere only you can read.");
            alert.show();
            alert.getDialogPane().applyCss();
        });
        return alert.getDialogPane();
    }

    private static void assertReadable(String theme, String what, Paint text, Color backdrop) {
        Color fill = Contrast.flat(text);
        double ratio = Contrast.ratio(fill, backdrop);
        assertThat(ratio)
                .withFailMessage("%s: %s is %s on %s, %.2f:1, short of the %.1f:1 text needs",
                        theme, what, fill, backdrop, ratio, Contrast.READABLE)
                .isGreaterThanOrEqualTo(Contrast.READABLE);
    }
}
