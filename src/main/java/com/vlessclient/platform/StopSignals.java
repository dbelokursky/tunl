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
 * networking without a word. The file now lives under the user's own data
 * directory, created owner-only, with a random token in its name: root (the
 * wrapper) can still read it, nobody else can reach it.</p>
 */
final class StopSignals {

    private static final SecureRandom RANDOM = new SecureRandom();

    private StopSignals() {
    }

    /**
     * A fresh, private stop-signal path for one session. The file itself is
     * created by the engine when it wants the core stopped.
     *
     * @return a path under the user's data directory that does not exist yet
     * @throws IOException if the private run directory cannot be created
     */
    static Path newStopSignalFile() throws IOException {
        Path dir = PlatformPaths.current().dataDir().resolve("run");
        SecureFiles.createPrivateDir(dir);
        byte[] token = new byte[12];
        RANDOM.nextBytes(token);
        Path file = dir.resolve("stop-" + HexFormat.of().formatHex(token) + ".signal");
        Files.deleteIfExists(file);
        return file;
    }
}
