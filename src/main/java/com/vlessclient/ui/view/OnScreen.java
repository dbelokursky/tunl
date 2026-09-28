package com.vlessclient.ui.view;

import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Whether a node can be seen at all: it is in a scene, and that scene's window
 * is showing and not minimised.
 *
 * <p>None of these follows from the others, and none is what
 * {@link Node#isVisible()} reports, which is the node's own flag. Views are
 * cached, so a page the user navigated away from keeps its whole node graph;
 * closing the main window to the tray hides the stage and leaves the scene on
 * it; a window minimised to the Dock or the taskbar still counts as showing.
 * Work that only produces pixels has no reader in any of these states.</p>
 */
public final class OnScreen {

    /** Never on screen: what a window that is not showing contributes. */
    private static final ObservableValue<Boolean> NEVER =
            new ReadOnlyBooleanWrapper(false).getReadOnlyProperty();

    private OnScreen() {
    }

    /**
     * Follows whether {@code node} is on screen.
     *
     * <p>The result is a fluent binding over the node's scene and that scene's
     * window, so it moves when the node is detached, when it lands in another
     * scene, when the window is hidden or shown again, and when it is
     * minimised or restored.</p>
     *
     * @param node the node to follow
     * @return true while the node is in a scene whose window is showing and,
     *         for a stage, not minimised
     */
    public static ObservableValue<Boolean> of(Node node) {
        return node.sceneProperty()
                .flatMap(Scene::windowProperty)
                .flatMap(OnScreen::seen)
                .orElse(false);
    }

    private static ObservableValue<Boolean> seen(Window window) {
        if (window instanceof Stage stage) {
            return stage.showingProperty().flatMap(showing -> showing
                    ? stage.iconifiedProperty().map(iconified -> !iconified)
                    : NEVER);
        }
        return window.showingProperty();
    }
}
