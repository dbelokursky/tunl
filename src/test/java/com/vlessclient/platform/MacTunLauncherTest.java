package com.vlessclient.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Content and behaviour checks for the two macOS TUN wrappers. MacTunLauncher
 * previously had no tests at all, and its preferred sudo-NOPASSWD wrapper was
 * missing the parent-pid watch the osascript and Linux wrappers already carried
 * — so a hard app death (SIGKILL, crash) leaked a root-owned core holding the
 * TUN up. These pin both wrappers to the same three-way watch.
 */
@EnabledOnOs({OS.MAC, OS.LINUX})
class MacTunLauncherTest {

    @Test
    void sudoWrapper_runsUnderSudoAndWatchesStopFileAndParent() {
        String wrapper = MacTunLauncher.sudoWrapperCommand(
                Path.of("/opt/sing-box"), Path.of("/tmp/c.json"), Path.of("/tmp/stop"));

        assertThat(wrapper).contains("sudo -n '/opt/sing-box' run -c '/tmp/c.json'");
        assertThat(wrapper).contains("[ ! -f '/tmp/stop' ]");
        // The fix: a dead app must never leak an elevated core. Without the
        // parent watch this assertion fails, forcing the loop-condition change.
        assertThat(wrapper).contains("kill -0 " + ProcessHandle.current().pid());
        // Must trap the signal the engine actually sends (Process.destroy =
        // SIGTERM), not EXIT alone — see sudoWrapperKillsChildOnSigterm.
        assertThat(wrapper).contains("EXIT INT TERM");
        assertThat(wrapper).contains("rm -f '/tmp/stop'");
    }

    @Test
    void osascriptWrapper_runsCoreAndWatchesStopFileAndParent() {
        String wrapper = MacTunLauncher.osascriptWrapperCommand(
                Path.of("/opt/sing-box"), Path.of("/tmp/c.json"), Path.of("/tmp/stop"));

        assertThat(wrapper).contains("'/opt/sing-box' run -c '/tmp/c.json'");
        assertThat(wrapper).doesNotContain("sudo -n");
        assertThat(wrapper).contains("[ ! -f '/tmp/stop' ]");
        assertThat(wrapper).contains("kill -0 " + ProcessHandle.current().pid());
        assertThat(wrapper).contains("EXIT INT TERM");
        assertThat(wrapper).contains("rm -f '/tmp/stop'");
    }

    @Test
    void wrappers_shellQuotePathsWithSpecials() {
        String sudo = MacTunLauncher.sudoWrapperCommand(
                Path.of("/opt/a b/sing-box"), Path.of("/tmp/it's.json"), Path.of("/tmp/stop"));
        assertThat(sudo).contains("'/opt/a b/sing-box'");
        assertThat(sudo).contains("'/tmp/it'\\''s.json'");

        String osa = MacTunLauncher.osascriptWrapperCommand(
                Path.of("/opt/a b/sing-box"), Path.of("/tmp/it's.json"), Path.of("/tmp/stop"));
        assertThat(osa).contains("'/opt/a b/sing-box'");
        assertThat(osa).contains("'/tmp/it'\\''s.json'");
    }

    /**
     * Regression for the orphaned-core / leaked-TUN bug: SIGTERM to the wrapper
     * shell (what {@code SingBoxEngine.forceStop} sends via
     * {@code Process.destroy()}) must reap the "sing-box" child. An EXIT-only
     * trap is skipped on signal death, leaving the core (and its TUN) running.
     * Uses the osascript wrapper because it runs without sudo.
     */
    @Test
    void sudoWrapperKillsChildOnSigterm() throws Exception {
        Path pidFile = Files.createTempFile("fake-core-pid", "");
        Path fakeCore = Files.createTempFile("fake-core", ".sh");
        Files.writeString(fakeCore,
                "#!/bin/sh\necho $$ > '" + pidFile + "'\nexec sleep 60\n");
        fakeCore.toFile().setExecutable(true);
        Path stop = Files.createTempFile("stop", "");
        Files.deleteIfExists(stop);

        String wrapper = MacTunLauncher.osascriptWrapperCommand(fakeCore, fakeCore, stop);
        Process sh = new ProcessBuilder("/bin/sh", "-c", wrapper)
                .redirectErrorStream(true).start();

        long deadline = System.currentTimeMillis() + 5_000;
        String pid = "";
        while (System.currentTimeMillis() < deadline) {
            pid = Files.readString(pidFile).strip();
            if (!pid.isEmpty()) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(pid).as("fake core should have started").isNotEmpty();

        sh.destroy();   // SIGTERM, exactly like SingBoxEngine.forceStop
        assertThat(sh.waitFor(10, TimeUnit.SECONDS))
                .as("wrapper shell should exit after SIGTERM").isTrue();

        // The child must be reaped, not orphaned. Its death is asynchronous to
        // the wrapper's exit, so poll with a deadline.
        boolean alive = true;
        long reapDeadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < reapDeadline) {
            alive = new ProcessBuilder("kill", "-0", pid).start().waitFor() == 0;
            if (!alive) {
                break;
            }
            Thread.sleep(50);
        }
        if (alive) {
            new ProcessBuilder("kill", "-9", pid).start().waitFor();
        }
        assertThat(alive).as("fake core (pid %s) must be reaped, not orphaned", pid)
                .isFalse();
    }
}
