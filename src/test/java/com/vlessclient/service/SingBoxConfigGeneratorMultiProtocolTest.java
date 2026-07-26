package com.vlessclient.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TransportType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SingBoxConfigGeneratorMultiProtocolTest {

    private SingBoxConfigGenerator generator;
    private ObjectMapper mapper;
    private AppSettings defaultSettings;

    @BeforeEach
    void setUp() {
        generator = new SingBoxConfigGenerator();
        mapper = new ObjectMapper();
        defaultSettings = new AppSettings();
    }

    /**
     * The server's own outbound — the first entry, ahead of the proxy group
     * and direct. It carries a per-server tag; the {@code proxy} tag belongs
     * to the group that fronts it (see proxyGroupFrontsTheServerOutbound).
     */
    private JsonNode proxyOutbound(ServerConfig server) throws Exception {
        String json = generator.generate(server, defaultSettings);
        return mapper.readTree(json).get("outbounds").get(0);
    }

    private static String memberTag(ServerConfig server) {
        return com.vlessclient.service.outbound.OutboundTags.server(server);
    }

    /** WireGuard lives under top-level {@code endpoints}, not {@code outbounds}. */
    private JsonNode wireguardEndpoint(ServerConfig server) throws Exception {
        String json = generator.generate(server, defaultSettings);
        return mapper.readTree(json).get("endpoints").get(0);
    }

    /**
     * The indirection proxy groups are built on: the {@code proxy} tag belongs
     * to a group, not to the server, while everything downstream keeps
     * resolving {@code proxy}. If this inverts, routing silently starts
     * pointing at one specific server again and groups cannot exist.
     */
    @Test
    void proxyGroupFrontsTheServerOutbound() throws Exception {
        ServerConfig server = vlessServer();

        JsonNode root = mapper.readTree(generator.generate(server, defaultSettings));
        JsonNode outbounds = root.get("outbounds");

        JsonNode group = null;
        for (JsonNode node : outbounds) {
            if ("proxy".equals(node.path("tag").asText())) {
                group = node;
            }
        }
        assertThat(group).as("a node tagged proxy must exist").isNotNull();
        assertThat(group.get("type").asText()).isEqualTo("selector");
        assertThat(group.get("outbounds").get(0).asText()).isEqualTo(memberTag(server));

        // The server itself is a member, not the entry point.
        assertThat(outbounds.get(0).get("tag").asText()).isEqualTo(memberTag(server));
        assertThat(outbounds.get(0).get("type").asText()).isEqualTo("vless");
    }

    /**
     * Tags are derived from the server id, never the name: names are
     * user-editable and duplicated freely by subscriptions, and a tag
     * collision would route traffic through the wrong server.
     */
    @Test
    void memberTagsAreUniquePerServerEvenWhenNamesCollide() throws Exception {
        ServerConfig first = vlessServer();
        first.setName("Same name");
        ServerConfig second = vlessServer();
        second.setName("Same name");

        assertThat(memberTag(first)).isNotEqualTo(memberTag(second));
        assertThat(mapper.readTree(generator.generate(first, defaultSettings))
                .get("outbounds").get(0).get("tag").asText())
                .isEqualTo(memberTag(first));
    }

    private ServerConfig vlessServer() {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.VLESS);
        server.setAddress("vless.example.com");
        server.setPort(443);
        server.setUuid("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        return server;
    }

    // -- VMess tests --

    @Test
    void vmess_generatesCorrectOutbound() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.VMESS);
        server.setAddress("vmess.example.com");
        server.setPort(443);
        server.setUuid("b1c2d3e4-f5a6-7890-abcd-ef1234567890");
        server.getTls().setEnabled(false);

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.get("type").asText()).isEqualTo("vmess");
        assertThat(proxy.get("tag").asText()).isEqualTo(memberTag(server));
        assertThat(proxy.get("server").asText()).isEqualTo("vmess.example.com");
        assertThat(proxy.get("server_port").asInt()).isEqualTo(443);
        assertThat(proxy.get("uuid").asText()).isEqualTo("b1c2d3e4-f5a6-7890-abcd-ef1234567890");
        assertThat(proxy.get("alter_id").asInt()).isEqualTo(0);
        assertThat(proxy.get("security").asText()).isEqualTo("auto");
    }

    @Test
    void vmess_withWebSocketTransport() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.VMESS);
        server.setAddress("vmess.example.com");
        server.setPort(80);
        server.setUuid("test-uuid");
        server.getTls().setEnabled(false);
        server.getTransport().setType(TransportType.WEBSOCKET);
        server.getTransport().setPath("/vmess-ws");

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.get("type").asText()).isEqualTo("vmess");
        JsonNode transport = proxy.get("transport");
        assertThat(transport).isNotNull();
        assertThat(transport.get("type").asText()).isEqualTo("ws");
        assertThat(transport.get("path").asText()).isEqualTo("/vmess-ws");
    }

    @Test
    void vmess_withTls() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.VMESS);
        server.setAddress("vmess.example.com");
        server.setPort(443);
        server.setUuid("test-uuid");
        server.getTls().setEnabled(true);
        server.getTls().setServerName("vmess.example.com");

        JsonNode proxy = proxyOutbound(server);

        JsonNode tls = proxy.get("tls");
        assertThat(tls).isNotNull();
        assertThat(tls.get("enabled").asBoolean()).isTrue();
        assertThat(tls.get("server_name").asText()).isEqualTo("vmess.example.com");
    }

    // -- Trojan tests --

    @Test
    void trojan_generatesCorrectOutbound() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.TROJAN);
        server.setAddress("trojan.example.com");
        server.setPort(443);
        server.setUuid("my-trojan-password");
        server.getTls().setEnabled(true);
        server.getTls().setServerName("trojan.example.com");

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.get("type").asText()).isEqualTo("trojan");
        assertThat(proxy.get("tag").asText()).isEqualTo(memberTag(server));
        assertThat(proxy.get("server").asText()).isEqualTo("trojan.example.com");
        assertThat(proxy.get("server_port").asInt()).isEqualTo(443);
        assertThat(proxy.get("password").asText()).isEqualTo("my-trojan-password");
        assertThat(proxy.has("uuid")).isFalse();
    }

    @Test
    void trojan_withGrpcTransport() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.TROJAN);
        server.setAddress("trojan.example.com");
        server.setPort(443);
        server.setUuid("trojan-pass");
        server.getTls().setEnabled(true);
        server.getTls().setServerName("trojan.example.com");
        server.getTransport().setType(TransportType.GRPC);
        server.getTransport().setServiceName("trojan-grpc");

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.get("type").asText()).isEqualTo("trojan");
        JsonNode transport = proxy.get("transport");
        assertThat(transport).isNotNull();
        assertThat(transport.get("type").asText()).isEqualTo("grpc");
        assertThat(transport.get("service_name").asText()).isEqualTo("trojan-grpc");
    }

    @Test
    void trojan_withTls() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.TROJAN);
        server.setAddress("trojan.example.com");
        server.setPort(443);
        server.setUuid("trojan-pass");
        server.getTls().setEnabled(true);
        server.getTls().setServerName("trojan.example.com");
        server.getTls().setAlpn("h2,http/1.1");

        JsonNode proxy = proxyOutbound(server);

        JsonNode tls = proxy.get("tls");
        assertThat(tls).isNotNull();
        assertThat(tls.get("enabled").asBoolean()).isTrue();
        assertThat(tls.get("server_name").asText()).isEqualTo("trojan.example.com");
        assertThat(tls.get("alpn").size()).isEqualTo(2);
    }

    // -- Shadowsocks tests --

    @Test
    void shadowsocks_generatesCorrectOutbound() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.SHADOWSOCKS);
        server.setAddress("ss.example.com");
        server.setPort(8388);
        server.setUuid("ss-password-123");
        server.setEncryption("aes-256-gcm");

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.get("type").asText()).isEqualTo("shadowsocks");
        assertThat(proxy.get("tag").asText()).isEqualTo(memberTag(server));
        assertThat(proxy.get("server").asText()).isEqualTo("ss.example.com");
        assertThat(proxy.get("server_port").asInt()).isEqualTo(8388);
        assertThat(proxy.get("method").asText()).isEqualTo("aes-256-gcm");
        assertThat(proxy.get("password").asText()).isEqualTo("ss-password-123");
    }

    @Test
    void shadowsocks_noTlsSection() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.SHADOWSOCKS);
        server.setAddress("ss.example.com");
        server.setPort(8388);
        server.setUuid("ss-password");
        server.setEncryption("chacha20-ietf-poly1305");
        server.getTls().setEnabled(true); // should be ignored

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.has("tls")).isFalse();
    }

    @Test
    void shadowsocks_noTransportSection() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.SHADOWSOCKS);
        server.setAddress("ss.example.com");
        server.setPort(8388);
        server.setUuid("ss-password");
        server.setEncryption("2022-blake3-aes-128-gcm");
        server.getTransport().setType(TransportType.WEBSOCKET); // should be ignored

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.has("transport")).isFalse();
    }

    // -- Hysteria2 tests --

    @Test
    void hysteria2_generatesCorrectOutbound() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.HYSTERIA2);
        server.setAddress("hy2.example.com");
        server.setPort(443);
        server.setUuid("hy2-auth-password");
        server.getTls().setEnabled(true);
        server.getTls().setServerName("hy2.example.com");
        server.getTls().setAlpn("h3");

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.get("type").asText()).isEqualTo("hysteria2");
        assertThat(proxy.get("tag").asText()).isEqualTo(memberTag(server));
        assertThat(proxy.get("server").asText()).isEqualTo("hy2.example.com");
        assertThat(proxy.get("server_port").asInt()).isEqualTo(443);
        assertThat(proxy.get("password").asText()).isEqualTo("hy2-auth-password");
        assertThat(proxy.has("obfs")).isFalse();

        JsonNode tls = proxy.get("tls");
        assertThat(tls).isNotNull();
        assertThat(tls.get("enabled").asBoolean()).isTrue();
        assertThat(tls.get("server_name").asText()).isEqualTo("hy2.example.com");
        assertThat(tls.get("alpn").get(0).asText()).isEqualTo("h3");
    }

    @Test
    void hysteria2_withObfuscation() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.HYSTERIA2);
        server.setAddress("hy2.example.com");
        server.setPort(443);
        server.setUuid("hy2-password");
        server.setFlow("my-obfs-password");
        server.getTls().setEnabled(true);
        server.getTls().setServerName("hy2.example.com");

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.get("type").asText()).isEqualTo("hysteria2");
        JsonNode obfs = proxy.get("obfs");
        assertThat(obfs).isNotNull();
        assertThat(obfs.get("type").asText()).isEqualTo("salamander");
        assertThat(obfs.get("password").asText()).isEqualTo("my-obfs-password");
    }

    @Test
    void hysteria2_noTransportSection() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.HYSTERIA2);
        server.setAddress("hy2.example.com");
        server.setPort(443);
        server.setUuid("hy2-password");
        server.getTls().setEnabled(true);
        server.getTls().setServerName("hy2.example.com");

        JsonNode proxy = proxyOutbound(server);

        assertThat(proxy.has("transport")).isFalse();
    }

    // -- WireGuard tests --
    //
    // WireGuard is generated as a sing-box 1.11+ endpoint (the legacy
    // wireguard outbound was removed in 1.13): top-level endpoints[] entry,
    // peer parameters nested under peers[0].

    @Test
    void wireguard_generatesCorrectEndpoint() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.WIREGUARD);
        server.setAddress("wg.example.com");
        server.setPort(51820);
        server.setUuid("wg-private-key-base64");
        server.setEncryption("wg-peer-public-key-base64");
        server.setFlow("10.0.0.2/32");
        server.getTls().setServerName("1,2,3");

        JsonNode endpoint = wireguardEndpoint(server);

        assertThat(endpoint.get("type").asText()).isEqualTo("wireguard");
        assertThat(endpoint.get("tag").asText()).isEqualTo(memberTag(server));
        assertThat(endpoint.get("private_key").asText()).isEqualTo("wg-private-key-base64");

        JsonNode address = endpoint.get("address");
        assertThat(address).isNotNull();
        assertThat(address.isArray()).isTrue();
        assertThat(address.get(0).asText()).isEqualTo("10.0.0.2/32");

        JsonNode peers = endpoint.get("peers");
        assertThat(peers).isNotNull();
        assertThat(peers.isArray()).isTrue();
        assertThat(peers.size()).isEqualTo(1);

        JsonNode peer = peers.get(0);
        assertThat(peer.get("address").asText()).isEqualTo("wg.example.com");
        assertThat(peer.get("port").asInt()).isEqualTo(51820);
        assertThat(peer.get("public_key").asText()).isEqualTo("wg-peer-public-key-base64");

        JsonNode allowedIps = peer.get("allowed_ips");
        assertThat(allowedIps).isNotNull();
        assertThat(allowedIps.isArray()).isTrue();
        assertThat(allowedIps.get(0).asText()).isEqualTo("0.0.0.0/0");
        assertThat(allowedIps.get(1).asText()).isEqualTo("::/0");

        JsonNode reserved = peer.get("reserved");
        assertThat(reserved).isNotNull();
        assertThat(reserved.isArray()).isTrue();
        assertThat(reserved.size()).isEqualTo(3);
        assertThat(reserved.get(0).asInt()).isEqualTo(1);
        assertThat(reserved.get(1).asInt()).isEqualTo(2);
        assertThat(reserved.get(2).asInt()).isEqualTo(3);
    }

    @Test
    void wireguard_keepsProxyOutOfOutbounds_andRoutesFinalToEndpoint() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.WIREGUARD);
        server.setAddress("wg.example.com");
        server.setPort(51820);
        server.setUuid("wg-private-key");

        JsonNode root = mapper.readTree(generator.generate(server, defaultSettings));

        // No wireguard outbound: it is an endpoint. What sits in outbounds is
        // the proxy group (pointing at that endpoint) and direct. Without an
        // explicit final the first outbound would become the default and all
        // traffic would bypass the tunnel.
        JsonNode outbounds = root.get("outbounds");
        assertThat(outbounds.size()).isEqualTo(2);
        assertThat(outbounds.get(0).get("tag").asText()).isEqualTo("proxy");
        assertThat(outbounds.get(0).get("type").asText()).isEqualTo("selector");
        // A selector referencing an endpoint is legal — verified against the
        // real core before this shape was adopted.
        assertThat(outbounds.get(0).get("outbounds").get(0).asText())
                .isEqualTo(memberTag(server));
        assertThat(outbounds.get(1).get("tag").asText()).isEqualTo("direct");
        assertThat(root.get("route").get("final").asText()).isEqualTo("proxy");
    }

    @Test
    void wireguard_withoutOptionalFields() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.WIREGUARD);
        server.setAddress("wg.example.com");
        server.setPort(51820);
        server.setUuid("wg-private-key");
        server.setEncryption("none");
        server.getTls().setServerName(null);

        JsonNode endpoint = wireguardEndpoint(server);

        assertThat(endpoint.get("type").asText()).isEqualTo("wireguard");
        assertThat(endpoint.get("private_key").asText()).isEqualTo("wg-private-key");
        assertThat(endpoint.has("address")).isFalse();

        JsonNode peer = endpoint.get("peers").get(0);
        assertThat(peer.has("public_key")).isFalse();
        assertThat(peer.has("reserved")).isFalse();
    }

    @Test
    void wireguard_invalidReservedBytes_doesNotCrash() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.WIREGUARD);
        server.setAddress("wg.example.com");
        server.setPort(51820);
        server.setUuid("wg-private-key");
        // Mix of valid and invalid integers — invalid values must be skipped gracefully
        server.getTls().setServerName("1,abc,2,,3,xyz");

        JsonNode endpoint = wireguardEndpoint(server);

        assertThat(endpoint.get("type").asText()).isEqualTo("wireguard");
        JsonNode reserved = endpoint.get("peers").get(0).get("reserved");
        assertThat(reserved).isNotNull();
        assertThat(reserved.isArray()).isTrue();
        assertThat(reserved.size()).isEqualTo(3);
        assertThat(reserved.get(0).asInt()).isEqualTo(1);
        assertThat(reserved.get(1).asInt()).isEqualTo(2);
        assertThat(reserved.get(2).asInt()).isEqualTo(3);
    }

    @Test
    void wireguard_wrongReservedCount_omitsReservedField() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.WIREGUARD);
        server.setAddress("wg.example.com");
        server.setPort(51820);
        server.setUuid("wg-private-key");
        // sing-box requires exactly 3 reserved bytes — 2 values must be dropped
        server.getTls().setServerName("1,2");

        JsonNode endpoint = wireguardEndpoint(server);

        assertThat(endpoint.get("peers").get(0).has("reserved")).isFalse();
    }

    @Test
    void wireguard_outOfRangeReservedByte_omitsReservedField() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.WIREGUARD);
        server.setAddress("wg.example.com");
        server.setPort(51820);
        server.setUuid("wg-private-key");
        // 300 is not a uint8: it is skipped, leaving 2 values — also dropped
        server.getTls().setServerName("300,1,2");

        JsonNode endpoint = wireguardEndpoint(server);

        assertThat(endpoint.get("peers").get(0).has("reserved")).isFalse();
    }

    @Test
    void wireguard_allInvalidReservedBytes_omitsReservedField() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.WIREGUARD);
        server.setAddress("wg.example.com");
        server.setPort(51820);
        server.setUuid("wg-private-key");
        server.getTls().setServerName("abc,def,ghi");

        JsonNode endpoint = wireguardEndpoint(server);

        assertThat(endpoint.get("type").asText()).isEqualTo("wireguard");
        assertThat(endpoint.get("peers").get(0).has("reserved")).isFalse();
    }

    @Test
    void wireguard_blankFlow_omitsAddress() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.WIREGUARD);
        server.setAddress("wg.example.com");
        server.setPort(51820);
        server.setUuid("wg-private-key");
        server.setFlow("   ");

        JsonNode endpoint = wireguardEndpoint(server);

        assertThat(endpoint.has("address")).isFalse();
    }

    @Test
    void wireguard_noTlsSection() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.WIREGUARD);
        server.setAddress("wg.example.com");
        server.setPort(51820);
        server.setUuid("wg-private-key");

        JsonNode endpoint = wireguardEndpoint(server);

        assertThat(endpoint.has("tls")).isFalse();
    }
}
