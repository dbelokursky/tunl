package com.vlessclient.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A SIGTERM — a {@code kill}, a Linux session ending — never reaches
 * JavaFX's {@code stop()}, and the shutdown hook only stopped MCP: the
 * traffic counted since the history's last flush, up to a minute of it, was
 * lost with the process. Runs the app's own hook in a JVM of its own and
 * stops that JVM the way the system would.
 *
 * <p>macOS and Linux only: on Windows {@link Process#destroy()} ends the
 * process outright, and no hook runs at all.</p>
 */
@EnabledOnOs({OS.MAC, OS.LINUX})
class SigtermSavesStateTest {

    @TempDir
    Path dataDir;

    @Test
    void aSigtermWritesTheTrafficCountedSinceTheLastFlush() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process probe = new ProcessBuilder(java.toString(), "-cp",
                System.getProperty("java.class.path"),
                ShutdownHookProbe.class.getName(), dataDir.toString())
                .redirectErrorStream(true)
                .start();
        try (BufferedReader out = new BufferedReader(
                new InputStreamReader(probe.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            do {
                line = out.readLine();
            } while (line != null && !line.equals("ready"));
            assertThat(line).as("the probe got as far as waiting").isEqualTo("ready");

            probe.destroy();   // SIGTERM
            assertThat(probe.waitFor(30, TimeUnit.SECONDS)).as("the probe stopped").isTrue();
        } finally {
            probe.destroyForcibly();
        }

        Path history = dataDir.resolve("traffic-history.json");
        assertThat(history).exists();
        assertThat(Files.readString(history)).contains("sigterm-probe").contains("60000");
    }
}
