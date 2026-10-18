package com.vlessclient.platform;

import com.vlessclient.testing.Await;
import com.vlessclient.testing.FormatLocale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * A directory holding stand-ins for getcap and setcap, where the launcher
     * is told libcap's tools live.
     */
    private static List<Path> toolsIn(Path dir) throws IOException {
        Path tools = Files.createDirectories(dir.resolve("sbin"));
        for (String tool : List.of("getcap", "setcap")) {
            Path stub = Files.writeString(tools.resolve(tool), "#!/bin/sh\nexit 0\n");
            stub.toFile().setExecutable(true, true);
        }
        return List.of(tools);
    }

    @AfterEach
    void reapSpawnedProcesses() {
        for (ProcessHandle handle : spawned) {
            handle.descendants().forEach(ProcessHandle::destroyForcibly);
            handle.destroyForcibly();
        }
    }

    /**
     * A dismissed PolicyKit dialog for the one-time grant is the user
     * cancelling the connect. The fallback's own prompt used to follow it at
     * once, asking again for what had just been declined, and dismissing that
     * one read as "exited with code 126".
     */
    @Test
    void aDismissedGrantCancelsTheConnectInsteadOfPromptingAgain(@TempDir Path dir)
            throws IOException {
        Path core = Files.writeString(dir.resolve("sing-box"), "");
        List<Path> tools = toolsIn(dir);
        String getcap = tools.getFirst().resolve("getcap").toString();
        FakeRunner runner = new FakeRunner(cmd -> cmd.getFirst().equals(getcap)
                ? new CommandRunner.Result(0, "")
                : new CommandRunner.Result(LinuxTunLauncher.PKEXEC_DISMISSED, ""));
        LinuxTunLauncher launcher =
                new LinuxTunLauncher(runner, "elevator-that-must-not-launch", tools);

        assertThatThrownBy(() -> launcher.launch(core, dir.resolve("config.json"),
                new TunLauncher.Prompt("setup", "each connect")))
                .isInstanceOf(ElevationDeclinedException.class);
        assertThat(runner.calls).extracting(List::getFirst)
                .as("what ran: the check and the grant, and no every-connect elevation")
                .containsExactly(getcap, "elevator-that-must-not-launch");
    }

    /**
     * getcap and setcap live in /usr/sbin, which a desktop user's PATH on
     * Debian does not hold. Run by name, getcap was not found, the core read
     * as having no capability, pkexec could not find setcap either, and every
     * connect raised a PolicyKit prompt.
     */
    @Test
    void libcapsToolsAreRunFromWhereTheyAreInstalled(@TempDir Path dir) throws IOException {
        Path core = Files.writeString(dir.resolve("sing-box"), "");
        List<Path> tools = toolsIn(dir);
        FakeRunner runner = new FakeRunner(cmd -> cmd.contains("cap_net_admin+ep")
                ? new CommandRunner.Result(LinuxTunLauncher.PKEXEC_DISMISSED, "")
                : new CommandRunner.Result(0, ""));
        LinuxTunLauncher launcher = new LinuxTunLauncher(runner, "pkexec", tools);

        assertThatThrownBy(() -> launcher.launch(core, dir.resolve("config.json"),
                new TunLauncher.Prompt("setup", "each connect")))
                .isInstanceOf(ElevationDeclinedException.class);
        assertThat(runner.calls).containsExactly(
                List.of(tools.getFirst().resolve("getcap").toString(),
                        core.toAbsolutePath().toString()),
                List.of("pkexec", tools.getFirst().resolve("setcap").toString(),
                        "cap_net_admin+ep", core.toAbsolutePath().toString()));
    }

    /** Without getcap a grant's result cannot be seen: no prompt is raised for it. */
    @Test
    void withoutGetcapNoGrantIsAskedFor(@TempDir Path dir) throws IOException {
        Path core = Files.writeString(dir.resolve("sing-box"), "");
        FakeRunner runner = new FakeRunner(cmd -> new CommandRunner.Result(0, ""));
        LinuxTunLauncher launcher = new LinuxTunLauncher(runner, "pkexec",
                List.of(Files.createDirectories(dir.resolve("empty"))));

        assertThat(launcher.hasNetAdminCapability(core)).isFalse();
        assertThat(runner.calls).as("commands run").isEmpty();
    }

    /**
     * NixOS and Guix keep libcap on the PATH alone, in none of the fixed
     * directories: getcap was not found, the core never got the capability,
     * every connect raised a PolicyKit prompt, and recovery, which will not
     * raise one, never brought a dropped tunnel back.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void getcapOnThePathIsFoundWhenNoneIsWhereLibcapInstallsIt(@TempDir Path dir)
            throws IOException {
        // A directory on this machine that only root can write to, with getcap in it.
        Path systemDir = List.of("/usr/sbin", "/sbin", "/usr/bin", "/bin").stream()
                .map(Path::of)
                .filter(d -> Files.isExecutable(d.resolve("getcap")))
                .findFirst().orElse(null);
        Assumptions.assumeTrue(systemDir != null, "libcap's getcap is not installed");
        Path core = Files.writeString(dir.resolve("sing-box"), "");
        FakeRunner runner = new FakeRunner(cmd -> new CommandRunner.Result(0, ""));
        LinuxTunLauncher launcher = new LinuxTunLauncher(runner, "pkexec",
                List.of(Files.createDirectories(dir.resolve("empty"))), List.of(systemDir));

        launcher.hasNetAdminCapability(core);

        assertThat(runner.calls).extracting(List::getFirst)
                .containsExactly(systemDir.resolve("getcap").toString());
    }

    /**
     * setcap found on the PATH runs as root through pkexec: one in a directory
     * anyone but root can write to is never run, whatever it is.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,
            org.junit.jupiter.api.condition.OS.LINUX})
    void libcapsToolsOnThePathAreNotRunWhereOthersCouldHavePlacedThem(@TempDir Path dir)
            throws IOException {
        Path core = Files.writeString(dir.resolve("sing-box"), "");
        Path open = Files.createDirectories(dir.resolve("open"));
        Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rwxrwxrwx"));
        for (String tool : List.of("getcap", "setcap")) {
            Path stub = Files.writeString(open.resolve(tool), "#!/bin/sh\nexit 0\n");
            stub.toFile().setExecutable(true, true);
        }
        FakeRunner runner = new FakeRunner(cmd -> new CommandRunner.Result(0, "cap_net_admin"));
        LinuxTunLauncher launcher = new LinuxTunLauncher(runner, "pkexec",
                List.of(Files.createDirectories(dir.resolve("empty"))), List.of(open));

        assertThat(launcher.hasNetAdminCapability(core)).isFalse();
        assertThat(runner.calls).as("commands run").isEmpty();
        assertThat(LinuxTunLauncher.onlyRootCanChange(open.resolve("setcap"))).isFalse();
    }

    /** A configuration that has the core keep its cache in {@code cache}. */
    private static Path configKeepingCacheIn(Path dir, Path cache) throws IOException {
        return Files.writeString(dir.resolve("config.json"),
                "{\"experimental\":{\"cache_file\":{\"enabled\":true,\"path\":\""
                        + cache.toAbsolutePath() + "\"}}}");
    }

    private static java.nio.file.attribute.UserPrincipal user(String name) throws IOException {
        return FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(name);
    }

    /**
     * The per-connect fallback's root core left its cache file root's, and
     * every start through the capability since, as the user, failed on it
     * with "permission denied" until the file was removed by hand.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,
            org.junit.jupiter.api.condition.OS.LINUX})
    void aCacheFileAnotherUserOwnsIsRemovedBeforeAStartAsTheUser(@TempDir Path dir)
            throws IOException {
        Path cache = Files.writeString(dir.resolve("sing-box-tun.db"), "rule-sets");
        Path config = configKeepingCacheIn(dir, cache);
        java.nio.file.attribute.UserPrincipal owner = Files.getOwner(cache);
        java.nio.file.attribute.UserPrincipal someoneElse =
                user(owner.getName().equals("root") ? "nobody" : "root");

        LinuxTunLauncher.removeForeignCacheFile(config, owner);
        assertThat(cache).as("the app user's own cache file").exists();

        LinuxTunLauncher.removeForeignCacheFile(config, someoneElse);
        assertThat(cache).as("a cache file the core could not open").doesNotExist();
    }

    /** The start through the capability does it; only root can hand a file to another user. */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void aStartThroughTheCapabilityRemovesTheCacheFileARootCoreLeft(@TempDir Path dir)
            throws Exception {
        Path config = dir.resolve("config.json");
        Files.writeString(config, "{}");
        Assumptions.assumeTrue(Files.getOwner(config).getName().equals("root"),
                "only root can give a file to another user");
        Path cache = Files.writeString(dir.resolve("sing-box-tun.db"), "rule-sets");
        Files.setOwner(cache, user("nobody"));
        configKeepingCacheIn(dir, cache);
        Path core = Files.writeString(dir.resolve("sing-box"), "#!/bin/sh\nexit 0\n");
        core.toFile().setExecutable(true, true);
        FakeRunner caps = new FakeRunner(cmd -> new CommandRunner.Result(0, "cap_net_admin=ep"));
        LinuxTunLauncher launcher =
                new LinuxTunLauncher(caps, "elevator-that-must-not-launch", toolsIn(dir));

        TunLauncher.Launched launched = launcher.launch(core, config,
                new TunLauncher.Prompt("setup", "each connect"));
        spawned.add(launched.process().toHandle());

        assertThat(launched.promptsEachLaunch()).as("the start through the capability").isFalse();
        assertThat(cache).doesNotExist();
    }

    @Test
    void capabilityCheck_readsGetcapOutput(@TempDir Path dir) throws IOException {
        FakeRunner caps = new FakeRunner(cmd -> new CommandRunner.Result(0,
                "/home/u/.local/share/vless-client/bin/sing-box cap_net_admin=ep"));
        List<Path> tools = toolsIn(dir);

        assertThat(new LinuxTunLauncher(caps, "pkexec", tools)
                .hasNetAdminCapability(Path.of("/home/u/.local/share/vless-client/bin/sing-box")))
                .isTrue();
        assertThat(caps.calls.getFirst().getFirst())
                .isEqualTo(tools.getFirst().resolve("getcap").toString());
    }

    @Test
    void capabilityCheck_falseWithoutTheCapability(@TempDir Path dir) throws IOException {
        FakeRunner caps = new FakeRunner(cmd -> new CommandRunner.Result(0, ""));

        assertThat(new LinuxTunLauncher(caps, "pkexec", toolsIn(dir))
                .hasNetAdminCapability(Path.of("/opt/sing-box"))).isFalse();
    }

    /**
     * An interrupt is the start being called off, at quit. It used to fail
     * getcap and the grant, which read as "no capability", and the launch
     * went on to the fallback: a PolicyKit prompt as the app quit.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void anInterruptedStartRaisesNoPrompt(@TempDir Path dir) throws IOException {
        Path core = Files.writeString(dir.resolve("sing-box"), "");
        Path invocations = dir.resolve("invocations.log");
        Path elevator = Files.writeString(dir.resolve("elevator"),
                "#!/bin/sh\necho \"$*\" >> '" + invocations + "'\n");
        elevator.toFile().setExecutable(true, true);
        LinuxTunLauncher launcher = new LinuxTunLauncher(
                CommandRunner.system(Duration.ofSeconds(30)), elevator.toString());

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> launcher.launch(core, dir.resolve("config.json"),
                    new TunLauncher.Prompt("setup", "each connect")))
                    .isInstanceOf(InterruptedIOException.class);
        } finally {
            Thread.interrupted();
        }
        assertThat(invocations).as("the elevator was run").doesNotExist();
    }

    @Test
    void capabilityCheck_falseWhenGetcapMissing() throws IOException {
        CommandRunner failing = cmd -> {
            throw new IOException("getcap not found");
        };

        assertThat(new LinuxTunLauncher(failing, "pkexec")
                .hasNetAdminCapability(Path.of("/opt/sing-box"))).isFalse();
    }

    /**
     * The fast path's core runs as the user, so its wrapper can watch its own
     * stdin instead of polling (StdinWatchTest runs that watch).
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,
            org.junit.jupiter.api.condition.OS.LINUX})
    void directWrapperCommand_watchesItsStdinAndDoesNotPoll() {
        String wrapper = LinuxTunLauncher.directWrapperCommand(
                Path.of("/opt/a b/sing-box"), Path.of("/tmp/it's.json"));

        assertThat(wrapper).isEqualTo(StdinWatch.around(
                "'/opt/a b/sing-box' run -c '/tmp/it'\\''s.json'"));
        assertThat(wrapper).doesNotContain("sleep");
    }

    /** The pkexec fallback's wrapper runs as root and keeps polling. */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,
            org.junit.jupiter.api.condition.OS.LINUX})
    void wrapperCommand_runsCoreAndWatchesStopFileAndParent() {
        String wrapper = LinuxTunLauncher.wrapperCommand(
                Path.of("/opt/sing-box"), Path.of("/tmp/c.json"), Path.of("/tmp/stop"));

        assertThat(wrapper).contains("'/opt/sing-box' run -c '/tmp/c.json'");
        // The stop file goes in through a variable, never quoted inside the
        // trap's own quotes; see aSigtermStopsTheCoreWhenTheStopPathHasASpace.
        assertThat(wrapper).startsWith("SBSTOP='/tmp/stop'; ");
        assertThat(wrapper).contains("[ ! -f \"$SBSTOP\" ]");
        // Parent watch: a dead app must never leak an elevated core.
        assertThat(wrapper).contains("kill -0 " + ProcessHandle.current().pid());
        // Must trap the signals the engine actually sends (Process.destroy =
        // SIGTERM), not EXIT alone — see wrapperKillsChildOnSigterm.
        assertThat(wrapper).contains("' INT TERM; ").contains("' EXIT; ");
        assertThat(wrapper).contains("rm -f \"$SBSTOP\"; exit $RC");
        // The trap has to be in place before the core starts: a SIGTERM
        // between the two killed the shell and left the core running.
        assertThat(wrapper.indexOf("trap "))
                .as("the trap is set before the core starts")
                .isLessThan(wrapper.indexOf(" run -c "));
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

    /**
     * The stop file's path, quoted inside the trap's own single quotes, split
     * the trap at its first space, which $XDG_DATA_HOME or $HOME may hold.
     * bash trapped the signals with an action cut short before its exit and
     * printed an error into the core's log; dash refused the trap, and a
     * SIGTERM killed the wrapper with the root core still running.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/bin/sh", "/bin/bash", "/bin/dash"})
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,
            org.junit.jupiter.api.condition.OS.LINUX})
    void aSigtermStopsTheCoreWhenTheStopPathHasASpace(String shell, @TempDir Path dir)
            throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of(shell)), shell + " is not installed");
        Path pidFile = dir.resolve("core.pid");
        Path fakeCore = Files.writeString(dir.resolve("core.sh"),
                "#!/bin/sh\necho $$ > '" + pidFile + "'\nexec sleep 60\n");
        fakeCore.toFile().setExecutable(true, true);
        Path stop = Files.createDirectories(dir.resolve("share dir/vless-client/run"))
                .resolve("stop-1.signal");

        Path output = dir.resolve("wrapper.out");
        Process sh = new ProcessBuilder(shell, "-c",
                LinuxTunLauncher.wrapperCommand(fakeCore, dir.resolve("c.json"), stop))
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

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,
            org.junit.jupiter.api.condition.OS.LINUX})
    void wrapperCommand_shellQuotesPathsWithSpecials() {
        String wrapper = LinuxTunLauncher.wrapperCommand(
                Path.of("/opt/a b/sing-box"), Path.of("/tmp/it's.json"), Path.of("/tmp/stop"));

        assertThat(wrapper).contains("'/opt/a b/sing-box'");
        assertThat(wrapper).contains("'/tmp/it'\\''s.json'");
    }

    /**
     * The app's pid reached the fallback's watch through String.format's %d,
     * which writes the digits of the default format locale. Under fa-IR,
     * ar-SA, bn-BD and the like the watch read "kill -0 ۱۲۳۴۵", which no shell
     * takes for a pid, and the loop ended on its first pass: bash stopped the
     * root core as it started, so every TUN connect failed, and dash mostly
     * lost that SIGTERM and left the core running with nothing watching it.
     * A full JDK has those locales; the installers' runtime keeps en and ru.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/bin/sh", "/bin/bash", "/bin/dash"})
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,
            org.junit.jupiter.api.condition.OS.LINUX})
    void theFallbackKeepsItsWatchUnderALocaleWithDigitsOfItsOwn(String shell, @TempDir Path dir)
            throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of(shell)), shell + " is not installed");
        Path core = Files.writeString(dir.resolve("core.sh"), "#!/bin/sh\nexec sleep 60\n");
        core.toFile().setExecutable(true, true);
        Path stop = dir.resolve("stop");
        String wrapper = FormatLocale.in("fa-IR",
                () -> LinuxTunLauncher.wrapperCommand(core, dir.resolve("c.json"), stop));

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
     * A stop that came before the root wrapper started: Disconnect or Quit
     * while the PolicyKit dialog was up, and the dialog answered within the
     * engine's grace. The loop ended on its first pass, and its SIGTERM
     * reached the core's background child before that had exec'd. Under
     * dash, Debian's and Ubuntu's /bin/sh, the child still had the wrapper's
     * TERM trap, which took the signal and lost it at the exec: the root core
     * ran on with its TUN up, the wrapper waited on it deaf to the stop file
     * and the app, and Disconnect could not end it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/bin/sh", "/bin/bash", "/bin/dash"})
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,
            org.junit.jupiter.api.condition.OS.LINUX})
    void aStopThatCameBeforeTheWrapperStartedLeavesNoCore(String shell, @TempDir Path dir)
            throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of(shell)), shell + " is not installed");
        Path started = dir.resolve("started");
        Path core = coreThatMarks(dir, started);
        for (int run = 0; run < 10; run++) {
            Path stop = Files.createFile(dir.resolve("stop-" + run));
            Process sh = new ProcessBuilder(shell, "-c", LinuxTunLauncher.wrapperCommand(
                    core, dir.resolve("c.json"), stop)).redirectErrorStream(true).start();
            spawned.add(sh.toHandle());

            assertThat(sh.waitFor(3, TimeUnit.SECONDS))
                    .as("run %d: the wrapper ends on a stop that came first", run).isTrue();
        }
        assertThat(started).as("a core whose stop came first").doesNotExist();
    }

    /** The same for an app that died while the dialog was up, answered after. */
    @ParameterizedTest
    @ValueSource(strings = {"/bin/sh", "/bin/bash", "/bin/dash"})
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,
            org.junit.jupiter.api.condition.OS.LINUX})
    void anAppGoneBeforeTheWrapperStartedLeavesNoCore(String shell, @TempDir Path dir)
            throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of(shell)), shell + " is not installed");
        Path started = dir.resolve("started");
        Path core = coreThatMarks(dir, started);
        for (int run = 0; run < 10; run++) {
            Process sh = new ProcessBuilder(shell, "-c", LinuxTunLauncher.wrapperCommand(
                    core, dir.resolve("c.json"), dir.resolve("stop-" + run), NO_SUCH_PID))
                    .redirectErrorStream(true).start();
            spawned.add(sh.toHandle());

            assertThat(sh.waitFor(3, TimeUnit.SECONDS))
                    .as("run %d: the wrapper ends when the app is already gone", run).isTrue();
        }
        assertThat(started).as("a core whose app was gone").doesNotExist();
    }

    /** A pid no process has: above every system's pid range. */
    private static final long NO_SUCH_PID = Integer.MAX_VALUE;

    /** A stand-in core that leaves {@code marker} when it starts, then runs on. */
    private static Path coreThatMarks(Path dir, Path marker) throws IOException {
        Path core = Files.writeString(dir.resolve("core.sh"),
                "#!/bin/sh\n: > '" + marker + "'\nexec sleep 60\n");
        core.toFile().setExecutable(true, true);
        return core;
    }

    /** A core that failed reached the app as "exited with code 0". */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC,
            org.junit.jupiter.api.condition.OS.LINUX})
    void fallbackWrapperExitsWithTheCoresCode(@TempDir Path dir) throws Exception {
        Path core = Files.writeString(dir.resolve("core.sh"), "#!/bin/sh\nexit 7\n");
        core.toFile().setExecutable(true, true);
        Process sh = new ProcessBuilder("/bin/sh", "-c", LinuxTunLauncher.wrapperCommand(
                core, dir.resolve("c.json"), dir.resolve("stop"))).redirectErrorStream(true).start();
        spawned.add(sh.toHandle());

        assertThat(sh.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(sh.exitValue()).isEqualTo(7);
    }

    /**
     * pkexec hands the root core none of the ids sudo does, so sing-box left
     * its cache file root's, and every later start through the capability,
     * as the user, failed on it with "permission denied".
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void theFallbacksRootCoreIsToldTheAppUsersIds(@TempDir Path dir) throws Exception {
        Path core = Files.writeString(dir.resolve("core.sh"),
                "#!/bin/sh\necho \"ids=$SUDO_UID:$SUDO_GID\"\n");
        core.toFile().setExecutable(true, true);
        Path mine = Files.createFile(dir.resolve("mine"));
        String expected = "ids=" + Files.getAttribute(mine, "unix:uid") + ":"
                + Files.getAttribute(mine, "unix:gid");

        Process sh = new ProcessBuilder("/bin/sh", "-c", LinuxTunLauncher.wrapperCommand(
                core, dir.resolve("c.json"), dir.resolve("stop"))).redirectErrorStream(true).start();
        spawned.add(sh.toHandle());
        String output = new String(sh.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(sh.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(output.strip()).isEqualTo(expected);
    }

    @Test
    void theRealIdsAreReadFromTheStatusFile(@TempDir Path dir) throws IOException {
        Path status = Files.writeString(dir.resolve("status"),
                "Name:\tjava\nUid:\t1000\t1000\t1000\t1000\nGid:\t1001\t1001\t1001\t1001\n");

        assertThat(LinuxTunLauncher.userIds(status)).isEqualTo("SUDO_UID=1000 SUDO_GID=1001 ");
        assertThat(LinuxTunLauncher.userIds(dir.resolve("missing"))).isEmpty();
    }

    private static String readQuietly(Path file) {
        try {
            return java.nio.file.Files.readString(file).strip();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * The capability setcap grants goes to whoever runs the binary, and the
     * core was world-executable: another user of the machine could hold
     * CAP_NET_ADMIN through it. The launch leaves it to its owner.
     */
    @Test
    void theCoreIsLeftExecutableByItsOwnerAlone(@TempDir Path dir) throws IOException {
        Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path binary = Files.createFile(dir.resolve("sing-box"));
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwxr-xr-x"));

        LinuxTunLauncher.restrictToOwner(binary);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(binary)))
                .isEqualTo("rwx------");
    }

    /**
     * A core found on the PATH can be a system-wide, root-owned one that every
     * account may run. The chmod fails there, and the grant used to go ahead
     * anyway, handing CAP_NET_ADMIN to every user of the machine. A core this
     * user cannot restrict gets no grant; the connect elevates per session.
     */
    @Test
    void aCoreThisUserCannotRestrictIsNeverGrantedTheCapability(@TempDir Path dir)
            throws IOException {
        // A file system with no POSIX permissions has nothing to restrict, and
        // this launcher only ever runs on Linux.
        Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path notOurs = dir.resolve("missing/sing-box");
        FakeRunner runner = new FakeRunner(cmd -> new CommandRunner.Result(0, ""));
        List<Path> tools = toolsIn(dir);
        LinuxTunLauncher launcher =
                new LinuxTunLauncher(runner, "elevator-that-must-not-launch", tools);

        assertThat(LinuxTunLauncher.restrictToOwner(notOurs)).isFalse();
        assertThatThrownBy(() -> launcher.launch(notOurs, dir.resolve("config.json"),
                new TunLauncher.Prompt("setup", "each connect")))
                .isInstanceOf(IOException.class);
        assertThat(runner.calls).extracting(List::getFirst).doesNotContain("elevator-that-must-not-launch")
                .allMatch(tools.getFirst().resolve("getcap").toString()::equals);
    }

    /**
     * Up to 1.24 a system-wide core, root's and runnable by every account, got
     * the grant all the same: the chmod failed and setcap still ran. Such a
     * core gets no grant since, but an upgraded install still carried the old
     * one: the launch read it and took the promptless path on a CAP_NET_ADMIN
     * every account could use, while the log said the core got none. It
     * elevates per connect now, and that elevated start takes the earlier
     * grant off before it starts the core. libcap before 2.27 printed the
     * same grant as "= cap_net_admin+ep".
     */
    @ParameterizedTest
    @ValueSource(strings = {" cap_net_admin=ep", " = cap_net_admin+ep"})
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void aSystemCoreAnEarlierTunlGrantedElevatesAndLosesTheGrant(String getcapSays,
            @TempDir Path dir) throws Exception {
        Path core = coreThisUserCannotRestrict(dir);
        Path asked = dir.resolve("asked-to-elevate");
        List<Path> tools = toolsIn(dir);
        FakeRunner caps = new FakeRunner(cmd -> new CommandRunner.Result(0,
                core.toAbsolutePath() + getcapSays + "\n"));
        LinuxTunLauncher launcher =
                new LinuxTunLauncher(caps, recordingElevator(dir, asked).toString(), tools);

        TunLauncher.Launched launched = launcher.launch(core, dir.resolve("config.json"),
                new TunLauncher.Prompt("setup", "each connect"));
        spawned.add(launched.process().toHandle());
        assertThat(launched.process().waitFor(10, TimeUnit.SECONDS)).isTrue();

        assertThat(launched.promptsEachLaunch())
                .as("a capability every account can use is not this user's to start on")
                .isTrue();
        List<String> elevated = Files.readAllLines(asked);
        assertThat(elevated.subList(0, 2)).containsExactly("/bin/sh", "-c");
        assertThat(elevated.get(2)).startsWith("'" + tools.getFirst().resolve("setcap") + "' -r '"
                + core.toAbsolutePath() + "' 2>/dev/null; SBSTOP=");
        assertThat(caps.calls).as("no new grant was asked for")
                .noneMatch(cmd -> cmd.contains("cap_net_admin+ep"));
    }

    /**
     * Capabilities Tunl never gave, such as an administrator's own broader
     * grant, are left as they are; the connect still elevates rather than
     * run on them.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void aSystemCoreWithCapabilitiesTunlNeverGaveElevatesAndKeepsThem(@TempDir Path dir)
            throws Exception {
        Path core = coreThisUserCannotRestrict(dir);
        Path asked = dir.resolve("asked-to-elevate");
        FakeRunner caps = new FakeRunner(cmd -> new CommandRunner.Result(0,
                core.toAbsolutePath() + " cap_net_admin,cap_net_raw=ep\n"));
        LinuxTunLauncher launcher = new LinuxTunLauncher(caps,
                recordingElevator(dir, asked).toString(), toolsIn(dir));

        TunLauncher.Launched launched = launcher.launch(core, dir.resolve("config.json"),
                new TunLauncher.Prompt("setup", "each connect"));
        spawned.add(launched.process().toHandle());
        assertThat(launched.process().waitFor(10, TimeUnit.SECONDS)).isTrue();

        assertThat(launched.promptsEachLaunch()).isTrue();
        assertThat(Files.readAllLines(asked).get(2)).startsWith("SBSTOP=").doesNotContain(" -r ");
    }

    @Test
    void onlyTheExactGrantAnEarlierTunlMadeCountsAsIts() {
        Path core = Path.of("/usr/local/bin/my tools/sing-box");

        assertThat(LinuxTunLauncher.isEarlierGrant(core, core + " cap_net_admin=ep\n")).isTrue();
        assertThat(LinuxTunLauncher.isEarlierGrant(core, core + " = cap_net_admin+ep")).isTrue();
        assertThat(LinuxTunLauncher.isEarlierGrant(core, core + " cap_net_admin=eip")).isFalse();
        assertThat(LinuxTunLauncher.isEarlierGrant(core,
                core + " cap_net_admin,cap_net_bind_service=ep")).isFalse();
        assertThat(LinuxTunLauncher.isEarlierGrant(core, "")).isFalse();
        assertThat(LinuxTunLauncher.isEarlierGrant(core, "/other/sing-box cap_net_admin=ep"))
                .isFalse();
    }

    /**
     * A core whose permissions this user cannot change, as with root's own
     * {@code /usr/bin/sing-box}: here a link to a file that is not there, so
     * the chmod fails whoever runs the test.
     */
    private static Path coreThisUserCannotRestrict(Path dir) throws IOException {
        Path core = Files.createSymbolicLink(dir.resolve("sing-box"), dir.resolve("root-owned"));
        assertThat(LinuxTunLauncher.restrictToOwner(core)).isFalse();
        return core;
    }

    /** A stand-in for pkexec that writes the command it was asked to run, one argument a line. */
    private static Path recordingElevator(Path dir, Path record) throws IOException {
        Path elevator = Files.writeString(dir.resolve("elevator"),
                "#!/bin/sh\nprintf '%s\\n' \"$@\" > '" + record + "'\n");
        elevator.toFile().setExecutable(true, true);
        return elevator;
    }
}
