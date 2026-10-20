package com.vlessclient.service;

import com.vlessclient.platform.PendingUpdate;
import com.vlessclient.platform.UpdateApplier;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.vlessclient.service.SignedTestRelease.checkWith;
import static com.vlessclient.service.SignedTestRelease.ed25519;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The half of update verification that says yes: a newer release, its
 * installer, a signature the key accepts, and the installer staged for the
 * next start.
 *
 * <p>Only rejections were tested. The release key is the maintainer's, so
 * every download a test could make ended at "signature does not match", and a
 * change that signed or checked the wrong manifest (the API's digest for the
 * downloaded one, the version and the asset swapped) left every test green
 * while every installed copy refused every update. Here the release is signed
 * with a key made for the test, over the manifest release.yml signs.</p>
 */
class UpdateAcceptanceTest {

    private static final String VERSION = "999.0.0";

    @TempDir
    Path dir;

    @Test
    void aNewerReleaseSignedForThisInstallerIsDownloadedAndStaged() throws Exception {
        KeyPair release = ed25519();
        SignedTestRelease fixture = new SignedTestRelease(VERSION, release.getPrivate());
        UpdateStaging.ReleaseCheck check = checkWith(release);
        UpdateStaging staging = new UpdateStaging(dir.resolve("staging"), check);
        UpdateManager manager = new UpdateManager(fixture.client, staging,
                UpdateApplier::current, check);

        assertThat(manager.processReleaseResponse(fixture.releaseJson()))
                .isEqualTo(UpdateManager.CheckResult.UPDATE_AVAILABLE);
        Path installer = manager.downloadUpdate(fixture.url, fixture.digest);

        assertThat(installer).as("downloaded and accepted").isNotNull().exists();
        assertThat(installer).hasBinaryContent(fixture.bytes);
        assertThat(manager.hasStagedUpdate()).isTrue();
        PendingUpdate staged = staging.pending().orElseThrow();
        assertThat(staged.version()).isEqualTo(VERSION);
        assertThat(staging.verify(staged)).as("and verified again before it is run").isTrue();
    }

    @Test
    void theManifestIsTheOneReleaseYmlSigns() {
        // printf 'tunl-release-v1\n%s\n%s\nsha256:%s' "$V" "$asset" "$hex"
        assertThat(ReleaseSignature.manifestFor(VERSION, "tunl_999.0.0.dmg", "sha256:ab12"))
                .isEqualTo("tunl-release-v1\n999.0.0\ntunl_999.0.0.dmg\nsha256:ab12");
    }

    @Test
    void anInstallerSignedWithAnotherKeyIsNotStaged() throws Exception {
        SignedTestRelease fixture = new SignedTestRelease(VERSION, ed25519().getPrivate());
        UpdateStaging.ReleaseCheck check = checkWith(ed25519());
        UpdateStaging staging = new UpdateStaging(dir.resolve("staging"), check);
        UpdateManager manager = new UpdateManager(fixture.client, staging,
                UpdateApplier::current, check);
        manager.processReleaseResponse(fixture.releaseJson());

        assertThat(manager.downloadUpdate(fixture.url, fixture.digest)).isNull();
        assertThat(manager.hasStagedUpdate()).isFalse();
        assertThat(staging.pending()).isEmpty();
    }

    @Test
    void theFixtureServesWhatItWasGiven() throws Exception {
        SignedTestRelease.ServingClient client = new SignedTestRelease.ServingClient();
        client.serve("https://example.invalid/a", "x".getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> found = client.send(
                HttpRequest.newBuilder(URI.create("https://example.invalid/a")).build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> missing = client.send(
                HttpRequest.newBuilder(URI.create("https://example.invalid/b")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(found.statusCode()).isEqualTo(200);
        assertThat(found.body()).isEqualTo("x");
        assertThat(missing.statusCode()).isEqualTo(404);
    }
}
