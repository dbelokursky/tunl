package com.vlessclient.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    @TempDir
    Path logDir;

    @Test
    void aSigtermWritesTheTrafficCountedSinceTheLastFlush() throws Exception {
        stopTheProbeWithSigterm();

        Path history = dataDir.resolve("traffic-history.json");
        assertThat(history).exists();
        assertThat(Files.readString(history)).contains("sigterm-probe").contains("60000");
    }

    /**
     * The probe logged into the developer's own log, the one the installed
     * app writes: surefire keeps the tests' directories out of the real
     * profile, and its settings do not reach a JVM a test starts. There it
     * logged an ERROR at every run, because the hook's save could not tell
     * the settings page about itself in a process that never started JavaFX.
     */
    @Test
    void theProbeLogsIntoTheTestsDirectoryAndSavesWithoutAnError() throws Exception {
        stopTheProbeWithSigterm();

        Path log = logDir.resolve("tunl.log");
        assertThat(log).as("the probe's log, in the directory the test gave it").exists();
        assertThat(Files.readAllLines(log, StandardCharsets.UTF_8))
                .noneMatch(line -> line.contains(" ERROR "));
    }

    private void stopTheProbeWithSigterm() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(List.of(java.toString()));
        // What surefire redirects for this JVM, redirected for the probe's too.
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith("vless.") && !name.equals("vless.log.dir")) {
                command.add("-D" + name + "=" + System.getProperty(name));
            }
        }
        command.add("-Dvless.log.dir=" + logDir);
        command.addAll(List.of("-cp", System.getProperty("java.class.path"),
                ShutdownHookProbe.class.getName(), dataDir.toString()));
        Process probe = new ProcessBuilder(command).redirectErrorStream(true).start();
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
    }
}
