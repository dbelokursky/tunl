package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.service.ThemeManager;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
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
        dressFrame(alert);
        return alert;
    }

    /**
     * Paints the dialog's title bar in the theme the app wears now.
     *
     * <p>The owner lends a dialog its stylesheets, inside JavaFX, but not its
     * color scheme, which is what JavaFX paints the native frame from. Left
     * alone that is the platform's value, which misses the macOS appearance,
     * and in the dark theme every alert came up under a white title bar.</p>
     *
     * @param dialog a dialog of the app's own making
     */
    static void dressFrame(Dialog<?> dialog) {
        ServiceLocator.find(ThemeManager.class).ifPresent(themes -> dialog.getDialogPane()
                .getScene().getPreferences().setColorScheme(themes.currentColorScheme()));
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
