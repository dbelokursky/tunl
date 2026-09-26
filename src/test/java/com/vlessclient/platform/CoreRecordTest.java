package com.vlessclient.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A core started without elevation is a plain child of the app, and killing
 * the app left it running with the ports the next run needs. The record lets
 * the next run end that core, and nothing else.
 */
class CoreRecordTest {

    @TempDir
    Path dir;

    private final List<Process> standIns = new ArrayList<>();

    @AfterEach
    void killStandIns() {
        standIns.forEach(Process::destroyForcibly);
    }

    @Test
    void theNextRunEndsTheCoreARunLeftRunning() throws Exception {
        Process core = standIn();
        assertThat(record().write(core.toHandle())).isNotNull();

        assertThat(record().endLeftover()).isEqualTo(CoreRecord.Leftover.ENDED);

        assertThat(core.onExit()).succeedsWithin(Duration.ofSeconds(10));
        assertThat(file()).doesNotExist();
    }

    @Test
    void aProcessThatWasGivenTheRecordedPidIsLeftAlone() throws Exception {
        Process other = standIn();
        ProcessHandle.Info info = other.toHandle().info();
        record().write(new CoreRecord.Entry(other.pid(),
                info.startInstant().orElseThrow().minus(Duration.ofHours(1)),
                info.command().orElse("")));

        assertThat(record().endLeftover()).isEqualTo(CoreRecord.Leftover.NOT_THE_CORE);

        assertThat(other.isAlive()).isTrue();
        assertThat(file()).doesNotExist();
    }

    @Test
    void aProcessOfAnotherExecutableIsLeftAlone() throws Exception {
        Process other = standIn();
        ProcessHandle.Info info = other.toHandle().info();
        assumeTrue(info.command().isPresent(), "the system does not report the executable");
        record().write(new CoreRecord.Entry(other.pid(), info.startInstant().orElseThrow(),
                dir.resolve("sing-box").toString()));

        assertThat(record().endLeftover()).isEqualTo(CoreRecord.Leftover.NOT_THE_CORE);

        assertThat(other.isAlive()).isTrue();
    }

    @Test
    void anExecutableTheSystemReportsAsDeletedIsStillTheRecordedOne() {
        // Linux appends " (deleted)" to /proc/<pid>/exe once the file is gone,
        // and a release with another core pin deletes the old core at startup.
        assertThat(CoreRecord.sameExecutable("/opt/tunl/sing-box", "/opt/tunl/sing-box (deleted)"))
                .isTrue();
        assertThat(CoreRecord.sameExecutable("/opt/tunl/sing-box", "/opt/tunl/sing-box")).isTrue();
        assertThat(CoreRecord.sameExecutable("/opt/tunl/sing-box", "/usr/bin/sleep")).isFalse();
        assertThat(CoreRecord.sameExecutable("/opt/tunl/sing-box", "/opt/tunl/sing-box2 (deleted)"))
                .isFalse();
    }

    /**
     * The same on a real process. Linux only: macOS reports a deleted
     * executable by its old path, and kills a copied system binary anyway.
     */
    @EnabledOnOs(OS.LINUX)
    @Test
    void aCoreWhoseBinaryWasDeletedSinceIsStillEnded() throws Exception {
        Path binary = dir.resolve("sing-box");
        Files.copy(Path.of("/bin/sleep"), binary);
        assertThat(binary.toFile().setExecutable(true)).isTrue();
        Process core = new ProcessBuilder(binary.toString(), "30")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        standIns.add(core);
        assertThat(record().write(core.toHandle())).isNotNull();
        // An update replaced the core before the next run ended this one.
        Files.delete(binary);

        assertThat(record().endLeftover()).isEqualTo(CoreRecord.Leftover.ENDED);
        assertThat(core.onExit()).succeedsWithin(Duration.ofSeconds(10));
    }

    @Test
    void aRecordWhoseCoreHasExitedIsDropped() throws Exception {
        Process core = standIn();
        assertThat(record().write(core.toHandle())).isNotNull();
        core.destroyForcibly().waitFor();

        assertThat(record().endLeftover()).isEqualTo(CoreRecord.Leftover.NONE);
        assertThat(file()).doesNotExist();
    }

    @Test
    void aDamagedRecordIsDropped() throws Exception {
        Files.writeString(file(), "pid=not a number");

        assertThat(record().endLeftover()).isEqualTo(CoreRecord.Leftover.NONE);
        assertThat(file()).doesNotExist();
    }

    @Test
    void clearingACoreKeepsTheRecordOfTheCoreThatReplacedIt() throws Exception {
        CoreRecord record = record();
        CoreRecord.Entry first = record.write(standIn().toHandle());
        CoreRecord.Entry second = record.write(standIn().toHandle());
        assertThat(second).isNotNull();

        record.clear(first);
        assertThat(record.read()).contains(second);

        record.clear(second);
        assertThat(file()).doesNotExist();
    }

    private CoreRecord record() {
        return new CoreRecord(file());
    }

    private Path file() {
        return dir.resolve(CoreRecord.FILE_NAME);
    }

    /**
     * A TUN core's launcher is recorded beside the direct core, and a run
     * learns from the record whether it is still up, for as long as it is.
     */
    @Test
    void theTunnelsRecordNamesItsRunningCoreUntilItExits() throws Exception {
        Process core = standIn();
        CoreRecord tunnel = record().forTunnel();
        assertThat(tunnel.write(core.toHandle())).isNotNull();

        assertThat(dir.resolve(CoreRecord.TUNNEL_FILE_NAME)).exists();
        assertThat(dir.resolve(CoreRecord.FILE_NAME)).as("the direct core's record").doesNotExist();
        assertThat(tunnel.read()).map(CoreRecord.Entry::command)
                .as("no executable: a launcher can replace its program as it runs")
                .contains("");
        assertThat(tunnel.runningCore()).map(ProcessHandle::pid).contains(core.pid());

        core.destroy();
        assertThat(core.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(tunnel.runningCore()).isEmpty();
        assertThat(dir.resolve(CoreRecord.TUNNEL_FILE_NAME))
                .as("the record of a core that has exited").doesNotExist();
    }

    @Test
    void aProcessThatWasGivenTheRecordedPidIsNoRunningCore() throws Exception {
        Process other = standIn();
        CoreRecord tunnel = record().forTunnel();
        tunnel.write(new CoreRecord.Entry(other.pid(),
                java.time.Instant.now().minus(Duration.ofHours(1)), ""));

        assertThat(tunnel.runningCore()).isEmpty();
        assertThat(other.isAlive()).as("left alone").isTrue();
        assertThat(dir.resolve(CoreRecord.TUNNEL_FILE_NAME)).doesNotExist();
    }

    /** A process to record: a JVM that sleeps for half a minute unless ended sooner. */
    private Process standIn() throws Exception {
        Process process = new ProcessBuilder(
                ProcessHandle.current().info().command().orElse("java"),
                "-cp", System.getProperty("java.class.path"),
                StandInCommand.class.getName(), "hang")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        standIns.add(process);
        return process;
    }
}
