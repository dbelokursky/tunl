package com.vlessclient.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Release-asset selection for the in-app updater: with per-arch installers in
 * one release the picker must honour the host architecture, while releases
 * that predate multi-arch assets keep working via the extension fallback.
 */
class UpdateManagerAssetSelectionTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode assets(String... names) throws Exception {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"name\":\"").append(names[i])
                    .append("\",\"browser_download_url\":\"https://x/")
                    .append(names[i]).append("\"}");
        }
        return MAPPER.readTree(json.append(']').toString());
    }

    @Test
    void picksTheDebMatchingTheHostArch() throws Exception {
        JsonNode release = assets(
                "vless-client_1.2.0_amd64.deb",
                "vless-client_1.2.0_arm64.deb",
                "vless-client_1.2.0.dmg");

        assertThat(UpdateManager.findInstallerAssetUrl(release, ".deb", "arm64"))
                .endsWith("_arm64.deb");
        assertThat(UpdateManager.findInstallerAssetUrl(release, ".deb", "amd64"))
                .endsWith("_amd64.deb");
    }

    @Test
    void fallsBackToTheOnlyDebWhenNoArchTokenMatches() throws Exception {
        // Releases published before multi-arch assets carry one deb.
        JsonNode release = assets("vless-client_1.1.0_amd64.deb", "vless-client_1.1.0.msi");

        assertThat(UpdateManager.findInstallerAssetUrl(release, ".deb", "arm64"))
                .endsWith("_amd64.deb");
    }

    @Test
    void otherPlatformsKeepTheirSingleAsset() throws Exception {
        JsonNode release = assets(
                "vless-client_1.2.0_amd64.deb",
                "vless-client_1.2.0_arm64.deb",
                "vless-client_1.2.0.msi",
                "vless-client_1.2.0.dmg");

        assertThat(UpdateManager.findInstallerAssetUrl(release, ".dmg", "arm64"))
                .endsWith(".dmg");
        assertThat(UpdateManager.findInstallerAssetUrl(release, ".msi", "amd64"))
                .endsWith(".msi");
    }

    @Test
    void emptyOrMissingAssetsYieldEmptyUrl() throws Exception {
        assertThat(UpdateManager.findInstallerAssetUrl(null, ".deb", "arm64")).isEmpty();
        assertThat(UpdateManager.findInstallerAssetUrl(assets(), ".deb", "arm64")).isEmpty();
        assertThat(UpdateManager.findInstallerAssetUrl(
                assets("vless-client_1.2.0.dmg"), ".deb", "arm64")).isEmpty();
    }
}
