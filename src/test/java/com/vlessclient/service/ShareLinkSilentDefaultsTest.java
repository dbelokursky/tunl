package com.vlessclient.service;

import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TransportType;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private static final String UUID = "b831381d-6324-4d53-ad4f-8cda48b30811";

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
    @DisplayName("vmess net=kcp is reported as unsupported, not dialled over tcp")
    void kcpIsReportedAsUnsupported() {
        // sing-box has no kcp transport. Mapping it to tcp produced a server that
        // dials a UDP service over TCP and never connects, with no word about why.
        assertThatThrownBy(() -> parser.parse(vmess("kcp")))
                .isInstanceOfSatisfying(ShareLinkParser.UnsupportedFeatureException.class,
                        e -> assertThat(e.feature()).isEqualTo("transport kcp"));
    }

    /**
     * Only "tls" and "reality", spelled exactly so, turned TLS on. TLS in
     * capitals, an empty security= on a Trojan link, which means TLS like no
     * security= at all, and a value with spaces around it all read as none:
     * the server dialled its TLS port in the clear and sent the Trojan
     * password, or the VLESS UUID, as its first bytes.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "trojan://secretpw@host.example:443?security=TLS&sni=host.example#t",
        "trojan://secretpw@host.example:443?security=&sni=host.example#t",
        "trojan://secretpw@host.example:443?security=%20tls%20&sni=host.example#t",
        "vless://" + UUID + "@host.example:443?security=TLS&sni=host.example#t",
        "vless://" + UUID + "@host.example:443?security=Tls&sni=host.example#t"
    })
    @DisplayName("security=tls in any spelling keeps TLS on")
    void tlsInAnySpellingKeepsTlsOn(String link) {
        ServerConfig config = parser.parseForImport(link);

        assertThat(config.getTls().isEnabled()).as("TLS").isTrue();
        assertThat(config.getTls().isReality()).as("REALITY").isFalse();
    }

    @Test
    @DisplayName("security=Reality is REALITY")
    void realityInAnotherCaseIsReality() {
        ServerConfig config = parser.parseForImport("vless://" + UUID + "@host.example:443"
                + "?security=Reality&sni=www.example.org&fp=chrome"
                + "&pbk=WZaG00XCAiVCF2SP5fmSbKiuTbBB-lMDg_81rC8hR80&sid=0123abcd#r");

        assertThat(config.getTls().isEnabled()).isTrue();
        assertThat(config.getTls().isReality()).isTrue();
    }

    /**
     * A VLESS link without TLS is legitimate, behind a CDN say, and VLESS
     * means none when the link names none; an empty value names none too.
     */
    @Test
    @DisplayName("an empty security= on a VLESS link is the VLESS default, none")
    void anEmptySecurityOnAVlessLinkIsNone() {
        ServerConfig config = parser.parseForImport(
                "vless://" + UUID + "@host.example:80?security=&type=ws&path=%2F#v");

        assertThat(config.getTls().isEnabled()).isFalse();
    }

    /**
     * Legacy XTLS, which Xray dropped and the core never had, read as no TLS
     * at all, and so did any word the app does not know. Neither may turn a
     * link that asks for protection into one that sends its credential in
     * the clear: the link is refused, and says what it asked for.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "trojan://secretpw@host.example:443?security=xtls&sni=host.example#t",
        "vless://" + UUID + "@host.example:443?security=XTLS&flow=xtls-rprx-direct#t"
    })
    @DisplayName("a security layer the core lacks is refused, not read as none")
    void aSecurityLayerTheCoreLacksIsRefused(String link) {
        assertThatThrownBy(() -> parser.parse(link))
                .isInstanceOfSatisfying(ShareLinkParser.UnsupportedFeatureException.class,
                        e -> assertThat(e.feature()).isEqualTo("security xtls"));
    }

    /**
     * v2rayN writes "net":"" for TCP and reads it back as TCP, and an empty
     * type= is the same. Both were refused as a transport named "", reported
     * as "the  transport", and a plain TCP server went missing.
     */
    @Test
    @DisplayName("an empty transport is TCP")
    void anEmptyTransportIsTcp() {
        assertThat(parser.parse(vmess("")).getTransport().getType())
                .isEqualTo(TransportType.TCP);
        assertThat(parser.parse("vless://" + UUID + "@host.example:443?type=&security=tls#v")
                .getTransport().getType()).isEqualTo(TransportType.TCP);
        assertThat(parser.parse("trojan://secretpw@host.example:443?type=#t")
                .getTransport().getType()).isEqualTo(TransportType.TCP);
    }
}
