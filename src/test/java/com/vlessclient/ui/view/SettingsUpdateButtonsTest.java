package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.UiTestServices;
import java.util.List;
import java.util.Locale;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Guards the pair the Updates block in Settings &gt; About is made of:
 * "Restart now" beside "Check for updates", at one edge of one card.
 *
 * <p>What each button should measure is derived here rather than written
 * down, for the reason the production code measures it: the answer is
 * language specific — "Check for updates" needs 142px in English and 201 in
 * Russian — and a number in this file would only restate the one in the code,
 * passing just as happily if both were wrong.</p>
 *
 * <p>The pair was pinned to a single shared width while it stood a row apart,
 * where two unequal widths read as two different controls. That pin measured
 * every button in the group but checked only one of them for a scene, so a
 * sibling that had not been laid out yet measured zero and dragged the whole
 * group down to the shorter label — which is how "Check for updates" came to
 * be rendered in a 107px button. Sharing a row removed the reason for the pin
 * along with the trap.</p>
 */
public class SettingsUpdateButtonsTest extends ApplicationTest {

    /** Layout snaps to whole pixels, so a measured width lands within 1px. */
    private static final double SNAP = 1.0;

    private Stage stage;
    private Group probeRoot;

    @BeforeAll
    static void setupHeadless() {
        System.setProperty("testfx.robot", "glass");
        System.setProperty("testfx.headless", "true");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        System.setProperty("java.awt.headless", "true");
        UiTestServices.initialize();
    }

    @AfterEach
    void resetLocale() {
        interact(() -> I18n.setLocale(Locale.ENGLISH));
    }

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
    }

    @Test
    void eachButtonIsAsWideAsItsOwnLabelNeeds() {
        Scene settings = loadSettings("light");

        assertFits(settings, Locale.ENGLISH);
    }

    /**
     * The width has to be re-measured on a language switch, and in both
     * directions: releasing the old pin first is what lets a button shrink
     * back when the shorter language returns.
     */
    @Test
    void theWidthsFollowTheLanguage() {
        Scene settings = loadSettings("light");

        for (Locale locale : List.of(Locale.ENGLISH, Locale.of("ru"), Locale.ENGLISH)) {
            interact(() -> I18n.setLocale(locale));
            assertFits(settings, locale);
        }
    }

    /**
     * The pair shares one row, so what has to hold is that they sit on the
     * same line and do not run into each other.
     *
     * <p>Both themes, because each carries its own copy of the header's rules
     * and only one of them has to drift for the pair to break in a theme
     * nobody screenshotted.</p>
     */
    @Test
    void theTwoButtonsShareOneRowWithoutOverlapping() {
        for (String theme : List.of("light", "dark")) {
            Scene settings = loadSettings(theme);

            Bounds check = boundsOf(settings, button(settings, "#checkUpdatesButton"));
            Bounds restart = boundsOf(settings, button(settings, "#appUpdateButton"));

            assertThat(centreY(restart))
                    .withFailMessage("in %s the buttons are on different lines — "
                                    + "appUpdateButton centres at %.1f, checkUpdatesButton at %.1f",
                            theme, centreY(restart), centreY(check))
                    .isCloseTo(centreY(check), within(SNAP));

            assertThat(restart.getMaxX())
                    .withFailMessage("in %s appUpdateButton ends at %.1f but "
                                    + "checkUpdatesButton starts at %.1f — they overlap",
                            theme, restart.getMaxX(), check.getMinX())
                    .isLessThanOrEqualTo(check.getMinX());
        }
    }

    /**
     * Each button holds exactly what its own label needs in this language:
     * wide enough that the label is not ellipsized, and no wider — a width
     * left over from another language would clear the first check and fail
     * this one.
     */
    private void assertFits(Scene settings, Locale locale) {
        assertFits(settings, locale, "#checkUpdatesButton", "settings.updates.check");
        assertFits(settings, locale, "#appUpdateButton", "settings.update.restart");
    }

    private void assertFits(Scene settings, Locale locale, String selector, String key) {
        Button button = button(settings, selector);
        double needed = naturalWidth(key);

        assertThat(widthOf(settings, button))
                .withFailMessage("in %s %s should measure %.1f — what \"%s\" needs — but it is "
                                + "%.1f, so the label is clipped or the pin is stale",
                        locale, selector, needed, I18n.get(key), widthOf(settings, button))
                .isCloseTo(needed, within(SNAP));
    }

    private static double centreY(Bounds bounds) {
        return bounds.getMinY() + bounds.getHeight() / 2;
    }

    /** Where the button ends up on screen, once the row has been laid out. */
    private Bounds boundsOf(Scene settings, Button button) {
        final Bounds[] bounds = new Bounds[1];
        interact(() -> {
            // Same reason as widthOf: a hidden button is not laid out, so its
            // position would be whatever it was before it was hidden.
            button.setVisible(true);
            button.setManaged(true);
            settings.getRoot().applyCss();
            settings.getRoot().layout();
            bounds[0] = button.localToScene(button.getBoundsInLocal());
        });
        return bounds[0];
    }

    /**
     * Laid-out width, which is what the row beside the button actually sees.
     *
     * <p>Shown first, every time. The restart button is hidden whenever no
     * update is staged, and this JVM runs a real updater that can find one —
     * or stop finding one — at any moment. An unmanaged node is skipped by
     * layout and keeps whatever width it last had, so a re-render landing
     * mid-test would otherwise be read as a stale pin.</p>
     */
    private double widthOf(Scene settings, Button button) {
        final double[] width = new double[1];
        interact(() -> {
            button.setVisible(true);
            button.setManaged(true);
            settings.getRoot().applyCss();
            settings.getRoot().layout();
            width[0] = button.getWidth();
        });
        return width[0];
    }

    /** What a secondary button needs for this label with nothing pinning it. */
    private double naturalWidth(String key) {
        final double[] width = new double[1];
        interact(() -> {
            Button loose = new Button(I18n.get(key));
            loose.getStyleClass().add("secondary-button");
            probeRoot.getChildren().setAll(loose);
            probeRoot.applyCss();
            probeRoot.layout();
            width[0] = loose.prefWidth(-1);
            probeRoot.getChildren().clear();
        });
        return width[0];
    }

    private Button button(Scene settings, String selector) {
        final Button[] holder = new Button[1];
        interact(() -> holder[0] = (Button) settings.getRoot().lookup(selector));
        assertThat(holder[0]).withFailMessage("%s is gone from SettingsView", selector).isNotNull();
        return holder[0];
    }

    /**
     * The real view, plus an off-stage scene carrying the same stylesheet to
     * measure loose buttons in — the pinned ones cannot answer what they
     * would have been.
     */
    private Scene loadSettings(String theme) {
        final Scene[] holder = new Scene[1];
        interact(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                        getClass().getResource("/fxml/SettingsView.fxml"));
                Parent root = loader.load();
                Scene scene = new Scene(root, 700, 720);
                scene.getStylesheets().add(
                        getClass().getResource("/css/" + theme + ".css").toExternalForm());
                stage.setScene(scene);
                stage.show();
                // Before anything is looked up: the view hangs off a
                // ScrollPane, whose skin builds the viewport that holds it
                // during the first CSS pass. show() only forces that pass on a
                // stage that was not already showing, and this runs twice.
                root.applyCss();
                root.layout();

                probeRoot = new Group();
                Scene probe = new Scene(probeRoot, 400, 100);
                probe.getStylesheets().setAll(scene.getStylesheets());
                new Stage().setScene(probe);

                // The restart button only appears once an update is staged;
                // it is measured and pinned either way, but it has to be laid
                // out to have a width to read.
                javafx.scene.Node restart = root.lookup("#appUpdateButton");
                restart.setVisible(true);
                restart.setManaged(true);

                root.applyCss();
                root.layout();
                holder[0] = scene;
            } catch (Exception e) {
                throw new IllegalStateException("could not load SettingsView", e);
            }
        });
        return holder[0];
    }
}
