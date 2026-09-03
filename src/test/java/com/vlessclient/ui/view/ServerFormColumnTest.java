package com.vlessclient.ui.view;

import com.vlessclient.app.ThemeCss;
import com.vlessclient.app.UiTestServices;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Save and Cancel have to end where the fields above them end.
 *
 * <p>The actions bar is a sibling of the scroll pane rather than a row inside
 * it, so it keeps its full width when a scrollbar takes some from the fields.
 * That put its buttons 14px right of every field the moment the form grew
 * tall enough to scroll — which is most of the time, since the form is taller
 * than the dialog for every protocol that has a TLS section.</p>
 *
 * <p>Checked at two heights for that reason: one that leaves the content
 * room, and one that cannot.</p>
 */
public class ServerFormColumnTest extends ApplicationTest {

    /** Layout snapping, not a misaligned button. */
    private static final double SLACK = 1.0;

    /** What the dialog opens at; see ServersViewController. */
    private static final int DIALOG_WIDTH = 520;


    @BeforeAll
    static void setupHeadless() {
        System.setProperty("testfx.robot", "glass");
        System.setProperty("testfx.headless", "true");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        System.setProperty("java.awt.headless", "true");
        UiTestServices.initialize();
    }


    @Test
    void theActionsEndWhereTheFieldsEndWithARoomyDialog() {
        assertSameRightEdge(1400);
    }

    @Test
    void theActionsEndWhereTheFieldsEndOnceTheFormScrolls() {
        assertSameRightEdge(420);
    }

    private void assertSameRightEdge(int height) {
        Scene scene = load(height);
        double[] edges = new double[2];
        interact(() -> {
            // Sized and laid out in the same action as the measurement: a
            // headless runner's screen can be smaller than the dialog asked
            // for, and the pulse that follows a clamped stage lays the root
            // out at that smaller size. Twice, because the actions bar's
            // padding follows the viewport bounds, so the pass that produces
            // those bounds cannot also be the pass that uses them.
            scene.getRoot().resize(DIALOG_WIDTH, height);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            edges[0] = rightEdgeOf(scene, "#addressField");
            edges[1] = rightEdgeOf(scene, "#saveButton");
        });
        double fields = edges[0];
        double save = edges[1];

        assertThat(save)
                .withFailMessage("at a dialog %dpx tall the buttons end %.1f from the fields — "
                                + "addressField at %.1f, saveButton at %.1f. The actions bar has "
                                + "to follow the scroll viewport, which loses width to a "
                                + "scrollbar while the bar does not.",
                        height, Math.abs(save - fields), fields, save)
                .isCloseTo(fields, within(SLACK));
    }

    /** Called on the FX thread, with the view already laid out. */
    private static double rightEdgeOf(Scene scene, String selector) {
        Node node = scene.getRoot().lookup(selector);
        assertThat(node).withFailMessage("%s is gone from ServerFormView", selector).isNotNull();
        return node.localToScene(node.getLayoutBounds()).getMaxX();
    }

    private Scene load(int height) {
        final Scene[] holder = new Scene[1];
        interact(() -> {
            try {
                Parent root = new FXMLLoader(getClass().getResource("/fxml/ServerFormView.fxml"))
                        .load();
                // Never shown: a scene only needs a root and its stylesheets
                // to apply CSS and lay out, and a stage ties the test to the
                // screen it runs on.
                Scene scene = new Scene(new Group(), DIALOG_WIDTH, height);
                scene.getStylesheets().setAll(ThemeCss.light());
                scene.setRoot(root);
                holder[0] = scene;
            } catch (Exception e) {
                throw new IllegalStateException("could not load ServerFormView", e);
            }
        });
        return holder[0];
    }
}
