package com.vlessclient.platform;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the Windows sealer tags and encodes what DPAPI hands back, and the one
 * case it still gives to PowerShell. Runs on every OS: DPAPI is a reversible
 * fake and PowerShell is recorded, not executed. {@link WindowsDpapiTest}
 * drives the real crypt32 calls on Windows.
 */
class WindowsDpapiSecretSealerTest {

    private static final String V1 = "@sealed:dpapi:v1:";
    private static final String V2 = "@sealed:dpapi:v2:";

    private static final String LEGACY_UNPROTECT =
            "Add-Type -AssemblyName System.Security;"
            + "$in=[Console]::In.ReadToEnd();"
            + "$p=[Convert]::FromBase64String($in);"
            + "$b=[Security.Cryptography.ProtectedData]::Unprotect($p,$null,'CurrentUser');"
            + "[Console]::Out.Write([Text.Encoding]::UTF8.GetString($b))";

    private final FakeDpapi dpapi = new FakeDpapi();
    private final RecordingSubprocess powershell = new RecordingSubprocess();
    private final WindowsDpapiSecretSealer sealer = new WindowsDpapiSecretSealer(dpapi, powershell);

    @Test
    void anAsciiSecretIsSealedUnderV1SoPowershellBuildsCanStillOpenIt() {
        String stored = sealer.seal("srv-1.uuid", "secret");

        assertThat(stored).isEqualTo(V1 + base64(FakeDpapi.blobOf(utf8("secret"))));
        assertThat(dpapi.calls).containsExactly("protect");
        assertThat(powershell.calls).isEmpty();
    }

    @Test
    void aNonAsciiSecretIsSealedUnderV2AsItsUtf8Bytes() {
        String stored = sealer.seal("sub-1.url", "https://пример.рф/sub?t=äöü");

        assertThat(stored).isEqualTo(
                V2 + base64(FakeDpapi.blobOf(utf8("https://пример.рф/sub?t=äöü"))));
        assertThat(powershell.calls).isEmpty();
    }

    @Test
    void sealReportsARefusedProtectAsNull() {
        dpapi.refuse = true;

        assertThat(sealer.seal("k", "secret")).isNull();
    }

    @Test
    void bothTagsRoundTripInProcess() {
        for (String secret : List.of("7c9e6679-7425-40de-944b-e07fc1f90ae7", "пароль-äöü")) {
            assertThat(sealer.unseal("k", sealer.seal("k", secret))).contains(secret);
        }
        assertThat(powershell.calls).isEmpty();
    }

    @Test
    void anAsciiV1ValueFromAPowershellBuildOpensWithoutPowershell() {
        String stored = V1 + base64(FakeDpapi.blobOf(utf8("xtls-rprx-vision")));

        assertThat(sealer.unseal("srv-1.flow", stored)).contains("xtls-rprx-vision");
        assertThat(powershell.calls).isEmpty();
    }

    @Test
    void aNonAsciiV1ValueGoesBackThroughTheLegacyPowershellScript() {
        // The bytes a PowerShell build protected after decoding UTF-8 stdin
        // in its console code page: not the UTF-8 of the text it was handed.
        String payload = base64(FakeDpapi.blobOf(utf8("ÐÐ°ÑÐ¾Ð»Ñ")));
        powershell.reply("пароль");

        assertThat(sealer.unseal("sub-1.url", V1 + payload)).contains("пароль");

        RecordingSubprocess.Call call = powershell.only();
        assertThat(call.command()).containsExactly(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", LEGACY_UNPROTECT);
        assertThat(call.stdin()).isEqualTo(payload);
    }

    @Test
    void unsealIsEmptyWhenDpapiRefusesTheBlob() {
        String stored = sealer.seal("k", "secret");
        dpapi.refuse = true;

        assertThat(sealer.unseal("k", stored)).isEmpty();
        assertThat(powershell.calls).isEmpty();
    }

    @Test
    void aPayloadThatIsNotBase64IsTreatedAsMissing() {
        assertThat(sealer.unseal("k", V1 + "not base64!")).isEmpty();
        assertThat(sealer.unseal("k", V2 + "not base64!")).isEmpty();
        assertThat(dpapi.calls).isEmpty();
        assertThat(powershell.calls).isEmpty();
    }

    @Test
    void unsealIgnoresPlaintextAndOtherBackends() {
        assertThat(sealer.unseal("k", null)).isEmpty();
        assertThat(sealer.unseal("k", "plain-uuid")).isEmpty();
        assertThat(sealer.unseal("k", "@sealed:keychain:v1")).isEmpty();
        assertThat(sealer.unseal("k", "@sealed:secretservice:v1")).isEmpty();
        assertThat(dpapi.calls).isEmpty();
        assertThat(powershell.calls).isEmpty();
    }

    @Test
    void deleteTouchesNothingBecauseTheCiphertextIsSelfContained() {
        sealer.delete("srv-1.uuid");

        assertThat(dpapi.calls).isEmpty();
        assertThat(powershell.calls).isEmpty();
    }

    @Test
    void availabilityIsProbedWithAProtectUnprotectRoundTripAndThenCached() {
        assertThat(sealer.isAvailable()).isTrue();
        assertThat(sealer.isAvailable()).isTrue();

        assertThat(dpapi.calls).containsExactly("protect", "unprotect");
        assertThat(powershell.calls).isEmpty();
    }

    @Test
    void availabilityIsFalseWhenDpapiCannotProtect() {
        dpapi.refuse = true;

        assertThat(sealer.isAvailable()).isFalse();
    }

    @Test
    void availabilityIsFalseWhenTheCanaryDoesNotSurviveTheRoundTrip() {
        dpapi.garbleUnprotect = true;

        assertThat(sealer.isAvailable()).isFalse();
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Reversible stand-in for DPAPI: a blob is a marker byte followed by the
     * plaintext, so a test knows the exact bytes the sealer protected.
     */
    private static final class FakeDpapi implements WindowsDpapiSecretSealer.Dpapi {

        private static final byte MARKER = 0x42;

        final List<String> calls = new ArrayList<>();
        boolean refuse;
        boolean garbleUnprotect;

        static byte[] blobOf(byte[] plaintext) {
            byte[] blob = new byte[plaintext.length + 1];
            blob[0] = MARKER;
            System.arraycopy(plaintext, 0, blob, 1, plaintext.length);
            return blob;
        }

        @Override
        public Optional<byte[]> protect(byte[] plaintext) {
            calls.add("protect");
            return refuse ? Optional.empty() : Optional.of(blobOf(plaintext));
        }

        @Override
        public Optional<byte[]> unprotect(byte[] blob) {
            calls.add("unprotect");
            if (refuse || blob.length == 0 || blob[0] != MARKER) {
                return Optional.empty();
            }
            return Optional.of(garbleUnprotect
                    ? utf8("garbage")
                    : Arrays.copyOfRange(blob, 1, blob.length));
        }
    }
}
