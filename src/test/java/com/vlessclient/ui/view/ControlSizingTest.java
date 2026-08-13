package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.fxml.FXMLLoader;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two rules the app sizes its controls by: no label is ever wider than
 * the control showing it, and every ordinary control is the same height.
 *
 * <p>JavaFX does not report "this text got an ellipsis" — the label simply
 * renders shorter — so every labelled control is compared against a probe
 * carrying the same style classes and the same string. If the probe wants
 * more width than the control was given, the user is reading a truncated
 * label.</p>
 *
 * <p>Both languages, because clipping is overwhelmingly a translation
 * problem: a width written into an FXML fits the English label it was
 * measured against and clips the Russian one. It cost the app its main action
 * — connectButton asked for 170px while "Подключить" needs 174 — the Routing
 * save button, and one item in the nav column. Both themes too, since each
 * carries its own copy of every size rule.</p>
 */
public class ControlSizingTest extends ApplicationTest {

    /**
     * How far short a control may measure before this counts it as clipped.
     *
     * <p>Asking the same control what it needs at two points in the layout
     * lifecycle can answer 1.2px apart — {@code retryInstallButton} is pinned
     * at 143 by a measurement it made of itself and reports 144.2 here, while
     * the rendered label is whole. Every real clip this found was 3.7px short
     * or worse (connectButton 170 against 173.7, saveBypassButton 92 against
     * 118.8), so 2px separates the noise from the defect.</p>
     */
    private static final double SLACK = 2.0;

    /** The shell at its default size; the views at the width it leaves them. */
    private static final int WINDOW_WIDTH = 1100;
    private static final int CONTENT_WIDTH = 888;

    /**
     * The classes that carry the app's ordinary control height. Deliberate
     * exceptions are simply not listed: .connect-button is the hero action at
     * 44, .nav-button is a 50-high sidebar row, .mode-combo is the compact
     * 30 the dashboard's hero row uses.
     */
    private static final List<String> SIZED = List.of(
            "form-field", "primary-button", "secondary-button", "icon-button");

    private static final List<String> VIEWS = List.of("MainView", "DashboardView", "ServersView",
            "SubscriptionsView", "RoutingView", "LogsView", "SettingsView", "ServerFormView");

    private Stage stage;

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
    void noLabelIsTruncatedInEitherLanguage() {
        List<String> clipped = new ArrayList<>();
        for (Locale locale : List.of(Locale.ENGLISH, Locale.of("ru"))) {
            interact(() -> I18n.setLocale(locale));
            for (String view : VIEWS) {
                Scene scene = load(view);
                // Collected inside the FX thread and asserted outside it, so
                // one offender does not hide the rest.
                interact(() -> {
                    for (String theme : List.of("light", "dark")) {
                        dress(scene, theme);
                        collectClipped(view, theme, locale, scene.getRoot(), clipped);
                    }
                });
            }
        }

        assertThat(clipped)
                .withFailMessage("labels the layout is too narrow for — these render with an "
                        + "ellipsis:%n  %s", String.join("\n  ", clipped))
                .isEmpty();
    }


    /**
     * Every ordinary control is one height, whatever kind of control it is and
     * whichever view it sits in. That is what {@code .secondary-button}'s
     * "one control height across the app" comment promises, and only that
     * class and {@code .icon-button} were keeping it: a primary button came
     * out 32 beside a secondary's 34 in three views, and a {@code .form-field}
     * ComboBox came out 42 beside a {@code .form-field} TextField's 34 in the
     * same column.
     *
     * <p>The expected height is read from {@code .secondary-button} rather
     * than written here, so this test cannot disagree with the stylesheet
     * about what the number is — only about whether everything follows it.</p>
     */
    @Test
    void everyOrdinaryControlIsOneHeight() {
        List<String> odd = new ArrayList<>();
        double[] expected = {0};
        interact(() -> I18n.setLocale(Locale.ENGLISH));
        for (String view : VIEWS) {
            Scene scene = load(view);
            interact(() -> {
                for (String theme : List.of("light", "dark")) {
                    dress(scene, theme);
                    for (Node node : scene.getRoot().lookupAll("*")) {
                        if (!(node instanceof Region region) || !isOnScreen(node)) {
                            continue;
                        }
                        String sized = SIZED.stream()
                                .filter(node.getStyleClass()::contains).findFirst().orElse(null);
                        if (sized == null || region.getHeight() <= 0) {
                            continue;
                        }
                        if ("secondary-button".equals(sized) && expected[0] == 0) {
                            expected[0] = region.getHeight();
                        }
                        if (expected[0] > 0 && Math.abs(region.getHeight() - expected[0]) > 0.5) {
                            odd.add("%s/%s %s [%s]: %.1f high, not %.1f".formatted(
                                    view, theme, describe(node), sized,
                                    region.getHeight(), expected[0]));
                        }
                    }
                }
            });
        }

        assertThat(odd)
                .withFailMessage("controls that should share the app's one control height but "
                        + "do not — these read as a ragged row wherever they sit beside "
                        + "another:%n  %s", String.join("\n  ", odd))
                .isEmpty();
    }

    private void collectClipped(String view, String theme, Locale locale, Parent root,
                                List<String> clipped) {
        for (Node node : root.lookupAll("*")) {
            if (!(node instanceof Labeled labeled) || !isOnScreen(node) || labeled.isWrapText()) {
                continue;
            }
            String text = labeled.getText();
            if (text == null || text.isBlank() || labeled.getWidth() <= 0) {
                continue;
            }
            double needed = naturalWidth(labeled);
            if (needed > labeled.getWidth() + SLACK) {
                clipped.add("%s/%s/%s %s \"%s\": has %.1f, needs %.1f".formatted(
                        view, theme, locale, describe(node), text,
                        labeled.getWidth(), needed));
            }
        }
    }

    /**
     * Visible and managed all the way up. A node's own flags say nothing about
     * its parents, and the collapsed banners and the form's protocol sections
     * keep children that report themselves visible while never being laid out:
     * they sit at a width of zero and would read as clipped.
     */
    private static boolean isOnScreen(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible() || !n.isManaged()) {
                return false;
            }
        }
        return true;
    }

    /**
     * What the control itself wants for the text it is showing, with whatever
     * pins it down released for the duration of the question.
     *
     * <p>Asking the control beats measuring a stand-in: a stand-in has to
     * reproduce the skin, the font, the padding and the graphic exactly, and
     * the pixel it gets wrong is the pixel this test would report as a
     * truncated label.</p>
     */
    private static double naturalWidth(Labeled labeled) {
        double min = labeled.getMinWidth();
        double pref = labeled.getPrefWidth();
        labeled.setMinWidth(Region.USE_COMPUTED_SIZE);
        labeled.setPrefWidth(Region.USE_COMPUTED_SIZE);
        labeled.applyCss();
        double needed = labeled.prefWidth(-1);
        labeled.setMinWidth(min);
        labeled.setPrefWidth(pref);
        labeled.applyCss();
        return needed;
    }

    private static String describe(Node node) {
        return node.getId() != null ? "#" + node.getId()
                : node.getClass().getSimpleName() + "." + String.join(".", node.getStyleClass());
    }

    private void dress(Scene scene, String theme) {
        String css = ControlSizingTest.class.getResource("/css/" + theme + ".css")
                .toExternalForm();
        scene.getStylesheets().setAll(css);
        scene.getRoot().applyCss();
        scene.getRoot().layout();
    }

    private Scene load(String view) {
        final Scene[] holder = new Scene[1];
        interact(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                        getClass().getResource("/fxml/" + view + ".fxml"));
                Parent root = loader.load();
                // Into a scene that is already dressed and on screen, which is
                // how MainView mounts a view. The order matters: the width
                // pins measure themselves the moment their button joins a
                // scene, and a scene with no stylesheet yet measures them in
                // the wrong font and keeps that number.
                Scene scene = new Scene(new Group(),
                        view.equals("MainView") ? WINDOW_WIDTH : CONTENT_WIDTH, 740);
                dress(scene, "light");
                stage.setScene(scene);
                stage.show();
                scene.setRoot(root);
                dress(scene, "light");
                holder[0] = scene;
            } catch (Exception e) {
                throw new IllegalStateException("could not load " + view, e);
            }
        });
        return holder[0];
    }
}
