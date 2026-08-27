package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owner-only file helpers for the config files that hold credentials
 * (servers.json, settings.json, subscriptions.json). Default JSON writes go
 * out under the process umask — 0644 on a typical Unix box — so on a
 * multi-user host any other local user could read a stored VLESS UUID or
 * password when the OS keychain isn't in use.
 *
 * <p>On POSIX filesystems these helpers pin the data directory to 0700 and
 * each file to 0600. On Windows there is no POSIX view; files under the
 * per-user {@code %APPDATA%} profile are already protected by its ACL, so the
 * helpers degrade to a plain write there.</p>
 */
public final class SecureFiles {

    private static final Logger log = LoggerFactory.getLogger(SecureFiles.class);

    private static final boolean POSIX = FileSystems.getDefault()
            .supportedFileAttributeViews().contains("posix");

    private SecureFiles() {
    }

    /**
     * Returns the absolute parent directory for a file path.
     *
     * @throws IOException when {@code path} does not identify a file beneath
     *                     a directory (for example, a filesystem root)
     */
    public static Path parentDirectory(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null || absolute.getFileName() == null) {
            throw new IOException("Path does not identify a file: " + path);
        }
        return parent;
    }

    /** Creates {@code dir} and its parents, restricted to the owner on POSIX. */
    public static void createPrivateDir(Path dir) throws IOException {
        Files.createDirectories(dir);
        if (POSIX) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /**
     * Writes {@code data} to {@code target} owner-only and atomically: the
     * bytes land in a sibling temp file (created 0600 on POSIX) which then
     * replaces the target, so a reader never sees a half-written config and a
     * crash mid-write can't truncate the previous one.
     */
    public static void writePrivately(Path target, byte[] data) throws IOException {
        Path absoluteTarget = target.toAbsolutePath();
        Path dir = parentDirectory(absoluteTarget);
        Path fileName = absoluteTarget.getFileName();
        if (fileName == null) {
            throw new IOException("Path does not identify a file: " + target);
        }
        Path tmp = Files.createTempFile(dir, fileName.toString(), ".tmp");
        try {
            if (POSIX) {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            }
            Files.write(tmp, data);
            try {
                Files.move(tmp, absoluteTarget, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, absoluteTarget, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Best-effort tightening of an already-existing file to 0600 on POSIX, so
     * a config written by an older build (0644) stops leaking on load without
     * waiting for the next save. No-op off POSIX or when the file is absent.
     */
    public static void restrictExisting(Path file) {
        if (!POSIX || !Files.exists(file)) {
            return;
        }
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException e) {
            log.debug("Could not restrict permissions on {}: {}", file, e.getMessage());
        }
    }
}
