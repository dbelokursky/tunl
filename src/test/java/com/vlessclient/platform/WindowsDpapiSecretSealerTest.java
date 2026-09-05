package com.vlessclient.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the Windows sealer hands to PowerShell, and what it makes of the
 * answers. Runs on every OS: the command is recorded, not executed.
 */
class WindowsDpapiSecretSealerTest {

    private static final String TAG = "@sealed:dpapi:v1:";

    private static final String PROTECT =
            "Add-Type -AssemblyName System.Security;"
            + "$in=[Console]::In.ReadToEnd();"
            + "$b=[Text.Encoding]::UTF8.GetBytes($in);"
            + "$p=[Security.Cryptography.ProtectedData]::Protect($b,$null,'CurrentUser');"
            + "[Console]::Out.Write([Convert]::ToBase64String($p))";

    private static final String UNPROTECT =
            "Add-Type -AssemblyName System.Security;"
            + "$in=[Console]::In.ReadToEnd();"
            + "$p=[Convert]::FromBase64String($in);"
            + "$b=[Security.Cryptography.ProtectedData]::Unprotect($p,$null,'CurrentUser');"
            + "[Console]::Out.Write([Text.Encoding]::UTF8.GetString($b))";

    private final RecordingSubprocess powershell = new RecordingSubprocess();
    private final WindowsDpapiSecretSealer sealer = new WindowsDpapiSecretSealer(powershell);

    @Test
    void sealPipesThePlaintextIntoProtectAndEmbedsTheBlobInTheStoredValue() {
        powershell.reply("AQIDBA==\r\n");

        assertThat(sealer.seal("srv-1.uuid", "secret")).isEqualTo(TAG + "AQIDBA==");

        RecordingSubprocess.Call call = powershell.only();
        assertThat(call.command()).containsExactly(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", PROTECT);
        assertThat(call.stdin()).isEqualTo("secret");
        assertThat(call.command())
                .as("DPAPI is keyed to the user, so the key never reaches the command")
                .doesNotContain("srv-1.uuid");
    }

    @Test
    void sealReportsAFailedCommandAsNull() {
        powershell.fail();

        assertThat(sealer.seal("k", "secret")).isNull();
    }

    @Test
    void unsealPipesTheBlobIntoUnprotectAndReturnsItsOutputVerbatim() {
        powershell.reply("secret");

        assertThat(sealer.unseal("srv-1.uuid", TAG + "AQIDBA==")).contains("secret");

        RecordingSubprocess.Call call = powershell.only();
        assertThat(call.command()).containsExactly(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", UNPROTECT);
        assertThat(call.stdin()).isEqualTo("AQIDBA==");
    }

    @Test
    void unsealIgnoresPlaintextAndOtherBackendsWithoutRunningPowershell() {
        assertThat(sealer.unseal("k", null)).isEmpty();
        assertThat(sealer.unseal("k", "plain-uuid")).isEmpty();
        assertThat(sealer.unseal("k", "@sealed:keychain:v1")).isEmpty();
        assertThat(sealer.unseal("k", "@sealed:secretservice:v1")).isEmpty();
        assertThat(powershell.calls).isEmpty();
    }

    @Test
    void unsealIsEmptyWhenUnprotectFails() {
        powershell.fail();

        assertThat(sealer.unseal("k", TAG + "AQIDBA==")).isEmpty();
    }

    @Test
    void deleteRunsNothingBecauseTheCiphertextIsSelfContained() {
        sealer.delete("srv-1.uuid");

        assertThat(powershell.calls).isEmpty();
    }

    @Test
    void availabilityIsProbedWithAProtectUnprotectRoundTripAndThenCached() {
        powershell.reply("QkxPQg==").reply("probe");

        assertThat(sealer.isAvailable()).isTrue();
        assertThat(sealer.isAvailable()).isTrue();

        assertThat(powershell.calls).hasSize(2);
        assertThat(powershell.calls.get(0).stdin()).isEqualTo("probe");
        assertThat(powershell.calls.get(1).stdin()).isEqualTo("QkxPQg==");
    }

    @Test
    void availabilityIsFalseWhenPowershellCannotProtect() {
        powershell.fail();

        assertThat(sealer.isAvailable()).isFalse();
    }

    @Test
    void availabilityIsFalseWhenTheCanaryDoesNotSurviveTheRoundTrip() {
        powershell.reply("QkxPQg==").reply("garbage");

        assertThat(sealer.isAvailable()).isFalse();
    }
}
