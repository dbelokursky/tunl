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

    /** Creates an empty label that takes the focus and presses like a button. */
    public PressableLabel() {
        setFocusTraversable(true);
        setAccessibleRole(AccessibleRole.BUTTON);
        addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                onPress.run();
                event.consume();
            }
        });
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
