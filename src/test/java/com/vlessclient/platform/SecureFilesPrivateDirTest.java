package com.vlessclient.platform;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A session's files go into a folder of a system location only where that
 * location is the user's alone ({@code $XDG_RUNTIME_DIR}, macOS's per-user
 * temporary folder): anyone who could write in it could swap the
 * configuration a root core is about to read.
 */
class SecureFilesPrivateDirTest {

    @BeforeEach
    void onPosix() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "owner-only modes are POSIX");
    }

    @Test
    void aFolderOnlyItsOwnerCanEnterIsPrivate(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectory(tmp.resolve("runtime"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));

        assertThat(SecureFiles.isPrivateDir(dir)).isTrue();
    }

    @Test
    void aFolderOthersCanEnterOrThatCannotBeWrittenIsNot(@TempDir Path tmp) throws Exception {
        for (String mode : new String[] {"rwxr-xr-x", "rwx--x---", "rwx-----x", "rwxrwxrwx",
                "r-x------"}) {
            Path dir = Files.createDirectory(tmp.resolve("runtime-" + mode.replace('-', '_')));
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(mode));

            assertThat(SecureFiles.isPrivateDir(dir)).as(mode).isFalse();
        }
    }

    @Test
    void aFileOrNothingIsNot(@TempDir Path tmp) throws Exception {
        Path file = Files.writeString(tmp.resolve("runtime"), "");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));

        assertThat(SecureFiles.isPrivateDir(file)).as("a file").isFalse();
        assertThat(SecureFiles.isPrivateDir(tmp.resolve("missing"))).as("nothing").isFalse();
    }
}
