package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Where a TUN session's stop-signal file lives.
 *
 * <p>The privileged wrapper watches this file and tears the core down when it
 * appears, so whoever can create it can end the tunnel. On macOS it used to
 * sit in the world-writable {@code /tmp} under a name derived from
 * {@code System.nanoTime()}, a narrow, monotonic range another local user
 * could guess — a local denial of tunnel that dropped the user to unprotected
 * networking without a word. The file now lives in the user's own run folder
 * ({@link PlatformPaths#runDir()}), beside the session's configuration,
 * created owner-only, with a random token in its name: root (the wrapper) can
 * still read it, nobody else can reach it.</p>
 */
final class StopSignals {

    private static final SecureRandom RANDOM = new SecureRandom();

    private StopSignals() {
    }

    /**
     * A fresh, private stop-signal path for one session. The file itself is
     * created by the engine when it wants the core stopped.
     *
     * @return a path in the user's run folder that does not exist yet
     * @throws IOException if the private run directory cannot be created
     */
    static Path newStopSignalFile() throws IOException {
        return newStopSignalFile(PlatformPaths.current());
    }

    /**
     * {@link #newStopSignalFile()} by the given paths; a seam for tests, whose
     * own paths keep the run folder in the data dir.
     *
     * @param paths the paths whose run folder it goes in
     * @return a path in that run folder that does not exist yet
     * @throws IOException if the private run directory cannot be created
     */
    static Path newStopSignalFile(PlatformPaths paths) throws IOException {
        Path dir = paths.runDir();
        SecureFiles.createPrivateDir(dir);
        byte[] token = new byte[12];
        RANDOM.nextBytes(token);
        Path file = dir.resolve("stop-" + HexFormat.of().formatHex(token) + ".signal");
        Files.deleteIfExists(file);
        return file;
    }
}
