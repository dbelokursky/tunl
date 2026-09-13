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
import javafx.scene.control.TextArea;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP command box in Settings, measured in MainView at the window sizes
 * the app opens at, in both themes.
 *
 * <p>The box is a wrapping TextArea, and a TextArea took its height from its
 * row count, never from its text. It did not even get the rows it asked for:
 * the skin leaves the control's own padding out of its preferred height, so
 * the {@code .form-field} inset came out of the three rows. At 820x500 and
 * 760x460 the command wraps to four lines in a box with room for two, and the
 * bearer token, the part anyone copies the command for, sat behind the box's
 * own scroll bar.</p>
 */
@UiTest
public class SettingsMcpCommandTest extends ApplicationTest {

    /** The window VlessClientApp opens at, and the smallest it can be dragged to. */
    private static final int[][] WINDOWS = {{820, 500}, {760, 460}};

    @Test
    void theCommandShowsWholeWithNoScrollBarOfItsOwn() {
        assertInEveryWindow("the MCP command box has to show the whole command, with no "
                + "scroll bar of its own", SettingsMcpCommandTest::fitsTheCommand);
    }

    /**
     * The box reads as one of the form's fields. {@code .form-field} padded the
     * control on top of the skin's own padded content region, so the command
     * sat 24px in from the box's edge where the Port field's text sits 13px in,
     * on a second box in a different grey inside the field.
     */
    @Test
    void theCommandBoxLooksLikeTheFieldsAroundIt() {
        assertInEveryWindow("the MCP command box has to look like the form fields around it",
                SettingsMcpCommandTest::looksLikeTheFields);
    }

    /** One measurement of one freshly laid-out window, made on the FX thread. */
    private interface Measurement {
        void collect(Scene scene, String where, List<String> wrong);
    }

    private void assertInEveryWindow(String rule, Measurement measurement) {
        List<String> wrong = new ArrayList<>();
        for (String theme : List.of("light", "dark")) {
            for (int[] window : WINDOWS) {
                // A scene of its own for every size and theme, so nothing laid
                // out for one is still in place to be measured for the next.
                Scene scene = mainViewShowingSettings(theme, window[0], window[1]);
                String where = "%s %dx%d".formatted(theme, window[0], window[1]);
                // Collected on the FX thread and asserted off it, so one window
                // does not hide the others.
                interact(() -> measurement.collect(scene, where, wrong));
            }
        }

        assertThat(wrong)
                .withFailMessage("%s:%n  %s", rule, String.join("\n  ", wrong))
                .isEmpty();
    }

    private static void fitsTheCommand(Scene scene, String where, List<String> wrong) {
        TextArea area = commandArea(scene, where, wrong);
        if (area == null) {
            return;
        }
        ScrollPane scroller = area.getChildrenUnmodifiable().stream()
                .filter(ScrollPane.class::isInstance).map(ScrollPane.class::cast)
                .findFirst().orElseThrow();
        for (Node child : scroller.getChildrenUnmodifiable()) {
            if (child instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL
                    && isShowing(bar)) {
                Bounds at = inScene(bar);
                wrong.add("%s: the box draws a vertical scroll bar at x=%.0f..%.0f".formatted(
                        where, at.getMinX(), at.getMaxX()));
            }
        }

        // The command against the part of the box that is not clipped away.
        Bounds shown = inScene(scroller.lookup(".viewport"));
        Bounds text = inScene(scroller.lookup(".text"));
        if (text.getMinY() < shown.getMinY() - 0.5 || text.getMaxY() > shown.getMaxY() + 0.5
                || text.getMaxX() > shown.getMaxX() + 0.5) {
            wrong.add(("%s: the command spans x=%.0f..%.0f y=%.0f..%.0f, the box shows "
                    + "x=%.0f..%.0f y=%.0f..%.0f").formatted(where,
                    text.getMinX(), text.getMaxX(), text.getMinY(), text.getMaxY(),
                    shown.getMinX(), shown.getMaxX(), shown.getMinY(), shown.getMaxY()));
        }
    }

    private static void looksLikeTheFields(Scene scene, String where, List<String> wrong) {
        TextArea area = commandArea(scene, where, wrong);
        if (area == null) {
            return;
        }
        Node command = area.lookup(".text");
        Node port = scene.lookup("#mcpPortField");
        double commandInset = inScene(command).getMinX() - inScene(area).getMinX();
        double portInset = inScene(port.lookup(".text")).getMinX() - inScene(port).getMinX();
        if (Math.abs(commandInset - portInset) > 0.5) {
            wrong.add(("%s: the command sits %.1fpx in from the box's edge, the port %.1fpx in "
                    + "from its field's").formatted(where, commandInset, portInset));
        }

        // One surface, the field's own: nothing between the box and its text
        // paints over .form-field's background.
        for (Node node = command.getParent(); node != area; node = node.getParent()) {
            if (node instanceof Region region && paints(region.getBackground())) {
                wrong.add("%s: %s paints %s inside the box, over the field's background"
                        .formatted(where, describe(region), region.getBackground().getFills()
                                .stream().map(BackgroundFill::getFill).toList()));
            }
        }
    }

    /** The command box, or null with the reason recorded when it holds no command. */
    private static TextArea commandArea(Scene scene, String where, List<String> wrong) {
        TextArea area = (TextArea) scene.lookup("#mcpCommandArea");
        // An empty box fits trivially; without the command there is nothing
        // here to measure.
        if (area.getText() == null || !area.getText().startsWith("claude mcp add")) {
            wrong.add(where + ": the box does not hold the command: \"" + area.getText() + "\"");
            return null;
        }
        return area;
    }

    /** Where a node is laid out, in scene coordinates. */
    private static Bounds inScene(Node node) {
        return node.getParent().localToScene(node.getBoundsInParent());
    }

    private static boolean paints(Background background) {
        return background != null && background.getFills().stream().anyMatch(fill ->
                !(fill.getFill() instanceof Color color) || color.getOpacity() > 0);
    }

    private static String describe(Node node) {
        return node.getClass().getSimpleName()
                + (node.getStyleClass().isEmpty() ? "" : "." + String.join(".", node.getStyleClass()));
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

    /**
     * MainView at a window size and theme, sidebar included, with Settings
     * mounted the way its nav button mounts it. Never shown: a scene lays out
     * without a stage, and a stage would clamp the size to the runner's screen.
     */
    private Scene mainViewShowingSettings(String theme, int width, int height) {
        final Scene[] holder = new Scene[1];
        interact(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainView.fxml"));
                Parent root = loader.load();
                Scene scene = new Scene(new Group(), width, height);
                // Dressed before Settings is mounted, as the app's scene is by
                // the time the nav button mounts it.
                scene.getStylesheets().setAll(ThemeCss.of(theme));
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
