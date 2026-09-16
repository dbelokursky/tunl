package com.vlessclient.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A second launch of the app used to start a second copy against the same
 * data directory. Its startup cleanup saw its own idle engine, took the running
 * copy's system proxy for one a crash had left behind, and turned it off: the
 * browser went around the tunnel while the first window still said Connected.
 * Both copies then wrote the same JSON files from their own memory.
 */
class SingleInstanceTest {

    @TempDir
    Path dir;

    @AfterEach
    void releaseTheDirectory() {
        SingleInstance.current().ifPresent(SingleInstance::close);
    }

    @Test
    void theFirstLaunchTakesTheDataDirectory() {
        assertThat(SingleInstance.acquire(dir)).isPresent();
    }

    @Test
    void aSecondLaunchInAnotherProcessIsRefusedWhileTheFirstRuns() throws Exception {
        assertThat(SingleInstance.acquire(dir)).isPresent();

        assertThat(launchSecondCopy(dir)).isEqualTo("refused");
    }

    @Test
    void theDirectoryIsFreeAgainOnceTheRunningCopyStops() throws Exception {
        SingleInstance.acquire(dir).orElseThrow().close();

        assertThat(launchSecondCopy(dir)).isEqualTo("acquired");
    }

    @Test
    void aSecondLaunchAsksTheRunningCopyToShowItsWindow() throws Exception {
        SingleInstance running = SingleInstance.acquire(dir).orElseThrow();
        CountDownLatch shown = new CountDownLatch(1);
        running.onShowRequest(shown::countDown);

        assertThat(SingleInstance.signalRunning(dir)).isTrue();
        assertThat(shown.await(5, TimeUnit.SECONDS)).isTrue();
    }

    /** Anything else on this machine can reach a loopback port; only the token opens it. */
    @Test
    void aRequestWithoutTheTokenIsIgnored() throws Exception {
        SingleInstance running = SingleInstance.acquire(dir).orElseThrow();
        CountDownLatch shown = new CountDownLatch(1);
        running.onShowRequest(shown::countDown);
        int port = Integer.parseInt(
                Files.readString(dir.resolve(SingleInstance.PORT_FILE)).strip().split(" ")[0]);

        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
                OutputStream out = socket.getOutputStream()) {
            out.write("not-the-token show\n".getBytes(StandardCharsets.US_ASCII));
        }

        assertThat(shown.await(1, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void thePortAndTokenAreReadableByTheOwnerOnly() throws Exception {
        assertThat(SingleInstance.acquire(dir)).isPresent();
        Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions are not a thing on this filesystem");

        assertThat(PosixFilePermissions.toString(
                Files.getPosixFilePermissions(dir.resolve(SingleInstance.PORT_FILE))))
                .isEqualTo("rw-------");
    }

    @Test
    void withNoCopyRunningASignalReachesNobody() {
        assertThat(SingleInstance.signalRunning(dir)).isFalse();
    }

    /** Starts a real second JVM, as a second launch of the app would be. */
    private static String launchSecondCopy(Path dataDir) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        // The test JVM's own redirections (data, logs, core install), so the
        // second copy never touches the developer's real profile.
        System.getProperties().stringPropertyNames().stream()
                .filter(name -> name.startsWith("vless."))
                .forEach(name -> command.add("-D" + name + "=" + System.getProperty(name)));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(SingleInstanceProbe.class.getName());
        command.add(dataDir.toString());
        Path output = Files.createTempFile("single-instance-probe-", ".log");
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("probe finished").isTrue();
            List<String> lines = Files.readAllLines(output);
            return lines.isEmpty() ? "" : lines.getLast().strip();
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
