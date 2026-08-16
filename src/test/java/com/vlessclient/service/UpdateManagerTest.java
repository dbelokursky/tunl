package com.vlessclient.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateManagerTest {

    // -- compareVersions / isNewerVersion tests --

    @Test
    void isNewerVersion_newerMinor_returnsTrue() {
        assertThat(UpdateManager.isNewerVersion("0.2.0", "0.1.0")).isTrue();
    }

    @Test
    void isNewerVersion_sameVersion_returnsFalse() {
        assertThat(UpdateManager.isNewerVersion("0.1.0", "0.1.0")).isFalse();
    }

    @Test
    void isNewerVersion_majorBump_returnsTrue() {
        assertThat(UpdateManager.isNewerVersion("1.0.0", "0.9.9")).isTrue();
    }

    @Test
    void isNewerVersion_olderVersion_returnsFalse() {
        assertThat(UpdateManager.isNewerVersion("0.1.0", "0.2.0")).isFalse();
    }

    @Test
    void stripVersionPrefix_removesLeadingV() {
        assertThat(UpdateManager.stripVersionPrefix("v1.2.3")).isEqualTo("1.2.3");
        assertThat(UpdateManager.stripVersionPrefix("V1.2.3")).isEqualTo("1.2.3");
        assertThat(UpdateManager.stripVersionPrefix("1.2.3")).isEqualTo("1.2.3");
    }

    @Test
    void isNewerVersion_withVPrefix_afterStripping() {
        String candidate = UpdateManager.stripVersionPrefix("v0.2.0");
        String current = UpdateManager.stripVersionPrefix("v0.1.0");
        assertThat(UpdateManager.isNewerVersion(candidate, current)).isTrue();
    }

    // -- installer asset selection --

    @Test
    void findInstallerAssetUrl_picksThisPlatformsInstaller() throws Exception {
        // A release carrying both installers: each platform must pick its own.
        String json = """
                {
                  "assets": [
                    {
                      "name": "checksums.txt",
                      "browser_download_url": "https://github.com/x/releases/checksums.txt"
                    },
                    {
                      "name": "VLESS-Client-0.2.0.dmg",
                      "browser_download_url": "https://github.com/x/releases/VLESS-Client-0.2.0.dmg"
                    },
                    {
                      "name": "VLESS Client-0.2.0.msi",
                      "browser_download_url": "https://github.com/x/releases/VLESS-Client-0.2.0.msi"
                    },
                    {
                      "name": "vless-client_0.2.0_amd64.deb",
                      "browser_download_url": "https://github.com/x/releases/vless-client_0.2.0_amd64.deb"
                    }
                  ]
                }
                """;
        var assets = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(json).path("assets");

        String url = UpdateManager.findInstallerAssetUrl(assets);

        assertThat(url).endsWith(UpdateManager.installerExtension());
        assertThat(url).startsWith("https://github.com/x/releases/");
    }

    @Test
    void findInstallerAssetUrl_noInstallerAsset_returnsEmpty() throws Exception {
        String json = """
                {
                  "assets": [
                    {
                      "name": "source.tar.gz",
                      "browser_download_url": "https://example.com/source.tar.gz"
                    }
                  ]
                }
                """;
        var assets = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(json).path("assets");

        assertThat(UpdateManager.findInstallerAssetUrl(assets)).isEmpty();
    }

    @Test
    void installerExtension_matchesHostPlatform() {
        String expected = switch (com.vlessclient.platform.Platform.current()) {
            case WINDOWS -> ".msi";
            case LINUX -> ".deb";
            default -> ".dmg";
        };
        assertThat(UpdateManager.installerExtension()).isEqualTo(expected);
    }

    @Test
    void versionFromReleaseUrl_readsTheTagOutOfTheAssetPath() {
        // The version recorded with a staged installer has to describe the file
        // that was actually fetched, so it is read back off that file's URL.
        assertThat(UpdateManager.versionFromReleaseUrl(
                "https://github.com/dbelokursky/tunl/releases/download/v1.6.0/tunl_1.6.0.dmg"))
                .isEqualTo("1.6.0");
    }

    @Test
    void versionFromReleaseUrl_isEmptyForAnythingOutsideTheReleasePrefix() {
        assertThat(UpdateManager.versionFromReleaseUrl(
                "https://evil.example.com/v9.9.9/tunl.dmg")).isEmpty();
        assertThat(UpdateManager.versionFromReleaseUrl(null)).isEmpty();
        assertThat(UpdateManager.versionFromReleaseUrl(
                "https://github.com/dbelokursky/tunl/releases/download/")).isEmpty();
    }

    @Test
    void downloadPolicy_downloadsUnlessSomethingIsAlreadyStaged() {
        // Re-downloading on top of a verified installer that is already
        // waiting is the one case not worth the bytes; everything else is.
        assertThat(UpdateDownloadPolicy.shouldDownloadNow(true)).isFalse();
        assertThat(UpdateDownloadPolicy.shouldDownloadNow(false)).isTrue();
    }
}
