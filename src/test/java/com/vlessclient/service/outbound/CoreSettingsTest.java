package com.vlessclient.service.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.testing.TestServers;
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

    /**
     * A credential the keychain did not return on load is kept as its sealed
     * tag, so the entry recovers once the keychain does. Handed to the core,
     * the tag failed the connect in the core's own words, which said nothing
     * about the keychain.
     */
    @Test
    void aCredentialTheKeychainDidNotReturnIsRefusedForWhatItIs() {
        String sealed = com.vlessclient.platform.SecretSealer.SEAL_PREFIX + "keychain:v1";
        assertThat(CoreSettings.refusal(reality(server -> server.setUuid(sealed))))
                .hasValueSatisfying(refusal -> assertThat(refusal.reason())
                        .isEqualTo(com.vlessclient.app.I18n.get(
                                "refusal.credential.unreadable")));
        assertThat(CoreSettings.refusal(reality(server -> server.setFlow(sealed))))
                .as("Hysteria2 keeps its obfuscation password in flow")
                .isPresent();
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
                    shadowsocks("2022-blake3-aes-128-gcm", KEY_32_BYTES),
                    quic(Protocol.VLESS, false),
                    withPlugin("kcptun", "mode=fast"),
                    withPlugin("obfs-local", "obfs=xyz"),
                    withPlugin("v2ray-plugin", "mode=quic"));
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

    /**
     * "create client transport: quic: TLS required", from the core's check
     * for VLESS, VMess and Trojan alike. The link parser took such a server,
     * and every connect that included it failed.
     */
    @Test
    void quicWithoutTlsIsRefused() {
        for (Protocol protocol : List.of(Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN)) {
            assertThat(CoreSettings.refusal(quic(protocol, false))).as(protocol.name())
                    .hasValueSatisfying(refusal -> assertThat(refusal.feature())
                            .isEqualTo("QUIC without TLS"));
            assertThat(CoreSettings.refusal(quic(protocol, true))).as(protocol + " with TLS")
                    .isEmpty();
        }
    }

    /**
     * "plugin not found: kcptun". The core has obfs-local and v2ray-plugin,
     * and simple-obfs is obfs-local under its old name.
     */
    @Test
    void aPluginTheCoreDoesNotHaveIsRefused() {
        assertThat(CoreSettings.refusal(withPlugin("kcptun", "mode=fast")))
                .hasValueSatisfying(refusal -> assertThat(refusal.feature())
                        .isEqualTo("plugin kcptun"));
        for (String plugin : new String[] {"obfs-local", "simple-obfs", "v2ray-plugin"}) {
            assertThat(CoreSettings.refusal(withPlugin(plugin, null))).as(plugin).isEmpty();
        }
    }

    /**
     * The modes the core runs, as its check answered: obfs-local's http and
     * tls ("unknown obfs mode xyz" otherwise), v2ray-plugin's websocket and
     * quic, spelled exactly so ("v2ray-plugin: unknown mode: ws"), and quic
     * only with tls ("TLS required").
     */
    @Test
    void aPluginModeTheCoreDoesNotRunIsRefused() {
        java.util.Map<ServerConfig, String> refused = new java.util.LinkedHashMap<>();
        refused.put(withPlugin("obfs-local", "obfs=xyz;obfs-host=cdn.example"),
                "obfs-local mode xyz");
        refused.put(withPlugin("v2ray-plugin", "mode=ws;host=cdn.example"),
                "v2ray-plugin mode ws");
        refused.put(withPlugin("v2ray-plugin", "mode=grpc;tls;host=cdn.example"),
                "v2ray-plugin mode grpc");
        refused.put(withPlugin("v2ray-plugin", "mode=quic;host=cdn.example"),
                "v2ray-plugin QUIC without TLS");
        refused.forEach((server, feature) -> assertThat(CoreSettings.refusal(server))
                .as(server.getPluginOpts())
                .hasValueSatisfying(refusal -> assertThat(refusal.feature()).isEqualTo(feature)));

        List<ServerConfig> accepted = List.of(
                withPlugin("obfs-local", "obfs=http;obfs-host=cdn.example"),
                withPlugin("simple-obfs", "obfs=tls;obfs-host=cdn.example"),
                withPlugin("v2ray-plugin", "mode=websocket;tls;host=cdn.example;path=/ws"),
                withPlugin("v2ray-plugin", "mode=quic;tls;host=cdn.example"),
                withPlugin("v2ray-plugin", "tls;host=cdn.example"));
        for (ServerConfig server : accepted) {
            assertThat(CoreSettings.refusal(server)).as(server.getPluginOpts()).isEmpty();
        }
    }

    static ServerConfig reality(Consumer<ServerConfig> change) {
        ServerConfig server = TestServers.server()
                .id("reality")
                .name("reality")
                .protocol(Protocol.VLESS)
                .address("203.0.113.1")
                .port(443)
                .uuid("b1c2d3e4-f5a6-7890-abcd-ef1234567890")
                .flow("xtls-rprx-vision")
                .reality("www.microsoft.com", REALITY_KEY, "0123abcd")
                .fingerprint("chrome")
                .build();
        change.accept(server);
        return server;
    }

    static ServerConfig shadowsocks(String method, String password) {
        return TestServers.server()
                .id("ss-" + method)
                .name("ss-" + method)
                .protocol(Protocol.SHADOWSOCKS)
                .address("203.0.113.3")
                .port(8388)
                .uuid(password)
                .encryption(method)
                .build();
    }

    static ServerConfig quic(Protocol protocol, boolean tls) {
        TestServers.Builder server = TestServers.server()
                .id("quic-" + protocol)
                .name("quic-" + protocol)
                .protocol(protocol)
                .address("203.0.113.4")
                .port(443)
                .uuid("b1c2d3e4-f5a6-7890-abcd-ef1234567890")
                .transport(com.vlessclient.model.TransportType.QUIC, null, null);
        return tls ? server.tls("quic.example").build() : server.build();
    }

    static ServerConfig withPlugin(String plugin, String options) {
        ServerConfig server = shadowsocks("aes-256-gcm", "password");
        server.setPlugin(plugin);
        server.setPluginOpts(options);
        return server;
    }

    /**
     * The core was asked for {@code auto} whatever cipher a VMess server had,
     * a link's {@code scy} or the form's choice.
     */
    @Test
    void aVmessServersOwnCipherIsAskedFor() {
        assertThat(CoreSettings.vmessSecurity(vmess("aes-128-gcm", false)))
                .isEqualTo("aes-128-gcm");
        assertThat(CoreSettings.vmessSecurity(vmess(" CHACHA20-POLY1305 ", false)))
                .isEqualTo("chacha20-poly1305");
        assertThat(CoreSettings.vmessSecurity(vmess("zero", true)))
                .as("no cipher of its own inside TLS")
                .isEqualTo("zero");
        assertThat(CoreSettings.vmessSecurity(vmess("none", true))).isEqualTo("none");
    }

    /**
     * Without TLS nothing else encrypts the payload, and servers made in the
     * form were stored with none, its default, while the core got auto.
     */
    @Test
    void aVmessCipherThatLeavesThePayloadClearIsAskedForOnlyInsideTls() {
        assertThat(CoreSettings.vmessSecurity(vmess("none", false))).isEqualTo("auto");
        assertThat(CoreSettings.vmessSecurity(vmess("zero", false))).isEqualTo("auto");
    }

    @Test
    void aVmessCipherTheCoreDoesNotKnowIsAskedForAsAuto() {
        assertThat(CoreSettings.vmessSecurity(vmess("aes-256-cfb", true))).isEqualTo("auto");
        assertThat(CoreSettings.vmessSecurity(vmess(null, true))).isEqualTo("auto");
    }

    private static ServerConfig vmess(String cipher, boolean tls) {
        ServerConfig server = TestServers.vless("Tokyo").protocol(Protocol.VMESS)
                .encryption(cipher).build();
        server.getTls().setEnabled(tls);
        return server;
    }
}
