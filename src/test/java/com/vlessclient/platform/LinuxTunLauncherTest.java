package com.vlessclient.platform;

import com.vlessclient.testing.Await;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class LinuxTunLauncherTest {

    /** Records every invocation and replies via a programmed responder. */
    private static final class FakeRunner implements CommandRunner {
        final List<List<String>> calls = new ArrayList<>();
        private final Function<List<String>, CommandRunner.Result> responder;

        FakeRunner(Function<List<String>, CommandRunner.Result> responder) {
            this.responder = responder;
        }

        @Override
        public CommandRunner.Result run(List<String> command) {
            calls.add(List.copyOf(command));
            return responder.apply(command);
        }
    }

    /**
     * Everything a test spawned: the wrapper shell and the fake core under it.
     * Killed forcibly after each test, so a failed assertion cannot leave a
     * "sleep 60" behind — the in-test kill -9 that used to do this ran only
     * on the happy path.
     */
    private final List<ProcessHandle> spawned = new ArrayList<>();

    @AfterEach
    void reapSpawnedProcesses() {
        for (ProcessHandle handle : spawned) {
            handle.descendants().forEach(ProcessHandle::destroyForcibly);
            handle.destroyForcibly();
        }
    }

    @Test
    void capabilityCheck_readsGetcapOutput() {
        FakeRunner caps = new FakeRunner(cmd -> new CommandRunner.Result(0,
                "/home/u/.local/share/vless-client/bin/sing-box cap_net_admin=ep"));

        assertThat(new LinuxTunLauncher(caps, "pkexec")
                .hasNetAdminCapability(Path.of("/home/u/.local/share/vless-client/bin/sing-box")))
                .isTrue();
        assertThat(caps.calls.getFirst().getFirst()).isEqualTo("getcap");
    }

    @Test
    void capabilityCheck_falseWithoutTheCapability() {
        FakeRunner caps = new FakeRunner(cmd -> new CommandRunner.Result(0, ""));

        assertThat(new LinuxTunLauncher(caps, "pkexec")
                .hasNetAdminCapability(Path.of("/opt/sing-box"))).isFalse();
    }

    @Test
    void capabilityCheck_falseWhenGetcapMissing() {
        CommandRunner failing = cmd -> {
            throw new IOException("getcap not found");
        };

        assertThat(new LinuxTunLauncher(failing, "pkexec")
                .hasNetAdminCapability(Path.of("/opt/sing-box"))).isFalse();
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,
            org.junit.jupiter.api.condition.OS.LINUX})
    void wrapperCommand_runsCoreAndWatchesStopFileAndParent() {
        String wrapper = LinuxTunLauncher.wrapperCommand(
                Path.of("/opt/sing-box"), Path.of("/tmp/c.json"), Path.of("/tmp/stop"));

        assertThat(wrapper).contains("'/opt/sing-box' run -c '/tmp/c.json'");
        assertThat(wrapper).contains("[ ! -f '/tmp/stop' ]");
        // Parent watch: a dead app must never leak an elevated core.
        assertThat(wrapper).contains("kill -0 " + ProcessHandle.current().pid());
        // Must trap the signals the engine actually sends (Process.destroy =
        // SIGTERM), not EXIT alone — see wrapperKillsChildOnSigterm.
        assertThat(wrapper).contains("EXIT INT TERM");
        assertThat(wrapper).contains("rm -f '/tmp/stop'");
    }

    /**
     * Regression for the orphaned-core / leaked-TUN bug: SIGTERM to the
     * wrapper shell (what {@code SingBoxEngine.forceStop} sends via
     * {@code Process.destroy()}) must reap the "sing-box" child. An
     * EXIT-only trap is skipped by dash on signal death, leaving the core
     * (and its TUN) running after the app exits. Found via desktop-VM QA.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,
            org.junit.jupiter.api.condition.OS.LINUX})
    void wrapperKillsChildOnSigterm() throws Exception {
        // A fake "core": a shell that sleeps and writes its PID to a file.
        Path pidFile = java.nio.file.Files.createTempFile("fake-core-pid", "");
        Path fakeCore = java.nio.file.Files.createTempFile("fake-core", ".sh");
        java.nio.file.Files.writeString(fakeCore,
                "#!/bin/sh\necho $$ > '" + pidFile + "'\nexec sleep 60\n");
        fakeCore.toFile().setExecutable(true);
        Path stop = java.nio.file.Files.createTempFile("stop", "");
        java.nio.file.Files.deleteIfExists(stop);

        String wrapper = LinuxTunLauncher.wrapperCommand(fakeCore, fakeCore, stop);
        Process sh = new ProcessBuilder("/bin/sh", "-c", wrapper)
                .redirectErrorStream(true).start();
        spawned.add(sh.toHandle());

        // Wait for the fake core to record its PID, then SIGTERM the wrapper.
        long pid = Long.parseLong(Await.untilValue("the fake core to record its pid",
                () -> readQuietly(pidFile), text -> !text.isEmpty(), Duration.ofSeconds(5)));
        // A handle taken while the core is certainly alive carries its start
        // time, so a later destroyForcibly cannot hit a recycled pid.
        ProcessHandle core = ProcessHandle.of(pid)
                .orElseThrow(() -> new AssertionError("fake core " + pid + " already gone"));
        spawned.add(core);

        sh.destroy();   // SIGTERM, exactly like SingBoxEngine.forceStop
        assertThat(sh.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
                .as("wrapper shell should exit after SIGTERM").isTrue();

        // The child must be gone — an orphaned core is the bug. Its death is
        // asynchronous to the wrapper's exit: the trap sends SIGTERM and exits
        // without waiting, so the child can linger in the process table for a
        // beat (briefly as a zombie until PID 1 reaps it). Poll with a
        // deadline instead of sampling once; the orphan bug this guards
        // against keeps the core alive indefinitely, not for milliseconds.
        Await.until("fake core " + pid + " to be reaped, not orphaned",
                () -> !core.isAlive(), Duration.ofSeconds(5));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,
            org.junit.jupiter.api.condition.OS.LINUX})
    void wrapperCommand_shellQuotesPathsWithSpecials() {
        String wrapper = LinuxTunLauncher.wrapperCommand(
                Path.of("/opt/a b/sing-box"), Path.of("/tmp/it's.json"), Path.of("/tmp/stop"));

        assertThat(wrapper).contains("'/opt/a b/sing-box'");
        assertThat(wrapper).contains("'/tmp/it'\\''s.json'");
    }

    private static String readQuietly(Path file) {
        try {
            return java.nio.file.Files.readString(file).strip();
        } catch (IOException e) {
            return "";
        }
    }
}
