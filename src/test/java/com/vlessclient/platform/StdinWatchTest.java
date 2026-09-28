package com.vlessclient.platform;

import com.vlessclient.testing.Await;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The watch the TUN wrappers that run as the user keep over the core, run in a
 * real {@code /bin/sh} around a stand-in core. The loop it replaced started a
 * {@code sleep 0.3} process three times a second for as long as the tunnel was
 * up; these pin that the watch starts nothing while it waits, and that it
 * still stops the core on every path the loop covered.
 */
@EnabledOnOs({OS.MAC, OS.LINUX})
class StdinWatchTest {

    @TempDir
    Path dir;

    /**
     * Everything a test spawned, killed forcibly afterwards so a failed
     * assertion cannot leave a stand-in core behind.
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
    void theWatchStartsNoProcessWhileItWaits() throws Exception {
        Process wrapper = startAround(coreThatRuns("exec sleep 60"));
        ProcessHandle core = awaitCore();

        Set<Long> before = descendants(wrapper);
        // The old loop started a new sleep every 0.3 s: five of them in this time.
        Thread.sleep(1_500);
        Set<Long> after = descendants(wrapper);

        assertThat(core.isAlive()).as("the core still runs").isTrue();
        assertThat(after).as("processes under the wrapper, 1.5 s apart").isEqualTo(before);
    }

    /**
     * What a stop does, and what the system does when the app dies, even from a
     * SIGKILL: the app's end of the wrapper's stdin is closed.
     */
    @Test
    void closingTheWrappersStdinStopsTheCoreAndEndsTheWrapper() throws Exception {
        Process wrapper = startAround(coreThatRuns("exec sleep 60"));
        ProcessHandle core = awaitCore();

        wrapper.getOutputStream().close();

        assertThat(wrapper.waitFor(10, TimeUnit.SECONDS))
                .as("the wrapper ends once its stdin is closed").isTrue();
        Await.until("the core to be stopped", () -> !core.isAlive(), Duration.ofSeconds(5));
    }

    @Test
    void aCoreThatExitsEndsTheWrapperAndItsWatcher() throws Exception {
        Process wrapper = startAround(coreThatRuns("sleep 1"));
        ProcessHandle core = awaitCore();
        Set<Long> underWrapper = descendants(wrapper);

        assertThat(wrapper.waitFor(10, TimeUnit.SECONDS))
                .as("the wrapper ends with the core").isTrue();
        assertThat(core.isAlive()).isFalse();
        Await.until("nothing the wrapper started to be left running",
                () -> underWrapper.stream().noneMatch(pid ->
                        ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)),
                Duration.ofSeconds(5));
    }

    /** The engine's last resort, {@code Process.destroy()}. */
    @Test
    void aSigtermToTheWrapperStopsTheCore() throws Exception {
        Process wrapper = startAround(coreThatRuns("exec sleep 60"));
        ProcessHandle core = awaitCore();

        wrapper.destroy();

        assertThat(wrapper.waitFor(10, TimeUnit.SECONDS))
                .as("the wrapper ends after SIGTERM").isTrue();
        Await.until("the core to be reaped, not orphaned", () -> !core.isAlive(),
                Duration.ofSeconds(5));
    }

    @Test
    void theTrapIsSetBeforeTheCoreStarts() {
        String script = StdinWatch.around("'/opt/sing-box' run -c '/tmp/c.json'");

        assertThat(script).contains("EXIT INT TERM");
        assertThat(script.indexOf("trap "))
                .as("a signal between the two must not leave the core running")
                .isLessThan(script.indexOf(" run -c "));
        assertThat(script).doesNotContain("sleep");
    }

    private Path coreThatRuns(String body) throws IOException {
        Path core = Files.writeString(dir.resolve("core.sh"),
                "#!/bin/sh\necho $$ > '" + dir.resolve("core.pid") + "'\n" + body + "\n");
        core.toFile().setExecutable(true, true);
        return core;
    }

    private Process startAround(Path core) throws IOException {
        Process wrapper = new ProcessBuilder("/bin/sh", "-c",
                StdinWatch.around("'" + core + "'"))
                .redirectErrorStream(true).start();
        spawned.add(wrapper.toHandle());
        return wrapper;
    }

    private ProcessHandle awaitCore() {
        long pid = Long.parseLong(Await.untilValue("the stand-in core to start",
                () -> readQuietly(dir.resolve("core.pid")), text -> !text.isEmpty(),
                Duration.ofSeconds(5)));
        // A handle taken while the core is certainly alive carries its start
        // time, so a later check cannot hit a recycled pid.
        ProcessHandle core = ProcessHandle.of(pid)
                .orElseThrow(() -> new AssertionError("stand-in core " + pid + " already gone"));
        spawned.add(core);
        return core;
    }

    private static Set<Long> descendants(Process wrapper) {
        return wrapper.toHandle().descendants().map(ProcessHandle::pid)
                .collect(Collectors.toSet());
    }

    private static String readQuietly(Path file) {
        try {
            return Files.readString(file).strip();
        } catch (IOException e) {
            return "";
        }
    }
}
