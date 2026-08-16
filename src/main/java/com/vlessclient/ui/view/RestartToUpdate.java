package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.service.ConnectionService;
import com.vlessclient.service.UpdateBootstrap;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

/**
 * Installs a staged update now rather than at the next launch: hands the
 * installer to the platform applier and quits, so the files this process is
 * running from can be replaced while nothing holds them open.
 *
 * <p>Shared because two places offer it — the Updates block in Settings and
 * the dashboard banner — and the part worth not duplicating is the question
 * asked first. Quitting drops the tunnel, and a VPN client that disconnects
 * the user without warning in order to install something is worse than one
 * that updates a day later.</p>
 */
public final class RestartToUpdate {

    /** What came of the offer. */
    public enum Outcome {

        /** The applier has the installer and this process is on its way out. */
        EXITING,

        /** The user was asked about dropping the tunnel and said no. */
        DECLINED,

        /** Nothing was staged, or the applier could not take it. */
        FAILED
    }

    private RestartToUpdate() {
    }

    /**
     * Asks if needed, then applies the staged update.
     *
     * @return what happened; only {@link Outcome#FAILED} is worth reporting to
     *         the user, since a decline is something they just chose
     */
    public static Outcome start() {
        if (tunnelIsUp() && !confirmed()) {
            return Outcome.DECLINED;
        }
        if (UpdateBootstrap.applyPendingUpdate()) {
            // The normal quit path — tray teardown, sing-box stop — which is
            // exactly the exit the applier's helper is waiting for.
            Platform.exit();
            return Outcome.EXITING;
        }
        return Outcome.FAILED;
    }

    private static boolean tunnelIsUp() {
        try {
            return ServiceLocator.get(ConnectionService.class).isRunning();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean confirmed() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("settings.update.restart"));
        confirm.setHeaderText(I18n.get("settings.update.restart.confirm.header"));
        confirm.setContentText(I18n.get("settings.update.restart.confirm.body"));
        return confirm.showAndWait().filter(button -> button == ButtonType.OK).isPresent();
    }
}
