package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

/**
 * Asks before an action that cannot be taken back.
 */
public final class Confirmations {

    private Confirmations() {
    }

    /**
     * Asks whether to go ahead with an action that cannot be undone.
     *
     * <p>Enter must not be the key that goes ahead. The stock confirmation
     * makes OK its default button, and a reflexive Enter was half of how an old
     * Clear link wiped the traffic history. Here Cancel takes Enter as well as
     * Escape, and the button that acts has to be clicked and says what it
     * does.</p>
     *
     * @param owner   the window the dialog belongs to. JavaFX gives a dialog
     *                the stylesheets of its owner's scene and no others, so
     *                without an owner it came up in stock light Modena over the
     *                dark theme
     * @param title   the dialog's title
     * @param header  the question
     * @param content what the action removes or breaks
     * @param action  the label of the button that acts
     * @return true when the user chose the action
     */
    public static boolean confirmIrreversible(Window owner, String title, String header,
                                              String content, String action) {
        ButtonType act = new ButtonType(action, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.get("button.cancel"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION, content, cancel, act);
        Dialogs.dressFrame(dialog);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        if (dialog.getDialogPane().lookupButton(act) instanceof Button acting) {
            acting.setDefaultButton(false);
        }
        if (dialog.getDialogPane().lookupButton(cancel) instanceof Button keeping) {
            keeping.setDefaultButton(true);
        }
        dialog.initOwner(owner);
        return dialog.showAndWait().filter(button -> button == act).isPresent();
    }
}
