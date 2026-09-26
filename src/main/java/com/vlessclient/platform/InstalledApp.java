package com.vlessclient.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;

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
    private static final String TRANSLOCATION_DIR = "AppTranslocation";

    /**
     * The top-level directory macOS mounts disk images under, the downloaded
     * Tunl.dmg among them. A name, compared element by element like
     * {@link #TRANSLOCATION_DIR}, rather than a path.
     */
    private static final String VOLUMES = "Volumes";

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
     * Whether the app bundle runs from where it was downloaded rather than
     * from where it was installed: a translocated copy, or the disk image
     * itself, which is mounted read-only under {@code /Volumes}. Neither can be
     * updated in place, and moving Tunl to Applications fixes both. A drive the
     * user keeps apps on can be written, and an Applications folder this
     * account cannot write is no download.
     *
     * @param bundle   the {@code .app} bundle, or {@code null} when unknown
     * @param writable whether a directory can be written
     * @return true when the bundle runs from its download
     */
    static boolean runsFromDownload(Path bundle, Predicate<Path> writable) {
        if (bundle == null) {
            return false;
        }
        if (isTranslocated(bundle)) {
            return true;
        }
        Path parent = bundle.getParent();
        boolean underVolumes = bundle.getRoot() != null && bundle.getNameCount() > 1
                && bundle.getName(0).toString().equals(VOLUMES);
        return underVolumes && parent != null && !writable.test(parent);
    }

    /**
     * {@link #runsFromDownload(Path, Predicate)} with the file system's answer
     * to whether a directory can be written.
     *
     * @param bundle the {@code .app} bundle, or {@code null} when unknown
     * @return true when the bundle runs from its download
     */
    static boolean runsFromDownload(Path bundle) {
        return runsFromDownload(bundle, Files::isWritable);
    }

    /**
     * Reports whether the given path sits inside a Gatekeeper translocation
     * mount, where an in-place update would be pointless.
     *
     * @param path the path to test
     * @return true when the path is a translocated copy
     */
    static boolean isTranslocated(Path path) {
        if (path == null) {
            return false;
        }
        // Compared element by element rather than as a substring of the whole
        // path: the rendered form uses the host's separator, so a substring
        // with slashes in it silently answers "no" anywhere but macOS. Element
        // equality also beats a bare `contains`, which would match a directory
        // merely named AppTranslocationTests.
        for (Path element : path) {
            if (element.toString().equals(TRANSLOCATION_DIR)) {
                return true;
            }
        }
        return false;
    }
}
