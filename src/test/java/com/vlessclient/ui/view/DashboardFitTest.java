package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layout regression guard: at the minimum content width the horizontal
 * scrollbar is disabled (fitToWidth + Hbar=NEVER), so any control wider than
 * the viewport is clipped and unreachable. This lays the Dashboard out at that
 * width and asserts the key actions stay within it.
 */
public class DashboardFitTest extends ApplicationTest {

    /** Window min 760 - sidebar 200 - content padding 48 = 512; test a hair tighter. */
    private static final double MIN_CONTENT_WIDTH = 500;

    private Scene scene;

    @BeforeAll
    static void setupHeadless() {
        System.setProperty("testfx.robot", "glass");
        System.setProperty("testfx.headless", "true");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        System.setProperty("java.awt.headless", "true");
        try {
            ServiceLocator.initialize();
        } catch (Exception e) {
            // headless CI tolerance
        }
    }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/DashboardView.fxml"));
        Parent root = loader.load();
        scene = new Scene(root, MIN_CONTENT_WIDTH, 520);
        scene.getStylesheets().add(getClass().getResource("/css/light.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void keyControlsFitWithinMinWidth() {
        interact(() -> {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        });

        assertWithinWidth("#connectButton");
        assertWithinWidth("#proxyModeCombo");
        // Pinned to a width of its own, so it can no longer shrink out of the
        // way when the row runs out of room — which is exactly what this guard
        // is for.
        assertWithinWidth("#recheckButton");
        assertWithinWidth("#addTargetButton");
    }

    private void assertWithinWidth(String selector) {
        Node node = lookup(selector).query();
        Bounds inScene = node.localToScene(node.getBoundsInLocal());
        assertThat(inScene.getMaxX())
                .withFailMessage("%s overflows the %.0fpx viewport (right edge at %.1f) — "
                                + "it would be clipped with no horizontal scrollbar",
                        selector, MIN_CONTENT_WIDTH, inScene.getMaxX())
                .isLessThanOrEqualTo(MIN_CONTENT_WIDTH + 0.5);
        assertThat(inScene.getMinX())
                .withFailMessage("%s starts off the left edge (%.1f)", selector, inScene.getMinX())
                .isGreaterThanOrEqualTo(-0.5);
    }
}
