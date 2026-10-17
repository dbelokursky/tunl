package com.vlessclient.platform;

import java.nio.file.Path;

/**
 * Windows: everything under {@code %APPDATA%\VlessClient} (roaming profile),
 * except a running session's files, under {@code %LOCALAPPDATA%}.
 */
public final class WindowsPlatformPaths implements PlatformPaths {

    private final Path appData;
    private final Path localAppData;

    public WindowsPlatformPaths() {
        this(resolveAppData(), resolveLocalAppData());
    }

    /** Test seam: inject the {@code %APPDATA%} base directory, beside its {@code Local}. */
    WindowsPlatformPaths(Path appData) {
        this(appData, appData.resolveSibling("Local"));
    }

    /** Test seam: inject the {@code %APPDATA%} and {@code %LOCALAPPDATA%} base directories. */
    WindowsPlatformPaths(Path appData, Path localAppData) {
        this.appData = appData;
        this.localAppData = localAppData;
    }

    @Override
    public Path dataDir() {
        return appData.resolve("VlessClient");
    }

    /**
     * {@code %LOCALAPPDATA%\VlessClient\run}: the local part of the profile,
     * which neither roams nor follows AppData folder redirection to a file
     * server. In {@code %APPDATA%} a plain-text copy of every server's
     * credential went with the roaming profile, and onto the share for every
     * session where AppData is redirected. The profile's ACL keeps it the
     * user's, and the elevated core reads it by its full path.
     */
    @Override
    public Path runDir() {
        return localAppData.resolve("VlessClient").resolve("run");
    }

    private static Path resolveAppData() {
        String appdata = System.getenv("APPDATA");
        if (appdata != null && !appdata.isBlank()) {
            return Path.of(appdata);
        }
        // Fallback if APPDATA is somehow unset.
        return Path.of(System.getProperty("user.home"), "AppData", "Roaming");
    }

    private static Path resolveLocalAppData() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData);
        }
        // The profile's own place for it, as for APPDATA.
        return Path.of(System.getProperty("user.home"), "AppData", "Local");
    }
}
