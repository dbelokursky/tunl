package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecureFilesTest {

    @Test
    void writePrivatelyWritesTheCompleteFile(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("settings.json");

        SecureFiles.writePrivately(target, "settings".getBytes());

        assertThat(target).hasContent("settings");
        assertThat(SecureFiles.parentDirectory(target)).isEqualTo(tempDir.toAbsolutePath());
    }

    /**
     * A megabyte, so the write goes through more than one channel write where
     * a platform takes it in parts, and nothing is left beside the target.
     */
    @Test
    void writePrivatelyWritesALargeFileWholeAndLeavesNoTempFile(@TempDir Path tempDir)
            throws Exception {
        byte[] data = new byte[1 << 20];
        new Random(42).nextBytes(data);
        Path target = tempDir.resolve("traffic-history.json");

        SecureFiles.writePrivately(target, data);

        assertThat(Files.readAllBytes(target)).isEqualTo(data);
        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files).as("the files in the directory").containsExactly(target);
        }
    }

    /**
     * Windows refuses to replace a file another program holds open, a virus
     * scanner reading the file just written, until it lets go a moment later.
     * The first refusal failed the save: on test-windows, servers.json stayed
     * sealed after the keychain box was unticked while the test read it.
     */
    @Test
    void aReplaceRefusedForAMomentIsTriedAgain(@TempDir Path tempDir) throws Exception {
        Path target = Files.writeString(tempDir.resolve("servers.json"), "sealed");
        Path source = Files.writeString(tempDir.resolve("servers.json.tmp"), "plaintext");
        AtomicInteger moves = new AtomicInteger();

        SecureFiles.replace(source, target, (from, to) -> {
            if (moves.incrementAndGet() <= 2) {
                throw new AccessDeniedException(from + " -> " + to);
            }
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }, true);

        assertThat(target).hasContent("plaintext");
        assertThat(moves).hasValue(3);
    }

    /** A file in use comes as a plain FileSystemException, not access denied. */
    @Test
    void aFileInUseIsTriedAgainToo(@TempDir Path tempDir) throws Exception {
        Path target = Files.writeString(tempDir.resolve("settings.json"), "old");
        Path source = Files.writeString(tempDir.resolve("settings.json.tmp"), "new");
        AtomicInteger moves = new AtomicInteger();

        SecureFiles.replace(source, target, (from, to) -> {
            if (moves.incrementAndGet() == 1) {
                throw new FileSystemException(from.toString(), to.toString(),
                        "The process cannot access the file because it is being used by"
                                + " another process");
            }
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }, true);

        assertThat(target).hasContent("new");
        assertThat(moves).hasValue(2);
    }

    /** A first save has no file to replace yet; a scanner may still hold the new one. */
    @Test
    void aRefusedFirstSaveIsTriedAgain(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("routing.json");
        Path source = Files.writeString(tempDir.resolve("routing.json.tmp"), "rules");
        AtomicInteger moves = new AtomicInteger();

        SecureFiles.replace(source, target, (from, to) -> {
            if (moves.incrementAndGet() == 1) {
                throw new AccessDeniedException(from + " -> " + to);
            }
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }, true);

        assertThat(target).hasContent("rules");
        assertThat(moves).hasValue(2);
    }

    /** Held for longer than the tries take, the save fails as it did, with the refusal. */
    @Test
    void aReplaceStillRefusedAfterTheTriesFails(@TempDir Path tempDir) throws Exception {
        Path target = Files.writeString(tempDir.resolve("servers.json"), "sealed");
        Path source = Files.writeString(tempDir.resolve("servers.json.tmp"), "plaintext");
        AtomicInteger moves = new AtomicInteger();

        assertThatThrownBy(() -> SecureFiles.replace(source, target, refusing(moves), true))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(moves).as("the first try and six more").hasValue(7);
        assertThat(target).hasContent("sealed");
    }

    /**
     * A directory where the file belongs (PersistenceFailureTest's blocker)
     * does not go away in a moment: it fails at once, as before.
     */
    @Test
    void aDirectoryWhereTheFileBelongsFailsAtOnce(@TempDir Path tempDir) throws Exception {
        Path target = Files.createDirectory(tempDir.resolve("servers.json"));
        Path source = Files.writeString(tempDir.resolve("servers.json.tmp"), "plaintext");
        AtomicInteger moves = new AtomicInteger();

        assertThatThrownBy(() -> SecureFiles.replace(source, target, refusing(moves), true))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(moves).hasValue(1);
    }

    /** POSIX replaces a file that is open, so a refusal there is a real one. */
    @Test
    void offWindowsARefusalFailsAtOnce(@TempDir Path tempDir) throws Exception {
        Path target = Files.writeString(tempDir.resolve("servers.json"), "sealed");
        Path source = Files.writeString(tempDir.resolve("servers.json.tmp"), "plaintext");
        AtomicInteger moves = new AtomicInteger();

        assertThatThrownBy(() -> SecureFiles.replace(source, target, refusing(moves), false))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(moves).hasValue(1);
    }

    /** No other error is one another program holding the file explains. */
    @Test
    void anotherErrorFailsAtOnce(@TempDir Path tempDir) throws Exception {
        Path target = Files.writeString(tempDir.resolve("servers.json"), "sealed");
        Path source = tempDir.resolve("servers.json.tmp");
        AtomicInteger moves = new AtomicInteger();

        assertThatThrownBy(() -> SecureFiles.replace(source, target, (from, to) -> {
            moves.incrementAndGet();
            throw new NoSuchFileException(from.toString());
        }, true)).isInstanceOf(NoSuchFileException.class);

        assertThat(moves).hasValue(1);
    }

    /** A quit that interrupts the writer gets the refusal at once, still interrupted. */
    @Test
    void anInterruptedWaitGivesUpWithTheRefusal(@TempDir Path tempDir) throws Exception {
        Path target = Files.writeString(tempDir.resolve("servers.json"), "sealed");
        Path source = Files.writeString(tempDir.resolve("servers.json.tmp"), "plaintext");
        AtomicInteger moves = new AtomicInteger();

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> SecureFiles.replace(source, target, refusing(moves), true))
                    .isInstanceOf(AccessDeniedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        assertThat(moves).hasValue(1);
    }

    private static SecureFiles.Mover refusing(AtomicInteger moves) {
        return (from, to) -> {
            moves.incrementAndGet();
            throw new AccessDeniedException(from + " -> " + to);
        };
    }

    @Test
    void parentDirectoryRejectsAFilesystemRoot() {
        Path root = Path.of("").toAbsolutePath().getRoot();

        assertThatThrownBy(() -> SecureFiles.parentDirectory(root))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("does not identify a file");
    }
}
