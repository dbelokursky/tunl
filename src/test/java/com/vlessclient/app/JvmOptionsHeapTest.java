package com.vlessclient.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Runs the launcher's heap options from {@code scripts/java-options.txt} in a
 * JVM of their own and checks that they still do their job: a heap that grew
 * comes back down once the app goes idle.
 *
 * <p>The installer smoke checks only prove that the options reach the
 * launcher. That was not enough: JDK 27 changed G1's default
 * {@code MaxHeapFreeRatio} from 70 to 100, the options named only the minimum,
 * and from then on the heap was never given back. A user's instance held all
 * 512 MB for 46 MB of live data, and every check stayed green.</p>
 */
class JvmOptionsHeapTest {

    private static final long MB = 1024 * 1024;

    /** The probe's live data; the heap has to grow past it. */
    private static final long GROWN_AT_LEAST = 200 * MB;

    /** Twice {@code -Xms}: the options shrink the heap to about -Xms. */
    private static final long IDLE_BOUND_MB = 128;

    @Test
    void theHeapComesBackDownOnceTheAppGoesIdle() throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(launcherOptions());
        // The period the app uses is a minute; the test does not wait that long.
        command.add("-XX:G1PeriodicGCInterval=1000");
        command.addAll(List.of("-cp", System.getProperty("java.class.path"),
                HeapGivesBackProbe.class.getName(), Long.toString(IDLE_BOUND_MB)));

        Process probe = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try {
            output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(probe.waitFor(60, TimeUnit.SECONDS)).as("probe exited").isTrue();
        } finally {
            probe.destroyForcibly();
        }

        assertThat(probe.exitValue()).as("probe exit code, output:%n%s", output).isZero();
        assertThat(reading(output, "grown"))
                .as("committed heap with 200 MB of live data, output:%n%s", output)
                .isGreaterThanOrEqualTo(GROWN_AT_LEAST);
        assertThat(reading(output, "idle"))
                .as("committed heap after the probe went idle, output:%n%s", output)
                .isLessThanOrEqualTo(IDLE_BOUND_MB * MB);
    }

    /** The options, read the way the packaging scripts read them. */
    private static List<String> launcherOptions() throws IOException {
        List<String> options = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("scripts", "java-options.txt"),
                StandardCharsets.UTF_8)) {
            String option = line.replaceFirst("#.*", "").strip();
            if (!option.isEmpty()) {
                options.add(option);
            }
        }
        assertThat(options).as("options in scripts/java-options.txt").isNotEmpty();
        return options;
    }

    private static long reading(String output, String label) {
        return output.lines()
                .filter(line -> line.startsWith(label + " "))
                .mapToLong(line -> Long.parseLong(line.substring(label.length() + 1).strip()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no '" + label + "' line in:\n" + output));
    }
}
