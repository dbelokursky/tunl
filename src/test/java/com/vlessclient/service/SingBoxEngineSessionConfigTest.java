package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vlessclient.model.ProxyMode;
import com.vlessclient.platform.OsPaths;
import com.vlessclient.platform.PlatformPaths;
import com.vlessclient.platform.TunLauncher;
import com.vlessclient.testing.FxToolkitExtension;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * The configuration a session hands the core carries every configured
 * server's credential in plain text. It was written to the system temp dir,
 * and only a clean stop in the same JVM deleted it, so every crash or kill
 * left one more copy in a folder Windows never empties.
 */
@ExtendWith(FxToolkitExtension.class)
class SingBoxEngineSessionConfigTest {

    private static final String CONFIG = "{\"log\":{\"level\":\"info\"}}";
    private static final String AT_THE_PROMPT = "the test ends the start at its prompt";

    /**
     * The run folder, which each OS keeps private and out of backups and
     * roaming profiles (PlatformPathsTest); the data dir's run folder, which
     * this used to pin, is backed up by Time Machine and Déjà Dup and roams
     * on Windows. The suite redirects the data dir, and the run folder with
     * it.
     */
    @Test
    void sessionConfigsLiveInTheRunFolder() {
        assertThat(SingBoxEngine.withoutCore().sessionConfigDir())
                .isEqualTo(PlatformPaths.current().runDir());
    }

    /**
     * Where a session start puts it, by each OS's own paths. The suite's
     * redirected data dir keeps its run folder in the data dir, so the test
     * above passed just the same with the engine back on the data dir's run
     * folder, the one backups copy and Windows roams.
     */
    @Test
    void aSessionStartWritesItsConfigurationInTheRunFolderOutOfTheDataDir(@TempDir Path tmp)
            throws Exception {
        for (PlatformPaths[] copies : copiesSharingTheirOsLocation(tmp)) {
            for (PlatformPaths paths : copies) {
                AtThePrompt launch = new AtThePrompt(() -> { });

                start(engineOn(paths, launch, tmp));

                assertThat(launch.config.getParent())
                        .as("the run folder of %s", paths.dataDir()).isEqualTo(paths.runDir());
                assertThat(launch.config.startsWith(paths.dataDir()))
                        .as("in the data dir %s", paths.dataDir()).isFalse();
                assertThat(launch.stillThere).as("what the elevated core reads").isTrue();
            }
        }
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
        if (!posix()) {
            return;
        }
        Path dir = tmp.resolve("run");

        Path written = SingBoxEngine.writeSessionConfig(dir, "{}");

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
                .isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(written)))
                .isEqualTo("rw-------");
    }

    /**
     * Two data dirs per OS whose run folders are in one OS location: Windows
     * copies with an {@code APPDATA} each and the profile's
     * {@code LOCALAPPDATA}, Linux profiles chosen by {@code XDG_DATA_HOME}
     * in one private runtime dir.
     */
    private static List<PlatformPaths[]> copiesSharingTheirOsLocation(Path tmp)
            throws IOException {
        List<PlatformPaths[]> copies = new ArrayList<>();
        Path local = tmp.resolve("Local");
        copies.add(new PlatformPaths[] {
            OsPaths.windows(tmp.resolve("a").resolve("Roaming"), local),
            OsPaths.windows(tmp.resolve("b").resolve("Roaming"), local)});
        if (posix()) {
            Path runtime = Files.createDirectory(tmp.resolve("run user 1000"));
            Files.setPosixFilePermissions(runtime, PosixFilePermissions.fromString("rwx------"));
            Path home = tmp.resolve("home");
            copies.add(new PlatformPaths[] {
                OsPaths.linux(home, tmp.resolve("profile a").toString(), runtime.toString()),
                OsPaths.linux(home, tmp.resolve("profile b").toString(), runtime.toString())});
        }
        return copies;
    }

    /**
     * An engine on {@code paths} whose TUN launches go to {@code launch}. Its
     * core is not there, so the check the core runs before each launch cannot
     * run, and the start goes on without it.
     */
    private static SingBoxEngine engineOn(PlatformPaths paths, TunLauncher launch, Path tmp) {
        SingBoxEngine engine = new SingBoxEngine(tmp.resolve("no core").resolve("sing-box"));
        engine.setPaths(paths);
        engine.setTunLauncher(launch);
        return engine;
    }

    /** A TUN start that ends at its elevation prompt. */
    private static void start(SingBoxEngine engine) {
        assertThatThrownBy(() -> engine.start(CONFIG, ProxyMode.TUN))
                .isInstanceOf(IOException.class)
                .hasMessage(AT_THE_PROMPT);
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /**
     * A TUN launch that goes no further than its elevation prompt: it notes
     * the configuration it was handed, runs what happens while the prompt is
     * up, notes whether the file is still there for the core, and fails.
     */
    private static final class AtThePrompt implements TunLauncher {

        private final Runnable whileItIsUp;
        private Path config;
        private boolean stillThere;

        AtThePrompt(Runnable whileItIsUp) {
            this.whileItIsUp = whileItIsUp;
        }

        @Override
        public Launched launch(Path binary, Path configFile, Prompt prompt) throws IOException {
            config = configFile;
            whileItIsUp.run();
            stillThere = Files.exists(configFile);
            throw new IOException(AT_THE_PROMPT);
        }
    }
}
