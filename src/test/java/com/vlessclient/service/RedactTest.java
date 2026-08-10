package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

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
    }
}
