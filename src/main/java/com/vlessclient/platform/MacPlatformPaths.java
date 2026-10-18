package com.vlessclient.platform;

import java.nio.file.Path;

/**
 * macOS: everything under {@code ~/Library/Application Support/VlessClient},
 * except a running session's files, which go to the per-user temporary
 * folder.
 */
public final class MacPlatformPaths implements PlatformPaths {

    private final Path tempDir;

    /** The current user's paths, with the temporary folder the JDK was given. */
    public MacPlatformPaths() {
        this(Path.of(System.getProperty("java.io.tmpdir")));
    }

    /** Test seam: inject the per-user temporary folder. */
    MacPlatformPaths(Path tempDir) {
        this.tempDir = tempDir;
    }

    private static String home() {
        return System.getProperty("user.home");
    }

    @Override
    public Path dataDir() {
        return Path.of(home(), "Library", "Application Support", "VlessClient");
    }

    /**
     * This data dir's folder in {@code VlessClient/run} in the per-user
     * temporary folder ({@link RunFolders}), the JDK's {@code java.io.tmpdir}
     * ({@code $TMPDIR} under /private/var/folders): only its user can open
     * it, Time Machine leaves it out, and root (the password-prompt fallback)
     * reads it by its full path. In the data dir, which Time Machine backs up
     * every hour, each backup made while connected took a plain-text copy of
     * every server's credential along. A temporary folder that is not this
     * user's alone (a {@code java.io.tmpdir} pointed at /tmp) keeps the data
     * dir's run folder.
     */
    @Override
    public Path runDir() {
        return SecureFiles.isPrivateDir(tempDir)
                ? RunFolders.forDataDir(tempDir.resolve("VlessClient").resolve("run"), dataDir())
                : PlatformPaths.super.runDir();
    }
}
