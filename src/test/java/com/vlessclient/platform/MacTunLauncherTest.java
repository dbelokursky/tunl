package com.vlessclient.platform;

import com.vlessclient.testing.Await;
import com.vlessclient.testing.FormatLocale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Content and behaviour checks for the two macOS TUN wrappers. MacTunLauncher
 * previously had no tests at all, and its preferred sudo-NOPASSWD wrapper was
 * missing the parent-pid watch the osascript and Linux wrappers already carried
 * — so a hard app death (SIGKILL, crash) leaked a root-owned core holding the
 * TUN up. These pin both wrappers to the same three-way watch.
 */
@EnabledOnOs({OS.MAC, OS.LINUX})
class MacTunLauncherTest {

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
    void sudoWrapper_runsUnderSudoAndWatchesItsStdin() {
        String wrapper = MacTunLauncher.sudoWrapperCommand(
                Path.of("/opt/sing-box"), Path.of("/tmp/c.json"));

        assertThat(wrapper).contains("sudo -n '/opt/sing-box' run -c '/tmp/c.json'");
        // A dead app must never leak an elevated core: its death closes the
        // wrapper's stdin, which the watch reads (StdinWatchTest runs it).
        assertThat(wrapper).isEqualTo(StdinWatch.around(
                "sudo -n '/opt/sing-box' run -c '/tmp/c.json'"));
        // No polling: the loop this replaced ran `sleep 0.3` three times a second.
        assertThat(wrapper).doesNotContain("sleep");
    }

    @Test
    void osascriptWrapper_runsCoreAndWatchesStopFileAndParent() {
        String wrapper = MacTunLauncher.osascriptWrapperCommand(
                Path.of("/opt/sing-box"), Path.of("/tmp/c.json"), Path.of("/tmp/stop"));

        assertThat(wrapper).contains("'/opt/sing-box' run -c '/tmp/c.json'");
        assertThat(wrapper).doesNotContain("sudo -n");
        // The stop file goes in through a variable, never quoted inside the
        // trap's own quotes; see aSigtermStopsTheCoreWhenTheStopPathHasASpace.
        assertThat(wrapper).startsWith("SBSTOP='/tmp/stop'; ");
        assertThat(wrapper).contains("[ ! -f \"$SBSTOP\" ]");
        assertThat(wrapper).contains("kill -0 " + ProcessHandle.current().pid());
        assertThat(wrapper).contains("' INT TERM; ").contains("' EXIT; ");
        assertThat(wrapper).contains("rm -f \"$SBSTOP\"; exit $RC");
        // The trap has to be in place before the core starts: a SIGTERM
        // between the two killed the shell and left the core running.
        assertThat(wrapper.indexOf("trap "))
                .as("the trap is set before the core starts")
                .isLessThan(wrapper.indexOf(" run -c "));
    }

    @Test
    void wrappers_shellQuotePathsWithSpecials() {
        String sudo = MacTunLauncher.sudoWrapperCommand(
                Path.of("/opt/a b/sing-box"), Path.of("/tmp/it's.json"));
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
        spawned.add(sh.toHandle());

        long pid = Long.parseLong(Await.untilValue("the fake core to record its pid",
                () -> readQuietly(pidFile), text -> !text.isEmpty(), Duration.ofSeconds(5)));
        // A handle taken while the core is certainly alive carries its start
        // time, so a later destroyForcibly cannot hit a recycled pid.
        ProcessHandle core = ProcessHandle.of(pid)
                .orElseThrow(() -> new AssertionError("fake core " + pid + " already gone"));
        spawned.add(core);

        sh.destroy();   // SIGTERM, exactly like SingBoxEngine.forceStop
        assertThat(sh.waitFor(10, TimeUnit.SECONDS))
                .as("wrapper shell should exit after SIGTERM").isTrue();

        // The child must be reaped, not orphaned. Its death is asynchronous to
        // the wrapper's exit, so poll with a deadline.
        Await.until("fake core " + pid + " to be reaped, not orphaned",
                () -> !core.isAlive(), Duration.ofSeconds(5));
    }

    /**
     * The stop file's path, quoted inside the trap's own single quotes, split
     * the trap at its first space, and the data directory always has one:
     * "Application Support". bash trapped the signals with an action cut
     * short before its exit and printed an error into the core's log; dash
     * refused the trap, and a SIGTERM killed the wrapper with the root core
     * still running.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/bin/sh", "/bin/bash", "/bin/dash"})
    void aSigtermStopsTheCoreWhenTheStopPathHasASpace(String shell, @TempDir Path dir)
            throws Exception {
        assumeTrue(Files.isExecutable(Path.of(shell)), shell + " is not installed");
        Path pidFile = dir.resolve("core.pid");
        Path fakeCore = Files.writeString(dir.resolve("core.sh"),
                "#!/bin/sh\necho $$ > '" + pidFile + "'\nexec sleep 60\n");
        fakeCore.toFile().setExecutable(true, true);
        Path stop = Files.createDirectories(dir.resolve("Application Support/VlessClient/run"))
                .resolve("stop-1.signal");

        Path output = dir.resolve("wrapper.out");
        Process sh = new ProcessBuilder(shell, "-c",
                MacTunLauncher.osascriptWrapperCommand(fakeCore, dir.resolve("c.json"), stop))
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        spawned.add(sh.toHandle());
        long pid = Long.parseLong(Await.untilValue("the fake core to record its pid",
                () -> readQuietly(pidFile), text -> !text.isEmpty(), Duration.ofSeconds(5)));
        ProcessHandle core = ProcessHandle.of(pid)
                .orElseThrow(() -> new AssertionError("fake core " + pid + " already gone"));
        spawned.add(core);

        sh.destroy();

        assertThat(sh.waitFor(10, TimeUnit.SECONDS)).as("the wrapper ends on SIGTERM").isTrue();
        Await.until("the core to be stopped with its wrapper", () -> !core.isAlive(),
                Duration.ofSeconds(5));
        assertThat(Files.readString(output, StandardCharsets.UTF_8))
                .as("what the shell said about its trap").doesNotContain("trap");
        assertThat(sh.exitValue()).as("the trap's own exit").isEqualTo(143);
    }

    /**
     * The osascript fallback's watch got the app's pid through String.format's
     * %d as well: under a format locale that writes digits of its own (ar-SA,
     * fa-IR, bn-BD, ...) the root wrapper read "kill -0 ١٢٣٤٥" and its loop
     * ended on the first pass. Under bash, macOS's /bin/sh, the core it had
     * just started, right after the password dialog, was stopped at once;
     * under dash it mostly ran on with no watch, deaf to the stop file.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/bin/sh", "/bin/bash", "/bin/dash"})
    void theOsascriptWrapperKeepsItsWatchUnderALocaleWithDigitsOfItsOwn(String shell,
            @TempDir Path dir) throws Exception {
        assumeTrue(Files.isExecutable(Path.of(shell)), shell + " is not installed");
        Path core = Files.writeString(dir.resolve("core.sh"), "#!/bin/sh\nexec sleep 60\n");
        core.toFile().setExecutable(true, true);
        Path stop = dir.resolve("stop");
        String wrapper = FormatLocale.in("ar-SA",
                () -> MacTunLauncher.osascriptWrapperCommand(core, dir.resolve("c.json"), stop));

        Process sh = new ProcessBuilder(shell, "-c", wrapper).redirectErrorStream(true).start();
        spawned.add(sh.toHandle());

        assertThat(sh.waitFor(1500, TimeUnit.MILLISECONDS))
                .as("the wrapper ended its core on its own, with no stop asked for")
                .isFalse();
        Files.createFile(stop);   // what SingBoxEngine.signalStop does
        assertThat(sh.waitFor(5, TimeUnit.SECONDS)).as("the wrapper heeds the stop file").isTrue();
        assertThat(wrapper).contains("kill -0 " + ProcessHandle.current().pid() + " ");
    }

    /**
     * A stop that came before the root wrapper started, while the password
     * dialog was up: the loop ended on its first pass and its SIGTERM reached
     * the core's background child before the exec. A dash child still had
     * the wrapper's TERM trap, which lost the signal at the exec, and the root
     * core ran on with nothing watching it. macOS's /bin/sh can be dash.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/bin/sh", "/bin/bash", "/bin/dash"})
    void aStopThatCameBeforeTheOsascriptWrapperStartedLeavesNoCore(String shell,
            @TempDir Path dir) throws Exception {
        assumeTrue(Files.isExecutable(Path.of(shell)), shell + " is not installed");
        Path started = dir.resolve("started");
        Path core = Files.writeString(dir.resolve("core.sh"),
                "#!/bin/sh\n: > '" + started + "'\nexec sleep 60\n");
        core.toFile().setExecutable(true, true);
        for (int run = 0; run < 10; run++) {
            Path stop = Files.createFile(dir.resolve("stop-" + run));
            Process sh = new ProcessBuilder(shell, "-c", MacTunLauncher.osascriptWrapperCommand(
                    core, dir.resolve("c.json"), stop)).redirectErrorStream(true).start();
            spawned.add(sh.toHandle());

            assertThat(sh.waitFor(3, TimeUnit.SECONDS))
                    .as("run %d: the wrapper ends on a stop that came first", run).isTrue();
        }
        assertThat(started).as("a core whose stop came first").doesNotExist();
    }

    @Test
    void launcherWrapper_handsTheConfigOnStdinAndWatchesItsOwnStdin() {
        String wrapper = MacTunLauncher.launcherWrapperCommand(
                Path.of("/usr/local/libexec/vless-client/tun-launch"),
                Path.of("/tmp/it's.json"));

        // The user's shell opens the config; the rule names no path for root.
        assertThat(wrapper).contains(
                "sudo -n '/usr/local/libexec/vless-client/tun-launch' < '/tmp/it'\\''s.json'");
        assertThat(wrapper).doesNotContain(" run -c ");
        assertThat(wrapper).isEqualTo(StdinWatch.around(
                "sudo -n '/usr/local/libexec/vless-client/tun-launch' < '/tmp/it'\\''s.json'"));
        assertThat(wrapper).doesNotContain("sleep");
    }

    /**
     * The launcher path end to end, with {@code sudo} standing in for itself:
     * the wrapper feeds the config to the real launcher script, the core it
     * starts gets the filtered config on stdin, and closing the wrapper's own
     * stdin, which is how the engine stops it, ends it.
     * The launcher replaces itself with the core, so the pid the wrapper
     * watches and kills is the core's.
     */
    @Test
    void launcherWrapper_runsTheCoreOnTheFilteredConfigUntilStopped(@TempDir Path base)
            throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/jq")), "the launcher needs /usr/bin/jq");
        Path bin = Files.createDirectory(base.resolve("bin"));
        Path sudo = Files.writeString(bin.resolve("sudo"),
                "#!/bin/sh\n[ \"$1\" = -n ] && shift\nexec \"$@\"\n");
        Path launcher = Files.writeString(base.resolve("tun-launch"),
                PrivilegeHelper.launcherScript(base));
        Path pidFile = base.resolve("core.pid");
        Path received = base.resolve("received.json");
        Path core = Files.writeString(base.resolve("sing-box"), "#!/bin/sh\n"
                + "echo $$ > '" + pidFile + "'\n"
                + "cat > '" + received + "'\n"
                + "exec sleep 60\n");
        for (Path script : List.of(sudo, launcher, core)) {
            script.toFile().setExecutable(true, true);
        }
        Path config = Files.writeString(base.resolve("config.json"),
                "{\"log\": {\"level\": \"info\", \"output\": \"/etc/owned\"}}");
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c",
                MacTunLauncher.launcherWrapperCommand(launcher, config))
                .redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        Process sh = pb.start();
        spawned.add(sh.toHandle());

        long pid = Long.parseLong(Await.untilValue("the core to start",
                () -> readQuietly(pidFile), text -> !text.isEmpty(), Duration.ofSeconds(10)));
        ProcessHandle running = ProcessHandle.of(pid)
                .orElseThrow(() -> new AssertionError("core " + pid + " already gone"));
        spawned.add(running);
        Await.until("the core to read its config", () -> readQuietly(received).endsWith("}"),
                Duration.ofSeconds(5));
        assertThat(readQuietly(received)).doesNotContain("/etc/owned").contains("\"info\"");

        sh.getOutputStream().close();
        assertThat(sh.waitFor(10, TimeUnit.SECONDS)).as("the wrapper ends once its stdin closes")
                .isTrue();
        Await.until("core " + pid + " to be stopped", () -> !running.isAlive(),
                Duration.ofSeconds(5));
    }

    /** A core that failed reached the app as "exited with code 0". */
    @Test
    void osascriptWrapperExitsWithTheCoresCode(@TempDir Path dir) throws Exception {
        Path core = Files.writeString(dir.resolve("core.sh"), "#!/bin/sh\nexit 7\n");
        core.toFile().setExecutable(true, true);
        Process sh = new ProcessBuilder("/bin/sh", "-c", MacTunLauncher.osascriptWrapperCommand(
                core, dir.resolve("c.json"), dir.resolve("stop"))).redirectErrorStream(true).start();
        spawned.add(sh.toHandle());

        assertThat(sh.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(sh.exitValue()).isEqualTo(7);
    }

    /** Stands in for the privileged setup, counting the checks. */
    private static final class FakePrivileges implements MacTunLauncher.Privileges {
        final AtomicInteger checks = new AtomicInteger();
        final List<String> setups = new CopyOnWriteArrayList<>();
        volatile boolean configured;

        @Override
        public boolean isConfigured(Path binary) {
            checks.incrementAndGet();
            return configured;
        }

        @Override
        public void configure(Path binary, String prompt) {
            setups.add(prompt);
            configured = true;
        }

        @Override
        public boolean usesLauncher() {
            return true;
        }
    }

    /**
     * Each check runs sudo -n -l and hashes the ~45 MB core and its root copy,
     * and an installed rule was checked twice on every connect, under the
     * engine's lock.
     */
    @Test
    void aSetupInPlaceIsCheckedOncePerConnect(@TempDir Path dir) {
        FakePrivileges privileges = new FakePrivileges();
        privileges.configured = true;
        List<List<String>> spawned = new CopyOnWriteArrayList<>();
        MacTunLauncher launcher = new MacTunLauncher(privileges, builder -> {
            spawned.add(builder.command());
            throw new IOException("not started in a test");
        });

        assertThatThrownBy(() -> launcher.launch(dir.resolve("sing-box"),
                dir.resolve("config.json"), new TunLauncher.Prompt("setup", "each connect")))
                .hasMessage("not started in a test");

        assertThat(privileges.checks).as("checks of the setup").hasValue(1);
        assertThat(privileges.setups).isEmpty();
        assertThat(spawned).singleElement().asString().contains("sudo -n");
    }

    /**
     * An interrupt is the start being called off, at quit. The sudo check read
     * it as "not set up", and the setup or every-connect password dialog came
     * up as the app quit.
     */
    @Test
    void anInterruptedStartRaisesNoDialog(@TempDir Path dir) {
        FakePrivileges privileges = new FakePrivileges();
        List<List<String>> spawned = new CopyOnWriteArrayList<>();
        MacTunLauncher launcher = new MacTunLauncher(privileges, builder -> {
            spawned.add(builder.command());
            throw new IOException("not started in a test");
        });

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> launcher.launch(dir.resolve("sing-box"),
                    dir.resolve("config.json"), new TunLauncher.Prompt("setup", "each connect")))
                    .isInstanceOf(InterruptedIOException.class);
        } finally {
            Thread.interrupted();
        }
        assertThat(privileges.setups).as("setup dialogs").isEmpty();
        assertThat(spawned).as("wrappers started").isEmpty();
    }

    private static String readQuietly(Path file) {
        try {
            return Files.readString(file).strip();
        } catch (IOException e) {
            return "";
        }
    }
}
