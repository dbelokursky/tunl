package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
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
 * "Check for updates" over "Restart now", a row apart at the same edge of one
 * card. Sized to their own labels they come out at different widths, and two
 * widths that far apart read as two different controls rather than one used
 * twice.
 *
 * <p>What the buttons should measure is derived here rather than written
 * down, for the reason the production code measures it: the answer is
 * language specific — 161 in English, 201 in Russian — and a number in this
 * file would only restate the one in the code, passing just as happily if
 * both were wrong.</p>
 *
 * <p>Width is half of it. The lower button sits inside a padded sub-block
 * and the upper one does not, so equal widths still leave the two staggered
 * unless the insets agree — hence the column check as well.</p>
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
        ServiceLocator.initialize();
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
    void bothButtonsTakeTheWidthTheWidestLabelNeeds() {
        Scene settings = loadSettings("light");

        assertSharedWidth(settings, Locale.ENGLISH);
    }

    /**
     * Equal widths only line the pair up if they start from the same edge.
     * The lower button is inset by the padding {@code .update-item} puts
     * around its contents, so {@code .updates-header} owes the upper one the
     * same inset — and this fails if either number moves without the other.
     *
     * <p>Both themes, because both carry their own copy of the two rules and
     * only one of them has to be forgotten for the pair to drift apart in a
     * theme nobody screenshotted.</p>
     */
    @Test
    void theTwoButtonsStandInOneColumn() {
        for (String theme : List.of("light", "dark")) {
            Scene settings = loadSettings(theme);

            Bounds check = boundsOf(settings, button(settings, "#checkUpdatesButton"));
            Bounds download = boundsOf(settings, button(settings, "#appUpdateButton"));

            assertThat(download.getMaxX())
                    .withFailMessage("in %s the buttons' right edges are %.1f apart — "
                                    + "checkUpdatesButton ends at %.1f, appUpdateButton at "
                                    + "%.1f. .updates-header's right padding has to match "
                                    + ".update-item's",
                            theme, Math.abs(check.getMaxX() - download.getMaxX()),
                            check.getMaxX(), download.getMaxX())
                    .isCloseTo(check.getMaxX(), within(SNAP));
        }
    }

    /**
     * The pin has to be re-measured on a language switch, and in both
     * directions: releasing the old one first is what lets the pair shrink
     * back when the shorter language returns.
     */
    @Test
    void theSharedWidthFollowsTheLanguage() {
        Scene settings = loadSettings("light");

        for (Locale locale : List.of(Locale.ENGLISH, Locale.of("ru"), Locale.ENGLISH)) {
            interact(() -> I18n.setLocale(locale));
            assertSharedWidth(settings, locale);
        }
    }

    /**
     * Both buttons hold exactly what the longer of the two labels needs in
     * this language: equal to each other, wide enough that neither label is
     * ellipsized, and no wider — a width left over from another language
     * would clear the first two checks and fail this one.
     */
    private void assertSharedWidth(Scene settings, Locale locale) {
        Button check = button(settings, "#checkUpdatesButton");
        Button restart = button(settings, "#appUpdateButton");
        double needed = Math.max(
                naturalWidth("settings.updates.check"),
                naturalWidth("settings.update.restart"));

        for (Button button : List.of(check, restart)) {
            assertThat(widthOf(settings, button))
                    .withFailMessage("in %s the pair should measure %.1f — what \"%s\" needs "
                                    + "— but %s is %.1f: check=%.1f restart=%.1f",
                            locale, needed, longerLabel(), button.getId(),
                            widthOf(settings, button), widthOf(settings, check),
                            widthOf(settings, restart))
                    .isCloseTo(needed, within(SNAP));
        }
    }

    private static String longerLabel() {
        String check = I18n.get("settings.updates.check");
        String restart = I18n.get("settings.update.restart");
        return check.length() >= restart.length() ? check : restart;
    }

    /** Where the button ends up on screen, once the row has been laid out. */
    private Bounds boundsOf(Scene settings, Button button) {
        final Bounds[] bounds = new Bounds[1];
        interact(() -> {
            settings.getRoot().applyCss();
            settings.getRoot().layout();
            bounds[0] = button.localToScene(button.getBoundsInLocal());
        });
        return bounds[0];
    }

    /** Laid-out width, which is what the row beside the button actually sees. */
    private double widthOf(Scene settings, Button button) {
        final double[] width = new double[1];
        interact(() -> {
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

                // The download button only appears once a release is out; it
                // is measured and pinned either way, but it has to be laid out
                // to have a width to read.
                javafx.scene.Node download = root.lookup("#appUpdateButton");
                download.setVisible(true);
                download.setManaged(true);

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
