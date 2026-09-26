package com.vlessclient.service;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where {@link SingBoxInstaller}'s runtime fallback downloads the core from.
 * No build or CI run ever requests these addresses (the bundling scripts have
 * their own), so a wrong one would only show on a user's machine, as a core
 * that cannot be installed.
 */
class SingBoxDownloadUrlTest {

    private static String url(String release, String os, String ext, String arch) {
        return String.format(Locale.ROOT,
                SingBoxInstaller.downloadUrlTemplate(release, os, ext), "1.14.2", "1.14.2", arch);
    }

    @Test
    void aPinnedReleaseIsDownloadedFromTunlsOwnBuild() {
        assertThat(url("core-v1.14.2-tunl1", "darwin", "tar.gz", "arm64")).isEqualTo(
                "https://github.com/dbelokursky/tunl/releases/download/core-v1.14.2-tunl1/"
                        + "sing-box-1.14.2-darwin-arm64.tar.gz");
    }

    @Test
    void noReleaseMeansUpstreamsBuild() {
        assertThat(url("", "windows", "zip", "amd64")).isEqualTo(
                "https://github.com/SagerNet/sing-box/releases/download/v1.14.2/"
                        + "sing-box-1.14.2-windows-amd64.zip");
    }
}
