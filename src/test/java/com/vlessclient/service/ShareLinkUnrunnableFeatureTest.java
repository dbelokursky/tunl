package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.TransportType;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Links asking for something the parser used to drop without a word. sing-box
 * accepts the server the link turned into, so it was stored, and then it never
 * carried traffic: the server on the other end speaks something else. Each is
 * refused at import with the reason, like a transport sing-box does not have.
 */
class ShareLinkUnrunnableFeatureTest {

    private static final String UUID = "b831381d-6324-4d53-ad4f-8cda48b30811";

    private final ShareLinkParser parser = new ShareLinkParser();

    /**
     * Xray's VLESS encryption. A server set up for it takes no plain VLESS, and
     * sing-box has no other. The value carries the client's keys after the
     * first dot, so the reason names the method only.
     */
    @Test
    void vlessEncryptionIsRefusedWithoutRepeatingItsKeys() {
        String link = "vless://" + UUID + "@vless.example:443?encryption="
                + "mlkem768x25519plus.native.0rtt.CLIENTKEYMATERIAL"
                + "&security=tls&sni=vless.example#E";

        assertThatThrownBy(() -> parser.parse(link))
                .isInstanceOfSatisfying(ShareLinkParser.UnsupportedFeatureException.class, e -> {
                    assertThat(e.feature()).isEqualTo("VLESS encryption mlkem768x25519plus");
                    assertThat(e.getMessage()).contains("mlkem768x25519plus")
                            .doesNotContain("CLIENTKEYMATERIAL");
                });
    }

    /** "none", or a VMess word some panels put there, asks for no encryption of VLESS. */
    @Test
    void vlessWithoutEncryptionStillParses() {
        for (String value : new String[] {"none", "auto", "zero", ""}) {
            String link = "vless://" + UUID + "@vless.example:443?encryption=" + value
                    + "&security=tls&sni=vless.example#E";
            assertThat(parser.parse(link).getProtocol()).as(value).isEqualTo(Protocol.VLESS);
        }
    }

    /**
     * TCP disguised as HTTP (headerType=http) was read as plain TCP, which a
     * server expecting the disguise does not answer.
     */
    @Test
    void tcpWithAnHttpHeaderIsRefused() {
        List<String> links = List.of(
                "vless://" + UUID + "@vless.example:80?type=tcp&headerType=http"
                        + "&host=cdn.example&path=%2F#V",
                "trojan://password@trojan.example:443?type=tcp&headerType=http"
                        + "&security=tls&sni=trojan.example#T",
                vmess("{\"v\":\"2\",\"add\":\"vmess.example\",\"port\":80,\"id\":\"" + UUID
                        + "\",\"net\":\"tcp\",\"type\":\"http\",\"host\":\"cdn.example\","
                        + "\"path\":\"/\"}"),
                "ss://" + userInfo() + "@ss.example:8388?type=tcp&headerType=http#S");
        for (String link : links) {
            assertThatThrownBy(() -> parser.parse(link)).as(link)
                    .isInstanceOfSatisfying(ShareLinkParser.UnsupportedFeatureException.class,
                            e -> assertThat(e.feature()).isEqualTo("TCP with an HTTP header"));
        }
    }

    @Test
    void tcpWithoutAHeaderStillParses() {
        assertThat(parser.parse("vless://" + UUID + "@vless.example:443?type=tcp"
                + "&headerType=none&security=tls&sni=vless.example#N")
                .getTransport().getType()).isEqualTo(TransportType.TCP);
        assertThat(parser.parse(vmess("{\"v\":\"2\",\"add\":\"vmess.example\",\"port\":443,"
                + "\"id\":\"" + UUID + "\",\"net\":\"tcp\",\"type\":\"none\"}"))
                .getTransport().getType()).isEqualTo(TransportType.TCP);
    }

    /**
     * 3x-ui hands out Shadowsocks over WebSocket, gRPC or TLS. sing-box runs
     * Shadowsocks over TCP only, and the link used to be read as plain
     * Shadowsocks, which such a server does not answer.
     */
    @Test
    void shadowsocksOverATransportOrTlsIsRefused() {
        Map<String, String> links = new LinkedHashMap<>();
        links.put("ss://" + userInfo() + "@ss.example:443?type=ws&path=%2Fss"
                + "&host=cdn.example&security=tls&sni=cdn.example#W", "Shadowsocks over ws");
        links.put("ss://" + userInfo() + "@ss.example:443?type=grpc&serviceName=svc#G",
                "Shadowsocks over grpc");
        links.put("ss://" + userInfo() + "@ss.example:443?type=tcp&security=tls"
                + "&sni=ss.example#T", "Shadowsocks over TLS");
        links.forEach((link, feature) -> assertThatThrownBy(() -> parser.parse(link)).as(link)
                .isInstanceOfSatisfying(ShareLinkParser.UnsupportedFeatureException.class,
                        e -> assertThat(e.feature()).isEqualTo(feature)));
    }

    /** What panels write for plain Shadowsocks, with or without a plugin. */
    @Test
    void shadowsocksOverTcpStillParses() {
        List<String> links = List.of(
                "ss://" + userInfo() + "@ss.example:8388?type=tcp#P",
                "ss://" + userInfo() + "@ss.example:8388?type=tcp&headerType=none"
                        + "&security=none#P",
                "ss://" + userInfo() + "@ss.example:8388/?plugin=obfs-local%3Bobfs%3Dhttp"
                        + "%3Bobfs-host%3Dcdn.example#P",
                "ss://" + userInfo() + "@ss.example:8388#P");
        for (String link : links) {
            assertThat(parser.parse(link).getProtocol()).as(link).isEqualTo(Protocol.SHADOWSOCKS);
        }
    }

    /** The import report shows the reason; the feature stays English, for the log. */
    @Test
    void theReasonIsWordedInTheLanguageOfTheUi() {
        java.util.Locale before = com.vlessclient.app.I18n.getLocale();
        try {
            com.vlessclient.app.I18n.setLocale(java.util.Locale.of("ru"));
            List<String> links = List.of(
                    "vless://" + UUID + "@vless.example:443?encryption=mlkem768x25519plus.native"
                            + ".0rtt.KEY&security=tls#E",
                    "vless://" + UUID + "@vless.example:80?type=tcp&headerType=http#V",
                    "ss://" + userInfo() + "@ss.example:443?type=ws&security=tls#W");
            for (String link : links) {
                assertThatThrownBy(() -> parser.parse(link)).as(link)
                        .isInstanceOfSatisfying(ShareLinkParser.UnsupportedFeatureException.class,
                                e -> {
                                    assertThat(e.getMessage()).containsPattern("\\p{IsCyrillic}");
                                    assertThat(e.feature())
                                            .doesNotContainPattern("\\p{IsCyrillic}");
                                });
            }
        } finally {
            com.vlessclient.app.I18n.setLocale(before);
        }
    }

    private static String userInfo() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString("aes-256-gcm:password".getBytes(StandardCharsets.UTF_8));
    }

    private static String vmess(String json) {
        return "vmess://" + Base64.getEncoder()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
