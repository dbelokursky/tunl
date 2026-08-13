package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
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

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
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
        double fields = rightEdgeOf(scene, "#addressField");
        double save = rightEdgeOf(scene, "#saveButton");

        assertThat(save)
                .withFailMessage("at a dialog %dpx tall the buttons end %.1f from the fields — "
                                + "addressField at %.1f, saveButton at %.1f. The actions bar has "
                                + "to follow the scroll viewport, which loses width to a "
                                + "scrollbar while the bar does not.",
                        height, Math.abs(save - fields), fields, save)
                .isCloseTo(fields, within(SLACK));
    }

    private double rightEdgeOf(Scene scene, String selector) {
        final double[] edge = new double[1];
        interact(() -> {
            Node node = scene.getRoot().lookup(selector);
            assertThat(node).withFailMessage("%s is gone from ServerFormView", selector).isNotNull();
            Bounds bounds = node.localToScene(node.getLayoutBounds());
            edge[0] = bounds.getMaxX();
        });
        return edge[0];
    }

    private Scene load(int height) {
        final Scene[] holder = new Scene[1];
        interact(() -> {
            try {
                Parent root = new FXMLLoader(getClass().getResource("/fxml/ServerFormView.fxml"))
                        .load();
                Scene scene = new Scene(new Group(), 520, height);
                scene.getStylesheets().setAll(
                        getClass().getResource("/css/light.css").toExternalForm());
                stage.setScene(scene);
                stage.show();
                scene.setRoot(root);
                // Sized explicitly rather than waiting for a pulse, and laid
                // out twice: the actions bar's padding follows the viewport
                // bounds, so the pass that produces those bounds cannot also be
                // the pass that uses them.
                root.resize(scene.getWidth(), scene.getHeight());
                root.applyCss();
                root.layout();
                root.applyCss();
                root.layout();
                holder[0] = scene;
            } catch (Exception e) {
                throw new IllegalStateException("could not load ServerFormView", e);
            }
        });
        return holder[0];
    }
}
