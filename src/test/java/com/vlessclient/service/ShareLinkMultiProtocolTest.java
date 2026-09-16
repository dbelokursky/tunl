package com.vlessclient.service;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TransportType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShareLinkMultiProtocolTest {

    private ShareLinkParser parser;
    private ShareLinkExporter exporter;

    @BeforeEach
    void setUp() {
        parser = new ShareLinkParser();
        exporter = new ShareLinkExporter();
    }

    @Nested
    class VmessTests {

        @Test
        void parseVmessBase64Json() {
            String json = """
                    {"v":"2","ps":"Tokyo Server","add":"jp.example.com","port":"443",\
                    "id":"550e8400-e29b-41d4-a716-446655440000","aid":"0","scy":"auto",\
                    "net":"ws","type":"none","host":"cdn.example.com","path":"/ws-path",\
                    "tls":"tls","sni":"sni.example.com","alpn":"h2,http/1.1","fp":"chrome"}""";
            String encoded = Base64.getEncoder().encodeToString(
                    json.getBytes(StandardCharsets.UTF_8));
            String uri = "vmess://" + encoded;

            ServerConfig config = parser.parse(uri);

            assertThat(config.getProtocol()).isEqualTo(Protocol.VMESS);
            assertThat(config.getName()).isEqualTo("Tokyo Server");
            assertThat(config.getAddress()).isEqualTo("jp.example.com");
            assertThat(config.getPort()).isEqualTo(443);
            assertThat(config.getUuid()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
            assertThat(config.getEncryption()).isEqualTo("auto");
            assertThat(config.getTransport().getType()).isEqualTo(TransportType.WEBSOCKET);
            assertThat(config.getTransport().getPath()).isEqualTo("/ws-path");
            assertThat(config.getTransport().getHost()).isEqualTo("cdn.example.com");
            assertThat(config.getTls().isEnabled()).isTrue();
            assertThat(config.getTls().getServerName()).isEqualTo("sni.example.com");
            assertThat(config.getTls().getAlpn()).isEqualTo("h2,http/1.1");
            assertThat(config.getTls().getFingerprint()).isEqualTo("chrome");
        }

        @Test
        void parseVmessWithGrpcTransport() {
            String json = """
                    {"v":"2","ps":"gRPC","add":"grpc.example.com","port":"443",\
                    "id":"test-uuid","aid":"0","scy":"aes-128-gcm","net":"grpc",\
                    "type":"none","host":"","path":"","tls":"tls","sni":"grpc.example.com",\
                    "alpn":"","fp":""}""";
            String encoded = Base64.getEncoder().encodeToString(
                    json.getBytes(StandardCharsets.UTF_8));

            ServerConfig config = parser.parse("vmess://" + encoded);

            assertThat(config.getTransport().getType()).isEqualTo(TransportType.GRPC);
            assertThat(config.getEncryption()).isEqualTo("aes-128-gcm");
        }

        @Test
        void parseVmessWithNoTls() {
            String json = """
                    {"v":"2","ps":"NoTLS","add":"plain.example.com","port":"80",\
                    "id":"test-uuid","aid":"0","scy":"auto","net":"tcp",\
                    "type":"none","host":"","path":"","tls":"","sni":"","alpn":"","fp":""}""";
            String encoded = Base64.getEncoder().encodeToString(
                    json.getBytes(StandardCharsets.UTF_8));

            ServerConfig config = parser.parse("vmess://" + encoded);

            assertThat(config.getTls().isEnabled()).isFalse();
            assertThat(config.getPort()).isEqualTo(80);
        }

        @Test
        void exportVmessProducesValidBase64Json() {
            ServerConfig config = new ServerConfig();
            config.setProtocol(Protocol.VMESS);
            config.setName("Test VMess");
            config.setAddress("vmess.example.com");
            config.setPort(443);
            config.setUuid("550e8400-e29b-41d4-a716-446655440000");
            config.setEncryption("auto");
            config.getTransport().setType(TransportType.WEBSOCKET);
            config.getTransport().setPath("/ws");
            config.getTransport().setHost("cdn.example.com");
            config.getTls().setEnabled(true);
            config.getTls().setServerName("sni.example.com");
            config.getTls().setFingerprint("chrome");

            String exported = exporter.export(config);

            assertThat(exported).startsWith("vmess://");
            String encoded = exported.substring("vmess://".length());
            // Should be valid base64
            byte[] decoded = Base64.getDecoder().decode(encoded);
            String json = new String(decoded, StandardCharsets.UTF_8);
            assertThat(json).contains("\"add\":\"vmess.example.com\"");
            assertThat(json).contains("\"id\":\"550e8400-e29b-41d4-a716-446655440000\"");
            assertThat(json).contains("\"net\":\"ws\"");
            assertThat(json).contains("\"tls\":\"tls\"");
        }

        @Test
        void roundTripVmess() {
            ServerConfig original = new ServerConfig();
            original.setProtocol(Protocol.VMESS);
            original.setName("RT VMess");
            original.setAddress("rt.example.com");
            original.setPort(8443);
            original.setUuid("uuid-vmess-rt");
            original.setEncryption("aes-128-gcm");
            original.getTransport().setType(TransportType.WEBSOCKET);
            original.getTransport().setPath("/path");
            original.getTransport().setHost("host.example.com");
            original.getTls().setEnabled(true);
            original.getTls().setServerName("sni.example.com");
            original.getTls().setAlpn("h2");
            original.getTls().setFingerprint("firefox");

            String exported = exporter.export(original);
            ServerConfig parsed = parser.parse(exported);

            assertThat(parsed.getProtocol()).isEqualTo(original.getProtocol());
            assertThat(parsed.getName()).isEqualTo(original.getName());
            assertThat(parsed.getAddress()).isEqualTo(original.getAddress());
            assertThat(parsed.getPort()).isEqualTo(original.getPort());
            assertThat(parsed.getUuid()).isEqualTo(original.getUuid());
            assertThat(parsed.getEncryption()).isEqualTo(original.getEncryption());
            assertThat(parsed.getTransport().getType()).isEqualTo(original.getTransport().getType());
            assertThat(parsed.getTransport().getPath()).isEqualTo(original.getTransport().getPath());
            assertThat(parsed.getTransport().getHost()).isEqualTo(original.getTransport().getHost());
            assertThat(parsed.getTls().isEnabled()).isEqualTo(original.getTls().isEnabled());
            assertThat(parsed.getTls().getServerName()).isEqualTo(original.getTls().getServerName());
            assertThat(parsed.getTls().getAlpn()).isEqualTo(original.getTls().getAlpn());
            assertThat(parsed.getTls().getFingerprint()).isEqualTo(original.getTls().getFingerprint());
        }
    }

    @Nested
    class TrojanTests {

        @Test
        void parseTrojanWithTlsAndWebSocket() {
            String uri = "trojan://my-secret-password@trojan.example.com:443"
                    + "?security=tls&sni=sni.example.com&type=ws"
                    + "&path=%2Fws-path&host=cdn.example.com#Trojan WS";

            ServerConfig config = parser.parse(uri);

            assertThat(config.getProtocol()).isEqualTo(Protocol.TROJAN);
            assertThat(config.getUuid()).isEqualTo("my-secret-password");
            assertThat(config.getAddress()).isEqualTo("trojan.example.com");
            assertThat(config.getPort()).isEqualTo(443);
            assertThat(config.getName()).isEqualTo("Trojan WS");
            assertThat(config.getTransport().getType()).isEqualTo(TransportType.WEBSOCKET);
            assertThat(config.getTransport().getPath()).isEqualTo("/ws-path");
            assertThat(config.getTransport().getHost()).isEqualTo("cdn.example.com");
            assertThat(config.getTls().isEnabled()).isTrue();
            assertThat(config.getTls().getServerName()).isEqualTo("sni.example.com");
        }

        @Test
        void parseTrojanDefaultsTlsEnabled() {
            String uri = "trojan://password@host.example:443#Simple";

            ServerConfig config = parser.parse(uri);

            assertThat(config.getTls().isEnabled()).isTrue();
        }

        @Test
        void parseTrojanWithGrpc() {
            String uri = "trojan://password@host.example:443"
                    + "?type=grpc&serviceName=grpcService&security=tls&sni=host.example#gRPC Trojan";

            ServerConfig config = parser.parse(uri);

            assertThat(config.getTransport().getType()).isEqualTo(TransportType.GRPC);
            assertThat(config.getTransport().getServiceName()).isEqualTo("grpcService");
        }

        @Test
        void exportTrojanProducesValidUri() {
            ServerConfig config = new ServerConfig();
            config.setProtocol(Protocol.TROJAN);
            config.setName("Test Trojan");
            config.setAddress("trojan.example.com");
            config.setPort(443);
            config.setUuid("secret-password");
            config.getTransport().setType(TransportType.WEBSOCKET);
            config.getTransport().setPath("/ws");
            config.getTls().setEnabled(true);
            config.getTls().setServerName("sni.example.com");

            String exported = exporter.export(config);

            assertThat(exported).startsWith("trojan://");
            assertThat(exported).contains("secret-password");
            assertThat(exported).contains("trojan.example.com:443");
            assertThat(exported).contains("type=ws");
            assertThat(exported).contains("security=tls");
            assertThat(exported).contains("sni=sni.example.com");
        }

        @Test
        void roundTripTrojan() {
            ServerConfig original = new ServerConfig();
            original.setProtocol(Protocol.TROJAN);
            original.setName("RT Trojan");
            original.setAddress("rt-trojan.example.com");
            original.setPort(8443);
            original.setUuid("trojan-password-123");
            original.getTransport().setType(TransportType.WEBSOCKET);
            original.getTransport().setPath("/trojan-ws");
            original.getTransport().setHost("cdn.example.com");
            original.getTls().setEnabled(true);
            original.getTls().setServerName("sni.example.com");
            original.getTls().setFingerprint("chrome");
            original.getTls().setAlpn("h2,http/1.1");

            String exported = exporter.export(original);
            ServerConfig parsed = parser.parse(exported);

            assertThat(parsed.getProtocol()).isEqualTo(original.getProtocol());
            assertThat(parsed.getName()).isEqualTo(original.getName());
            assertThat(parsed.getAddress()).isEqualTo(original.getAddress());
            assertThat(parsed.getPort()).isEqualTo(original.getPort());
            assertThat(parsed.getUuid()).isEqualTo(original.getUuid());
            assertThat(parsed.getTransport().getType()).isEqualTo(original.getTransport().getType());
            assertThat(parsed.getTransport().getPath()).isEqualTo(original.getTransport().getPath());
            assertThat(parsed.getTransport().getHost()).isEqualTo(original.getTransport().getHost());
            assertThat(parsed.getTls().isEnabled()).isEqualTo(original.getTls().isEnabled());
            assertThat(parsed.getTls().getServerName()).isEqualTo(original.getTls().getServerName());
            assertThat(parsed.getTls().getFingerprint()).isEqualTo(original.getTls().getFingerprint());
            assertThat(parsed.getTls().getAlpn()).isEqualTo(original.getTls().getAlpn());
        }
    }

    @Nested
    class ShadowsocksTests {

        @Test
        void parseSsSip002Format() {
            String method = "aes-256-gcm";
            String password = "my-password";
            String userInfo = method + ":" + password;
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(userInfo.getBytes(StandardCharsets.UTF_8));
            String uri = "ss://" + encoded + "@ss.example.com:8388#SS%20Server";

            ServerConfig config = parser.parse(uri);

            assertThat(config.getProtocol()).isEqualTo(Protocol.SHADOWSOCKS);
            assertThat(config.getEncryption()).isEqualTo("aes-256-gcm");
            assertThat(config.getUuid()).isEqualTo("my-password");
            assertThat(config.getAddress()).isEqualTo("ss.example.com");
            assertThat(config.getPort()).isEqualTo(8388);
            assertThat(config.getName()).isEqualTo("SS Server");
        }

        @Test
        void parseSsWithStandardBase64() {
            String userInfo = "chacha20-ietf-poly1305:secret123";
            String encoded = Base64.getEncoder()
                    .encodeToString(userInfo.getBytes(StandardCharsets.UTF_8));
            String uri = "ss://" + encoded + "@ss.example.com:1080#Test";

            ServerConfig config = parser.parse(uri);

            assertThat(config.getEncryption()).isEqualTo("chacha20-ietf-poly1305");
            assertThat(config.getUuid()).isEqualTo("secret123");
        }

        @Test
        void parseSsWithPluginQuery() {
            String userInfo = "aes-256-gcm:password";
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(userInfo.getBytes(StandardCharsets.UTF_8));
            String uri = "ss://" + encoded + "@host.example:443/"
                    + "?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dbing.com#Name";

            ServerConfig config = parser.parse(uri);

            assertThat(config.getAddress()).isEqualTo("host.example");
            assertThat(config.getPort()).isEqualTo(443);
            assertThat(config.getEncryption()).isEqualTo("aes-256-gcm");
            assertThat(config.getUuid()).isEqualTo("password");
            // The plugin is the point of a link that carries one: the server
            // speaks nothing else. Dropping it left the app reporting a
            // connected tunnel that moved no traffic.
            assertThat(config.getPlugin()).isEqualTo("obfs-local");
            assertThat(config.getPluginOpts()).isEqualTo("obfs=http;obfs-host=bing.com");
        }

        @Test
        void parseSsWithPlainPercentEncodedUserInfo() {
            // SIP002 allows the userinfo in the clear, percent-encoded, and the
            // 2022 ciphers require it: their key is base64 already, so wrapping
            // the pair in another base64 is what breaks interoperability.
            String uri = "ss://2022-blake3-aes-128-gcm:L3zK%2FQ%3D%3D@host.example:8388#Name";

            ServerConfig config = parser.parse(uri);

            assertThat(config.getEncryption()).isEqualTo("2022-blake3-aes-128-gcm");
            assertThat(config.getUuid()).isEqualTo("L3zK/Q==");
            assertThat(config.getAddress()).isEqualTo("host.example");
            assertThat(config.getPort()).isEqualTo(8388);
        }

        @Test
        void parseSsWithoutFragment() {
            String userInfo = "aes-256-gcm:pass";
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(userInfo.getBytes(StandardCharsets.UTF_8));
            String uri = "ss://" + encoded + "@host.example:8388";

            ServerConfig config = parser.parse(uri);

            assertThat(config.getName()).isEqualTo("host.example:8388");
        }

        @Test
        void exportSsProducesValidBase64() {
            ServerConfig config = new ServerConfig();
            config.setProtocol(Protocol.SHADOWSOCKS);
            config.setName("SS Export");
            config.setAddress("ss.example.com");
            config.setPort(8388);
            config.setEncryption("aes-256-gcm");
            config.setUuid("my-password");

            String exported = exporter.export(config);

            assertThat(exported).startsWith("ss://");
            assertThat(exported).contains("@ss.example.com:8388");
            assertThat(exported).endsWith("#SS+Export");
        }

        @Test
        void roundTripShadowsocks() {
            ServerConfig original = new ServerConfig();
            original.setProtocol(Protocol.SHADOWSOCKS);
            original.setName("RT Shadowsocks");
            original.setAddress("ss-rt.example.com");
            original.setPort(8388);
            original.setEncryption("chacha20-ietf-poly1305");
            original.setUuid("ss-password-123");

            String exported = exporter.export(original);
            ServerConfig parsed = parser.parse(exported);

            assertThat(parsed.getProtocol()).isEqualTo(original.getProtocol());
            assertThat(parsed.getName()).isEqualTo(original.getName());
            assertThat(parsed.getAddress()).isEqualTo(original.getAddress());
            assertThat(parsed.getPort()).isEqualTo(original.getPort());
            assertThat(parsed.getEncryption()).isEqualTo(original.getEncryption());
            assertThat(parsed.getUuid()).isEqualTo(original.getUuid());
        }
    }

    @Nested
    class Hysteria2Tests {

        @Test
        void parseHysteria2WithObfuscation() {
            String uri = "hysteria2://my-hy2-password@hy2.example.com:443"
                    + "?sni=sni.example.com&obfs=salamander&obfs-password=obfs-secret#HY2 Server";

            ServerConfig config = parser.parse(uri);

            assertThat(config.getProtocol()).isEqualTo(Protocol.HYSTERIA2);
            assertThat(config.getUuid()).isEqualTo("my-hy2-password");
            assertThat(config.getAddress()).isEqualTo("hy2.example.com");
            assertThat(config.getPort()).isEqualTo(443);
            assertThat(config.getName()).isEqualTo("HY2 Server");
            assertThat(config.getFlow()).isEqualTo("obfs-secret");
            assertThat(config.getEncryption()).isEqualTo("salamander");
            assertThat(config.getTls().isEnabled()).isTrue();
            assertThat(config.getTls().getServerName()).isEqualTo("sni.example.com");
        }

        @Test
        void parseHysteria2WithHy2Scheme() {
            String uri = "hy2://password@host.example:443?sni=host.example#HY2";

            ServerConfig config = parser.parse(uri);

            assertThat(config.getProtocol()).isEqualTo(Protocol.HYSTERIA2);
            assertThat(config.getUuid()).isEqualTo("password");
            assertThat(config.getTls().isEnabled()).isTrue();
        }

        @Test
        void parseHysteria2WithInsecure() {
            String uri = "hysteria2://pass@host.example:443?insecure=1#Test";

            ServerConfig config = parser.parse(uri);

            assertThat(config.getTls().isAllowInsecure()).isTrue();
        }

        @Test
        void parseHysteria2Minimal() {
            String uri = "hysteria2://pass@host.example:443#Minimal";

            ServerConfig config = parser.parse(uri);

            assertThat(config.getProtocol()).isEqualTo(Protocol.HYSTERIA2);
            assertThat(config.getUuid()).isEqualTo("pass");
            assertThat(config.getTls().isEnabled()).isTrue();
            assertThat(config.getFlow()).isNull();
        }

        @Test
        void exportHysteria2ProducesValidUri() {
            ServerConfig config = new ServerConfig();
            config.setProtocol(Protocol.HYSTERIA2);
            config.setName("Test HY2");
            config.setAddress("hy2.example.com");
            config.setPort(443);
            config.setUuid("hy2-password");
            config.setEncryption("salamander");
            config.setFlow("obfs-secret");
            config.getTls().setEnabled(true);
            config.getTls().setServerName("sni.example.com");

            String exported = exporter.export(config);

            assertThat(exported).startsWith("hysteria2://");
            assertThat(exported).contains("hy2-password");
            assertThat(exported).contains("hy2.example.com:443");
            assertThat(exported).contains("sni=sni.example.com");
            assertThat(exported).contains("obfs=salamander");
            assertThat(exported).contains("obfs-password=obfs-secret");
        }

        @Test
        void roundTripHysteria2() {
            ServerConfig original = new ServerConfig();
            original.setProtocol(Protocol.HYSTERIA2);
            original.setName("RT Hysteria2");
            original.setAddress("hy2-rt.example.com");
            original.setPort(8443);
            original.setUuid("hy2-rt-password");
            original.setEncryption("salamander");
            original.setFlow("obfs-pw-123");
            original.getTls().setEnabled(true);
            original.getTls().setServerName("sni.example.com");

            String exported = exporter.export(original);
            ServerConfig parsed = parser.parse(exported);

            assertThat(parsed.getProtocol()).isEqualTo(original.getProtocol());
            assertThat(parsed.getName()).isEqualTo(original.getName());
            assertThat(parsed.getAddress()).isEqualTo(original.getAddress());
            assertThat(parsed.getPort()).isEqualTo(original.getPort());
            assertThat(parsed.getUuid()).isEqualTo(original.getUuid());
            assertThat(parsed.getEncryption()).isEqualTo(original.getEncryption());
            assertThat(parsed.getFlow()).isEqualTo(original.getFlow());
            assertThat(parsed.getTls().isEnabled()).isEqualTo(original.getTls().isEnabled());
            assertThat(parsed.getTls().getServerName()).isEqualTo(original.getTls().getServerName());
        }
    }

    @Nested
    class EdgeCaseTests {

        @Test
        void parseUnsupportedSchemeThrows() {
            assertThatThrownBy(() -> parser.parse("wireguard://test"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported protocol scheme");
        }

        @Test
        void parseNullUriThrows() {
            assertThatThrownBy(() -> parser.parse(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void parseBlankUriThrows() {
            assertThatThrownBy(() -> parser.parse("  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void trojanPasswordWithUrlEncoding() {
            String uri = "trojan://p%40ss%3Aword@host.example:443#Encoded";

            ServerConfig config = parser.parse(uri);

            assertThat(config.getUuid()).isEqualTo("p@ss:word");
        }

        @Test
        void vmessPortAsString() {
            String json = """
                    {"v":"2","ps":"StringPort","add":"host.example","port":"8080",\
                    "id":"uuid-test","aid":"0","scy":"auto","net":"tcp",\
                    "type":"none","host":"","path":"","tls":"","sni":"","alpn":"","fp":""}""";
            String encoded = Base64.getEncoder().encodeToString(
                    json.getBytes(StandardCharsets.UTF_8));

            ServerConfig config = parser.parse("vmess://" + encoded);

            assertThat(config.getPort()).isEqualTo(8080);
        }
    }
}
