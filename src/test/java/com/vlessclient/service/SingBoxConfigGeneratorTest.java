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

class SingBoxConfigGeneratorTest {

    private SingBoxConfigGenerator generator;
    private ObjectMapper mapper;
    private AppSettings defaultSettings;

    @BeforeEach
    void setUp() {
        generator = new SingBoxConfigGenerator();
        mapper = new ObjectMapper();
        defaultSettings = new AppSettings();
    }

    // -- Fixtures --

    private ServerConfig createVlessServer() {
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

    private ServerConfig createVlessServerWithWs() {
        ServerConfig server = createVlessServer();
        server.getTransport().setType(TransportType.WEBSOCKET);
        server.getTransport().setPath("/ws");
        return server;
    }

    private ServerConfig createVlessServerWithGrpc() {
        ServerConfig server = createVlessServer();
        server.getTransport().setType(TransportType.GRPC);
        server.getTransport().setServiceName("my-grpc-service");
        return server;
    }

    private ServerConfig createVlessServerWithTls() {
        ServerConfig server = createVlessServer();
        server.getTls().setEnabled(true);
        server.getTls().setServerName("example.com");
        server.getTls().setAlpn("h2,http/1.1");
        return server;
    }

    private JsonNode parse(String json) throws Exception {
        return mapper.readTree(json);
    }

    // -- Inbound tests --

    @Test
    void vlessTcp_generatesCorrectInbounds() throws Exception {
        String json = generator.generate(createVlessServer(), defaultSettings);
        JsonNode root = parse(json);

        JsonNode inbounds = root.get("inbounds");
        assertThat(inbounds).isNotNull();
        assertThat(inbounds.size()).isEqualTo(2);

        JsonNode socks = inbounds.get(0);
        assertThat(socks.get("type").asText()).isEqualTo("socks");
        assertThat(socks.get("tag").asText()).isEqualTo("socks-in");
        assertThat(socks.get("listen").asText()).isEqualTo("127.0.0.1");
        assertThat(socks.get("listen_port").asInt()).isEqualTo(1080);

        JsonNode http = inbounds.get(1);
        assertThat(http.get("type").asText()).isEqualTo("http");
        assertThat(http.get("tag").asText()).isEqualTo("http-in");
        assertThat(http.get("listen").asText()).isEqualTo("127.0.0.1");
        assertThat(http.get("listen_port").asInt()).isEqualTo(1081);
    }

    @Test
    void vlessTcp_generatesCorrectOutbound() throws Exception {
        ServerConfig server = createVlessServer();
        String json = generator.generate(server, defaultSettings);
        JsonNode root = parse(json);

        // Three now: the server's own outbound, the proxy group fronting it,
        // and direct.
        JsonNode outbounds = root.get("outbounds");
        assertThat(outbounds).isNotNull();
        assertThat(outbounds.size()).isEqualTo(3);

        JsonNode proxy = outbounds.get(0);
        assertThat(proxy.get("type").asText()).isEqualTo("vless");
        assertThat(proxy.get("tag").asText())
                .isEqualTo(com.vlessclient.service.outbound.OutboundTags.server(server));
        assertThat(proxy.get("server").asText()).isEqualTo("1.2.3.4");
        assertThat(proxy.get("server_port").asInt()).isEqualTo(443);
        assertThat(proxy.get("uuid").asText()).isEqualTo("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

        JsonNode group = outbounds.get(1);
        assertThat(group.get("tag").asText()).isEqualTo("proxy");
        assertThat(group.get("type").asText()).isEqualTo("selector");

        JsonNode direct = outbounds.get(2);
        assertThat(direct.get("type").asText()).isEqualTo("direct");
        assertThat(direct.get("tag").asText()).isEqualTo("direct");
    }

    @Test
    void vlessTcp_noTransportSection() throws Exception {
        String json = generator.generate(createVlessServer(), defaultSettings);
        JsonNode proxy = parse(json).get("outbounds").get(0);

        assertThat(proxy.has("transport")).isFalse();
    }

    // -- Transport tests --

    @Test
    void vlessWebSocket_generatesWsTransport() throws Exception {
        String json = generator.generate(createVlessServerWithWs(), defaultSettings);
        JsonNode proxy = parse(json).get("outbounds").get(0);

        JsonNode transport = proxy.get("transport");
        assertThat(transport).isNotNull();
        assertThat(transport.get("type").asText()).isEqualTo("ws");
        assertThat(transport.get("path").asText()).isEqualTo("/ws");
    }

    @Test
    void vlessGrpc_generatesGrpcTransport() throws Exception {
        String json = generator.generate(createVlessServerWithGrpc(), defaultSettings);
        JsonNode proxy = parse(json).get("outbounds").get(0);

        JsonNode transport = proxy.get("transport");
        assertThat(transport).isNotNull();
        assertThat(transport.get("type").asText()).isEqualTo("grpc");
        assertThat(transport.get("service_name").asText()).isEqualTo("my-grpc-service");
    }

    // -- TLS tests --

    @Test
    void vlessWithTlsEnabled_generatesTlsSection() throws Exception {
        String json = generator.generate(createVlessServerWithTls(), defaultSettings);
        JsonNode proxy = parse(json).get("outbounds").get(0);

        JsonNode tls = proxy.get("tls");
        assertThat(tls).isNotNull();
        assertThat(tls.get("enabled").asBoolean()).isTrue();
        assertThat(tls.get("server_name").asText()).isEqualTo("example.com");

        JsonNode alpn = tls.get("alpn");
        assertThat(alpn).isNotNull();
        assertThat(alpn.size()).isEqualTo(2);
        assertThat(alpn.get(0).asText()).isEqualTo("h2");
        assertThat(alpn.get(1).asText()).isEqualTo("http/1.1");
    }

    @Test
    void vlessWithTlsDisabled_noTlsSection() throws Exception {
        ServerConfig server = createVlessServer();
        server.getTls().setEnabled(false);

        String json = generator.generate(server, defaultSettings);
        JsonNode proxy = parse(json).get("outbounds").get(0);

        assertThat(proxy.has("tls")).isFalse();
    }

    // -- Flow tests --

    @Test
    void vlessWithFlow_flowFieldPresent() throws Exception {
        ServerConfig server = createVlessServer();
        server.setFlow("xtls-rprx-vision");

        String json = generator.generate(server, defaultSettings);
        JsonNode proxy = parse(json).get("outbounds").get(0);

        assertThat(proxy.get("flow").asText()).isEqualTo("xtls-rprx-vision");
    }

    @Test
    void vlessWithoutFlow_flowFieldAbsent() throws Exception {
        ServerConfig server = createVlessServer();
        // flow is null by default

        String json = generator.generate(server, defaultSettings);
        JsonNode proxy = parse(json).get("outbounds").get(0);

        assertThat(proxy.has("flow")).isFalse();
    }

    @Test
    void vlessWithEmptyFlow_flowFieldAbsent() throws Exception {
        ServerConfig server = createVlessServer();
        server.setFlow("");

        String json = generator.generate(server, defaultSettings);
        JsonNode proxy = parse(json).get("outbounds").get(0);

        assertThat(proxy.has("flow")).isFalse();
    }

    // -- Reality tests --

    @Test
    void vlessWithReality_generatesRealityFields() throws Exception {
        ServerConfig server = createVlessServer();
        server.getTls().setEnabled(true);
        server.getTls().setServerName("reality.example.com");
        server.getTls().setReality(true);
        server.getTls().setRealityPublicKey("abc123publickey");
        server.getTls().setRealityShortId("deadbeef");

        String json = generator.generate(server, defaultSettings);
        JsonNode proxy = parse(json).get("outbounds").get(0);

        JsonNode tls = proxy.get("tls");
        assertThat(tls).isNotNull();
        assertThat(tls.get("enabled").asBoolean()).isTrue();
        assertThat(tls.get("server_name").asText()).isEqualTo("reality.example.com");

        JsonNode reality = tls.get("reality");
        assertThat(reality).isNotNull();
        assertThat(reality.get("enabled").asBoolean()).isTrue();
        assertThat(reality.get("public_key").asText()).isEqualTo("abc123publickey");
        assertThat(reality.get("short_id").asText()).isEqualTo("deadbeef");
    }

    // -- Experimental / Clash API tests --

    @Test
    void clashApi_configuredInExperimentalSection() throws Exception {
        String json = generator.generate(createVlessServer(), defaultSettings);
        JsonNode root = parse(json);

        JsonNode experimental = root.get("experimental");
        assertThat(experimental).isNotNull();

        JsonNode clashApi = experimental.get("clash_api");
        assertThat(clashApi).isNotNull();
        assertThat(clashApi.get("external_controller").asText()).isEqualTo("127.0.0.1:9090");
    }

    @Test
    void customPorts_reflectedInConfig() throws Exception {
        AppSettings settings = new AppSettings();
        settings.setSocksPort(2080);
        settings.setHttpPort(2081);
        settings.setClashApiPort(9091);

        String json = generator.generate(createVlessServer(), settings);
        JsonNode root = parse(json);

        assertThat(root.get("inbounds").get(0).get("listen_port").asInt()).isEqualTo(2080);
        assertThat(root.get("inbounds").get(1).get("listen_port").asInt()).isEqualTo(2081);
        assertThat(root.get("experimental").get("clash_api")
                .get("external_controller").asText()).isEqualTo("127.0.0.1:9091");
    }

    // -- Log section test --

    @Test
    void logSection_hasInfoLevelAndTimestamp() throws Exception {
        String json = generator.generate(createVlessServer(), defaultSettings);
        JsonNode log = parse(json).get("log");

        assertThat(log).isNotNull();
        assertThat(log.get("level").asText()).isEqualTo("info");
        assertThat(log.get("timestamp").asBoolean()).isTrue();
    }

    // -- TLS fingerprint / utls test --

    @Test
    void vlessWithTlsFingerprint_generatesUtlsSection() throws Exception {
        ServerConfig server = createVlessServerWithTls();
        server.getTls().setFingerprint("chrome");

        String json = generator.generate(server, defaultSettings);
        JsonNode tls = parse(json).get("outbounds").get(0).get("tls");

        JsonNode utls = tls.get("utls");
        assertThat(utls).isNotNull();
        assertThat(utls.get("enabled").asBoolean()).isTrue();
        assertThat(utls.get("fingerprint").asText()).isEqualTo("chrome");
    }
}
