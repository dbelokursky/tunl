package com.vlessclient.platform;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the packaged application this JVM is running inside. The
 * {@link UpdateApplier} implementations need it for two things: the thing to
 * replace, and the thing to relaunch afterwards.
 *
 * <p>The answer comes from {@code jpackage.app-path}, which the native
 * launcher jpackage generates sets to its own full path. Reconstructing it
 * from {@code java.home} would also "work" and is exactly the kind of guess
 * that must not be made here — an updater that mis-identifies its target
 * deletes the wrong directory.</p>
 */
final class InstalledApp {

    /** Set by the jpackage-generated launcher; absent for jar and IDE runs. */
    static final String APP_PATH_PROPERTY = "jpackage.app-path";

    /**
     * macOS moves a quarantined app to a read-only, randomly-named mount
     * before running it (Gatekeeper path randomization). Until the builds are
     * signed and notarized this is a real state for us to be in, and updating
     * from it would replace a throwaway copy while leaving the app the user
     * actually double-clicks untouched.
     */
    private static final String TRANSLOCATION_MARKER = "/AppTranslocation/";

    private InstalledApp() {
    }

    /**
     * Returns the launcher executable of the installed application, or
     * {@code null} when this JVM was not started from a packaged build.
     *
     * @return the launcher path, or {@code null} when not running packaged
     */
    static Path launcher() {
        String appPath = System.getProperty(APP_PATH_PROPERTY);
        if (appPath == null || appPath.isBlank()) {
            return null;
        }
        Path launcher = Path.of(appPath);
        return Files.exists(launcher) ? launcher : null;
    }

    /**
     * Reports whether the given path sits inside a Gatekeeper translocation
     * mount, where an in-place update would be pointless.
     *
     * @param path the path to test
     * @return true when the path is a translocated copy
     */
    static boolean isTranslocated(Path path) {
        return path != null && path.toString().contains(TRANSLOCATION_MARKER);
    }
}
