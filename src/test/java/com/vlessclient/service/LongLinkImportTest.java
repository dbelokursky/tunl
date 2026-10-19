package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A link whose field holds a long run of letters and digits: the parser's
 * message for it quotes the field, and every import path scrubs that message
 * for URLs before it is shown or logged. The scrubbing tried every letter of
 * the run as the start of a scheme and read the rest of the run each time, so
 * one 32 KB link froze the Servers view, which imports on the FX thread, for
 * 10 to 16 seconds, and held a subscription refresh, and every refresh queued
 * behind it, for as long.
 */
class LongLinkImportTest {

    private static final String UUID = "b831381d-6324-4d53-ad4f-8cda48b30811";

    /** Ample for a few hundred kilobytes read once; the old cost was minutes. */
    private static final Duration PROMPTLY = Duration.ofSeconds(10);

    @TempDir
    Path dir;

    @Test
    void aPastedLinkWithALongFingerprintIsReportedPromptly() {
        ServerBackupService backup = new ServerBackupService(new ConfigStore(dir.resolve("b")),
                new ShareLinkParser());
        String clipboard = "vless://" + UUID + "@h.example.com:443?security=tls&fp="
                + "a".repeat(128 * 1024);

        ServerBackupService.ImportResult result =
                assertTimeoutPreemptively(PROMPTLY, () -> backup.importShareLinks(clipboard));

        assertThat(result.added()).isZero();
        assertThat(result.skipped()).hasSize(1);
        assertThat(result.skipped().getFirst().reason()).doesNotContain(UUID);
    }

    @Test
    void aSubscriptionLineWithALongPortListIsReadPromptly() {
        SubscriptionService service = new SubscriptionService(new ConfigStore(dir.resolve("s")),
                new ShareLinkParser(), dir, HttpClient.newHttpClient());
        String body = "hy2://pw@hy.example.com:443?mport=" + "a".repeat(128 * 1024) + "\n"
                + "trojan://pw@tr.example.com:443#Kept";

        SubscriptionService.ParsedContent parsed =
                assertTimeoutPreemptively(PROMPTLY, () -> service.parseContent(body));

        assertThat(parsed.servers()).extracting(server -> server.getName())
                .containsExactly("Kept");
        assertThat(parsed.skipped()).isEqualTo(1);
    }
}
