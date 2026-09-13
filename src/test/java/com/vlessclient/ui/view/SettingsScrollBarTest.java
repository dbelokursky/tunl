package com.vlessclient.ui.view;

import com.vlessclient.app.ThemeCss;
import com.vlessclient.testing.UiTest;
import java.util.ArrayList;
import java.util.List;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Settings scrolls under one vertical bar: the one MainView's
 * {@link ContentScrollPane} gives every page.
 *
 * <p>SettingsView was a ScrollPane of its own, and MainView mounts every page
 * inside that wrapper, so Settings was scrolled twice. The wrapper pins a
 * page's minimum height to its preferred height, and a scroll pane reports
 * that height for no particular width, with every wrapping hint on one line.
 * At the width the sidebar leaves, the hints wrap, so the inner pane came out
 * shorter than its own content and drew a second, almost full-length bar
 * beside the wrapper's.</p>
 */
@UiTest
public class SettingsScrollBarTest extends ApplicationTest {

    /**
     * The narrowest window VlessClientApp allows, and the one the README
     * screenshots are taken at, where the second bar was noticed. Settings is
     * taller than both, so each has to show a bar, and only one.
     */
    private static final int[][] WINDOWS = {{760, 460}, {1100, 740}};

    @Test
    void settingsScrollsUnderTheShellsBarAndNoOther() {
        List<String> wrong = new ArrayList<>();
        for (int[] window : WINDOWS) {
            // A scene of its own for each size, so nothing laid out at one
            // size is still there to be measured at the next.
            Scene scene = mainViewShowingSettings(window[0], window[1]);
            // Collected on the FX thread and asserted off it, so one size
            // does not hide the other.
            interact(() -> {
                List<ScrollBar> bars = verticalBarsScrollingThePage(scene);
                if (bars.size() != 1 || !(bars.get(0).getParent() instanceof ContentScrollPane)) {
                    wrong.add("%dx%d: %s".formatted(window[0], window[1], describe(bars)));
                }
            });
        }

        assertThat(wrong)
                .withFailMessage("Settings should scroll under ContentScrollPane's bar and no "
                        + "other, but shows:%n  %s", String.join("\n  ", wrong))
                .isEmpty();
    }

    /**
     * The vertical bars of every scroll pane the Settings title sits in. Not
     * every bar in the view: the MCP command box is a TextArea whose own bar
     * shows at both sizes, and that one scrolls the command, not the page.
     */
    private static List<ScrollBar> verticalBarsScrollingThePage(Scene scene) {
        List<ScrollBar> bars = new ArrayList<>();
        for (Node node = scene.lookup("#titleLabel"); node != null; node = node.getParent()) {
            if (node instanceof ScrollPane pane) {
                for (Node child : pane.getChildrenUnmodifiable()) {
                    if (child instanceof ScrollBar bar
                            && bar.getOrientation() == Orientation.VERTICAL && isShowing(bar)) {
                        bars.add(bar);
                    }
                }
            }
        }
        return bars;
    }

    /**
     * Visible all the way up, and laid out with a size. A scroll pane's skin
     * keeps both of its bars in the scene graph and hides the one it does not
     * need, so being found says nothing.
     */
    private static boolean isShowing(ScrollBar bar) {
        for (Node node = bar; node != null; node = node.getParent()) {
            if (!node.isVisible()) {
                return false;
            }
        }
        Bounds box = bar.getBoundsInParent();
        return box.getWidth() > 0 && box.getHeight() > 0;
    }

    /** Whose each bar is, and where it sits across the window. */
    private static String describe(List<ScrollBar> bars) {
        List<String> described = new ArrayList<>();
        for (ScrollBar bar : bars) {
            Parent pane = bar.getParent();
            Bounds inScene = pane.localToScene(bar.getBoundsInParent());
            described.add("%s (.%s) at x=%.0f..%.0f".formatted(pane.getClass().getSimpleName(),
                    String.join(".", pane.getStyleClass()), inScene.getMinX(), inScene.getMaxX()));
        }
        return bars.size() + " vertical bar(s) " + described;
    }

    /**
     * MainView at a window size, with Settings mounted the way its nav button
     * mounts it. Never shown, as in ControlSizingTest: a scene lays out
     * without a stage, and a stage would clamp the size to the runner's
     * screen.
     */
    private Scene mainViewShowingSettings(int width, int height) {
        final Scene[] holder = new Scene[1];
        interact(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainView.fxml"));
                Parent root = loader.load();
                Scene scene = new Scene(new Group(), width, height);
                // Dressed before Settings is mounted, as the app's scene is by
                // the time the nav button mounts it.
                scene.getStylesheets().setAll(ThemeCss.light());
                scene.setRoot(root);
                MainViewController controller = loader.getController();
                controller.showSettings();
                root.resize(width, height);
                root.applyCss();
                root.layout();
                holder[0] = scene;
            } catch (Exception e) {
                throw new IllegalStateException("could not load MainView", e);
            }
        });
        return holder[0];
    }
}
