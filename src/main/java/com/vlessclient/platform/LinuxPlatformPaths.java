package com.vlessclient.platform;

import java.nio.file.Path;

/**
 * Linux: XDG base directories. App data (and logs/core underneath it) lives
 * in {@code $XDG_DATA_HOME/vless-client}, defaulting to
 * {@code ~/.local/share/vless-client}. Downloads resolve through the
 * {@code user-dirs.dirs} mechanism with {@code ~/Downloads} as the fallback.
 * A running session's files go to {@code $XDG_RUNTIME_DIR} where it is
 * private.
 */
public final class LinuxPlatformPaths implements PlatformPaths {

    private final Path home;
    private final String xdgDataHome;
    private final String xdgRuntimeDir;

    public LinuxPlatformPaths() {
        this(Path.of(System.getProperty("user.home")), System.getenv("XDG_DATA_HOME"),
                System.getenv("XDG_RUNTIME_DIR"));
    }

    /** Test seam: inject the home directory and the {@code $XDG_DATA_HOME} value. */
    LinuxPlatformPaths(Path home, String xdgDataHome) {
        this(home, xdgDataHome, null);
    }

    /** Test seam: also inject the {@code $XDG_RUNTIME_DIR} value. */
    LinuxPlatformPaths(Path home, String xdgDataHome, String xdgRuntimeDir) {
        this.home = home;
        this.xdgDataHome = xdgDataHome;
        this.xdgRuntimeDir = xdgRuntimeDir;
    }

    @Override
    public Path dataDir() {
        Path base = xdgDataHome != null && !xdgDataHome.isBlank()
                ? Path.of(xdgDataHome)
                : home.resolve(".local").resolve("share");
        return base.resolve("vless-client");
    }

    /**
     * This data dir's folder in {@code $XDG_RUNTIME_DIR/vless-client}
     * ({@link RunFolders}): a tmpfs only its user can open, which root (the
     * PolicyKit fallback) reads by its full path, no home backup includes,
     * and logout empties. In the data dir, which Déjà Dup and most other
     * backups take with the rest of the home folder, each backup made while
     * connected took a plain-text copy of every server's credential along.
     * Without a runtime dir that is this user's alone (none set, or another
     * user's inherited through su), the data dir's run folder.
     */
    @Override
    public Path runDir() {
        if (xdgRuntimeDir != null && !xdgRuntimeDir.isBlank()) {
            Path runtime = Path.of(xdgRuntimeDir);
            if (runtime.isAbsolute() && SecureFiles.isPrivateDir(runtime)) {
                return RunFolders.forDataDir(runtime.resolve("vless-client"), dataDir());
            }
        }
        return PlatformPaths.super.runDir();
    }
}
