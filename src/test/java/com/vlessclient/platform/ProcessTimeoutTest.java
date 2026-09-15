package com.vlessclient.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A command that neither exits nor closes its output used to hold its caller
 * for as long as it ran: both runners read the output to the end before their
 * timed wait began, so the timeout never fired. A command stuck on a prompt
 * nobody answers is such a command, and the app unseals its credentials before
 * its window first appears.
 */
class ProcessTimeoutTest {

    /** Short, but long enough for a stand-in JVM to start and write its pid. */
    private static final Duration SHORT = Duration.ofSeconds(3);
    private static final Duration AMPLE = Duration.ofSeconds(30);

    /** Well past the timeouts used here, and well short of a hanging stand-in's 30 s. */
    private static final Duration CALLER_LIMIT = Duration.ofSeconds(20);

    /** More than a pipe buffer holds, so a runner that waits before it reads deadlocks. */
    private static final int PAST_PIPE_BUFFER = 1024 * 1024;

    @TempDir
    Path dir;

    @Test
    void aCommandThatNeverExitsIsKilledWhenItTimesOut() throws Exception {
        CommandRunner runner = CommandRunner.system(SHORT);
        Path pid = dir.resolve("hang.pid");

        assertTimeoutPreemptively(CALLER_LIMIT, () ->
                assertThatThrownBy(() -> runner.run(standIn("hang", pid.toString())))
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("timed out"));
        assertEnded(pid);
    }

    @Test
    void aCommandThatExitsReturnsItsExitCodeAndOutput() throws IOException {
        CommandRunner.Result result = CommandRunner.system(AMPLE).run(standIn("exit", "3"));

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.output()).contains("started");
    }

    @Test
    void aCommandPrintingMoreThanAPipeHoldsStillFinishes() {
        CommandRunner runner = CommandRunner.system(AMPLE);
        List<String> flood = standIn("flood", String.valueOf(PAST_PIPE_BUFFER));

        assertTimeoutPreemptively(CALLER_LIMIT, () ->
                assertThat(runner.run(flood).output())
                        .hasSizeGreaterThanOrEqualTo(PAST_PIPE_BUFFER));
    }

    /**
     * A command can exit while a child it started still holds its output. The
     * caller then waited out the whole timeout a second time.
     */
    @Test
    void aCommandWhoseChildKeepsItsOutputOpenDoesNotHoldTheCaller() throws Exception {
        Duration timeout = Duration.ofSeconds(10);
        CommandRunner runner = CommandRunner.system(timeout);
        Path pid = dir.resolve("orphan.pid");
        try {
            long started = System.nanoTime();
            assertTimeoutPreemptively(CALLER_LIMIT, () -> {
                try {
                    runner.run(standIn("orphan", pid.toString()));
                } catch (IOException outputStillOpen) {
                    // Either outcome will do; how long it took is the point.
                }
            });
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(timeout);
        } finally {
            killOnceStarted(pid);
        }
    }

    @Test
    void aSecretBackendThatNeverExitsIsKilledWhenItTimesOut() throws Exception {
        SecretSealers.Subprocess backend = SecretSealers.system(SHORT);
        Path pid = dir.resolve("hang.pid");

        assertTimeoutPreemptively(CALLER_LIMIT, () ->
                assertThat(backend.run(argv("hang", pid.toString()), "secret")).isEmpty());
        assertEnded(pid);
    }

    @Test
    void aSecretBackendGetsInputLargerThanAPipeHoldsAndReturnsItsOutput() {
        SecretSealers.Subprocess backend = SecretSealers.system(AMPLE);
        String secret = "s".repeat(PAST_PIPE_BUFFER);

        assertTimeoutPreemptively(CALLER_LIMIT, () ->
                assertThat(backend.run(argv("echo"), secret)).contains(secret));
    }

    @Test
    void aSecretBackendThatTimedOutIsNotRunAgain() throws Exception {
        Path control = dir.resolve("control");
        Path ran = dir.resolve("ran");
        // The run skipped below proves nothing unless a run would have had the
        // time to create its file.
        SecretSealers.system(SHORT).run(argv("touch", control.toString()), null);
        assumeTrue(Files.exists(control), "a stand-in cannot start within " + SHORT + " here");
        SecretSealers.Subprocess backend = SecretSealers.system(SHORT);

        assertTimeoutPreemptively(CALLER_LIMIT, () -> {
            assertThat(backend.run(argv("hang"), null)).isEmpty();
            assertThat(backend.run(argv("touch", ran.toString()), null)).isEmpty();
        });
        assertThat(ran).doesNotExist();
    }

    /** Output a leftover child holds open is no timeout, so later commands still run. */
    @Test
    void aSecretBackendWhoseChildKeepsItsOutputOpenStillRunsTheNextCommand() throws Exception {
        SecretSealers.Subprocess backend = SecretSealers.system(Duration.ofSeconds(10));
        Path pid = dir.resolve("orphan.pid");
        Path ran = dir.resolve("ran");
        try {
            assertTimeoutPreemptively(CALLER_LIMIT, () -> {
                backend.run(argv("orphan", pid.toString()), null);
                backend.run(argv("touch", ran.toString()), null);
            });
            assertThat(ran).exists();
        } finally {
            killOnceStarted(pid);
        }
    }

    /** The stand-in command in a separate JVM, with the given arguments. */
    private static List<String> standIn(String... args) {
        return Stream.concat(
                        Stream.of(ProcessHandle.current().info().command().orElse("java"),
                                "-cp", System.getProperty("java.class.path"),
                                StandInCommand.class.getName()),
                        Stream.of(args))
                .toList();
    }

    private static String[] argv(String... args) {
        return standIn(args).toArray(String[]::new);
    }

    /** The stand-in that wrote {@code pidFile} was killed, not left to run out its 30 s. */
    private static void assertEnded(Path pidFile) throws IOException {
        assumeTrue(Files.exists(pidFile), "the stand-in did not start before its timeout");
        long pid = Long.parseLong(Files.readString(pidFile).strip());
        ProcessHandle.of(pid).ifPresent(process ->
                assertThat(process.onExit()).succeedsWithin(Duration.ofSeconds(5)));
    }

    /** Kills a leftover child once it has written its pid, so it does not outlive the test. */
    private static void killOnceStarted(Path pidFile) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!Files.exists(pidFile) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        if (Files.exists(pidFile)) {
            ProcessHandle.of(Long.parseLong(Files.readString(pidFile).strip()))
                    .ifPresent(ProcessHandle::destroyForcibly);
        }
    }
}
