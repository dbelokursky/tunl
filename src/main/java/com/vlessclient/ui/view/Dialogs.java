package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;

/**
 * Dialogs whose stock buttons are worded in the language of the UI.
 *
 * <p>JavaFX words {@link ButtonType#OK} and {@link ButtonType#CANCEL} from a
 * bundle of its own, and JavaFX 26 has none for Russian: every dialog of the
 * Russian UI said "OK" and "Cancel", deleting a server or a subscription
 * included. The button types stay the stock ones, so a caller still compares
 * the result with {@code ButtonType.OK}; only the buttons' words change.</p>
 */
public final class Dialogs {

    private Dialogs() {
    }

    /**
     * An alert of this type, its buttons worded in the language of the UI.
     *
     * @param type the alert's type
     * @return the alert
     */
    public static Alert alert(Alert.AlertType type) {
        Alert alert = new Alert(type);
        localizeButtons(alert.getDialogPane());
        return alert;
    }

    /**
     * Words the stock OK and Cancel buttons of a dialog in the language of
     * the UI. Call it once the dialog's button types are set.
     *
     * @param pane the dialog's pane
     */
    public static void localizeButtons(DialogPane pane) {
        word(pane, ButtonType.OK, "button.ok");
        word(pane, ButtonType.CANCEL, "button.cancel");
    }

    static void word(DialogPane pane, ButtonType type, String key) {
        if (pane.lookupButton(type) instanceof Button button) {
            button.setText(I18n.get(key));
        }
    }
}
