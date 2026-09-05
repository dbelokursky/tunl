package com.vlessclient.ui;

import com.vlessclient.app.ThemeCss;
import com.vlessclient.testing.UiTest;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.Effect;
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
 * this asserts token-driven properties (the .card background and shadow)
 * actually resolve to the expected values in light and dark.
 *
 * <p>Both stylesheets are applied, in the order the app applies them:
 * base.css carries the rules, the theme file carries only the token values,
 * and neither renders anything on its own.</p>
 */
@UiTest
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
        assertCardBg("light", Color.web("#ffffff"));
        assertCardBg("dark", Color.web("#26262a"));
    }

    /**
     * A token inside an effect function, not just as a whole property value.
     * JavaFX resolves looked-up colours in {@code dropshadow(...)} arguments —
     * verified here rather than assumed, because if it did not, every card and
     * button shadow would have to stay duplicated per theme.
     */
    @Test
    void shadowTokenResolvesInsideDropshadowInBothThemes() {
        assertCardShadow("light", Color.rgb(16, 24, 40, 0.05));
        assertCardShadow("dark", Color.rgb(0, 0, 0, 0.28));
    }

    @Test
    void textLabelledIconButtonIsReadableInBothThemes() {
        assertIconButtonTextFill("light", Color.web("#5b6570"));
        assertIconButtonTextFill("dark", Color.web("#a0a4aa"));
    }

    @Test
    void destructiveIconButtonTurnsRedOnHoverInBothThemes() {
        assertDestructiveHoverFill("light", Color.web("#d32f2f"));
        assertDestructiveHoverFill("dark", Color.web("#ef5350"));
    }

    private void assertCardBg(String theme, Color expected) {
        applyStylesheets(theme, card);
        Color actual = (Color) card.getBackground().getFills().get(0).getFill();
        assertThat(actual)
                .withFailMessage("%s: -c-surface did not resolve (got %s, want %s)",
                        theme, actual, expected)
                .isEqualTo(expected);
    }

    private void assertCardShadow(String theme, Color expected) {
        applyStylesheets(theme, card);
        Effect effect = card.getEffect();
        assertThat(effect)
                .withFailMessage("%s: .card has no drop shadow — the -c-shadow-card token "
                        + "inside dropshadow() did not parse", theme)
                .isInstanceOf(DropShadow.class);
        Color actual = (Color) ((DropShadow) effect).getColor();
        assertThat(actual)
                .withFailMessage("%s: -c-shadow-card did not resolve inside dropshadow() "
                        + "(got %s, want %s). If this ever fails, JavaFX has stopped "
                        + "resolving looked-up colours in effect arguments and the shadow "
                        + "rules have to move back into the per-theme files.",
                        theme, actual, expected)
                .isEqualTo(expected);
    }

    private void assertIconButtonTextFill(String theme, Color expected) {
        applyStylesheets(theme, iconButton);
        Color actual = (Color) iconButton.getTextFill();
        assertThat(actual)
                .withFailMessage("%s: .icon-button text is %s, not the -c-text-secondary"
                        + " token %s -- a glyph-less icon button is unreadable",
                        theme, actual, expected)
                .isEqualTo(expected);
    }

    /**
     * Drives the :hover state directly rather than moving the mouse, which is
     * unreliable headless; the point under test is that the rule resolves and
     * out-specifies the plain .icon-button:hover fill, not pointer plumbing.
     */
    private void assertDestructiveHoverFill(String theme, Color expected) {
        interact(() -> destructiveIconButton.pseudoClassStateChanged(HOVER, true));
        applyStylesheets(theme, destructiveIconButton);
        Color actual = (Color) destructiveIconButton.getTextFill();
        assertThat(actual)
                .withFailMessage("%s: hovered .destructive icon button is %s, not the"
                        + " -c-danger-fg token %s", theme, actual, expected)
                .isEqualTo(expected);
    }

    private void applyStylesheets(String theme, Node styled) {
        interact(() -> {
            scene.getStylesheets().setAll(ThemeCss.of(theme));
            styled.applyCss();
        });
    }
}
