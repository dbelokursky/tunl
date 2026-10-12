package com.vlessclient.ui.view;

import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * A label that works as a button for the keyboard and a screen reader: it
 * takes the focus, and Enter, Space and a screen reader's press run its
 * action.
 *
 * <p>For a line of text that doubles as a control, where a
 * {@link javafx.scene.control.Button} would look like a button. A plain label
 * answered the pointer only, and a screen reader read it as text.</p>
 */
public class PressableLabel extends Label {

    private Runnable onPress = () -> { };

    /** The key that pressed the label and has not been released yet, or null. */
    private KeyCode armedBy;

    /**
     * Creates an empty label that takes the focus and presses like a button.
     *
     * <p>Like a Button, it presses once per press of the key, on its release,
     * and not under a modifier. It used to run the action on every
     * KEY_PRESSED, which a held key repeats about thirty times a second:
     * holding Enter on the dashboard's session total flickered the traffic
     * history open and shut and rewrote settings.json on every repeat, and
     * Shift+Enter or Ctrl+Space toggled it too.</p>
     */
    public PressableLabel() {
        setFocusTraversable(true);
        setAccessibleRole(AccessibleRole.BUTTON);
        addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (isPressKey(event)) {
                if (armedBy == null) {
                    armedBy = event.getCode();
                }
                event.consume();
            }
        });
        addEventHandler(KeyEvent.KEY_RELEASED, event -> {
            if (armedBy != null && event.getCode() == armedBy) {
                armedBy = null;
                onPress.run();
                event.consume();
            }
        });
        // A press the focus left mid-way is called off, as a Button's is.
        focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                armedBy = null;
            }
        });
    }

    private static boolean isPressKey(KeyEvent event) {
        return (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE)
                && !event.isShiftDown() && !event.isControlDown() && !event.isAltDown()
                && !event.isMetaDown() && !event.isShortcutDown();
    }

    /**
     * Sets what Enter, Space and a screen reader's press do. A click is left to
     * the view, as for any label.
     *
     * @param onPress the action, or null for none
     */
    public void setOnPress(Runnable onPress) {
        this.onPress = onPress == null ? () -> { } : onPress;
    }

    @Override
    public void executeAccessibleAction(AccessibleAction action, Object... parameters) {
        if (action == AccessibleAction.FIRE) {
            onPress.run();
        } else {
            super.executeAccessibleAction(action, parameters);
        }
    }
}
