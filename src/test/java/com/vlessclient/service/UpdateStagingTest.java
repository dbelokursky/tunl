package com.vlessclient.service;

import com.vlessclient.platform.PendingUpdate;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The staging directory is what stands between "an installer was downloaded"
 * and "an installer gets executed at the next start", so the tests here are
 * about the guarantees that make the second step safe: a marker that describes
 * the file next to it, a digest re-checked after the file has sat on disk, and
 * an attempt count that keeps a failing update from looping forever.
 */
class UpdateStagingTest {

    @TempDir
    Path tempDir;

    private PendingUpdate installerOf(String contents) throws Exception {
        Path installer = tempDir.resolve("staging").resolve("tunl_1.6.0.dmg");
        Files.createDirectories(installer.getParent());
        Files.writeString(installer, contents);
        return new PendingUpdate("1.6.0", installer, UpdateStaging.sha256(installer));
    }

    private UpdateStaging staging() {
        return new UpdateStaging(tempDir.resolve("staging"));
    }

    @Test
    void stagedUpdateComesBackAsItWentIn() throws Exception {
        UpdateStaging staging = staging();
        PendingUpdate update = installerOf("installer bytes");

        staging.stage(update);

        assertThat(staging.pending()).hasValueSatisfying(read -> {
            assertThat(read.version()).isEqualTo("1.6.0");
            assertThat(read.installer()).isEqualTo(update.installer());
            assertThat(read.digest()).isEqualTo(update.digest());
        });
    }

    @Test
    void noMarkerMeansNothingIsPending() {
        assertThat(staging().pending()).isEmpty();
    }

    @Test
    void markerPointingAtAMissingFileIsDiscarded() throws Exception {
        UpdateStaging staging = staging();
        PendingUpdate update = installerOf("installer bytes");
        staging.stage(update);

        Files.delete(update.installer());

        // Reporting a pending update whose file is gone would send the next
        // start into an apply that cannot possibly work.
        assertThat(staging.pending()).isEmpty();
    }

    @Test
    void verifyRejectsAnInstallerChangedAfterItWasStaged() throws Exception {
        UpdateStaging staging = staging();
        PendingUpdate update = installerOf("installer bytes");
        staging.stage(update);
        assertThat(staging.verify(update)).isTrue();

        // The window this closes: the file waits in a user-writable directory
        // across a restart, and is executed without being downloaded again.
        Files.writeString(update.installer(), "something else entirely");

        assertThat(staging.verify(update)).isFalse();
    }

    @Test
    void attemptsAreCountedAcrossReads() throws Exception {
        UpdateStaging staging = staging();
        staging.stage(installerOf("installer bytes"));
        assertThat(staging.attempts()).isZero();

        staging.recordAttempt();
        staging.recordAttempt();

        assertThat(staging.attempts()).isEqualTo(2);
    }

    @Test
    void clearRemovesInstallerMarkerAndApplierLeftovers() throws Exception {
        UpdateStaging staging = staging();
        PendingUpdate update = installerOf("installer bytes");
        staging.stage(update);
        Files.writeString(staging.dir().resolve("apply-update.log"), "relay output");

        staging.clear();

        assertThat(Files.exists(update.installer())).isFalse();
        assertThat(staging.pending()).isEmpty();
        try (var entries = Files.list(staging.dir())) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void unreadableMarkerIsTreatedAsNoUpdate() throws Exception {
        UpdateStaging staging = staging();
        Files.createDirectories(staging.dir());
        Files.writeString(staging.dir().resolve("pending.json"), "{ not json");

        assertThat(staging.pending()).isEmpty();
    }
}
