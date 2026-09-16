package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
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

    @Test
    void parentDirectoryRejectsAFilesystemRoot() {
        Path root = Path.of("").toAbsolutePath().getRoot();

        assertThatThrownBy(() -> SecureFiles.parentDirectory(root))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("does not identify a file");
    }
}
