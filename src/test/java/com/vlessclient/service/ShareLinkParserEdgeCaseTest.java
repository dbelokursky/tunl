package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TransportType;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Edge-case and malformed-input coverage for {@link ShareLinkParser}.
 *
 * <p>These tests pin the parser's contract: malformed share links surface as
 * {@link IllegalArgumentException} (or a subclass such as {@link NumberFormatException}).
 */
class ShareLinkParserEdgeCaseTest {

    private ShareLinkParser parser;
    private ShareLinkExporter exporter;

    @BeforeEach
    void setUp() {
        parser = new ShareLinkParser();
        exporter = new ShareLinkExporter();
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Nested
    class MalformedUriTests {

        @Test
        void emptyStringThrows() {
            assertThatThrownBy(() -> parser.parse(""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null or blank");
        }

        @Test
        void whitespaceOnlyThrows() {
            assertThatThrownBy(() -> parser.parse(" \t\r\n "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null or blank");
        }

        @Test
        void inputWithoutSchemeSeparatorThrowsClearValidationError() {
            assertThatThrownBy(() -> parser.parse("not a share link"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must contain ://");
            assertThatThrownBy(() -> parser.parse("example.com:443"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must contain ://");
        }

        @Test
        void emptySchemeThrows() {
            assertThatThrownBy(() -> parser.parse("://host.example:443"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported protocol scheme");
        }

        @Test
        void unsupportedSchemeThrows() {
            assertThatThrownBy(() -> parser.parse("ftp://files.example.com:21"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported protocol scheme: ftp");
        }

        @Test
        void leadingWhitespaceIsNotTrimmed() {
            assertThatThrownBy(() -> parser.parse(" vless://uuid@host.example:443"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported protocol scheme");
        }

        @Test
        void schemeOnlyVlessThrows() {
            assertThatThrownBy(() -> parser.parse("vless://"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid VLESS URI format");
        }

        @Test
        void schemeOnlyHy2Throws() {
            assertThatThrownBy(() -> parser.parse("hy2://"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid Hysteria2 URI format");
        }

        @Test
        void emptyUserInfoBeforeAtThrows() {
            assertThatThrownBy(() -> parser.parse("vless://@host.example:443"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Missing UUID");
        }

        @Test
        void emptyHostThrows() {
            // java.net.URI falls back to a registry-based authority, so host AND userinfo
            // come back null; the parser reports the userinfo check first.
            assertThatThrownBy(() -> parser.parse("vless://uuid@:443"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Missing UUID");
        }

        @Test
        void spaceInHostThrows() {
            assertThatThrownBy(() -> parser.parse("vless://uuid@ho st.example:443"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid VLESS URI format");
        }

        @Test
        void controlCharactersInUriThrow() {
            assertThatThrownBy(() -> parser.parse("vless://uuid@host.example:443\r\n"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid VLESS URI format");
        }

        @Test
        void nonNumericPortRejected() {
            // The invalid port makes java.net.URI treat the authority as registry-based,
            // so the failure surfaces as a missing-userinfo IllegalArgumentException.
            assertThatThrownBy(() -> parser.parse("vless://uuid@host.example:notaport"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void negativePortRejected() {
            assertThatThrownBy(() -> parser.parse("vless://uuid@host.example:-1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void portAboveValidRangeIsAcceptedAsIs() {
            // Pins current leniency: the parser performs no 1..65535 range validation.
            ServerConfig config = parser.parse("vless://uuid@host.example:70000#big");

            assertThat(config.getPort()).isEqualTo(70000);
        }

        @Test
        void portZeroIsAcceptedAsIs() {
            ServerConfig config = parser.parse("vless://uuid@host.example:0#zero");

            assertThat(config.getPort()).isZero();
        }

        @Test
        void missingPortDefaultsTo443() {
            ServerConfig config = parser.parse("vless://uuid@host.example#nop");

            assertThat(config.getPort()).isEqualTo(443);
        }

        @Test
        void invalidPercentEncodingInFragmentThrows() {
            assertThatThrownBy(() -> parser.parse("vless://uuid@host.example:443#%zz"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("URLDecoder");
        }

        @Test
        void secondHashInFragmentKeptVerbatim() {
            ServerConfig config = parser.parse("vless://uuid@host.example:443#alpha#beta");

            assertThat(config.getName()).isEqualTo("alpha#beta");
        }

        @Test
        void emptyFragmentYieldsEmptyName() {
            // With a trailing '#' the name is the decoded empty fragment,
            // not the host:port fallback.
            ServerConfig config = parser.parse("vless://uuid@host.example:443#");

            assertThat(config.getName()).isEmpty();
        }

        @Test
        void plusInFragmentDecodedAsSpace() {
            // Fragments run through URLDecoder (form decoding), so '+' means space.
            ServerConfig config = parser.parse("vless://uuid@host.example:443#My+Server");

            assertThat(config.getName()).isEqualTo("My Server");
        }
    }

    @Nested
    class VmessTests {

        @Test
        void invalidBase64PayloadThrows() {
            assertThatThrownBy(() -> parser.parse("vmess://!!!not-base64!!!"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Illegal base64 character");
        }

        @Test
        void base64ButNotJsonThrows() {
            assertThatThrownBy(() -> parser.parse("vmess://" + b64("this is not json")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid vmess JSON");
        }

        @Test
        void emptyPayloadThrows() {
            assertThatThrownBy(() -> parser.parse("vmess://"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Missing address");
        }

        @Test
        void jsonArrayPayloadThrows() {
            assertThatThrownBy(() -> parser.parse("vmess://" + b64("[1,2,3]")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Missing address");
        }

        @Test
        void jsonNullPayloadThrows() {
            assertThatThrownBy(() -> parser.parse("vmess://" + b64("null")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Missing address");
        }

        @Test
        void emptyJsonObjectThrows() {
            assertThatThrownBy(() -> parser.parse("vmess://" + b64("{}")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Missing address");
        }

        @Test
        void missingAddressThrows() {
            assertThatThrownBy(() -> parser.parse("vmess://" + b64("{\"id\":\"u\",\"port\":443}")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Missing address");
        }

        @Test
        void missingUuidThrows() {
            String json = "{\"add\":\"h.example\",\"port\":443}";

            assertThatThrownBy(() -> parser.parse("vmess://" + b64(json)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Missing UUID");
        }

        @Test
        void nonNumericPortStringFallsBackToDefault() {
            String json = "{\"add\":\"h.example\",\"id\":\"u\",\"port\":\"not-a-number\"}";

            ServerConfig config = parser.parse("vmess://" + b64(json));

            assertThat(config.getPort()).isEqualTo(443);
        }

        @Test
        void missingPortDefaultsTo443() {
            String json = "{\"add\":\"h.example\",\"id\":\"u\"}";

            ServerConfig config = parser.parse("vmess://" + b64(json));

            assertThat(config.getPort()).isEqualTo(443);
        }

        @Test
        void booleanPortFallsBackToDefault() {
            String json = "{\"add\":\"h.example\",\"id\":\"u\",\"port\":true}";

            ServerConfig config = parser.parse("vmess://" + b64(json));

            assertThat(config.getPort()).isEqualTo(443);
        }

        @Test
        void numericNameCoercedToText() {
            String json = "{\"add\":\"h.example\",\"id\":\"u\",\"port\":443,\"ps\":12345}";

            ServerConfig config = parser.parse("vmess://" + b64(json));

            assertThat(config.getName()).isEqualTo("12345");
        }

        @Test
        void unknownNetworkTypeThrows() {
            String json = "{\"add\":\"h.example\",\"id\":\"u\",\"port\":443,\"net\":\"xhttp\"}";

            assertThatThrownBy(() -> parser.parse("vmess://" + b64(json)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown transport type");
        }

        @Test
        void uppercaseTlsValueEnablesTls() {
            String json = "{\"add\":\"h.example\",\"id\":\"u\",\"port\":443,\"tls\":\"TLS\"}";

            ServerConfig config = parser.parse("vmess://" + b64(json));

            assertThat(config.getTls().isEnabled()).isTrue();
        }

        @Test
        void missingScyDefaultsToAuto() {
            String json = "{\"add\":\"h.example\",\"id\":\"u\",\"port\":443}";

            ServerConfig config = parser.parse("vmess://" + b64(json));

            assertThat(config.getEncryption()).isEqualTo("auto");
        }

        @Test
        void uppercaseSchemeAccepted() {
            String json = "{\"add\":\"h.example\",\"id\":\"u\",\"port\":443}";

            ServerConfig config = parser.parse("VMESS://" + b64(json));

            assertThat(config.getProtocol()).isEqualTo(Protocol.VMESS);
            assertThat(config.getAddress()).isEqualTo("h.example");
        }
    }

    @Nested
    class ShadowsocksTests {

        @Test
        void plainUserinfoIsNotSupported() {
            // SIP002 also allows plain "method:password" userinfo (used by AEAD-2022
            // ciphers), but this parser only accepts Base64 userinfo and rejects the
            // plain form because ':' is not a valid Base64 character.
            assertThatThrownBy(() -> parser.parse("ss://aes-256-gcm:pass@host.example:8388#n"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Illegal base64 character");
        }

        @Test
        void brokenBase64UserinfoThrows() {
            assertThatThrownBy(() -> parser.parse("ss://!!!@host.example:8388"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Illegal base64 character");
        }

        @Test
        void userinfoWithoutColonThrows() {
            // Base64 decodes fine but has no method:password separator.
            String uri = "ss://" + b64("justpassword") + "@h.example:8388";

            assertThatThrownBy(() -> parser.parse(uri))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid Shadowsocks userinfo format");
        }

        @Test
        void legacyPayloadWithoutAtThrows() {
            assertThatThrownBy(() -> parser.parse("ss://" + b64("aes-256-gcm:pw")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid Shadowsocks legacy format");
        }

        @Test
        void missingPortThrows() {
            assertThatThrownBy(() -> parser.parse("ss://" + b64Url("aes-256-gcm:pw") + "@hostonly"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Missing port");
        }

        @Test
        void nonNumericPortThrowsNumberFormatException() {
            // Integer.parseInt propagates raw; NumberFormatException still satisfies the
            // broader IllegalArgumentException contract.
            String uri = "ss://" + b64Url("aes-256-gcm:pw") + "@host.example:abc";

            assertThatThrownBy(() -> parser.parse(uri))
                    .isInstanceOf(NumberFormatException.class);
        }

        @Test
        void legacyFormatWithSpecialCharsInPassword() {
            // Legacy password may contain '@' and ':' - the parser must split on the
            // LAST '@' and the FIRST ':'.
            String legacy = b64("bf-cfb:test/!@#:pass@192.168.100.1:8888");

            ServerConfig config = parser.parse("ss://" + legacy + "#legacy");

            assertThat(config.getEncryption()).isEqualTo("bf-cfb");
            assertThat(config.getUuid()).isEqualTo("test/!@#:pass");
            assertThat(config.getAddress()).isEqualTo("192.168.100.1");
            assertThat(config.getPort()).isEqualTo(8888);
        }

        @Test
        void sip002PasswordKeepsEmbeddedColons() {
            String uri = "ss://" + b64Url("aes-256-gcm:pa:ss:wd") + "@host.example:8388";

            ServerConfig config = parser.parse(uri);

            assertThat(config.getEncryption()).isEqualTo("aes-256-gcm");
            assertThat(config.getUuid()).isEqualTo("pa:ss:wd");
        }

        @Test
        void ipv6HostPreservedWithBrackets() {
            String uri = "ss://" + b64Url("aes-256-gcm:pw") + "@[2001:db8::2]:8388#v6";

            ServerConfig config = parser.parse(uri);

            assertThat(config.getAddress()).isEqualTo("[2001:db8::2]");
            assertThat(config.getPort()).isEqualTo(8388);
        }

        @Test
        void uppercaseSchemeAccepted() {
            String uri = "SS://" + b64Url("aes-256-gcm:pw") + "@host.example:8388#Upper";

            ServerConfig config = parser.parse(uri);

            assertThat(config.getProtocol()).isEqualTo(Protocol.SHADOWSOCKS);
            assertThat(config.getEncryption()).isEqualTo("aes-256-gcm");
        }
    }

    @Nested
    class TrojanAndHysteria2Tests {

        @Test
        void trojanEmptyPasswordThrows() {
            assertThatThrownBy(() -> parser.parse("trojan://@host.example:443"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Missing password");
        }

        @Test
        void trojanNonNumericPortRejected() {
            assertThatThrownBy(() -> parser.parse("trojan://pw@host.example:notaport"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void trojanSecurityNoneDisablesTls() {
            ServerConfig config = parser.parse("trojan://pw@host.example:443?security=none#n");

            assertThat(config.getTls().isEnabled()).isFalse();
        }

        @Test
        void trojanPasswordWithLiteralPercentIsDecodedOnce() {
            ServerConfig config = parser.parse("trojan://100%25pass@host.example:443#n");

            assertThat(config.getUuid()).isEqualTo("100%pass");
        }

        @Test
        void trojanRawPlusInPasswordRemainsLiteral() {
            ServerConfig config = parser.parse("trojan://pass+word@host.example:443#n");

            assertThat(config.getUuid()).isEqualTo("pass+word");
        }

        @Test
        void hysteria2EmptyPasswordThrows() {
            assertThatThrownBy(() -> parser.parse("hysteria2://@host.example:443"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Missing password");
        }

        @Test
        void hysteria2NonNumericPortRejected() {
            assertThatThrownBy(() -> parser.parse("hysteria2://pw@host.example:notaport"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void hysteria2PasswordWithLiteralPercentIsDecodedOnce() {
            ServerConfig config = parser.parse("hysteria2://100%25pass@host.example:443#n");

            assertThat(config.getUuid()).isEqualTo("100%pass");
        }

        @Test
        void hysteria2RawPlusInPasswordRemainsLiteral() {
            ServerConfig config = parser.parse("hysteria2://pass+word@host.example:443#n");

            assertThat(config.getUuid()).isEqualTo("pass+word");
        }
    }

    @Nested
    class QueryParamTests {

        @Test
        void duplicateParamLastValueWins() {
            ServerConfig config = parser.parse(
                    "vless://uuid@host.example:443?type=ws&type=grpc#dup");

            assertThat(config.getTransport().getType()).isEqualTo(TransportType.GRPC);
        }

        @Test
        void duplicateSecurityParamLastValueWins() {
            ServerConfig config = parser.parse(
                    "vless://uuid@host.example:443?security=tls&security=none#dup");

            assertThat(config.getTls().isEnabled()).isFalse();
        }

        @Test
        void emptyAndMalformedPairsIgnored() {
            // "&&", a valueless key, an empty value and a keyless value must all be
            // skipped without throwing.
            ServerConfig config = parser.parse(
                    "vless://uuid@host.example:443?&&flow=&=value&lonelykey#n");

            assertThat(config.getFlow()).isNull();
            assertThat(config.getTransport().getType()).isEqualTo(TransportType.TCP);
        }

        @Test
        void unknownParamsIgnoredWithoutThrowing() {
            ServerConfig config = parser.parse(
                    "vless://uuid@host.example:443?foo=bar&x-obscure=1&headerType=none#n");

            assertThat(config.getProtocol()).isEqualTo(Protocol.VLESS);
            assertThat(config.getAddress()).isEqualTo("host.example");
        }

        @Test
        void percentEncodedKeysAndValuesDecoded() {
            // "pa%74h" decodes to "path"; the value decodes to "/api/v1".
            ServerConfig config = parser.parse(
                    "vless://uuid@host.example:443?type=ws&pa%74h=%2Fapi%2Fv1#n");

            assertThat(config.getTransport().getPath()).isEqualTo("/api/v1");
        }

        @Test
        void tenThousandCharacterParamValueParses() {
            String big = "x".repeat(10_000);

            ServerConfig config = parser.parse(
                    "vless://uuid@host.example:443?type=ws&path=%2F" + big + "#long");

            assertThat(config.getTransport().getPath()).hasSize(10_001);
            assertThat(config.getTransport().getPath()).startsWith("/x");
        }
    }

    @Nested
    class HostAndUnicodeTests {

        @Test
        void ipv6BracketHostPreserved() {
            ServerConfig config = parser.parse("vless://uuid@[2001:db8::1]:8443#v6");

            assertThat(config.getAddress()).isEqualTo("[2001:db8::1]");
            assertThat(config.getPort()).isEqualTo(8443);
        }

        @Test
        void ipv6HostWithoutPortDefaultsTo443() {
            ServerConfig config = parser.parse("vless://uuid@[::1]#v6");

            assertThat(config.getAddress()).isEqualTo("[::1]");
            assertThat(config.getPort()).isEqualTo(443);
        }

        @Test
        void rawUnicodeHostRejected() {
            // java.net.URI cannot form a server-based authority from a non-ASCII host,
            // so userinfo/host come back null and the parser rejects the link.
            assertThatThrownBy(() -> parser.parse("vless://uuid@日本.example:443#jp"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void punycodeHostAccepted() {
            ServerConfig config = parser.parse("vless://uuid@xn--wgbh1c.example:443#idn");

            assertThat(config.getAddress()).isEqualTo("xn--wgbh1c.example");
        }

        @Test
        void rawUnicodeFragmentPreserved() {
            ServerConfig config = parser.parse("vless://uuid@host.example:443#Сервер");

            assertThat(config.getName()).isEqualTo("Сервер");
        }

        @Test
        void percentEncodedUnicodeFragmentDecoded() {
            ServerConfig config = parser.parse(
                    "vless://uuid@host.example:443#%D0%A1%D0%B5%D1%80%D0%B2%D0%B5%D1%80");

            assertThat(config.getName()).isEqualTo("Сервер");
        }
    }

    @Nested
    class WireguardTests {

        // ShareLinkParser has no wireguard branch: every wireguard-shaped link is
        // rejected at the scheme dispatch, regardless of key or reserved-bytes shape.
        // Protocol.WIREGUARD configs come from other sources (e.g. subscriptions).

        @Test
        void wireguardSchemeRejected() {
            String uri = "wireguard://cHJpdmF0ZWtleQ@wg.example.com:51820"
                    + "?publickey=cHVibGlja2V5&reserved=1,2,3#WG";

            assertThatThrownBy(() -> parser.parse(uri))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported protocol scheme: wireguard");
        }

        @Test
        void wgShortSchemeRejected() {
            assertThatThrownBy(() -> parser.parse("wg://key@wg.example.com:51820"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported protocol scheme: wg");
        }

        @Test
        void malformedWireguardVariantsAllRejectedAtSchemeLevel() {
            // Missing keys and malformed reserved-bytes shapes never reach field-level
            // validation - the scheme dispatch rejects them all identically.
            String[] variants = {
                "wireguard://@wg.example.com:51820",
                "wireguard://key@wg.example.com:51820?reserved=1,2",
                "wireguard://key@wg.example.com:51820?reserved=a,b,c",
                "wireguard://key@wg.example.com:51820?reserved=1,2,3,4",
                "wireguard://wg.example.com:51820?publickey=",
            };

            for (String uri : variants) {
                assertThatThrownBy(() -> parser.parse(uri))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("Unsupported protocol scheme");
            }
        }

        @Test
        void exporterRejectsWireguardProtocol() {
            ServerConfig config = new ServerConfig();
            config.setProtocol(Protocol.WIREGUARD);
            config.setAddress("wg.example.com");
            config.setPort(51820);
            config.setUuid("private-key");

            assertThatThrownBy(() -> exporter.export(config))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Export not supported");
        }
    }

    @Nested
    class RoundTripTests {

        @Test
        void vlessUnicodeNameWithEmojiRoundTrips() {
            ServerConfig original = new ServerConfig();
            original.setProtocol(Protocol.VLESS);
            original.setUuid("550e8400-e29b-41d4-a716-446655440000");
            original.setAddress("host.example");
            original.setPort(443);
            original.setName("Мой сервер 🚀");

            ServerConfig parsed = parser.parse(exporter.export(original));

            assertThat(parsed.getName()).isEqualTo("Мой сервер 🚀");
            assertThat(parsed.getUuid()).isEqualTo(original.getUuid());
        }

        @Test
        void vlessNameWithPercentAmpersandAndHashRoundTrips() {
            ServerConfig original = new ServerConfig();
            original.setProtocol(Protocol.VLESS);
            original.setUuid("550e8400-e29b-41d4-a716-446655440000");
            original.setAddress("host.example");
            original.setPort(443);
            original.setName("100% legit & tricky?name=#yes");

            ServerConfig parsed = parser.parse(exporter.export(original));

            assertThat(parsed.getName()).isEqualTo("100% legit & tricky?name=#yes");
        }

        @Test
        void vmessUnicodeNameRoundTrips() {
            ServerConfig original = new ServerConfig();
            original.setProtocol(Protocol.VMESS);
            original.setUuid("uuid-1");
            original.setAddress("host.example");
            original.setPort(443);
            original.setName("東京 サーバー");

            ServerConfig parsed = parser.parse(exporter.export(original));

            assertThat(parsed.getName()).isEqualTo("東京 サーバー");
        }

        @Test
        void shadowsocksNastyPasswordAndUnicodeNameRoundTrip() {
            // Base64 userinfo shields arbitrary password characters end to end.
            ServerConfig original = new ServerConfig();
            original.setProtocol(Protocol.SHADOWSOCKS);
            original.setEncryption("chacha20-ietf-poly1305");
            original.setUuid("p@ss:w%rd+ /?#[]&=");
            original.setAddress("ss.example.com");
            original.setPort(8388);
            original.setName("Сервер 日本 #1");

            ServerConfig parsed = parser.parse(exporter.export(original));

            assertThat(parsed.getUuid()).isEqualTo("p@ss:w%rd+ /?#[]&=");
            assertThat(parsed.getEncryption()).isEqualTo("chacha20-ietf-poly1305");
            assertThat(parsed.getName()).isEqualTo("Сервер 日本 #1");
        }

        @Test
        void trojanPasswordWithSafeSpecialCharsRoundTrips() {
            ServerConfig original = new ServerConfig();
            original.setProtocol(Protocol.TROJAN);
            original.setUuid("p@ss:word-123");
            original.setAddress("host.example");
            original.setPort(8443);
            original.setName("RT Trojan");
            original.getTls().setEnabled(true);

            ServerConfig parsed = parser.parse(exporter.export(original));

            assertThat(parsed.getUuid()).isEqualTo("p@ss:word-123");
            assertThat(parsed.getPort()).isEqualTo(8443);
        }

        @Test
        void hysteria2ObfsPasswordWithSpecialCharsRoundTrips() {
            // Query params are encoded once and decoded once, so specials survive.
            ServerConfig original = new ServerConfig();
            original.setProtocol(Protocol.HYSTERIA2);
            original.setUuid("simple-pw");
            original.setAddress("hy2.example.com");
            original.setPort(443);
            original.setName("HY2 RT");
            original.setEncryption("salamander");
            original.setFlow("obfs+pw%1 &x=y");
            original.getTls().setEnabled(true);
            original.getTls().setServerName("sni.example.com");

            ServerConfig parsed = parser.parse(exporter.export(original));

            assertThat(parsed.getFlow()).isEqualTo("obfs+pw%1 &x=y");
            assertThat(parsed.getEncryption()).isEqualTo("salamander");
        }

        @Test
        void trojanPasswordWithLiteralPercentRoundTrips() {
            ServerConfig original = new ServerConfig();
            original.setProtocol(Protocol.TROJAN);
            original.setUuid("50%off");
            original.setAddress("host.example");
            original.setPort(443);
            original.setName("Percent");

            String exported = exporter.export(original);

            assertThat(exported).startsWith("trojan://50%25off@");
            assertThat(parser.parse(exported).getUuid()).isEqualTo(original.getUuid());
        }

        @Test
        void trojanPasswordWithSpaceAndPlusRoundTrips() {
            ServerConfig original = new ServerConfig();
            original.setProtocol(Protocol.TROJAN);
            original.setUuid("pass word+x");
            original.setAddress("host.example");
            original.setPort(443);
            original.setName("Plus");

            ServerConfig parsed = parser.parse(exporter.export(original));

            assertThat(parsed.getUuid()).isEqualTo(original.getUuid());
        }

        @Test
        void hysteria2PasswordWithPercentSpaceAndPlusRoundTrips() {
            ServerConfig original = new ServerConfig();
            original.setProtocol(Protocol.HYSTERIA2);
            original.setUuid("50% off+x");
            original.setAddress("host.example");
            original.setPort(443);
            original.setName("HY2 Password");

            String exported = exporter.export(original);
            ServerConfig parsed = parser.parse(exported);

            assertThat(exported).startsWith("hysteria2://50%25%20off%2Bx@");
            assertThat(parsed.getUuid()).isEqualTo(original.getUuid());
        }
    }
}
