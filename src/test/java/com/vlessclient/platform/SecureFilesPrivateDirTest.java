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

    /**
     * Owner-only, but another user's: a runtime dir inherited through su
     * without "-". Taken for this user's, the run folder could not even be
     * made in it, and every connect failed.
     */
    @Test
    void anOwnerOnlyFolderOfAnotherUsersIsNot(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectory(tmp.resolve("runtime"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        String owner = System.getProperty("user.name");
        String other = "root".equals(owner) ? "nobody" : "root";
        // A user that could not be looked up would read as "not private" as
        // well, and say nothing about the owner.
        assertThat(dir.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(other)).isNotEqualTo(Files.getOwner(dir));

        assertThat(SecureFiles.isPrivateDir(dir, other)).as("for %s", other).isFalse();
        assertThat(SecureFiles.isPrivateDir(dir, owner)).as("for its owner").isTrue();
    }

    /** The same by the OS paths, on a folder that is another user's: only root can make one. */
    @Test
    void aRuntimeOrTemporaryFolderOfAnotherUsersKeepsTheDataDirsRunFolder(@TempDir Path tmp)
            throws Exception {
        assumeTrue("root".equals(System.getProperty("user.name")),
                "handing a folder to another user takes root");
        Path theirs = Files.createDirectory(tmp.resolve("run user 1000"));
        Files.setPosixFilePermissions(theirs, PosixFilePermissions.fromString("rwx------"));
        Files.setOwner(theirs, theirs.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName("nobody"));
        LinuxPlatformPaths linux = new LinuxPlatformPaths(tmp.resolve("home"), null,
                theirs.toString());
        MacPlatformPaths mac = new MacPlatformPaths(theirs);

        assertThat(SecureFiles.isPrivateDir(theirs)).isFalse();
        assertThat(linux.runDir()).isEqualTo(linux.dataDir().resolve("run"));
        assertThat(mac.runDir()).isEqualTo(mac.dataDir().resolve("run"));
    }
}
