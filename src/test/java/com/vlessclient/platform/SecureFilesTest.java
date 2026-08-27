package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test
    void parentDirectoryRejectsAFilesystemRoot() {
        Path root = Path.of("").toAbsolutePath().getRoot();

        assertThatThrownBy(() -> SecureFiles.parentDirectory(root))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("does not identify a file");
    }
}
