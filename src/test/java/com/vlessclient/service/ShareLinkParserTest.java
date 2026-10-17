package com.vlessclient.service;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.TransportType;
import com.vlessclient.model.ServerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShareLinkParserTest {

    private ShareLinkParser parser;

    @BeforeEach
    void setUp() {
        parser = new ShareLinkParser();
    }

    @Test
    void parseMinimalVlessLink() {
        String uri = "vless://test-uuid-1234@example.com:443#MyServer";

        ServerConfig config = parser.parse(uri);

        assertThat(config.getProtocol()).isEqualTo(Protocol.VLESS);
        assertThat(config.getUuid()).isEqualTo("test-uuid-1234");
        assertThat(config.getAddress()).isEqualTo("example.com");
        assertThat(config.getPort()).isEqualTo(443);
        assertThat(config.getName()).isEqualTo("MyServer");
        assertThat(config.getEncryption()).isEqualTo("none");
        assertThat(config.getTransport().getType()).isEqualTo(TransportType.TCP);
        assertThat(config.getTls().isEnabled()).isFalse();
    }

    @Test
    void parseVlessWithWebSocket() {
        String uri = "vless://test-uuid@host.example:443"
                + "?type=ws&path=/ws&host=example.com&security=tls&sni=example.com#WS Server";

        ServerConfig config = parser.parse(uri);

        assertThat(config.getProtocol()).isEqualTo(Protocol.VLESS);
        assertThat(config.getUuid()).isEqualTo("test-uuid");
        assertThat(config.getAddress()).isEqualTo("host.example");
        assertThat(config.getPort()).isEqualTo(443);
        assertThat(config.getName()).isEqualTo("WS Server");
        assertThat(config.getTransport().getType()).isEqualTo(TransportType.WEBSOCKET);
        assertThat(config.getTransport().getPath()).isEqualTo("/ws");
        assertThat(config.getTransport().getHost()).isEqualTo("example.com");
        assertThat(config.getTls().isEnabled()).isTrue();
        assertThat(config.getTls().isReality()).isFalse();
        assertThat(config.getTls().getServerName()).isEqualTo("example.com");
    }

    @Test
    void parseVlessWithGrpc() {
        String uri = "vless://test-uuid@host.example:443"
                + "?type=grpc&serviceName=mygrpc&security=tls&sni=example.com#gRPC";

        ServerConfig config = parser.parse(uri);

        assertThat(config.getTransport().getType()).isEqualTo(TransportType.GRPC);
        assertThat(config.getTransport().getServiceName()).isEqualTo("mygrpc");
        assertThat(config.getTls().isEnabled()).isTrue();
        assertThat(config.getTls().getServerName()).isEqualTo("example.com");
        assertThat(config.getName()).isEqualTo("gRPC");
    }

    @Test
    void parseVlessWithReality() {
        String uri = "vless://test-uuid@host.example:443"
                + "?security=reality&pbk=publickey123&sid=shortid123"
                + "&sni=example.com&fp=chrome&flow=xtls-rprx-vision#Reality";

        ServerConfig config = parser.parse(uri);

        assertThat(config.getTls().isEnabled()).isTrue();
        assertThat(config.getTls().isReality()).isTrue();
        assertThat(config.getTls().getRealityPublicKey()).isEqualTo("publickey123");
        assertThat(config.getTls().getRealityShortId()).isEqualTo("shortid123");
        assertThat(config.getTls().getServerName()).isEqualTo("example.com");
        assertThat(config.getTls().getFingerprint()).isEqualTo("chrome");
        assertThat(config.getFlow()).isEqualTo("xtls-rprx-vision");
        assertThat(config.getName()).isEqualTo("Reality");
    }

    /**
     * A space after the flow, %20 or a + in the link, was kept: checked as
     * Vision, written as "xtls-rprx-vision " and refused by the core, and
     * copied back out with the space.
     */
    @Test
    void theFlowIsStoredWithoutTheSpacesAroundIt() {
        for (String flow : new String[] {"xtls-rprx-vision%20", "xtls-rprx-vision+",
            "%20xtls-rprx-vision", "xtls-rprx-vision%0A"}) {
            ServerConfig config = parser.parseForImport("vless://test-uuid@host.example:443"
                    + "?security=tls&sni=example.com&flow=" + flow + "#V");

            assertThat(config.getFlow()).as(flow).isEqualTo("xtls-rprx-vision");
        }
    }

    @Test
    void parseVlessWithUrlEncodedFragment() {
        String uri = "vless://test-uuid@host.example:443#My%20Server%20%26%20Proxy";

        ServerConfig config = parser.parse(uri);

        assertThat(config.getName()).isEqualTo("My Server & Proxy");
    }

    @Test
    void invalidSchemeThrowsException() {
        assertThatThrownBy(() -> parser.parse("http://example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported protocol scheme");
    }

    @Test
    void missingUuidThrowsException() {
        assertThatThrownBy(() -> parser.parse("vless://host.example:443#name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing UUID");
    }

    @Test
    void roundTripExportAndParse() {
        ServerConfig original = new ServerConfig();
        original.setProtocol(Protocol.VLESS);
        original.setUuid("550e8400-e29b-41d4-a716-446655440000");
        original.setAddress("proxy.example.com");
        original.setPort(8443);
        original.setName("Test Round Trip");
        original.setFlow("xtls-rprx-vision");
        original.getTransport().setType(TransportType.WEBSOCKET);
        original.getTransport().setPath("/ws-path");
        original.getTransport().setHost("cdn.example.com");
        original.getTls().setEnabled(true);
        original.getTls().setServerName("sni.example.com");
        original.getTls().setFingerprint("chrome");
        original.getTls().setAlpn("h2,http/1.1");

        ShareLinkExporter exporter = new ShareLinkExporter();
        String exported = exporter.export(original);

        ServerConfig parsed = parser.parse(exported);

        assertThat(parsed.getProtocol()).isEqualTo(original.getProtocol());
        assertThat(parsed.getUuid()).isEqualTo(original.getUuid());
        assertThat(parsed.getAddress()).isEqualTo(original.getAddress());
        assertThat(parsed.getPort()).isEqualTo(original.getPort());
        assertThat(parsed.getName()).isEqualTo(original.getName());
        assertThat(parsed.getFlow()).isEqualTo(original.getFlow());
        assertThat(parsed.getTransport().getType()).isEqualTo(original.getTransport().getType());
        assertThat(parsed.getTransport().getPath()).isEqualTo(original.getTransport().getPath());
        assertThat(parsed.getTransport().getHost()).isEqualTo(original.getTransport().getHost());
        assertThat(parsed.getTls().isEnabled()).isEqualTo(original.getTls().isEnabled());
        assertThat(parsed.getTls().getServerName()).isEqualTo(original.getTls().getServerName());
        assertThat(parsed.getTls().getFingerprint()).isEqualTo(original.getTls().getFingerprint());
        assertThat(parsed.getTls().getAlpn()).isEqualTo(original.getTls().getAlpn());
    }
}
