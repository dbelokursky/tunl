package com.vlessclient.service;

import com.vlessclient.platform.PendingUpdate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
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

    /**
     * Signature check stubbed to accept: these cases are about the marker and
     * the bytes. The real check has its own tests below - a forged signature
     * must be rejected, and that one needs no private key.
     */
    private UpdateStaging staging() {
        return new UpdateStaging(tempDir.resolve("staging"), (digest, sig) -> true);
    }

    @Test
    void stagedUpdateComesBackAsItWentIn() throws Exception {
        UpdateStaging staging = staging();
        PendingUpdate update = installerOf("installer bytes");

        staging.stage(update, "test-signature");

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
        staging.stage(update, "test-signature");

        Files.delete(update.installer());

        // Reporting a pending update whose file is gone would send the next
        // start into an apply that cannot possibly work.
        assertThat(staging.pending()).isEmpty();
    }

    @Test
    void verifyRejectsAnInstallerChangedAfterItWasStaged() throws Exception {
        UpdateStaging staging = staging();
        PendingUpdate update = installerOf("installer bytes");
        staging.stage(update, "test-signature");
        assertThat(staging.verify(update)).isTrue();

        // The window this closes: the file waits in a user-writable directory
        // across a restart, and is executed without being downloaded again.
        Files.writeString(update.installer(), "something else entirely");

        assertThat(staging.verify(update)).isFalse();
    }

    @Test
    void sha256IncludesEveryByteAcrossMultipleReadBuffers() throws Exception {
        byte[] contents = new byte[20_000];
        for (int i = 0; i < contents.length; i++) {
            contents[i] = (byte) (i % 251);
        }
        Path installer = tempDir.resolve("large-installer.bin");
        Files.write(installer, contents);
        String expected = "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(contents));

        assertThat(UpdateStaging.sha256(installer)).isEqualTo(expected);
    }

    @Test
    void attemptsAreCountedAcrossReads() throws Exception {
        UpdateStaging staging = staging();
        staging.stage(installerOf("installer bytes"), "test-signature");
        assertThat(staging.attempts()).isZero();

        staging.recordAttempt();
        staging.recordAttempt();

        assertThat(staging.attempts()).isEqualTo(2);
    }

    @Test
    void clearRemovesInstallerMarkerAndApplierLeftovers() throws Exception {
        UpdateStaging staging = staging();
        PendingUpdate update = installerOf("installer bytes");
        staging.stage(update, "test-signature");
        Files.writeString(staging.dir().resolve("apply-update.log"), "relay output");

        staging.clear();

        assertThat(Files.exists(update.installer())).isFalse();
        assertThat(staging.pending()).isEmpty();
        try (var entries = Files.list(staging.dir())) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void unreadableMarkerIsHandledAcrossAllAccessors() throws Exception {
        UpdateStaging staging = staging();
        Files.createDirectories(staging.dir());
        Files.writeString(staging.dir().resolve("pending.json"), "{ not json");

        assertThat(staging.stagedAt()).isZero();
        assertThat(staging.attempts()).isZero();
        staging.recordAttempt();
        assertThat(staging.pending()).isEmpty();
    }

    // --- The staged update must not be able to certify itself ---

    /** The production wiring: the real Ed25519 check against the compiled-in key. */
    private UpdateStaging realCheckStaging() {
        return new UpdateStaging(tempDir.resolve("staging"));
    }

    @Test
    @DisplayName("a marker that rewrites the digest cannot re-sign it")
    void aRewrittenDigestIsRejected() throws Exception {
        UpdateStaging staging = realCheckStaging();
        PendingUpdate original = installerOf("the genuine installer");
        staging.stage(original, "not-a-real-signature");

        // The whole attack in three lines: whoever can replace the installer in
        // this directory can also rewrite the digest in the marker beside it,
        // so a digest-only check passes. What they cannot do is produce a
        // signature over the new digest without the release private key.
        Path installer = original.installer();
        Files.writeString(installer, "a hostile installer");
        Path marker = tempDir.resolve("staging").resolve("pending.json");
        String rewritten = Files.readString(marker)
                .replace(original.digest(), UpdateStaging.sha256(installer));
        Files.writeString(marker, rewritten);

        PendingUpdate staged = staging.pending().orElseThrow();
        assertThat(UpdateStaging.sha256(staged.installer()))
                .as("the file and the marker agree, which used to be enough")
                .isEqualTo(staged.digest());
        assertThat(staging.verify(staged))
                .as("but the release never signed this digest")
                .isFalse();
    }

    @Test
    @DisplayName("an unsigned marker is refused rather than trusted")
    void aMarkerWithNoSignatureIsRejected() throws Exception {
        UpdateStaging staging = realCheckStaging();
        PendingUpdate update = installerOf("installer bytes");
        staging.stage(update, "");

        assertThat(staging.verify(staging.pending().orElseThrow())).isFalse();
    }

    @Test
    @DisplayName("an installer outside the staging directory is refused")
    void anInstallerOutsideTheDirectoryIsRefused() throws Exception {
        UpdateStaging staging = staging();
        PendingUpdate update = installerOf("installer bytes");
        staging.stage(update, "test-signature");

        // A rewritten marker pointing anywhere on the machine — the file it
        // names is what this app executes at the next start.
        Path elsewhere = tempDir.resolve("elsewhere.dmg");
        Files.writeString(elsewhere, "something else entirely");
        Path marker = tempDir.resolve("staging").resolve("pending.json");
        Files.writeString(marker, Files.readString(marker).replace(
                update.installer().toAbsolutePath().toString(),
                elsewhere.toAbsolutePath().toString()));

        assertThat(staging.pending())
                .as("the marker names the file we are about to run; it has to "
                        + "stay inside the directory this app owns")
                .isEmpty();
    }
}
