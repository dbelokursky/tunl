package com.vlessclient.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * Three threads can reach the download — the six-hourly timer, the tunnel
     * coming up, and Settings' own check — and the "already staged?" guard
     * upstream cannot separate them: both see nothing staged and both proceed.
     * Two streams copied into one staging path with REPLACE_EXISTING produce
     * bytes matching neither, so the digest check throws away both downloads.
     */
    @Test
    void aSecondDownloadIsRefusedWhileOneIsRunning() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // Counts arrivals at the fetch itself, which is what the gate has to
        // keep to one at a time. A refused caller returns null without ever
        // getting here — and so does a failed one, so the count is the only
        // thing that tells the two apart.
        AtomicInteger fetches = new AtomicInteger();
        UpdateManager manager = new UpdateManager() {
            @Override
            Path fetchAndStage(String url, String expectedDigest) {
                fetches.incrementAndGet();
                inside.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }
        };

        Thread first = new Thread(() -> manager.downloadUpdate(OFFICIAL, DIGEST), "first");
        first.setDaemon(true);
        first.start();
        assertThat(inside.await(10, TimeUnit.SECONDS))
                .as("the first download never started").isTrue();

        try {
            manager.downloadUpdate(OFFICIAL, DIGEST);
            assertThat(fetches)
                    .as("a second fetch started alongside the first, into the same path")
                    .hasValue(1);
        } finally {
            release.countDown();
            first.join(TimeUnit.SECONDS.toMillis(10));
        }

        // The gate has to reopen, or the app would download nothing ever again.
        assertThat(manager.downloadingProperty().get()).isFalse();
        manager.downloadUpdate(OFFICIAL, DIGEST);
        assertThat(fetches).as("the gate stayed shut after the first download finished")
                .hasValue(2);
    }

    /**
     * The Settings row reads this flag to say "Downloading…", and nothing else
     * reports the fetch now that no button asks for one. Every refusal above
     * returns from a different point inside the download, so a flag cleared on
     * the way out of only some of them would leave the row claiming a download
     * is running for the rest of the session — with no button left to press.
     */
    @Test
    void theDownloadingFlagIsClearedByEveryRefusalPath() {
        UpdateManager manager = new UpdateManager();

        assertThat(manager.downloadingProperty().get())
                .as("nothing has been downloaded yet").isFalse();

        for (String[] refusal : new String[][] {
                {"https://evil.example.com/tunl_9.9.9.dmg", DIGEST},
                {"https://github.com/dbelokursky/tunl/raw/main/x.dmg", DIGEST},
                {OFFICIAL, ""},
                {OFFICIAL, null},
                {OFFICIAL, "md5:abcd"},
                {null, DIGEST},
                {"  ", DIGEST}}) {
            assertThat(manager.downloadUpdate(refusal[0], refusal[1])).isNull();
            assertThat(manager.downloadingProperty().get())
                    .as("still flagged as downloading after refusing url=%s digest=%s",
                            refusal[0], refusal[1])
                    .isFalse();
        }
    }
}
