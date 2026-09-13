package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A share link that will not parse must not put its credential into the
 * exception that says so.
 *
 * <p>That exception's message is what every caller reports: the link dialog
 * logs it and shows it, and a file import folds the first skipped line's
 * reason into its own failure, which is logged too. VLESS, Trojan and
 * Hysteria2 links are handed to {@link java.net.URI}, whose complaint ends
 * with the whole input, here {@code http://<uuid-or-password>@host…}; vmess
 * passed on Jackson's, which quotes the token it could not read.</p>
 *
 * <p>The illegal character sits inside the credential on purpose. Scrubbing
 * URLs out of the message afterwards does not reach that case: a space or a
 * quote is exactly where {@link Redact#urlsIn} ends a URL, so the part of a
 * password after it survived the scrub.</p>
 */
class ShareLinkParserRedactionTest {

    private static final String UUID = "0b7e5f2a-4c1d-4e8f-9a3b-6d2c1e0f9a8b";

    private final ShareLinkParser parser = new ShareLinkParser();

    static Stream<Arguments> malformedLinks() {
        return Stream.of(
                Arguments.of("a raw space in a VLESS path",
                        "vless://" + UUID + "@gateway.example:443?type=ws&path=/a b#Broken",
                        List.of(UUID)),
                Arguments.of("a space inside a Trojan password",
                        "trojan://alpha7 omega9@gateway.example:443#Broken",
                        List.of("alpha7", "omega9")),
                Arguments.of("a quote inside a Hysteria2 password",
                        "hysteria2://alpha7\"omega9@gateway.example:443#Broken",
                        List.of("alpha7", "omega9")),
                Arguments.of("angle brackets inside an hy2 password",
                        "hy2://alpha7<omega9>@gateway.example:443",
                        List.of("alpha7", "omega9")),
                Arguments.of("a vmess payload whose id lost its quotes",
                        "vmess://" + base64("{\"v\":\"2\",\"ps\":\"Broken\","
                                + "\"add\":\"gateway.example\",\"port\":\"443\","
                                + "\"id\":deadbeef-1234-4abc-8def-0123456789ab}"),
                        List.of("deadbeef")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedLinks")
    void theFailureNeverQuotesTheCredential(String description, String link,
                                            List<String> secrets) {
        Throwable thrown = catchThrowable(() -> parser.parse(link));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        // The whole trace, causes included: a clean message over a cause that
        // still holds the link prints the link all the same.
        String logged = stackTrace(thrown);
        for (String secret : secrets) {
            assertThat(logged).doesNotContain(secret);
        }
    }

    @Test
    void theFailureStillSaysWhatIsWrongAndWhereInTheLink() {
        String vless = "vless://alpha7 omega9@gateway.example:443";
        String trojan = "trojan://alpha7 omega9@gateway.example:443";
        String hy2 = "hy2://alpha7 omega9@gateway.example:443";

        // Positions count from the start of the link as given, not from the
        // http:// form it is parsed as, whose prefix has another length.
        assertThat(vless.charAt(14)).isEqualTo(' ');
        assertThatThrownBy(() -> parser.parse(vless)).hasMessage(
                "Invalid VLESS URI format: Illegal character in authority at index 14");
        assertThat(trojan.charAt(15)).isEqualTo(' ');
        assertThatThrownBy(() -> parser.parse(trojan)).hasMessage(
                "Invalid Trojan URI format: Illegal character in authority at index 15");
        assertThat(hy2.charAt(12)).isEqualTo(' ');
        assertThatThrownBy(() -> parser.parse(hy2)).hasMessage(
                "Invalid Hysteria2 URI format: Illegal character in authority at index 12");
    }

    private static String base64(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** The exception as a log file shows it: the trace, with every message in the chain. */
    private static String stackTrace(Throwable thrown) {
        StringWriter out = new StringWriter();
        thrown.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
