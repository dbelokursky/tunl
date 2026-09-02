package com.vlessclient.service;

import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TransportType;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parser branches that change the produced config without saying so.
 *
 * <p>These are the ones worth pinning: each silently decides something the
 * imported server will then be built with — which port it dials, whether REALITY
 * is on, which sing-box transport a vmess {@code net} value becomes. A
 * regression here does not throw and does not warn; it produces a server that
 * connects to the wrong place, or does not connect at all, and the share link
 * still looks fine to the user who pasted it.</p>
 */
class ShareLinkSilentDefaultsTest {

    private final ShareLinkParser parser = new ShareLinkParser();

    private static String vmess(String net) {
        String json = """
                {"v":"2","ps":"Net Test","add":"jp.example.com","port":"443",\
                "id":"550e8400-e29b-41d4-a716-446655440000","aid":"0","scy":"auto",\
                "net":"%s","type":"none","tls":"tls"}""".formatted(net);
        return "vmess://" + Base64.getEncoder()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a trojan link with no port dials 443, not 0")
    void trojanWithoutAPortDefaultsTo443() {
        ServerConfig config = parser.parse("trojan://password@host.example#NoPort");

        assertThat(config.getPort()).isEqualTo(443);
    }

    @Test
    @DisplayName("a hysteria2 link with no port dials 443, not 0")
    void hysteria2WithoutAPortDefaultsTo443() {
        ServerConfig config = parser.parse("hysteria2://password@host.example#NoPort");

        assertThat(config.getPort()).isEqualTo(443);
    }

    @Test
    @DisplayName("security=reality turns on both TLS and REALITY")
    void realityImpliesTls() {
        ServerConfig config = parser.parse(
                "trojan://password@host.example:443?security=reality"
                        + "&sni=www.example.org&fp=chrome#Reality");

        assertThat(config.getTls().isEnabled())
                .as("REALITY is a TLS mode; leaving TLS off would produce a "
                        + "server that cannot connect")
                .isTrue();
        assertThat(config.getTls().isReality()).isTrue();
    }

    @Test
    @DisplayName("vmess net=h2 becomes the http transport")
    void h2MapsToHttp() {
        assertThat(parser.parse(vmess("h2")).getTransport().getType())
                .isEqualTo(TransportType.HTTP2);
    }

    @Test
    @DisplayName("vmess net=kcp becomes the tcp transport")
    void kcpMapsToTcp() {
        // sing-box has no kcp transport; the parser maps it to tcp rather than
        // passing a value the core would reject at start.
        assertThat(parser.parse(vmess("kcp")).getTransport().getType())
                .isEqualTo(TransportType.TCP);
    }
}
