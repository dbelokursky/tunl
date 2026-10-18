package com.vlessclient.ui.view;

import com.vlessclient.platform.Platform;
import javafx.scene.Parent;

/**
 * The style class that tells base.css which OS a scene is drawn on, put on
 * the root of every scene the app builds: the main window's, each
 * {@link DialogStage}'s and each dialog's that {@link Dialogs} dresses.
 *
 * <p>base.css sets the log, the bypass list and the install command in
 * monospace, which JavaFX draws in Courier New on a Mac, and names Menlo for
 * them under {@code .os-mac}. MainViewController put that class on the main
 * window's root alone, and a selector does not reach into another scene: the
 * sing-box installer, a window of its own, drew the same install command the
 * Dashboard shows in Menlo in Courier New.</p>
 */
final class OsStyleClass {

    /** The class base.css keys the Mac's faces on. */
    static final String MAC = "os-mac";

    /** The OS the scenes are dressed for: this one, or the one a test stands in for. */
    private static volatile Platform os = Platform.current();

    private OsStyleClass() {
    }

    /**
     * Puts the class of the OS the app runs on on {@code root}, once.
     *
     * @param root the root of a scene the app built
     */
    static void applyTo(Parent root) {
        if (os.isMac() && !root.getStyleClass().contains(MAC)) {
            root.getStyleClass().add(MAC);
        }
    }

    /**
     * Dresses the scenes built from now on as on {@code platform}, so a test
     * on another OS can see what a Mac gets.
     *
     * @param platform the OS to stand in for
     * @return the OS the scenes were dressed for until now, to put back
     */
    static Platform dressAs(Platform platform) {
        Platform was = os;
        os = platform;
        return was;
    }
}
