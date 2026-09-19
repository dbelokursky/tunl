package com.vlessclient.service.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The settings sing-box refuses however they are spelled. Each rule was taken
 * from the pinned core's own answer; {@code SingBoxRealBinarySmokeTest} checks
 * the same servers against the core itself.
 */
class CoreSettingsTest {

    static final String REALITY_KEY = "Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw";
    static final String REALITY_KEY_STANDARD_ALPHABET =
            "WZaG00XCAiVCF2SP5fmSbKiuTbBB+lMDg/81rC8hR80=";
    static final String KEY_16_BYTES = "AAAAAAAAAAAAAAAAAAAAAA==";
    static final String KEY_32_BYTES = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    /** The same 32 bytes in both alphabets: '-' and '_' against '+' and '/'. */
    static final String URL_KEY_32_BYTES = "-__7__v_-__7__v_-__7__v_-__7__v_-__7__v_-_8";
    static final String STANDARD_KEY_32_BYTES = "+//7//v/+//7//v/+//7//v/+//7//v/+//7//v/+/8=";

    @Test
    void serversTheCoreAcceptsHaveNoRefusal() {
        assertThat(CoreSettings.refusal(reality(server -> { }))).isEmpty();
        assertThat(CoreSettings.refusal(reality(server -> {
            server.getTls().setFingerprint("Chrome");
            server.getTls().setRealityPublicKey(REALITY_KEY_STANDARD_ALPHABET);
            server.getTls().setRealityShortId("");
        }))).isEmpty();
        assertThat(CoreSettings.refusal(shadowsocks("chacha20-poly1305", "password"))).isEmpty();
        assertThat(CoreSettings.refusal(
                shadowsocks("2022-blake3-aes-256-gcm", KEY_32_BYTES + ":" + KEY_32_BYTES)))
                .isEmpty();
    }

    @Test
    void aPortOutsideTheRangeIsRefused() {
        assertThat(CoreSettings.refusal(reality(server -> server.setPort(70000))))
                .hasValueSatisfying(refusal -> assertThat(refusal.feature())
                        .isEqualTo("port 70000"));
        assertThat(CoreSettings.refusal(reality(server -> server.setPort(0)))).isPresent();
    }

    @Test
    void aVlessFlowOtherThanVisionIsRefused() {
        assertThat(CoreSettings.refusal(reality(server -> server.setFlow("xtls-rprx-vision-udp443"))))
                .hasValueSatisfying(refusal -> assertThat(refusal.feature())
                        .isEqualTo("flow xtls-rprx-vision-udp443"));
        assertThat(CoreSettings.refusal(reality(server -> server.setFlow("")))).isEmpty();
    }

    /** Hysteria2 keeps its obfuscation password in flow, and WireGuard its address. */
    @Test
    void aFlowFieldThatIsNotAVlessFlowIsNotReadAsOne() {
        ServerConfig hysteria2 = reality(server -> {
            server.setProtocol(Protocol.HYSTERIA2);
            server.getTls().setReality(false);
            server.setFlow("obfs-password");
        });
        assertThat(CoreSettings.refusal(hysteria2)).isEmpty();
    }

    @Test
    void anUnknownFingerprintIsRefused() {
        assertThat(CoreSettings.refusal(reality(server ->
                server.getTls().setFingerprint("randomizednoalpn"))))
                .hasValueSatisfying(refusal -> assertThat(refusal.feature())
                        .isEqualTo("uTLS fingerprint randomizednoalpn"));
    }

    @Test
    void aRealityKeyThatIsNotThirtyTwoBytesIsRefused() {
        assertThat(CoreSettings.refusal(reality(server ->
                server.getTls().setRealityPublicKey("pubkey123")))).isPresent();
        assertThat(CoreSettings.refusal(reality(server ->
                server.getTls().setRealityPublicKey(null)))).isPresent();
    }

    @Test
    void aRealityShortIdThatIsNotHexInPairsOfUpToSixteenDigitsIsRefused() {
        for (String shortId : new String[] {"abc", "xyz", "0123456789abcdef01"}) {
            assertThat(CoreSettings.refusal(reality(server ->
                    server.getTls().setRealityShortId(shortId))))
                    .as(shortId).isPresent();
        }
        for (String shortId : new String[] {"ab", "AB", "0123456789abcdef"}) {
            assertThat(CoreSettings.refusal(reality(server ->
                    server.getTls().setRealityShortId(shortId))))
                    .as(shortId).isEmpty();
        }
    }

    @Test
    void anUnknownCipherIsRefused() {
        assertThat(CoreSettings.refusal(shadowsocks("chacha20", "password")))
                .hasValueSatisfying(refusal -> assertThat(refusal.feature())
                        .isEqualTo("cipher chacha20"));
    }

    @Test
    void aShadowsocks2022KeyOfTheWrongLengthIsRefused() {
        assertThat(CoreSettings.refusal(
                shadowsocks("2022-blake3-aes-128-gcm", KEY_32_BYTES))).isPresent();
        assertThat(CoreSettings.refusal(
                shadowsocks("2022-blake3-aes-128-gcm", KEY_16_BYTES))).isEmpty();
        assertThat(CoreSettings.refusal(
                shadowsocks("2022-blake3-aes-256-gcm", KEY_32_BYTES + ":password")))
                .isPresent();
    }

    /**
     * The pinned core's check refused " 0123abcd" with "decode short_id:
     * encoding/hex: invalid byte: U+0020", while the refusal above, which
     * strips it, let the server in: stored, and never connecting.
     */
    @Test
    void aRealityShortIdIsWrittenWithoutTheSpacesAroundIt() {
        assertThat(CoreSettings.realityShortId(" 0123abcd ")).isEqualTo("0123abcd");
        assertThat(CoreSettings.realityShortId("0123abcd")).isEqualTo("0123abcd");
        assertThat(CoreSettings.realityShortId(null)).isEmpty();
    }

    /**
     * The core decodes a Shadowsocks 2022 key as standard base64 with padding
     * ("decode key: illegal base64 data" otherwise). Links also carry the same
     * bytes without the padding or in the URL alphabet, and the refusal above
     * accepts both, since the key is the right length either way.
     */
    @Test
    void aShadowsocks2022KeyIsWrittenInTheStandardAlphabetWithPadding() {
        assertThat(CoreSettings.shadowsocksPassword("2022-blake3-aes-128-gcm",
                "AAAAAAAAAAAAAAAAAAAAAA")).isEqualTo(KEY_16_BYTES);
        assertThat(CoreSettings.shadowsocksPassword("2022-blake3-aes-128-gcm",
                "-_-_-_-_-_-_-_-_-_-_-w")).isEqualTo("+/+/+/+/+/+/+/+/+/+/+w==");
        assertThat(CoreSettings.shadowsocksPassword("2022-blake3-aes-256-gcm",
                URL_KEY_32_BYTES + ":" + URL_KEY_32_BYTES))
                .isEqualTo(STANDARD_KEY_32_BYTES + ":" + STANDARD_KEY_32_BYTES);
        assertThat(CoreSettings.shadowsocksPassword("2022-blake3-aes-128-gcm", KEY_16_BYTES))
                .isEqualTo(KEY_16_BYTES);
    }

    /** Any string is a password for the older ciphers: nothing in it is spelling. */
    @Test
    void aPasswordForAnOlderCipherIsWrittenAsItIs() {
        assertThat(CoreSettings.shadowsocksPassword("aes-256-gcm", " pass-word_ "))
                .isEqualTo(" pass-word_ ");
        assertThat(CoreSettings.shadowsocksPassword(null, "password")).isEqualTo("password");
    }

    /** "plugin not found: simple-obfs": the core knows the same plugin as obfs-local. */
    @Test
    void simpleObfsIsWrittenUnderTheNameTheCoreKnows() {
        assertThat(CoreSettings.shadowsocksPlugin("simple-obfs")).isEqualTo("obfs-local");
        assertThat(CoreSettings.shadowsocksPlugin("obfs-local")).isEqualTo("obfs-local");
        assertThat(CoreSettings.shadowsocksPlugin("v2ray-plugin")).isEqualTo("v2ray-plugin");
    }

    /**
     * The reason is shown to the user: in the import report, in the server
     * form and on the Dashboard among the servers left out. It was an English
     * sentence in every language; the feature stays English, for the log.
     */
    @Test
    void theReasonIsWordedInTheLanguageOfTheUi() {
        Locale before = I18n.getLocale();
        try {
            I18n.setLocale(Locale.of("ru"));
            List<ServerConfig> refused = List.of(
                    reality(server -> server.setPort(70000)),
                    reality(server -> server.setFlow("xtls-rprx-vision-udp443")),
                    reality(server -> server.getTls().setFingerprint("randomizednoalpn")),
                    reality(server -> server.getTls().setRealityPublicKey("pubkey123")),
                    reality(server -> server.getTls().setRealityShortId("0123456789abcdef01")),
                    shadowsocks("chacha20", "password"),
                    shadowsocks("2022-blake3-aes-128-gcm", KEY_32_BYTES));
            for (ServerConfig server : refused) {
                assertThat(CoreSettings.refusal(server)).hasValueSatisfying(refusal -> {
                    assertThat(refusal.reason()).as(refusal.feature())
                            .containsPattern("\\p{IsCyrillic}");
                    assertThat(refusal.feature()).as(refusal.feature())
                            .doesNotContainPattern("\\p{IsCyrillic}");
                });
            }
        } finally {
            I18n.setLocale(before);
        }
    }

    static ServerConfig reality(Consumer<ServerConfig> change) {
        ServerConfig server = new ServerConfig();
        server.setId("reality");
        server.setName("reality");
        server.setProtocol(Protocol.VLESS);
        server.setAddress("203.0.113.1");
        server.setPort(443);
        server.setUuid("b1c2d3e4-f5a6-7890-abcd-ef1234567890");
        server.setFlow("xtls-rprx-vision");
        server.getTls().setEnabled(true);
        server.getTls().setReality(true);
        server.getTls().setServerName("www.microsoft.com");
        server.getTls().setFingerprint("chrome");
        server.getTls().setRealityPublicKey(REALITY_KEY);
        server.getTls().setRealityShortId("0123abcd");
        change.accept(server);
        return server;
    }

    static ServerConfig shadowsocks(String method, String password) {
        ServerConfig server = new ServerConfig();
        server.setId("ss-" + method);
        server.setName("ss-" + method);
        server.setProtocol(Protocol.SHADOWSOCKS);
        server.setAddress("203.0.113.3");
        server.setPort(8388);
        server.setUuid(password);
        server.setEncryption(method);
        return server;
    }
}
