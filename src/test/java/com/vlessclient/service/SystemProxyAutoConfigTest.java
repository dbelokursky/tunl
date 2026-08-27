package com.vlessclient.service;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TransportType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The set_system_proxy flow: the generator marks the http inbound in
 * SYSTEM_PROXY mode (unless opted out), and the engine finds that inbound's
 * endpoint so the {@code SystemProxyGuard} knows what to clean up after a
 * non-graceful core exit. OS-portable: no processes are started.
 */
class SystemProxyAutoConfigTest {

    private SingBoxConfigGenerator generator;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        // Capability forced on: these tests pin the settings/mode matrix,
        // not the host gate (covered separately below).
        generator = new SingBoxConfigGenerator(() -> true);
        mapper = JsonMapper.builder().build();
    }

    private ServerConfig server() {
        ServerConfig server = new ServerConfig();
        server.setName("Test Server");
        server.setProtocol(Protocol.VLESS);
        server.setAddress("1.2.3.4");
        server.setPort(443);
        server.setUuid("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        server.getTls().setEnabled(false);
        server.getTransport().setType(TransportType.TCP);
        return server;
    }

    private JsonNode httpInbound(AppSettings settings) throws Exception {
        JsonNode config = mapper.readTree(generator.generate(server(), settings));
        for (JsonNode inbound : config.get("inbounds")) {
            if ("http".equals(inbound.path("type").asString())) {
                return inbound;
            }
        }
        throw new AssertionError("no http inbound in generated config");
    }

    // -- Generator --

    @Test
    void systemProxyMode_marksHttpInboundByDefault() throws Exception {
        AppSettings settings = new AppSettings();
        settings.setProxyMode(ProxyMode.SYSTEM_PROXY);

        JsonNode http = httpInbound(settings);

        assertThat(http.path("set_system_proxy").asBoolean(false)).isTrue();
    }

    @Test
    void systemProxyMode_respectsOptOut() throws Exception {
        AppSettings settings = new AppSettings();
        settings.setProxyMode(ProxyMode.SYSTEM_PROXY);
        settings.setSystemProxyAutoConfig(false);

        JsonNode http = httpInbound(settings);

        assertThat(http.has("set_system_proxy")).isFalse();
    }

    @Test
    void hostWithoutProxyStore_neverMarksTheInbound() throws Exception {
        // The gnomeless-CI probe pinned why this gate exists: sing-box FATALs
        // at startup when it cannot write the OS proxy store (e.g. Linux
        // without the GNOME schema), so the generator must omit the flag.
        SingBoxConfigGenerator gated = new SingBoxConfigGenerator(() -> false);
        AppSettings settings = new AppSettings();
        settings.setProxyMode(ProxyMode.SYSTEM_PROXY);
        settings.setSystemProxyAutoConfig(true);

        JsonNode config = mapper.readTree(gated.generate(server(), settings));
        for (JsonNode inbound : config.get("inbounds")) {
            assertThat(inbound.has("set_system_proxy")).isFalse();
        }
    }

    @Test
    void tunMode_neverSetsSystemProxy() throws Exception {
        AppSettings settings = new AppSettings();
        settings.setProxyMode(ProxyMode.TUN);
        settings.setSystemProxyAutoConfig(true);

        JsonNode http = httpInbound(settings);

        assertThat(http.has("set_system_proxy")).isFalse();
    }

    @Test
    void socksInboundIsNeverMarked() throws Exception {
        AppSettings settings = new AppSettings();
        settings.setProxyMode(ProxyMode.SYSTEM_PROXY);

        JsonNode config = mapper.readTree(generator.generate(server(), settings));
        for (JsonNode inbound : config.get("inbounds")) {
            if ("socks".equals(inbound.path("type").asString())) {
                assertThat(inbound.has("set_system_proxy")).isFalse();
            }
        }
    }

    // -- Engine target extraction --

    @Test
    void engineFindsTheMarkedInboundEndpoint() {
        AppSettings settings = new AppSettings();
        settings.setProxyMode(ProxyMode.SYSTEM_PROXY);
        settings.setHttpPort(18081);
        String config = generator.generate(server(), settings);

        SingBoxEngine.SystemProxyTarget target =
                SingBoxEngine.extractSystemProxyTarget(config);

        assertThat(target).isNotNull();
        assertThat(target.host()).isEqualTo("127.0.0.1");
        assertThat(target.port()).isEqualTo(18081);
    }

    @Test
    void engineReturnsNullWhenNothingIsMarked() {
        AppSettings settings = new AppSettings();
        settings.setProxyMode(ProxyMode.SYSTEM_PROXY);
        settings.setSystemProxyAutoConfig(false);
        String config = generator.generate(server(), settings);

        assertThat(SingBoxEngine.extractSystemProxyTarget(config)).isNull();
    }

    @Test
    void engineToleratesMalformedConfig() {
        assertThat(SingBoxEngine.extractSystemProxyTarget("not json at all")).isNull();
        assertThat(SingBoxEngine.extractSystemProxyTarget("{}")).isNull();
    }
}
