package com.vlessclient.ui;

import javafx.css.PseudoClass;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the -c-* design-token system: verifies the tokens resolve at
 * runtime in both themes. A looked-up-color typo renders as a silent
 * fallback that JavaFX does not otherwise surface as a test failure, so
 * this asserts a token-driven property (the .card background) actually
 * resolves to the expected value in light and dark.
 */
public class ThemeTokenResolutionTest extends ApplicationTest {

    private static final PseudoClass HOVER = PseudoClass.getPseudoClass("hover");

    private Region card;
    private Button iconButton;
    private Button destructiveIconButton;
    private Scene scene;

    @Override
    public void start(Stage stage) {
        card = new Region();
        card.getStyleClass().add("card");
        // A text-labelled icon button: .icon-button only colours the SVG
        // .nav-icon-glyph, so a glyph-less one silently falls back to the
        // modena default text fill, which is unreadable on a dark surface.
        iconButton = new Button("✕");
        iconButton.getStyleClass().setAll("icon-button");
        destructiveIconButton = new Button("✕");
        destructiveIconButton.getStyleClass().setAll("icon-button", "destructive");
        scene = new Scene(new VBox(card, iconButton, destructiveIconButton), 200, 160);
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void cardBackgroundResolvesInBothThemes() {
        assertCardBg("/css/light.css", Color.web("#ffffff"));
        assertCardBg("/css/dark.css", Color.web("#26262a"));
    }

    @Test
    void textLabelledIconButtonIsReadableInBothThemes() {
        assertIconButtonTextFill("/css/light.css", Color.web("#5b6570"));
        assertIconButtonTextFill("/css/dark.css", Color.web("#a0a4aa"));
    }

    @Test
    void destructiveIconButtonTurnsRedOnHoverInBothThemes() {
        assertDestructiveHoverFill("/css/light.css", Color.web("#d32f2f"));
        assertDestructiveHoverFill("/css/dark.css", Color.web("#ef5350"));
    }

    private void assertCardBg(String stylesheet, Color expected) {
        applyStylesheet(stylesheet, card);
        Color actual = (Color) card.getBackground().getFills().get(0).getFill();
        assertThat(actual)
                .withFailMessage("%s: -c-surface did not resolve (got %s, want %s)",
                        stylesheet, actual, expected)
                .isEqualTo(expected);
    }

    private void assertIconButtonTextFill(String stylesheet, Color expected) {
        applyStylesheet(stylesheet, iconButton);
        Color actual = (Color) iconButton.getTextFill();
        assertThat(actual)
                .withFailMessage("%s: .icon-button text is %s, not the -c-text-secondary"
                        + " token %s -- a glyph-less icon button is unreadable",
                        stylesheet, actual, expected)
                .isEqualTo(expected);
    }

    /**
     * Drives the :hover state directly rather than moving the mouse, which is
     * unreliable headless; the point under test is that the rule resolves and
     * out-specifies the plain .icon-button:hover fill, not pointer plumbing.
     */
    private void assertDestructiveHoverFill(String stylesheet, Color expected) {
        interact(() -> destructiveIconButton.pseudoClassStateChanged(HOVER, true));
        applyStylesheet(stylesheet, destructiveIconButton);
        Color actual = (Color) destructiveIconButton.getTextFill();
        assertThat(actual)
                .withFailMessage("%s: hovered .destructive icon button is %s, not the"
                        + " -c-danger-fg token %s", stylesheet, actual, expected)
                .isEqualTo(expected);
    }

    private void applyStylesheet(String stylesheet, javafx.scene.Node styled) {
        interact(() -> {
            scene.getStylesheets().setAll(
                    getClass().getResource(stylesheet).toExternalForm());
            styled.applyCss();
        });
    }
}
