package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.testing.UiTest;
import java.util.ArrayList;
import java.util.List;
import javafx.beans.value.ObservableValue;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * A node is on screen only while it sits in a scene whose window is showing:
 * a cached view taken off the scene and a stage hidden to the tray both count
 * as off screen, whatever the node's own visible flag says.
 */
@UiTest
public class OnScreenTest extends ApplicationTest {

    private Stage stage;
    private StackPane host;
    private Label node;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        node = new Label("row");
        host = new StackPane(node);
        stage.setScene(new Scene(host, 200, 100));
        stage.show();
    }

    @Test
    void followsDetachingFromTheSceneAndComingBack() {
        List<Boolean> seen = new ArrayList<>();
        ObservableValue<Boolean> onScreen = OnScreen.of(node);
        interact(() -> onScreen.addListener((obs, was, is) -> seen.add(is)));
        assertThat(onScreen.getValue()).isTrue();

        interact(() -> host.getChildren().clear());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(onScreen.getValue()).isFalse();
        // The node's own flag never moved; it is not what decides.
        assertThat(node.isVisible()).isTrue();

        interact(() -> host.getChildren().add(node));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(onScreen.getValue()).isTrue();
        assertThat(seen).containsExactly(false, true);
    }

    @Test
    void followsTheWindowBeingHiddenAndShownAgain() {
        ObservableValue<Boolean> onScreen = OnScreen.of(node);
        List<Boolean> seen = new ArrayList<>();
        interact(() -> onScreen.addListener((obs, was, is) -> seen.add(is)));

        interact(() -> stage.hide());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(onScreen.getValue()).isFalse();
        assertThat(node.getScene()).isNotNull();

        interact(() -> stage.show());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(onScreen.getValue()).isTrue();
        assertThat(seen).containsExactly(false, true);
    }
}
