package com.vlessclient.service;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.ServerConfig;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The clash_api control endpoint is secured with a token so another local
 * user can't read traffic stats or drive the core over 127.0.0.1:port.
 * Covers the three sides: the generated config carries the secret, the
 * TrafficMonitor request sends it, and ConfigStore mints a fresh ephemeral one.
 */
class ClashApiSecretTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static ServerConfig vlessServer() {
        ServerConfig s = new ServerConfig();
        s.setProtocol(Protocol.VLESS);
        s.setAddress("203.0.113.10");
        s.setPort(443);
        s.setUuid("3fa85f64-5717-4562-b3fc-2c963f66afa6");
        return s;
    }

    private static JsonNode clashApi(String configJson) throws Exception {
        return MAPPER.readTree(configJson).path("experimental").path("clash_api");
    }

    @Test
    void generatedConfigCarriesTheSecretWhenSet() throws Exception {
        AppSettings settings = new AppSettings();
        settings.setClashApiSecret("tok-abc-123");

        String cfg = new SingBoxConfigGenerator()
                .generate(vlessServer(), settings, new RoutingConfig());

        assertThat(clashApi(cfg).path("secret").asString()).isEqualTo("tok-abc-123");
    }

    @Test
    void generatedConfigOmitsTheSecretWhenBlank() throws Exception {
        AppSettings settings = new AppSettings();   // secret is "" by default

        String cfg = new SingBoxConfigGenerator()
                .generate(vlessServer(), settings, new RoutingConfig());

        JsonNode clash = clashApi(cfg);
        assertThat(clash.has("external_controller")).isTrue();
        assertThat(clash.has("secret")).isFalse();
    }

    @Test
    void trafficRequestSendsBearerWhenSecretSet() {
        HttpRequest req = TrafficMonitor.buildTrafficRequest(9090, "tok-abc-123");
        assertThat(req.headers().firstValue("Authorization")).contains("Bearer tok-abc-123");
        assertThat(req.uri().toString()).isEqualTo("http://127.0.0.1:9090/traffic");
    }

    @Test
    void trafficRequestOmitsAuthWhenSecretBlank() {
        assertThat(TrafficMonitor.buildTrafficRequest(9090, "")
                .headers().firstValue("Authorization")).isEmpty();
        assertThat(TrafficMonitor.buildTrafficRequest(9090, null)
                .headers().firstValue("Authorization")).isEmpty();
    }

    @Test
    void configStoreMintsAFreshEphemeralSecretNeverWrittenToDisk(@TempDir Path dir) throws Exception {
        ConfigStore store = new ConfigStore(dir);
        String secret = store.getSettings().getClashApiSecret();

        assertThat(secret).isNotBlank();
        assertThat(secret.length()).isGreaterThanOrEqualTo(32);   // 24 random bytes as hex

        // @JsonIgnore: a save must not leak the token into settings.json.
        store.saveSettings(store.getSettings());
        assertThat(Files.readString(dir.resolve("settings.json"))).doesNotContain(secret);

        // Fresh each run: a second store gets a different token.
        String other = new ConfigStore(dir).getSettings().getClashApiSecret();
        assertThat(other).isNotBlank().isNotEqualTo(secret);
    }

    @Test
    void savingAFreshSettingsInstanceKeepsTheRuntimeSecret(@TempDir Path dir) {
        ConfigStore store = new ConfigStore(dir);
        String secret = store.getSettings().getClashApiSecret();
        assertThat(secret).isNotBlank();

        // A caller that swaps in a brand-new (secret-less) AppSettings must not
        // silently drop the token — that would reopen the control endpoint.
        store.saveSettings(new AppSettings());

        assertThat(store.getSettings().getClashApiSecret()).isEqualTo(secret);
    }
}
