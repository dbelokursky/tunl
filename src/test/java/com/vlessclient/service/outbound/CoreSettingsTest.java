package com.vlessclient.service.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.testing.TestServers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /**
     * A REALITY server is sent a fingerprint whose hello newer REALITY servers
     * accept in place of {@code random}, and of a browser the core has only an
     * old hello for, whatever the link's spelling; only with REALITY and TLS
     * on. The server form asks with either unticked, and the connect log asks
     * for every member, WireGuard's and Shadowsocks' included.
     */
    @Test
    void aRealityServerIsSentAHelloNewerServersAcceptInPlaceOfOneTheyRefuse() {
        Map<String, String> replacements = Map.of(
                "random", "randomized",
                "edge", "randomized",
                "qq", "randomized",
                "360", "randomized",
                "android", "randomized",
                "ios", "safari");
        replacements.forEach((named, sent) -> assertThat(CoreSettings.realityReplacement(
                reality(server -> server.getTls().setFingerprint(named)).getTls()))
                .as(named).contains(sent));
        assertThat(CoreSettings.realityReplacement(reality(server ->
                server.getTls().setFingerprint(" RANDOM ")).getTls())).contains("randomized");
        assertThat(CoreSettings.realityReplacement(reality(server ->
                server.getTls().setFingerprint("iOS")).getTls())).contains("safari");
        for (String asItIs : List.of("chrome", "firefox", "safari", "randomized", "chrome_pq")) {
            assertThat(CoreSettings.realityReplacement(reality(server ->
                    server.getTls().setFingerprint(asItIs)).getTls()))
                    .as("%s, sent as it is", asItIs).isEmpty();
        }
        assertThat(CoreSettings.realityReplacement(reality(server ->
                server.getTls().setFingerprint(null)).getTls())).as("none named: chrome").isEmpty();
        for (String named : replacements.keySet()) {
            assertThat(CoreSettings.realityReplacement(reality(server -> {
                server.getTls().setFingerprint(named);
                server.getTls().setReality(false);
            }).getTls())).as("plain TLS, %s", named).isEmpty();
        }
        assertThat(CoreSettings.realityReplacement(reality(server -> {
            server.getTls().setFingerprint("edge");
            server.getTls().setEnabled(false);
        }).getTls())).as("TLS off").isEmpty();
        assertThat(CoreSettings.realityReplacement(null)).as("no TLS settings").isEmpty();
    }

    /**
     * {@code packaging/sing-box/verify.sh} checks the hello of every
     * fingerprint a REALITY server can be sent, which is every one the core
     * knows as {@link CoreSettings#fingerprintToSend} hands it over. A
     * fingerprint added there, or a replacement changed, without the check
     * following would reach a REALITY server unchecked.
     */
    @Test
    void theCoreCheckCoversEveryFingerprintARealityServerIsSent() throws IOException {
        Set<String> sent = new TreeSet<>();
        for (String fingerprint : CoreSettings.fingerprints()) {
            sent.add(CoreSettings.fingerprintToSend(reality(server ->
                    server.getTls().setFingerprint(fingerprint))));
        }

        assertThat(verifiedFingerprints()).containsExactlyInAnyOrderElementsOf(sent);
    }

    /** The fingerprints {@code verify.sh} starts the core with, its {@code fingerprints=(…)}. */
    private static List<String> verifiedFingerprints() throws IOException {
        String script = Files.readString(Path.of("packaging", "sing-box", "verify.sh"));
        Matcher list = Pattern.compile("(?m)^fingerprints=\\(([^)]*)\\)").matcher(script);
        assertThat(list.find()).as("verify.sh names its fingerprints in fingerprints=(...)")
                .isTrue();
        return List.of(list.group(1).strip().split("\\s+"));
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
     * A WireGuard server with no peer key, or a key the core cannot decode,
     * was let through: the check passes it, and the core then stops at start
     * printing the interface's settings, its private key among them, which
     * went into the app log. The keys are read as the core reads them, 32
     * bytes in padded standard base64; the pinned core refused the URL-safe
     * alphabet and a key without its padding with "illegal base64 data".
     */
    @Test
    void aWireGuardServerTheCoreCannotStartIsRefused() {
        assertThat(CoreSettings.refusal(wireguard(server -> { }))).isEmpty();
        assertThat(CoreSettings.refusal(wireguard(server -> server.setFlow("10.0.0.2"))))
                .as("an address without its prefix, which the builder adds").isEmpty();

        assertThat(CoreSettings.refusal(wireguard(server -> server.setEncryption(null))))
                .hasValueSatisfying(refusal -> assertThat(refusal.reason())
                        .isEqualTo(I18n.get("refusal.wireguard.peer.key")));
        assertThat(CoreSettings.refusal(wireguard(server -> server.setEncryption("none"))))
                .as("what the form left in the field").isPresent();
        assertThat(CoreSettings.refusal(wireguard(server -> server.setUuid("not-a-key"))))
                .hasValueSatisfying(refusal -> assertThat(refusal.reason())
                        .isEqualTo(I18n.get("refusal.wireguard.private.key")));
        assertThat(CoreSettings.refusal(wireguard(server -> server.setUuid(KEY_16_BYTES))))
                .as("a key of 16 bytes").isPresent();
        assertThat(CoreSettings.refusal(wireguard(server -> server.setUuid(URL_KEY_32_BYTES))))
                .as("32 bytes in the URL-safe alphabet").isPresent();
        assertThat(CoreSettings.refusal(wireguard(server -> server.setEncryption(
                STANDARD_KEY_32_BYTES.substring(0, 43)))))
                .as("32 bytes without the padding").isPresent();
        assertThat(CoreSettings.refusal(wireguard(server -> server.setFlow(" "))))
                .hasValueSatisfying(refusal -> assertThat(refusal.reason())
                        .isEqualTo(I18n.get("refusal.wireguard.address")));
    }

    static ServerConfig wireguard(Consumer<ServerConfig> change) {
        ServerConfig server = TestServers.server()
                .id("wireguard")
                .name("wireguard")
                .protocol(Protocol.WIREGUARD)
                .address("203.0.113.5")
                .port(51820)
                .uuid(KEY_32_BYTES)
                .encryption(STANDARD_KEY_32_BYTES)
                .flow("10.0.0.2/32")
                .build();
        change.accept(server);
        return server;
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
