package com.vlessclient.service;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.platform.PlatformPaths;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Each session's configuration carries every configured server's credential
 * in plain text, and a run that did not end cleanly left its file where it
 * was written: up to 1.24 the system temp dir, which Windows never empties,
 * and in 1.25 the data dir's run folder, which backups copy. The sweep a
 * session start runs only looks in the folder it writes to now.
 */
class EarlierSessionConfigsTest {

    private static final String UUID = "b1c2d3e4-f5a6-7890-abcd-ef1234567890";

    @Test
    void aConfigAnEarlierReleaseLeftInTheTempDirIsRemoved(@TempDir Path tmp) throws Exception {
        Path dataDir = Files.createDirectories(tmp.resolve("data"));
        Path tempDir = Files.createDirectories(tmp.resolve("temp"));
        // Named the way 1.24 and every release before it named them.
        Path left = Files.writeString(Files.createTempFile(tempDir, "singbox-", ".json"),
                configOf(dataDir));

        int removed = EarlierSessionConfigs.remove(tempDir, dataDir, runtimeDir(tmp));

        assertThat(left).as("a 1.24 session config in the temp dir").doesNotExist();
        assertThat(removed).isEqualTo(1);
    }

    /** The configuration as the generator writes it, on every OS's paths. */
    @Test
    void theGeneratorsOwnConfigurationIsRecognised(@TempDir Path tmp) throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.VLESS);
        server.setAddress("203.0.113.10");
        server.setPort(443);
        server.setUuid(UUID);
        String config = new SingBoxConfigGenerator().generate(server, new AppSettings());
        Path tempDir = Files.createDirectories(tmp.resolve("temp"));
        Path left = Files.writeString(tempDir.resolve("singbox-8045171633040447385.json"), config);

        EarlierSessionConfigs.remove(tempDir, PlatformPaths.current().dataDir(), runtimeDir(tmp));

        assertThat(left).doesNotExist();
    }

    @Test
    void aConfig125LeftInTheDataDirsRunFolderIsRemovedWithTheFolder(@TempDir Path tmp)
            throws Exception {
        Path dataDir = tmp.resolve("data");
        Path oldRun = Files.createDirectories(dataDir.resolve("run"));
        Path left = Files.writeString(oldRun.resolve("singbox-4242.json"), configOf(dataDir));

        int removed = EarlierSessionConfigs.remove(tmp.resolve("temp"), dataDir, runtimeDir(tmp));

        assertThat(left).as("a 1.25 session config in the data dir").doesNotExist();
        assertThat(oldRun).as("nothing of a session's is left in the data dir").doesNotExist();
        assertThat(removed).isEqualTo(1);
    }

    @Test
    void aStopSignal125LeftKeepsItsFolder(@TempDir Path tmp) throws Exception {
        Path dataDir = tmp.resolve("data");
        Path oldRun = Files.createDirectories(dataDir.resolve("run"));
        Path config = Files.writeString(oldRun.resolve("singbox-4242.json"), configOf(dataDir));
        Path stopSignal = Files.writeString(oldRun.resolve("stop-ab.signal"), "");

        EarlierSessionConfigs.remove(tmp.resolve("temp"), dataDir, runtimeDir(tmp));

        assertThat(config).doesNotExist();
        assertThat(stopSignal).as("only configurations are swept").exists();
    }

    /**
     * The temp dir is shared: on Linux with every user of the machine, and
     * everywhere with every other program, another sing-box front end among
     * them. Only a file this app's session wrote is removed: named as the
     * engine names them, a regular file, and pointing its cache into this
     * data dir, where every release has kept it.
     */
    @Test
    void whatCannotBeToldForThisAppsSessionConfigStays(@TempDir Path tmp) throws Exception {
        Path dataDir = Files.createDirectories(tmp.resolve("data"));
        Path tempDir = Files.createDirectories(tmp.resolve("temp"));
        List<Path> others = List.of(
                Files.writeString(tempDir.resolve("singbox-config.json"), configOf(dataDir)),
                Files.writeString(tempDir.resolve("singbox-12.json.bak"), configOf(dataDir)),
                Files.writeString(tempDir.resolve("sing-box-13.json"), configOf(dataDir)),
                // Another data dir's: another user's, or another copy's.
                Files.writeString(tempDir.resolve("singbox-7.json"),
                        configOf(tmp.resolve("another").resolve("vless-client"))),
                // Another front end's, with no cache of this app's.
                Files.writeString(tempDir.resolve("singbox-8.json"),
                        "{\"outbounds\":[{\"type\":\"vless\",\"uuid\":\"" + UUID + "\"}]}"),
                Files.writeString(tempDir.resolve("singbox-9.json"),
                        configOf(dataDir).substring(0, 80)),
                Files.writeString(tempDir.resolve("singbox-10.json"), "not json"),
                Files.createDirectory(tempDir.resolve("singbox-11.json")));

        int removed = EarlierSessionConfigs.remove(tempDir, dataDir, runtimeDir(tmp));

        assertThat(removed).isZero();
        assertThat(others).allMatch(Files::exists);
    }

    @Test
    void aLinkNamedLikeOneStaysAndSoDoesWhatItPointsAt(@TempDir Path tmp) throws Exception {
        assumePosix();
        Path dataDir = Files.createDirectories(tmp.resolve("data"));
        Path tempDir = Files.createDirectories(tmp.resolve("temp"));
        Path target = Files.writeString(tmp.resolve("elsewhere.json"), configOf(dataDir));
        Path link = Files.createSymbolicLink(tempDir.resolve("singbox-14.json"), target);

        EarlierSessionConfigs.remove(tempDir, dataDir, runtimeDir(tmp));

        assertThat(link).exists();
        assertThat(target).exists();
    }

    /** Another user's file in a shared /tmp is left unread: only root can make one here. */
    @Test
    void aFileAnotherUserOwnsStays(@TempDir Path tmp) throws Exception {
        assumePosix();
        assumeTrue("root".equals(System.getProperty("user.name")),
                "handing a file to another user takes root");
        Path dataDir = Files.createDirectories(tmp.resolve("data"));
        Path tempDir = Files.createDirectories(tmp.resolve("temp"));
        Path theirs = Files.writeString(tempDir.resolve("singbox-15.json"), configOf(dataDir));
        Files.setOwner(theirs, tempDir.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName("nobody"));

        EarlierSessionConfigs.remove(tempDir, dataDir, runtimeDir(tmp));

        assertThat(theirs).exists();
    }

    /**
     * Where this release writes them is the session sweep's: the sweep runs
     * at startup beside an auto-connect, whose core may be about to read its
     * configuration once an elevation prompt is answered. The data dir's run
     * folder is the current one where no private runtime dir is found, and
     * whenever the data dir is redirected.
     */
    @Test
    void theRunFolderInUseIsLeftAlone(@TempDir Path tmp) throws Exception {
        Path dataDir = tmp.resolve("data");
        Path runDir = Files.createDirectories(dataDir.resolve("run"));
        Path live = Files.writeString(runDir.resolve("singbox-16.json"), configOf(dataDir));

        EarlierSessionConfigs.remove(tmp.resolve("temp"), dataDir, runDir);
        // Nor when java.io.tmpdir is pointed at it.
        EarlierSessionConfigs.remove(runDir, dataDir, runDir);

        assertThat(live).exists();
    }

    /** macOS's run folder is inside the temp dir: only the temp dir's own files are 1.24's. */
    @Test
    void aRunFolderInsideTheTempDirIsLeftAlone(@TempDir Path tmp) throws Exception {
        Path dataDir = Files.createDirectories(tmp.resolve("data"));
        Path tempDir = tmp.resolve("T");
        Path runDir = Files.createDirectories(tempDir.resolve("VlessClient").resolve("run"));
        Path live = Files.writeString(runDir.resolve("singbox-17.json"), configOf(dataDir));

        EarlierSessionConfigs.remove(tempDir, dataDir, runDir);

        assertThat(live).exists();
    }

    /**
     * A session configuration as every release has written one: the
     * credentials early on, the cache file in the data dir at the end.
     */
    private static String configOf(Path dataDir) {
        String cache = JsonMapper.builder().build().writeValueAsString(
                dataDir.resolve("cache").resolve("sing-box-tun.db").toString());
        return "{\"log\":{\"level\":\"info\"},"
                + "\"outbounds\":[{\"type\":\"vless\",\"tag\":\"proxy\","
                + "\"server\":\"203.0.113.10\",\"uuid\":\"" + UUID + "\"}],"
                + "\"route\":{\"rules\":[{\"action\":\"sniff\"}],\"final\":\"proxy\"},"
                + "\"experimental\":{\"clash_api\":{\"external_controller\":\"127.0.0.1:9090\","
                + "\"secret\":\"s\"},\"cache_file\":{\"enabled\":true,\"path\":" + cache + "}}}";
    }

    /** The run folder somewhere else, as on a system with a private one. */
    private static Path runtimeDir(Path tmp) throws IOException {
        return Files.createDirectories(tmp.resolve("runtime").resolve("vless-client"));
    }

    private static void assumePosix() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX links and owners");
    }
}
