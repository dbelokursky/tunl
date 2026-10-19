package com.vlessclient.platform;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        if (posix()) {
            assertThat(PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(first.getParent()))).isEqualTo("rwx------");
        }
    }

    /**
     * By each OS's own paths. The suite's redirected data dir keeps its run
     * folder in the data dir, so the test above passed just the same with the
     * stop signal back in the data dir's run folder, beside what backups copy
     * and Windows roams.
     */
    @Test
    void aStopSignalGoesInTheRunFolderOutOfTheDataDir(@TempDir Path tmp) throws Exception {
        List<PlatformPaths> oses = new ArrayList<>(List.of(
                new WindowsPlatformPaths(tmp.resolve("Roaming"), tmp.resolve("Local"))));
        if (posix()) {
            oses.add(new LinuxPlatformPaths(tmp.resolve("home"), null,
                    privateDir(tmp.resolve("run user 1000")).toString()));
            oses.add(new MacPlatformPaths(privateDir(tmp.resolve("T"))));
        }

        for (PlatformPaths paths : oses) {
            Path stopSignal = StopSignals.newStopSignalFile(paths);

            assertThat(stopSignal.getParent()).as("the run folder of %s", paths.dataDir())
                    .isEqualTo(paths.runDir());
            assertThat(stopSignal.startsWith(paths.dataDir()))
                    .as("in the data dir %s", paths.dataDir()).isFalse();
            assertThat(stopSignal).doesNotExist();
            if (posix()) {
                assertThat(PosixFilePermissions.toString(
                        Files.getPosixFilePermissions(stopSignal.getParent())))
                        .isEqualTo("rwx------");
            }
        }
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static Path privateDir(Path dir) throws Exception {
        Files.createDirectory(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        return dir;
    }
}
