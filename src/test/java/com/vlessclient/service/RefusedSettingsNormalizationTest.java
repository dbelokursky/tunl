package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TransportConfig;
import com.vlessclient.model.TransportType;
import com.vlessclient.service.outbound.OutboundTags;
import com.vlessclient.testing.TestServers;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Settings a share link spells in a way sing-box refuses, although what they
 * mean is not in doubt: a REALITY server without a uTLS fingerprint, a
 * fingerprint in capitals, a REALITY key in the standard base64 alphabet, a
 * WireGuard address without a prefix, a Shadowsocks cipher under its Xray name.
 * They are corrected whenever a configuration is generated, so a server stored
 * before the correction connects too. The real core's verdict on the corrected
 * shapes is asserted in {@code SingBoxRealBinarySmokeTest}.
 */
class RefusedSettingsNormalizationTest {

    private static final String REALITY_KEY = "Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw";

    private final SingBoxConfigGenerator generator = new SingBoxConfigGenerator();
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void realityWithoutAFingerprintIsSentThroughChrome() throws Exception {
        ServerConfig server = reality();
        server.getTls().setFingerprint(null);

        JsonNode utls = memberOf(server, "outbounds").get("tls").get("utls");

        assertThat(utls).isNotNull();
        assertThat(utls.get("enabled").asBoolean()).isTrue();
        assertThat(utls.get("fingerprint").asString()).isEqualTo("chrome");
    }

    /**
     * Plain TLS whose link names no fingerprint went out with Go's own
     * ClientHello, which no browser sends and DPI tells apart. It goes as
     * Chrome now, like REALITY and like other clients by default. The real
     * core's greeting is asserted in {@code SingBoxRealBinarySmokeTest}.
     */
    @Test
    void plainTlsWithoutAFingerprintGoesAsChromeToo() throws Exception {
        ServerConfig server = reality();
        server.getTls().setReality(false);
        server.getTls().setFingerprint("");

        JsonNode utls = memberOf(server, "outbounds").get("tls").get("utls");

        assertThat(utls).isNotNull();
        assertThat(utls.get("enabled").asBoolean()).isTrue();
        assertThat(utls.get("fingerprint").asString()).isEqualTo("chrome");
    }

    /**
     * Over QUIC, Hysteria2's or the QUIC transport's, no fingerprint is sent,
     * not even one the link sets: the core fails every such connection with
     * "unsupported usage for uTLS".
     */
    @Test
    void noFingerprintGoesOverQuic() throws Exception {
        ServerConfig overQuic = reality();
        overQuic.getTls().setReality(false);
        overQuic.getTls().setFingerprint("chrome");
        TransportConfig quic = new TransportConfig();
        quic.setType(TransportType.QUIC);
        overQuic.setTransport(quic);

        assertThat(memberOf(overQuic, "outbounds").get("tls").has("utls")).isFalse();
    }

    @Test
    void aFingerprintIsSentInTheLowerCaseTheCoreKnows() throws Exception {
        ServerConfig server = reality();
        server.getTls().setFingerprint(" Chrome ");

        assertThat(memberOf(server, "outbounds").get("tls").get("utls").get("fingerprint")
                .asString()).isEqualTo("chrome");
    }

    @Test
    void aRealityKeyInTheStandardAlphabetIsSentAsBase64Url() throws Exception {
        ServerConfig server = reality();
        server.getTls().setRealityPublicKey(" ab+cd/ef== ");

        assertThat(memberOf(server, "outbounds").get("tls").get("reality").get("public_key")
                .asString()).isEqualTo("ab-cd_ef");
    }

    @Test
    void aWireGuardAddressWithoutAPrefixIsASingleHost() throws Exception {
        assertThat(interfaceAddress("10.0.0.2")).isEqualTo("10.0.0.2/32");
        assertThat(interfaceAddress("fd00::2")).isEqualTo("fd00::2/128");
        assertThat(interfaceAddress("10.0.0.2/24")).isEqualTo("10.0.0.2/24");
    }

    @Test
    void shadowsocksCiphersAreSentByTheNamesTheCoreKnows() throws Exception {
        assertThat(method("chacha20-poly1305")).isEqualTo("chacha20-ietf-poly1305");
        assertThat(method("xchacha20-poly1305")).isEqualTo("xchacha20-ietf-poly1305");
        assertThat(method("plain")).isEqualTo("none");
        assertThat(method(" AES-256-GCM ")).isEqualTo("aes-256-gcm");
        assertThat(method("2022-blake3-aes-256-gcm")).isEqualTo("2022-blake3-aes-256-gcm");
    }

    private ServerConfig reality() {
        return TestServers.server()
                .id("reality")
                .name("reality")
                .protocol(Protocol.VLESS)
                .address("203.0.113.1")
                .port(443)
                .uuid("b1c2d3e4-f5a6-7890-abcd-ef1234567890")
                .reality("www.microsoft.com", REALITY_KEY, "0123abcd")
                .build();
    }

    private String interfaceAddress(String address) throws Exception {
        ServerConfig server = new ServerConfig();
        server.setId("wireguard");
        server.setName("wireguard");
        server.setProtocol(Protocol.WIREGUARD);
        server.setAddress("203.0.113.2");
        server.setPort(51820);
        server.setUuid("wg-private-key-base64");
        server.setEncryption("wg-peer-public-key-base64");
        server.setFlow(address);
        return memberOf(server, "endpoints").get("address").get(0).asString();
    }

    private String method(String method) throws Exception {
        ServerConfig server = new ServerConfig();
        server.setId("shadowsocks");
        server.setName("shadowsocks");
        server.setProtocol(Protocol.SHADOWSOCKS);
        server.setAddress("203.0.113.3");
        server.setPort(8388);
        server.setUuid("password");
        server.setEncryption(method);
        return memberOf(server, "outbounds").get("method").asString();
    }

    /** The server's own outbound or endpoint in the generated configuration. */
    private JsonNode memberOf(ServerConfig server, String section) throws Exception {
        JsonNode root = mapper.readTree(generator.generate(server, new AppSettings()));
        for (JsonNode member : root.path(section)) {
            if (OutboundTags.server(server).equals(member.path("tag").asString())) {
                return member;
            }
        }
        throw new AssertionError("no entry for the server in " + section);
    }
}
