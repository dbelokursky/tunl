package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.platform.PlatformPaths;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The configuration a session hands the core carries every configured
 * server's credential in plain text. It was written to the system temp dir,
 * and only a clean stop in the same JVM deleted it, so every crash or kill
 * left one more copy in a folder Windows never empties.
 */
class SingBoxEngineSessionConfigTest {

    /**
     * The run folder, which each OS keeps private and out of backups and
     * roaming profiles (PlatformPathsTest); the data dir's run folder, which
     * this used to pin, is backed up by Time Machine and Déjà Dup and roams
     * on Windows. The suite redirects the data dir, and the run folder with
     * it.
     */
    @Test
    void sessionConfigsLiveInTheRunFolder() {
        assertThat(SingBoxEngine.sessionConfigDir()).isEqualTo(PlatformPaths.current().runDir());
    }

    @Test
    void aConfigAnEarlierRunLeftBehindIsRemovedAtTheNextStart(@TempDir Path tmp)
            throws Exception {
        Path dir = tmp.resolve("run");
        Files.createDirectories(dir);
        Path orphan = Files.writeString(dir.resolve("singbox-123.json"), "{\"uuid\":\"secret\"}");
        Path stopSignal = Files.writeString(dir.resolve("stop-ab.signal"), "");

        Path written = SingBoxEngine.writeSessionConfig(dir, "{\"log\":{}}");

        assertThat(orphan).doesNotExist();
        assertThat(stopSignal).as("only configurations are swept").exists();
        assertThat(written.getParent()).isEqualTo(dir);
        assertThat(Files.readString(written)).isEqualTo("{\"log\":{}}");
    }

    @Test
    void theConfigIsReadableByItsOwnerAlone(@TempDir Path tmp) throws Exception {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Path dir = tmp.resolve("run");

        Path written = SingBoxEngine.writeSessionConfig(dir, "{}");

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
                .isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(written)))
                .isEqualTo("rw-------");
    }
}
