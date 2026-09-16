package com.vlessclient.platform;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real crypt32 calls, and compatibility both ways with the PowerShell
 * builds whose blobs already sit in users' config files. Windows only; DPAPI
 * keeps no state, so nothing is left behind.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsDpapiTest {

    private static final String V1 = "@sealed:dpapi:v1:";

    /** How PowerShell builds sealed: the base64 blob they stored after the tag. */
    private static final String LEGACY_PROTECT =
            "Add-Type -AssemblyName System.Security;"
            + "$in=[Console]::In.ReadToEnd();"
            + "$b=[Text.Encoding]::UTF8.GetBytes($in);"
            + "$p=[Security.Cryptography.ProtectedData]::Protect($b,$null,'CurrentUser');"
            + "[Console]::Out.Write([Convert]::ToBase64String($p))";

    private static final String LEGACY_UNPROTECT =
            "Add-Type -AssemblyName System.Security;"
            + "$in=[Console]::In.ReadToEnd();"
            + "$p=[Convert]::FromBase64String($in);"
            + "$b=[Security.Cryptography.ProtectedData]::Unprotect($p,$null,'CurrentUser');"
            + "[Console]::Out.Write([Text.Encoding]::UTF8.GetString($b))";

    private final WindowsDpapi dpapi = new WindowsDpapi();

    @Test
    void protectedBytesOpenAgainAndDoNotCarryThePlaintext() {
        byte[] secret = ("s3cr3t-äöü-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

        byte[] blob = dpapi.protect(secret).orElseThrow();

        assertThat(blob).isNotEqualTo(secret).hasSizeGreaterThan(secret.length);
        assertThat(dpapi.unprotect(blob))
                .hasValueSatisfying(opened -> assertThat(opened).isEqualTo(secret));
    }

    @Test
    void bytesThatAreNotADpapiBlobAreRefusedRatherThanThrown() {
        assertThat(dpapi.unprotect(new byte[] {1, 2, 3, 4})).isEmpty();
    }

    @Test
    void anAsciiValueFromAPowershellBuildOpensWithoutRunningPowershell() {
        String secret = "legacy-" + UUID.randomUUID();
        String blob = powershell(LEGACY_PROTECT, secret).trim();
        WindowsDpapiSecretSealer sealer = new WindowsDpapiSecretSealer(dpapi,
                (command, stdin) -> {
                    throw new AssertionError("PowerShell ran for an ASCII value");
                });

        assertThat(sealer.unseal("k", V1 + blob)).contains(secret);
    }

    @Test
    void aNonAsciiValueFromAPowershellBuildStillOpens() {
        String secret = "legacy-äöü-" + UUID.randomUUID();
        String blob = powershell(LEGACY_PROTECT, secret).trim();
        WindowsDpapiSecretSealer sealer =
                new WindowsDpapiSecretSealer(dpapi, SecretSealers.system());

        assertThat(sealer.unseal("k", V1 + blob)).contains(secret);
    }

    @Test
    void anAsciiValueSealedInProcessStillOpensInAPowershellBuild() {
        String secret = "downgrade-" + UUID.randomUUID();
        String stored = new WindowsDpapiSecretSealer(dpapi, SecretSealers.system())
                .seal("k", secret);

        assertThat(stored).startsWith(V1);
        assertThat(powershell(LEGACY_UNPROTECT, stored.substring(V1.length())))
                .isEqualTo(secret);
    }

    private static String powershell(String script, String stdin) {
        return SecretSealers.system().run(
                        new String[] {
                            "powershell", "-NoProfile", "-NonInteractive", "-Command", script
                        },
                        stdin)
                .orElseThrow(() -> new AssertionError("PowerShell did not exit 0"));
    }
}
