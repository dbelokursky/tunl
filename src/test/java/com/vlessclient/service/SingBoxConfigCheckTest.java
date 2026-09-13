package com.vlessclient.service;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a refusal from {@code sing-box check} becomes a line someone can act on.
 * The outputs are the real core's (1.14.0), captured while designing the check;
 * {@code SingBoxRealBinarySmokeTest} runs the same path against the binary.
 */
class SingBoxConfigCheckTest {

    private static final Path CONFIG = Path.of("tmp", "singbox-123.json").toAbsolutePath();

    @Test
    void theFatalLineIsTheReasonEvenAfterDeprecationErrors() {
        String output = """
                ERROR[0000] missing `route.default_domain_resolver` or `domain_resolver` \
                in dial fields is deprecated in sing-box 1.12.0
                FATAL[0000] to continuing using this feature, set environment variable \
                ENABLE_DEPRECATED_MISSING_DOMAIN_RESOLVER=true
                """;

        assertThat(SingBoxConfigCheck.describe(output, 1, CONFIG)).isEqualTo(
                "to continuing using this feature, set environment variable "
                        + "ENABLE_DEPRECATED_MISSING_DOMAIN_RESOLVER=true");
    }

    @Test
    void colourCodesTheLogPrefixAndTheTemporaryPathAreRemoved() {
        String output = "\033[31mFATAL\033[0m[0000] decode config at " + CONFIG
                + ": outbounds[0].transport: unknown transport type: xhttp\r\n";

        assertThat(SingBoxConfigCheck.describe(output, 1, CONFIG))
                .isEqualTo("outbounds[0].transport: unknown transport type: xhttp");
    }

    @Test
    void aPathQuotedAnywhereElseShrinksToTheFileName() {
        String output = "FATAL[0000] read " + CONFIG + ": permission denied\n";

        assertThat(SingBoxConfigCheck.describe(output, 1, CONFIG))
                .isEqualTo("read singbox-123.json: permission denied");
    }

    @Test
    void withoutAFatalLineTheLastLineIsTheReason() {
        assertThat(SingBoxConfigCheck.describe("something odd\nunexpected end\n", 2, CONFIG))
                .isEqualTo("unexpected end");
    }

    @Test
    void silenceIsReportedAsTheExitCode() {
        assertThat(SingBoxConfigCheck.describe("\n   \n", 3, CONFIG)).isEqualTo("exit code 3");
    }

    @Test
    void anOverlongReasonIsCutRatherThanFloodingTheDialog() {
        String reason = SingBoxConfigCheck.describe("FATAL[0000] " + "x".repeat(1000), 1, CONFIG);

        assertThat(reason).hasSize(300).endsWith("…");
    }
}
