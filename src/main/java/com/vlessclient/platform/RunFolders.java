package com.vlessclient.platform;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A run folder for each data dir in an OS location every copy of the app the
 * user runs shares ({@code $XDG_RUNTIME_DIR}, {@code %LOCALAPPDATA%}, the
 * macOS per-user temporary folder).
 *
 * <p>A session start sweeps its run folder of the configurations a run that
 * did not end cleanly left, which holds only while one engine at a time
 * writes there. The single-instance lock is the data dir's, so copies with
 * data dirs of their own (profiles chosen by {@code XDG_DATA_HOME}, an
 * {@code APPDATA} of their own) run side by side. In one shared folder each
 * copy's connect deleted the configuration the other was about to start its
 * core on: with its elevation prompt up, the password was typed and the TUN
 * connect failed on a file that was gone.</p>
 */
final class RunFolders {

    /** How much of the digest names the folder: 16 hex digits. */
    private static final int NAME_BYTES = 8;

    private RunFolders() {
    }

    /**
     * The data dir's own folder in {@code shared}, named after a digest of
     * its path: the same at every start and in every release, and another one
     * for each other data dir. A configuration a crash left is swept only by
     * a start in the folder it is in.
     *
     * @param shared  the OS location every copy shares
     * @param dataDir the data dir the folder is for
     * @return the folder
     */
    static Path forDataDir(Path shared, Path dataDir) {
        byte[] path = dataDir.toAbsolutePath().normalize().toString()
                .getBytes(StandardCharsets.UTF_8);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(path);
            return shared.resolve(HexFormat.of().formatHex(digest, 0, NAME_BYTES));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform has to provide SHA-256.
            throw new IllegalStateException(e);
        }
    }
}
