package com.vlessclient.ui.view;

import com.vlessclient.service.UpdateBootstrap;
import javafx.application.Platform;

/**
 * Installs a staged update now rather than at the next launch: hands the
 * installer to the platform applier and quits, so the files this process is
 * running from can be replaced while nothing holds them open.
 *
 * <p>Shared because two places offer it — the Updates block in Settings and
 * the dashboard banner — and neither should have to know how the handoff
 * works.</p>
 *
 * <p>Nothing is asked first. There used to be a confirmation, on the grounds
 * that quitting drops the tunnel and a VPN client that disconnects the user
 * unannounced is worse than one that updates a day later. The reasoning still
 * holds; the dialog was not what carried it. Both buttons that arrive here are
 * labelled "Restart now", and the dialog asked "Restart now?" — so it repeated
 * the label back and charged a second press for it, which is the shape of a
 * confirmation that has stopped informing anyone.</p>
 */
public final class RestartToUpdate {

    /** What came of the offer. */
    public enum Outcome {

        /** The applier has the installer and this process is on its way out. */
        EXITING,

        /** Nothing was staged, or the applier could not take it. */
        FAILED
    }

    private RestartToUpdate() {
    }

    /**
     * Applies the staged update and quits.
     *
     * @return what happened; only {@link Outcome#FAILED} is worth reporting to
     *         the user, since the other outcome is the app going away
     */
    public static Outcome start() {
        if (UpdateBootstrap.applyPendingUpdate()) {
            // The normal quit path — tray teardown, sing-box stop — which is
            // exactly the exit the applier's helper is waiting for.
            Platform.exit();
            return Outcome.EXITING;
        }
        return Outcome.FAILED;
    }
}
