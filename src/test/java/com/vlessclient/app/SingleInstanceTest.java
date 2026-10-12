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

    /**
     * The lock was taken, then the loopback listener or its port file failed,
     * and the failure path let the lock go: this copy ran on unguarded while
     * the directory looked free. A later launch took it, and its startup reap
     * ended this copy's core. The listener only lets a second launch bring
     * this window forward; failing that must not cost the lock.
     */
    @Test
    void aListenerThatCannotStartKeepsTheLock() throws Exception {
        // A directory where the port file goes: writing the file fails.
        Files.createDirectories(dir.resolve(SingleInstance.PORT_FILE));

        assertThat(SingleInstance.claim(dir)).isEqualTo(SingleInstance.Claim.CLAIMED);

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

    /**
     * The running copy let go of the directory from a shutdown hook, while
     * its other hooks still stopped the core and wrote the traffic history:
     * a launch right after a SIGTERM took the directory, loaded the files
     * and then had its state overwritten by the old copy's last writes. The
     * lock now stays until the process has ended.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({
        org.junit.jupiter.api.condition.OS.MAC, org.junit.jupiter.api.condition.OS.LINUX})
    void aCopyBeingStoppedHoldsTheDirectoryUntilItsProcessEnds() throws Exception {
        Process running = new ProcessBuilder(probeCommand(dir, "hold"))
                .redirectErrorStream(true).start();
        try {
            try (var out = new java.io.BufferedReader(new java.io.InputStreamReader(
                    running.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                do {
                    line = out.readLine();
                } while (line != null && !line.strip().equals("ready"));
                assertThat(line).as("the running copy took the directory").isNotNull();

                running.destroy();
                Thread.sleep(1_000);

                assertThat(running.isAlive()).as("still in its shutdown hooks").isTrue();
                assertThat(SingleInstance.claim(dir))
                        .as("a launch while the old copy still writes")
                        .isEqualTo(SingleInstance.Claim.HELD_ELSEWHERE);
            }
            assertThat(running.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(SingleInstance.claim(dir)).as("once it has gone")
                    .isEqualTo(SingleInstance.Claim.CLAIMED);
        } finally {
            running.destroyForcibly();
        }
    }

    /**
     * A copy that is quitting stops answering, and its directory frees once
     * its process ends. A launch then signalled nobody, or a copy that never
     * showed the window, and left: a Quit from the tray followed by a launch
     * left no Tunl running. The launch now waits for the directory.
     */
    @Test
    void aLaunchWhileTheRunningCopyQuitsStartsOnceItHasGone() throws Exception {
        SingleInstance quitting = SingleInstance.acquire(dir).orElseThrow();
        quitting.stopListening();
        assertThat(SingleInstance.signalRunning(dir)).as("a quitting copy answers no one")
                .isFalse();
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            quitting.close();
        });

        assertThat(SingleInstance.claimOrHandOver(dir, null, java.time.Duration.ofSeconds(10)))
                .isEqualTo(SingleInstance.Claim.CLAIMED);
    }

    /** A copy that answers keeps the launch's request, and the launch leaves at once. */
    @Test
    void aLaunchHandsItselfToACopyThatAnswers() throws Exception {
        SingleInstance running = SingleInstance.acquire(dir).orElseThrow();
        CountDownLatch shown = new CountDownLatch(1);
        running.onShowRequest(shown::countDown);

        assertThat(SingleInstance.claimOrHandOver(dir, null, java.time.Duration.ofSeconds(10)))
                .isEqualTo(SingleInstance.Claim.HELD_ELSEWHERE);
        assertThat(shown.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void withNoCopyRunningASignalReachesNobody() {
        assertThat(SingleInstance.signalRunning(dir)).isFalse();
    }

    /** Starts a real second JVM, as a second launch of the app would be. */
    private static String launchSecondCopy(Path dataDir) throws Exception {
        List<String> command = probeCommand(dataDir);
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

    /** The command line of a probe JVM working in {@code dataDir}. */
    private static List<String> probeCommand(Path dataDir, String... args) {
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
        command.addAll(List.of(args));
        return command;
    }

    /**
     * A tunl:// link a second launch was started with goes to the running
     * copy, which shows its window and opens it.
     */
    @Test
    void aSecondLaunchWithALinkHandsItToTheRunningCopy() throws Exception {
        SingleInstance running = SingleInstance.acquire(dir).orElseThrow();
        CountDownLatch shown = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> opened =
                new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch openedOne = new CountDownLatch(1);
        running.onShowRequest(shown::countDown);
        running.onOpenRequest(link -> {
            opened.set(link);
            openedOne.countDown();
        });
        String link = "tunl://install-config?url=https%3A%2F%2Fsub.example%2F%D1%84";

        assertThat(SingleInstance.signalRunning(dir, link)).isTrue();

        assertThat(openedOne.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(opened.get()).isEqualTo(link);
        assertThat(shown.await(1, TimeUnit.SECONDS)).as("and comes forward").isTrue();
    }

    @Test
    void aLinkOutsideTunlsSchemeIsNotOpened() throws Exception {
        SingleInstance running = SingleInstance.acquire(dir).orElseThrow();
        CountDownLatch opened = new CountDownLatch(1);
        running.onOpenRequest(link -> opened.countDown());
        String token = Files.readString(dir.resolve(SingleInstance.PORT_FILE)).strip()
                .split(" ")[1];
        int port = Integer.parseInt(
                Files.readString(dir.resolve(SingleInstance.PORT_FILE)).strip().split(" ")[0]);

        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
                OutputStream out = socket.getOutputStream()) {
            out.write((token + " open https://sub.example/x\n")
                    .getBytes(StandardCharsets.UTF_8));
        }

        assertThat(opened.await(1, TimeUnit.SECONDS)).isFalse();
    }
}
