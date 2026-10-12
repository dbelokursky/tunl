package com.vlessclient.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The UI graphs start from an empty data dir, whatever an earlier, stopped
 * run left there, and nothing but the test data dir is ever emptied.
 */
class UiTestDataDirTest {

    @TempDir
    Path tmp;

    @Test
    void whatAStoppedRunLeftIsEmptiedAway() throws IOException {
        Path dir = Files.createDirectory(tmp.resolve("test-data-dir"));
        Files.writeString(dir.resolve("servers.json"), "{\"servers\":[{\"name\":\"left\"}]}");
        Files.createDirectories(dir.resolve("logs"));
        Files.writeString(dir.resolve("logs").resolve("app.log"), "line");

        assertThat(UiTestServices.emptyTestDataDir(dir)).isTrue();

        assertThat(dir).isEmptyDirectory();
    }

    @Test
    void aDirectoryThatIsNotTheTestOneIsNeverEmptied() throws IOException {
        Path profile = Files.createDirectory(tmp.resolve("VlessClient"));
        Files.writeString(profile.resolve("servers.json"), "{\"servers\":[]}");

        assertThat(UiTestServices.emptyTestDataDir(profile)).isFalse();

        assertThat(profile.resolve("servers.json")).exists();
    }

    @Test
    void surefireBuildsTheGraphsOnTheTestOne() {
        String dataDir = System.getProperty("vless.data.dir");

        assertThat(dataDir).isNotNull();
        assertThat(Path.of(dataDir).getFileName().toString()).startsWith("test-data-dir");
    }
}
