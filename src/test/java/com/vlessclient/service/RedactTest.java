package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RedactTest {

    /** The two secret shapes this app actually stores. */
    private static final String SUBSCRIPTION_URL =
            "https://provider.example/api/v1/client/subscribe?token=9f3caa1b2c3d4e5f";
    private static final String SHARE_LINK =
            "vless://b1e4c0de-1234-4321-abcd-9f3caa1b2c3d@gateway.example:443"
                    + "?security=reality&pbk=SECRETPUBKEY#Amsterdam";

    @Nested
    @DisplayName("url()")
    class Url {

        @Test
        @DisplayName("keeps the host but drops the subscription token")
        void dropsSubscriptionToken() {
            String redacted = Redact.url(SUBSCRIPTION_URL);

            assertThat(redacted).isEqualTo("https://provider.example/…");
            assertThat(redacted).doesNotContain("9f3caa1b2c3d4e5f");
        }

        @Test
        @DisplayName("drops the uuid carried in a share link's userinfo")
        void dropsShareLinkUserinfo() {
            String redacted = Redact.url(SHARE_LINK);

            assertThat(redacted).isEqualTo("vless://gateway.example:443/…");
            assertThat(redacted)
                    .doesNotContain("b1e4c0de-1234-4321-abcd-9f3caa1b2c3d")
                    .doesNotContain("SECRETPUBKEY");
        }

        @Test
        @DisplayName("collapses a vmess base64 payload, which is not a parseable URI")
        void collapsesUnparseablePayload() {
            String vmess = "vmess://eyJ2IjoiMiIsInBzIjoibm9kZSIsImlkIjoic2VjcmV0In0=";

            assertThat(Redact.url(vmess)).isEqualTo("vmess://<redacted>");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "not a url at all",
            "servers.json",
            "1.2.3.4:443",
        })
        @DisplayName("collapses anything without a scheme rather than passing it through")
        void collapsesNonUrls(String value) {
            assertThat(Redact.url(value)).isEqualTo("<redacted>");
        }

        @Test
        @DisplayName("passes null and blank through untouched")
        void passesEmptyThrough() {
            assertThat(Redact.url(null)).isNull();
            assertThat(Redact.url("")).isEmpty();
            assertThat(Redact.url("   ")).isEqualTo("   ");
        }
    }

    @Nested
    @DisplayName("urlsIn()")
    class UrlsIn {

        @Test
        @DisplayName("redacts a URL quoted inside an exception message")
        void redactsInsideMessage() {
            String message = "Illegal character in query at index 42: " + SUBSCRIPTION_URL;

            String redacted = Redact.urlsIn(message);

            assertThat(redacted)
                    .startsWith("Illegal character in query at index 42: ")
                    .contains("https://provider.example/…")
                    .doesNotContain("9f3caa1b2c3d4e5f");
        }

        @Test
        @DisplayName("redacts every URL, not just the first")
        void redactsAllOccurrences() {
            String message = "redirect " + SUBSCRIPTION_URL + " -> " + SHARE_LINK + " failed";

            String redacted = Redact.urlsIn(message);

            assertThat(redacted).isEqualTo(
                    "redirect https://provider.example/… -> vless://gateway.example:443/… failed");
        }

        @Test
        @DisplayName("leaves text without URLs exactly as it was")
        void leavesPlainTextAlone() {
            String message = "Connection reset by peer";

            assertThat(Redact.urlsIn(message)).isSameAs(message);
        }

        @Test
        @DisplayName("handles null and empty")
        void handlesEmpty() {
            assertThat(Redact.urlsIn(null)).isNull();
            assertThat(Redact.urlsIn("")).isEmpty();
        }

        @Test
        @DisplayName("reads a long run of letters once, not once per letter")
        void readsALongRunOnce() {
            // A parser message quoting a link's field: every letter of the run
            // was tried as the start of a scheme, and each try read the rest
            // of the run, so 32 KB took seconds and this took hours.
            String message = "Malformed mport in Hysteria2 URI: " + "a".repeat(200_000);
            String alternating = "fp=" + "a1".repeat(100_000);

            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                assertThat(Redact.urlsIn(message)).isSameAs(message);
                assertThat(Redact.urlsIn(alternating)).isSameAs(alternating);
                assertThat(Redact.urlsIn(message + SHARE_LINK))
                        .doesNotContain("b1e4c0de-1234-4321-abcd-9f3caa1b2c3d");
            });
        }

        @Test
        @DisplayName("starts a URL at the first letter before its ://, as it always did")
        void startsAtTheFirstLetterOfTheScheme() {
            assertThat(Redact.urlsIn("error 1vless://b1e4c0de@gateway.example:443?x#y"))
                    .isEqualTo("error 1vless://gateway.example:443/…");
            assertThat(Redact.urlsIn("x".repeat(40) + SHARE_LINK))
                    .isEqualTo("x".repeat(40) + "vless://gateway.example:443/…");
            assertThat(Redact.urlsIn("see 123://secret and ://nothing"))
                    .isEqualTo("see 123://secret and ://nothing");
            assertThat(Redact.urlsIn("ends with vless://")).isEqualTo("ends with vless://");
            assertThat(Redact.urlsIn("'" + SHARE_LINK + "' then \"" + SUBSCRIPTION_URL + "\""))
                    .isEqualTo("'vless://gateway.example:443/…' then \"https://provider.example/…\"");
        }

        @Test
        @DisplayName("finds the URLs the regular expression it replaced found")
        void findsWhatTheRegularExpressionFound() {
            Pattern before = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.\\-]*://[^\\s\"'<>\\\\]+");
            String alphabet = "av1+.-:/:/ @#?=\"'<>\\\t\n　 x";
            Random random = new Random(20261017);
            for (int i = 0; i < 20_000; i++) {
                StringBuilder text = new StringBuilder();
                for (int length = random.nextInt(40); length > 0; length--) {
                    text.append(random.nextInt(4) == 0 ? "://"
                            : alphabet.charAt(random.nextInt(alphabet.length())));
                }
                String sample = text.toString();
                String expected = before.matcher(sample).replaceAll(
                        match -> Matcher.quoteReplacement(Redact.url(match.group())));

                assertThat(Redact.urlsIn(sample)).as("%s", sample).isEqualTo(expected);
            }
        }
    }

    @Nested
    @DisplayName("publicIpsIn()")
    class PublicIpsIn {

        @Test
        @DisplayName("redacts the address the core dialed, IPv4 or IPv6, and keeps the port")
        void redactsDialedAddresses() {
            assertThat(Redact.publicIpsIn("dial tcp 203.0.113.7:443: i/o timeout"))
                    .isEqualTo("dial tcp <redacted>:443: i/o timeout");
            assertThat(Redact.publicIpsIn("dial tcp [2001:db8::7]:443: connect: refused"))
                    .isEqualTo("dial tcp [<redacted>]:443: connect: refused");
            assertThat(Redact.publicIpsIn("dial udp 198.51.100.7:53, then 203.0.113.8:53."))
                    .isEqualTo("dial udp <redacted>:53, then <redacted>:53.");
            assertThat(Redact.publicIpsIn("from ::ffff:203.0.113.7 and 2001:db8:0:0:0:0:0:7"))
                    .isEqualTo("from ::ffff:<redacted> and <redacted>");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "listen tcp 127.0.0.1:1081: bind: address already in use",
            "inbound connection from [::1]:52345",
            "tun address 172.19.0.1/30, router 192.168.1.1, lan 10.0.0.7",
            "tun address fdfe:dcba:9876::1/126, link fe80::1%en0",
            "listen 0.0.0.0:1080 and [::]:1080, link-local 169.254.1.1",
            "mdns 224.0.0.251:5353",
        })
        @DisplayName("keeps the addresses that describe this machine and name no one")
        void keepsLocalAddresses(String line) {
            assertThat(Redact.publicIpsIn(line)).isEqualTo(line);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "+0300 2026-09-27 12:00:00 INFO [3402963502 0ms] router: updated",
            "2026-09-27 12:00:00.000 [main] INFO  c.v.service.SingBoxEngine - started",
            "sing-box 1.14.2, build 1.2.3.4.5, windows 10.0.19045.3803",
            "interface aa:bb:cc:dd:ee:ff, octet 999.1.1.1",
            "goroutine 4211 [running]: route.go:123 +0x2c",
        })
        @DisplayName("leaves times, versions and MAC addresses alone")
        void leavesLookalikesAlone(String line) {
            assertThat(Redact.publicIpsIn(line)).isEqualTo(line);
        }

        @Test
        @DisplayName("handles null and empty")
        void handlesEmpty() {
            assertThat(Redact.publicIpsIn(null)).isNull();
            assertThat(Redact.publicIpsIn("")).isEmpty();
        }
    }
}
