package com.vlessclient.service;

import com.vlessclient.platform.PendingUpdate;
import com.vlessclient.platform.UpdateApplier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decision made before anything else starts: install a staged update, or
 * carry on booting. Its two dangerous answers are "yes" when the installer is
 * no longer the one that was verified, and "yes, again" forever — the second
 * being what a failed swap turns into, since applying always ends with this
 * process exiting and the same build starting back up.
 */
class UpdateBootstrapTest {

    @TempDir
    Path tempDir;

    /** Records what it was asked to apply and answers with a fixed outcome. */
    private static final class RecordingApplier implements UpdateApplier {
        private final Outcome outcome;
        private final List<PendingUpdate> applied = new ArrayList<>();

        RecordingApplier(Outcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public Outcome apply(PendingUpdate update) {
            applied.add(update);
            return outcome;
        }

        @Override
        public boolean selfUpdates() {
            return true;
        }
    }

    private UpdateStaging staging() {
        return new UpdateStaging(tempDir.resolve("staging"));
    }

    private PendingUpdate stage(UpdateStaging staging, String version) throws Exception {
        Path installer = staging.dir().resolve("tunl_" + version + ".dmg");
        Files.writeString(installer, "installer for " + version);
        PendingUpdate update =
                new PendingUpdate(version, installer, UpdateStaging.sha256(installer));
        staging.stage(update);
        return update;
    }

    @Test
    void withNothingStagedStartupJustContinues() {
        RecordingApplier applier = new RecordingApplier(UpdateApplier.Outcome.HANDED_OFF);

        assertThat(UpdateBootstrap.applyPendingUpdate(staging(), applier, "1.5.0")).isFalse();
        assertThat(applier.applied).isEmpty();
    }

    @Test
    void aHandoffTellsTheCallerToExit() throws Exception {
        UpdateStaging staging = staging();
        PendingUpdate update = stage(staging, "1.6.0");
        RecordingApplier applier = new RecordingApplier(UpdateApplier.Outcome.HANDED_OFF);

        assertThat(UpdateBootstrap.applyPendingUpdate(staging, applier, "1.5.0")).isTrue();
        assertThat(applier.applied).containsExactly(update);
        // The installer is in use by the relay right now — clearing it here
        // would delete the file being installed.
        assertThat(Files.exists(update.installer())).isTrue();
    }

    @Test
    void anUpdateThatIsNoLongerNewerIsCleanedUp() throws Exception {
        UpdateStaging staging = staging();
        PendingUpdate update = stage(staging, "1.6.0");
        RecordingApplier applier = new RecordingApplier(UpdateApplier.Outcome.HANDED_OFF);

        // This is the run after a successful swap: the new build finds the
        // marker its predecessor left behind.
        assertThat(UpdateBootstrap.applyPendingUpdate(staging, applier, "1.6.0")).isFalse();

        assertThat(applier.applied).isEmpty();
        assertThat(staging.pending()).isEmpty();
        assertThat(Files.exists(update.installer())).isFalse();
    }

    @Test
    void aTamperedInstallerIsNeverHandedToTheApplier() throws Exception {
        UpdateStaging staging = staging();
        PendingUpdate update = stage(staging, "1.6.0");
        Files.writeString(update.installer(), "swapped between runs");
        RecordingApplier applier = new RecordingApplier(UpdateApplier.Outcome.HANDED_OFF);

        assertThat(UpdateBootstrap.applyPendingUpdate(staging, applier, "1.5.0")).isFalse();

        assertThat(applier.applied).isEmpty();
        assertThat(staging.pending()).isEmpty();
    }

    @Test
    void aRepeatedlyFailingUpdateIsAbandonedRatherThanLoopingForever() throws Exception {
        UpdateStaging staging = staging();
        stage(staging, "1.6.0");
        RecordingApplier applier = new RecordingApplier(UpdateApplier.Outcome.HANDED_OFF);

        for (int attempt = 0; attempt < UpdateBootstrap.MAX_ATTEMPTS; attempt++) {
            assertThat(UpdateBootstrap.applyPendingUpdate(staging, applier, "1.5.0")).isTrue();
        }

        // Nothing changed on disk, so the next start finds the same marker —
        // and must boot the app instead of handing off again.
        assertThat(UpdateBootstrap.applyPendingUpdate(staging, applier, "1.5.0")).isFalse();
        assertThat(applier.applied).hasSize(UpdateBootstrap.MAX_ATTEMPTS);
        assertThat(staging.pending()).isEmpty();
    }

    @Test
    void anUnsupportedPlatformKeepsTheStagedUpdate() throws Exception {
        UpdateStaging staging = staging();
        PendingUpdate update = stage(staging, "1.6.0");
        RecordingApplier applier = new RecordingApplier(UpdateApplier.Outcome.UNSUPPORTED);

        assertThat(UpdateBootstrap.applyPendingUpdate(staging, applier, "1.5.0")).isFalse();

        // Linux installs through the package manager: the app keeps running
        // and the installer stays available to the user.
        assertThat(staging.pending()).isPresent();
        assertThat(Files.exists(update.installer())).isTrue();
    }
}
