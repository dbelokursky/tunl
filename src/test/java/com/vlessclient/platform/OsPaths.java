package com.vlessclient.platform;

import java.nio.file.Path;

/**
 * Each OS's real paths, built on given locations, for tests outside this
 * package: the suite's own {@link PlatformPaths#current()} is the redirected
 * data dir, whose run folder is the data dir's, so it cannot tell a session's
 * files placed by the run folder from files placed by the data dir.
 */
public final class OsPaths {

    private OsPaths() {
    }

    /**
     * Linux's paths.
     *
     * @param home          the home folder
     * @param xdgDataHome   {@code $XDG_DATA_HOME}, or null
     * @param xdgRuntimeDir {@code $XDG_RUNTIME_DIR}, or null
     * @return the paths
     */
    public static PlatformPaths linux(Path home, String xdgDataHome, String xdgRuntimeDir) {
        return new LinuxPlatformPaths(home, xdgDataHome, xdgRuntimeDir);
    }

    /**
     * Windows' paths.
     *
     * @param appData      {@code %APPDATA%}
     * @param localAppData {@code %LOCALAPPDATA%}
     * @return the paths
     */
    public static PlatformPaths windows(Path appData, Path localAppData) {
        return new WindowsPlatformPaths(appData, localAppData);
    }
}
