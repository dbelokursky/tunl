package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
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
 * The two themes must lay out identically — only their colours may differ.
 *
 * <p>There is no shared base stylesheet: light.css and dark.css each carry
 * their own copy of every structural rule, so a size that lands in one of
 * them and not the other is invisible until someone switches theme. That is
 * how {@code .separator .line} came to be 3px high in light and 2px in dark,
 * shifting everything below the three separators on the server form.</p>
 *
 * <p>Measured rather than diffed as text: a rule can be missing from one file
 * and still change nothing, and two rules that read differently can lay out
 * the same. What matters is where the pixels end up.</p>
 */
public class ThemeGeometryParityTest extends ApplicationTest {

    /** Layout snapping, not a real disagreement. */
    private static final double SLACK = 0.5;

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
    void theThemesLayOutIdentically() {
        List<String> differences = new ArrayList<>();
        for (Locale locale : List.of(Locale.ENGLISH, Locale.of("ru"))) {
            interact(() -> I18n.setLocale(locale));
            for (String view : VIEWS) {
                Scene scene = load(view);
                // Both themes on the one loaded scene, inside a single FX
                // action: several views carry state that moves on its own —
                // health checks land, a connection attempt fails — and a
                // reload between the two passes shows up as a difference the
                // stylesheets had nothing to do with.
                interact(() -> {
                    Map<String, Bounds> light = measure(scene, "light");
                    Map<String, Bounds> dark = measure(scene, "dark");
                    for (Map.Entry<String, Bounds> entry : light.entrySet()) {
                        Bounds other = dark.get(entry.getKey());
                        if (other == null) {
                            continue;
                        }
                        Bounds mine = entry.getValue();
                        if (Math.abs(mine.getWidth() - other.getWidth()) > SLACK
                                || Math.abs(mine.getHeight() - other.getHeight()) > SLACK
                                || Math.abs(mine.getMinX() - other.getMinX()) > SLACK
                                || Math.abs(mine.getMinY() - other.getMinY()) > SLACK) {
                            differences.add("%s/%s %s: light %s, dark %s".formatted(
                                    view, locale, entry.getKey(), describe(mine), describe(other)));
                        }
                    }
                });
            }
        }

        assertThat(differences)
                .withFailMessage("the two themes disagree about where things go — only colour "
                        + "may differ between them:%n  %s", String.join("\n  ", differences))
                .isEmpty();
    }

    /** Every laid-out region, keyed by something stable across both passes. */
    private Map<String, Bounds> measure(Scene scene, String theme) {
        scene.getStylesheets().setAll(
                getClass().getResource("/css/" + theme + ".css").toExternalForm());
        scene.getRoot().applyCss();
        scene.getRoot().layout();

        Map<String, Bounds> found = new HashMap<>();
        for (Node node : scene.getRoot().lookupAll("*")) {
            if (!(node instanceof Region) || !node.isVisible() || !node.isManaged()) {
                continue;
            }
            // Layout bounds, not bounds-in-local: the latter grows by whatever
            // drop shadow a card or a focused field draws, which is a colour
            // decision and legitimately differs between the themes.
            Bounds bounds = node.localToScene(node.getLayoutBounds());
            if (bounds.getWidth() > 0 && bounds.getHeight() > 0) {
                found.put(key(node), bounds);
            }
        }
        return found;
    }

    /**
     * Enough to pair a node with itself across the two passes without pairing
     * two siblings with each other. Duplicates collapse onto one entry, which
     * only costs coverage of the ones that are alike anyway.
     */
    private static String key(Node node) {
        String text = node instanceof Labeled labeled && labeled.getText() != null
                ? labeled.getText() : "";
        return node.getClass().getSimpleName() + "#" + (node.getId() == null ? "" : node.getId())
                + "." + String.join(".", node.getStyleClass()) + "[" + text + "]";
    }

    private static String describe(Bounds bounds) {
        return "%.1fx%.1f at %.1f,%.1f".formatted(
                bounds.getWidth(), bounds.getHeight(), bounds.getMinX(), bounds.getMinY());
    }

    private Scene load(String view) {
        final Scene[] holder = new Scene[1];
        interact(() -> {
            try {
                Parent root = new FXMLLoader(getClass().getResource("/fxml/" + view + ".fxml"))
                        .load();
                Scene scene = new Scene(new Group(), view.equals("MainView") ? 1100 : 888, 740);
                scene.getStylesheets().setAll(
                        getClass().getResource("/css/light.css").toExternalForm());
                stage.setScene(scene);
                stage.show();
                scene.setRoot(root);
                // Sized explicitly, not left to the next pulse: setRoot does
                // not resize the root, and laying out a root that is still at
                // its own preferred width measures every child against a
                // window it will never be shown in. Locally a pulse happened
                // to land first; on a CI runner it did not, and the install
                // banner reported labels 96px wide.
                root.resize(scene.getWidth(), scene.getHeight());
                root.applyCss();
                root.layout();
                holder[0] = scene;
            } catch (Exception e) {
                throw new IllegalStateException("could not load " + view, e);
            }
        });
        return holder[0];
    }
}
