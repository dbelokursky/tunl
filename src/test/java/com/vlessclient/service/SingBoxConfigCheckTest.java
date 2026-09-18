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

    /**
     * A Go panic has no FATAL line and ends with a stack trace, so the last
     * line used to be read as the reason: "main.go:8 +0x24" says nothing about
     * what went wrong. Output of sing-box 1.14.1 for a REALITY short ID of 18
     * hex digits, shortened.
     */
    @Test
    void aPanicIsReportedByItsOwnLineRatherThanTheLastFrame() {
        String output = """
                panic: runtime error: index out of range [8] with length 8

                goroutine 1 [running]:
                encoding/hex.Decode({0x688b5911d860?, 0x688b58d46e10?, 0x2b?}, {0x688b590d9860?})
                \tencoding/hex/hex.go:101 +0x15c
                github.com/sagernet/sing-box/common/tls.newRealityClient({_, _}, {_, _})
                \tgithub.com/sagernet/sing-box/common/tls/reality_client.go:79 +0x200
                main.main()
                \tgithub.com/sagernet/sing-box/cmd/sing-box/main.go:8 +0x24
                """;

        assertThat(SingBoxConfigCheck.describe(output, 2, CONFIG))
                .isEqualTo("panic: runtime error: index out of range [8] with length 8");
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
