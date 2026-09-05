package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.ServerConfig;
import org.junit.jupiter.api.Test;

/**
 * The display name comes straight out of a link's fragment and used to be
 * stored and logged verbatim: a fragment holding {@code %0A} forged a line in
 * tunl.log, the file people attach to bug reports.
 */
class ShareLinkNameSanitizingTest {

    private final ShareLinkParser parser = new ShareLinkParser();

    @Test
    void controlCharactersInTheFragmentDoNotSurvive() {
        ServerConfig config = parser.parse(
                "vless://uuid@host.example:443?security=tls#Tokyo%0A2099-01-01+FATAL+forged");

        assertThat(config.getName()).doesNotContain("\n").doesNotContain("\r");
        assertThat(config.getName()).startsWith("Tokyo");
    }

    @Test
    void anOverlongFragmentIsCapped() {
        String longName = "x".repeat(ShareLinkParser.MAX_NAME_LENGTH * 3);

        ServerConfig config = parser.parse("trojan://pw@host.example:443#" + longName);

        assertThat(config.getName()).hasSize(ShareLinkParser.MAX_NAME_LENGTH);
    }

    @Test
    void aFragmentMadeOnlyOfNoiseFallsBackToHostAndPort() {
        ServerConfig config = parser.parse("vless://uuid@host.example:443?security=tls#%0A%09");

        assertThat(config.getName()).isEqualTo("host.example:443");
    }

    @Test
    void cleanNameKeepsOrdinaryUnicode() {
        assertThat(ShareLinkParser.cleanName("  Сервер · 東京  ")).isEqualTo("Сервер · 東京");
        assertThat(ShareLinkParser.cleanName(null)).isEmpty();
    }
}
