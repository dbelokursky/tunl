package com.vlessclient.service;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TransportType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShareLinkExporterTest {

    private ShareLinkExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new ShareLinkExporter();
    }

    @Test
    void exportMinimalVless() {
        ServerConfig config = new ServerConfig();
        config.setProtocol(Protocol.VLESS);
        config.setUuid("test-uuid");
        config.setAddress("example.com");
        config.setPort(443);
        config.setName("Simple");

        String uri = exporter.export(config);

        assertThat(uri).isEqualTo("vless://test-uuid@example.com:443#Simple");
    }

    @Test
    void exportVlessWithWsAndTls() {
        ServerConfig config = new ServerConfig();
        config.setProtocol(Protocol.VLESS);
        config.setUuid("test-uuid");
        config.setAddress("proxy.example.com");
        config.setPort(443);
        config.setName("WS TLS");
        config.getTransport().setType(TransportType.WEBSOCKET);
        config.getTransport().setPath("/ws");
        config.getTransport().setHost("cdn.example.com");
        config.getTls().setEnabled(true);
        config.getTls().setServerName("sni.example.com");
        config.getTls().setFingerprint("chrome");

        String uri = exporter.export(config);

        assertThat(uri).startsWith("vless://test-uuid@proxy.example.com:443?");
        assertThat(uri).contains("type=ws");
        assertThat(uri).contains("security=tls");
        assertThat(uri).contains("sni=sni.example.com");
        assertThat(uri).contains("fp=chrome");
        assertThat(uri).contains("path=%2Fws");
        assertThat(uri).contains("host=cdn.example.com");
        assertThat(uri).endsWith("#WS+TLS");
    }

    @Test
    void exportVlessWithReality() {
        ServerConfig config = new ServerConfig();
        config.setProtocol(Protocol.VLESS);
        config.setUuid("test-uuid");
        config.setAddress("reality.example.com");
        config.setPort(443);
        config.setName("Reality Server");
        config.setFlow("xtls-rprx-vision");
        config.getTls().setEnabled(true);
        config.getTls().setReality(true);
        config.getTls().setRealityPublicKey("pubkey123");
        config.getTls().setRealityShortId("sid456");
        config.getTls().setServerName("sni.example.com");
        config.getTls().setFingerprint("chrome");

        String uri = exporter.export(config);

        assertThat(uri).startsWith("vless://test-uuid@reality.example.com:443?");
        assertThat(uri).contains("security=reality");
        assertThat(uri).contains("pbk=pubkey123");
        assertThat(uri).contains("sid=sid456");
        assertThat(uri).contains("fp=chrome");
        assertThat(uri).contains("flow=xtls-rprx-vision");
        assertThat(uri).contains("sni=sni.example.com");
    }

    @Test
    void exportOmitsDefaultValues() {
        ServerConfig config = new ServerConfig();
        config.setProtocol(Protocol.VLESS);
        config.setUuid("test-uuid");
        config.setAddress("example.com");
        config.setPort(443);
        config.setName("Defaults");
        // transport is TCP (default), tls is disabled (default), encryption is "none" (default)

        String uri = exporter.export(config);

        assertThat(uri).doesNotContain("type=");
        assertThat(uri).doesNotContain("security=");
        assertThat(uri).doesNotContain("encryption=");
        assertThat(uri).isEqualTo("vless://test-uuid@example.com:443#Defaults");
    }

    @Test
    void exportedTrojanRealityComesBackWhole() {
        // Export has always written pbk and sid; the parser ignored them, so
        // the app's own export produced a Trojan server it could not import.
        ServerConfig config = new ServerConfig();
        config.setProtocol(Protocol.TROJAN);
        config.setUuid("trojan-password");
        config.setAddress("host.example");
        config.setPort(443);
        config.setName("Reality");
        config.getTls().setEnabled(true);
        config.getTls().setReality(true);
        config.getTls().setServerName("www.example.com");
        config.getTls().setFingerprint("chrome");
        config.getTls().setRealityPublicKey("PUBLICKEY123");
        config.getTls().setRealityShortId("ab12");

        ServerConfig back = new ShareLinkParser().parse(exporter.export(config));

        assertThat(back.getTls().isReality()).isTrue();
        assertThat(back.getTls().getRealityPublicKey()).isEqualTo("PUBLICKEY123");
        assertThat(back.getTls().getRealityShortId()).isEqualTo("ab12");
        assertThat(back.getTls().getFingerprint()).isEqualTo("chrome");
    }

    /**
     * A Trojan link without security= means TLS, which is how Trojan runs
     * almost always, so the export left the parameter out for a server with
     * TLS off, and the import turned TLS back on.
     */
    @Test
    void exportedTrojanWithoutTlsComesBackWithoutTls() {
        ServerConfig config = new ServerConfig();
        config.setProtocol(Protocol.TROJAN);
        config.setUuid("trojan-password");
        config.setAddress("host.example");
        config.setPort(8080);
        config.setName("Plain");
        config.getTls().setEnabled(false);

        String link = exporter.export(config);
        ServerConfig back = new ShareLinkParser().parse(link);

        assertThat(link).contains("security=none");
        assertThat(back.getTls().isEnabled()).as("TLS after the round trip").isFalse();
    }

    @Test
    void exportShadowsocksKeepsThePluginSoTheLinkStillWorks() {
        ServerConfig config = new ServerConfig();
        config.setProtocol(Protocol.SHADOWSOCKS);
        config.setEncryption("aes-256-gcm");
        config.setUuid("password");
        config.setAddress("host.example");
        config.setPort(443);
        config.setPlugin("obfs-local");
        config.setPluginOpts("obfs=http;obfs-host=bing.com");

        String uri = exporter.export(config);

        ServerConfig back = new ShareLinkParser().parse(uri);
        assertThat(back.getPlugin()).isEqualTo("obfs-local");
        assertThat(back.getPluginOpts()).isEqualTo("obfs=http;obfs-host=bing.com");
        assertThat(back.getEncryption()).isEqualTo("aes-256-gcm");
        assertThat(back.getUuid()).isEqualTo("password");
    }

    @Test
    void exportUrlEncodesServerName() {
        ServerConfig config = new ServerConfig();
        config.setProtocol(Protocol.VLESS);
        config.setUuid("test-uuid");
        config.setAddress("example.com");
        config.setPort(443);
        config.setName("My Server & Proxy");

        String uri = exporter.export(config);

        assertThat(uri).endsWith("#My+Server+%26+Proxy");
    }
}
