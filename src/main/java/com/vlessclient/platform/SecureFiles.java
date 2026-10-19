package com.vlessclient.platform;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
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

    /**
     * The pauses before each further try at a replace Windows refused: 630 ms
     * in all, long past a scan of a config file, and short enough for a save
     * the window waits on.
     */
    private static final long[] REPLACE_RETRY_MILLIS = {10, 20, 40, 80, 160, 320};

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
     * crash mid-write can't truncate the previous one. The temp file is forced
     * to the device before the move, so a power cut after the move cannot leave
     * the target empty either.
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
            // A filesystem may commit the rename before the data it points at:
            // a power cut in between left the config empty, and an empty
            // servers.json was quarantined as corrupt on the next start.
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ByteBuffer remaining = ByteBuffer.wrap(data);
                while (remaining.hasRemaining()) {
                    channel.write(remaining);
                }
                channel.force(true);
            }
            replace(tmp, absoluteTarget, SecureFiles::move, !POSIX);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Puts one file in another's place; the seam a test refuses a move through. */
    @FunctionalInterface
    interface Mover {
        void move(Path source, Path target) throws IOException;
    }

    /**
     * Moves {@code source} into {@code target}'s place, and on Windows tries
     * again for a moment when the move is refused while {@code target} may
     * just be held open.
     *
     * <p>Windows does not replace a file another program has open: a virus
     * scanner or a sync client reading the file just written, or anything
     * else reading it, and says "access denied", or that the file is in use,
     * until it lets go a moment later. The first such refusal failed the
     * save: credentials stayed sealed after the keychain box was unticked,
     * and Settings asked to retry a save that a second try would have made.
     * A refusal that lasts, a directory where the file belongs or a read-only
     * file, still fails at once.</p>
     */
    static void replace(Path source, Path target, Mover mover, boolean windows)
            throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                mover.move(source, target);
                return;
            } catch (IOException e) {
                if (!windows || attempt == REPLACE_RETRY_MILLIS.length
                        || !mayBeHeldOpen(e, target)) {
                    throw e;
                }
                log.debug("Replacing {} was refused ({}); trying again", target, e.toString());
                pause(REPLACE_RETRY_MILLIS[attempt], e);
            }
        }
    }

    /**
     * Whether a refused move may be another program holding the file open:
     * Windows reports that as access denied, or, for a file in use, as a
     * plain {@link FileSystemException}.
     */
    private static boolean mayBeHeldOpen(IOException e, Path target) {
        boolean heldOpenError = e instanceof AccessDeniedException
                || e.getClass() == FileSystemException.class;
        return heldOpenError && !Files.isDirectory(target)
                && (Files.notExists(target) || Files.isWritable(target));
    }

    private static void pause(long millis, IOException refusal) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw refusal;
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Best-effort tightening of an already-existing file to 0600 on POSIX, so
     * a config written by an older build (0644) stops leaking on load without
     * waiting for the next save. No-op off POSIX, or when no regular file is
     * there: whatever else stands at a config file's path (a directory) is
     * not the app's to change, and the store reports it as unopenable.
     */
    public static void restrictExisting(Path file) {
        if (!POSIX || !Files.isRegularFile(file)) {
            return;
        }
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException e) {
            log.debug("Could not restrict permissions on {}: {}", file, e.getMessage());
        }
    }

    /**
     * Whether {@code dir} is a directory of this user's that nobody else can
     * enter, owner-only {@code rwx}: what a system folder must be before a
     * session's files go into it, since anyone who could write in it could
     * swap the configuration a root core is about to read. False off POSIX
     * and whenever it cannot be told.
     */
    static boolean isPrivateDir(Path dir) {
        return isPrivateDir(dir, System.getProperty("user.name"));
    }

    /**
     * {@link #isPrivateDir(Path)} for the user named {@code userName}; a seam
     * for tests, which cannot hand a folder to another user without root.
     */
    static boolean isPrivateDir(Path dir, String userName) {
        if (!POSIX) {
            return false;
        }
        try {
            UserPrincipal user = dir.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(userName);
            return Files.isDirectory(dir)
                    && Files.getOwner(dir).equals(user)
                    && Files.getPosixFilePermissions(dir)
                            .equals(PosixFilePermissions.fromString("rwx------"));
        } catch (IOException | RuntimeException e) {
            log.debug("Could not tell whether {} is private: {}", dir, e.toString());
            return false;
        }
    }
}
