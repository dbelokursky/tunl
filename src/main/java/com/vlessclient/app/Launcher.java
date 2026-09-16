package com.vlessclient.app;

import com.vlessclient.platform.PlatformPaths;
import java.nio.file.Path;

/**
 * Plain-class entry point. Kept separate from {@link VlessClientApp} so the
 * launched jar can be run without pulling in JavaFX on the command line
 * (the JavaFX runtime does its own bootstrap inside {@code Application.launch}).
 *
 * <p>Also the place to set system properties that must be applied before
 * AWT / JavaFX initialize — for example the macOS application name that
 * shows up in the menu bar ("Tunl" instead of the fully qualified
 * main-class name).</p>
 */
public final class Launcher {

    private static final String APP_NAME = "Tunl";

    private Launcher() {
    }

    /**
     * Sets the process-wide system properties that must be in place before AWT
     * or JavaFX initialize, then hands off to {@link VlessClientApp#main(String[])}.
     *
     * @param args the command-line arguments passed on to the JavaFX application
     */
    public static void main(String[] args) {
        // Nothing to do about logging here any more: logback asks
        // PlatformPaths for the directory itself, through
        // LogDirPropertyDefiner, so there is no property to set before the
        // first class with a static logger loads.

        // One copy of the app per data directory, claimed before anything else
        // runs. A second copy that went on would clear the running copy's
        // system proxy at startup, write the same JSON files from its own
        // memory, and could install a staged update underneath it. Instead it
        // asks the running copy to show its window, and leaves.
        Path dataDir = PlatformPaths.current().dataDir();
        if (SingleInstance.claim(dataDir) == SingleInstance.Claim.HELD_ELSEWHERE) {
            SingleInstance.signalRunning(dataDir);
            return;
        }

        // An update downloaded during an earlier run installs here, before
        // anything else exists to tear down. A handoff means a helper is now
        // waiting for this process to exit so it can replace the files it is
        // running from: leave immediately and start nothing.
        if (com.vlessclient.service.UpdateBootstrap.applyPendingUpdate()) {
            return;
        }

        // Must be set before any AWT / Swing / JavaFX class touches the
        // Toolkit; otherwise the menu bar and Dock keep the fully-qualified
        // main-class name that java-lang assigns by default.
        System.setProperty("apple.awt.application.name", APP_NAME);
        System.setProperty("com.apple.mrj.application.apple.menu.about.name", APP_NAME);

        // Pre-initialize AWT Toolkit while our property is the latest thing
        // set, so the Aqua UI caches the app name before JavaFX starts its
        // own NSApplication setup.
        try {
            java.awt.Toolkit.getDefaultToolkit();
        } catch (Throwable ignored) {
            // Headless or missing AWT — skip silently
        }

        VlessClientApp.main(args);
    }
}
