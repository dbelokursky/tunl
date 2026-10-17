package com.vlessclient.platform;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stop signal goes where the session's configuration goes, the run
 * folder: whoever can create it there ends the tunnel, so the folder has to
 * be the user's alone wherever the OS puts it.
 */
class StopSignalsTest {

    @Test
    void aStopSignalIsAFreshNameInTheOwnerOnlyRunFolder() throws Exception {
        Path first = StopSignals.newStopSignalFile();
        Path second = StopSignals.newStopSignalFile();

        assertThat(first.getParent()).isEqualTo(PlatformPaths.current().runDir());
        assertThat(first).doesNotExist();
        assertThat(second).isNotEqualTo(first);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(first.getParent()))).isEqualTo("rwx------");
        }
    }
}
