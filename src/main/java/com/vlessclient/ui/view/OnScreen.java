package com.vlessclient.ui.view;

import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Window;

/**
 * Whether a node can be seen at all: it is in a scene, and that scene's window
 * is showing.
 *
 * <p>Neither half follows from the other, and neither is what
 * {@link Node#isVisible()} reports, which is the node's own flag. Views are
 * cached, so a page the user navigated away from keeps its whole node graph;
 * closing the main window to the tray hides the stage and leaves the scene on
 * it. Work that only produces pixels has no reader in either state.</p>
 */
public final class OnScreen {

    private OnScreen() {
    }

    /**
     * Follows whether {@code node} is on screen.
     *
     * <p>The result is a fluent binding over the node's scene and that scene's
     * window, so it moves when the node is detached, when it lands in another
     * scene, and when the window is hidden or shown again.</p>
     *
     * @param node the node to follow
     * @return true while the node is in a scene whose window is showing
     */
    public static ObservableValue<Boolean> of(Node node) {
        return node.sceneProperty()
                .flatMap(Scene::windowProperty)
                .flatMap(Window::showingProperty)
                .orElse(false);
    }
}
