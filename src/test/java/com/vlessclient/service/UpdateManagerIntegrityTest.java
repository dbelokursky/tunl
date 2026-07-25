package com.vlessclient.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integrity gates on the in-app updater. The app ships unsigned, so users are
 * trained to click past Gatekeeper/SmartScreen — an installer that lands in
 * Downloads is likely to be run. A download is therefore only kept when it
 * comes from the official release prefix AND matches the SHA-256 the Releases
 * API published for that asset.
 *
 * <p>The download itself is exercised through the refusal paths, which return
 * before any network call — the happy path needs a real asset and is covered
 * by the manual release checklist.</p>
 */
class UpdateManagerIntegrityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String OFFICIAL =
            "https://github.com/dbelokursky/tunl/releases/download/v9.9.9/tunl_9.9.9.dmg";
    private static final String DIGEST =
            "sha256:0000000000000000000000000000000000000000000000000000000000000000";

    private static JsonNode assets(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void assetCarriesTheDigestPublishedForThatSameAsset() throws Exception {
        JsonNode release = assets("""
                [
                  {"name":"tunl_1.5.0_amd64.deb","browser_download_url":"https://x/amd64",
                   "digest":"sha256:aaaa"},
                  {"name":"tunl_1.5.0_arm64.deb","browser_download_url":"https://x/arm64",
                   "digest":"sha256:bbbb"}
                ]""");

        UpdateManager.InstallerAsset picked =
                UpdateManager.findInstallerAsset(release, ".deb", "arm64");

        // The URL and the digest must come from the same entry, or the bytes
        // would be checked against a different asset's hash.
        assertThat(picked.url()).isEqualTo("https://x/arm64");
        assertThat(picked.digest()).isEqualTo("sha256:bbbb");
    }

    @Test
    void assetWithoutADigestIsReportedAsSuch() throws Exception {
        JsonNode release = assets("""
                [{"name":"tunl_1.5.0.dmg","browser_download_url":"https://x/dmg"}]""");

        assertThat(UpdateManager.findInstallerAsset(release, ".dmg", "arm64").digest()).isEmpty();
    }

    @Test
    void refusesAnUrlOutsideTheOfficialReleasePrefix() {
        UpdateManager manager = new UpdateManager();

        // A tampered API response naming somebody else's host must not be
        // fetched, digest or no digest.
        assertThat(manager.downloadUpdate(
                "https://evil.example.com/tunl_9.9.9.dmg", DIGEST)).isNull();
        // Same host, but not under the releases path.
        assertThat(manager.downloadUpdate(
                "https://github.com/dbelokursky/tunl/raw/main/x.dmg", DIGEST)).isNull();
    }

    @Test
    void refusesToDownloadWhenTheReleasePublishedNoDigest() {
        UpdateManager manager = new UpdateManager();

        assertThat(manager.downloadUpdate(OFFICIAL, "")).isNull();
        assertThat(manager.downloadUpdate(OFFICIAL, null)).isNull();
        // A non-sha256 digest form is not silently accepted either.
        assertThat(manager.downloadUpdate(OFFICIAL, "md5:abcd")).isNull();
    }

    @Test
    void refusesABlankUrl() {
        UpdateManager manager = new UpdateManager();

        assertThat(manager.downloadUpdate(null, DIGEST)).isNull();
        assertThat(manager.downloadUpdate("  ", DIGEST)).isNull();
    }
}
