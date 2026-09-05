package com.vlessclient.platform;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the macOS sealer hands to {@code security}, and what it makes of the
 * answers. Runs on every OS: the command is recorded, not executed, so the
 * developer's login Keychain is never touched.
 */
class MacKeychainSecretSealerTest {

    private static final String TAG = "@sealed:keychain:v1";

    private final RecordingSubprocess security = new RecordingSubprocess();
    private final MacKeychainSecretSealer sealer = new MacKeychainSecretSealer(security);

    private static String base64(String plaintext) {
        return Base64.getEncoder().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void sealFeedsTheItemToSecurityOverStdinAndReturnsTheBareTag() {
        security.reply("");

        String sealed = sealer.seal("srv-1.uuid", "pässw\"ord");

        assertThat(sealed).isEqualTo(TAG);
        RecordingSubprocess.Call call = security.only();
        assertThat(call.command()).containsExactly("security", "-i");
        assertThat(call.stdin()).isEqualTo("add-generic-password -U -a \"srv-1.uuid\""
                + " -s \"VLESS Client\" -w \"" + base64("pässw\"ord") + "\"\n");
        assertThat(call.stdin())
                .as("the secret travels base64-encoded, never in the clear")
                .doesNotContain("pässw");
    }

    @Test
    void sealEscapesQuotesAndBackslashesInTheKeyForTheLineParser() {
        security.reply("");

        sealer.seal("a\"b\\c", "x");

        assertThat(security.only().stdin())
                .startsWith("add-generic-password -U -a \"a\\\"b\\\\c\" -s \"VLESS Client\" -w ");
    }

    @Test
    void sealRefusesAKeyWithControlCharactersWithoutRunningAnything() {
        assertThat(sealer.seal("bad\nkey", "x")).isNull();
        assertThat(sealer.seal("badkey", "x")).isNull();
        assertThat(security.calls).isEmpty();
    }

    @Test
    void sealReportsAFailedCommandAsNullSoTheCallerKeepsThePlaintext() {
        security.fail();

        assertThat(sealer.seal("k", "v")).isNull();
        assertThat(security.calls).hasSize(1);
    }

    @Test
    void unsealAsksForThePasswordByAccountAndServiceAndDecodesIt() {
        security.reply(base64("secret-value") + "\n");

        assertThat(sealer.unseal("srv-1.uuid", TAG)).contains("secret-value");
        RecordingSubprocess.Call call = security.only();
        assertThat(call.command()).containsExactly(
                "security", "find-generic-password", "-a", "srv-1.uuid", "-s", "VLESS Client", "-w");
        assertThat(call.stdin()).isNull();
    }

    @Test
    void unsealIgnoresPlaintextAndOtherBackendsWithoutAskingTheKeychain() {
        assertThat(sealer.unseal("k", null)).isEmpty();
        assertThat(sealer.unseal("k", "plain-uuid")).isEmpty();
        assertThat(sealer.unseal("k", "@sealed:dpapi:v1:AQID")).isEmpty();
        assertThat(sealer.unseal("k", "@sealed:secretservice:v1")).isEmpty();
        assertThat(security.calls).isEmpty();
    }

    @Test
    void unsealTreatsAPayloadThatIsNotBase64AsMissing() {
        security.reply("not base64 at all!\n");

        assertThat(sealer.unseal("k", TAG)).isEmpty();
    }

    @Test
    void unsealIsEmptyWhenSecurityFails() {
        security.fail();

        assertThat(sealer.unseal("k", TAG)).isEmpty();
    }

    @Test
    void deleteRemovesTheItemByAccountAndService() {
        security.reply("");

        sealer.delete("srv-1.uuid");

        RecordingSubprocess.Call call = security.only();
        assertThat(call.command()).containsExactly(
                "security", "delete-generic-password", "-a", "srv-1.uuid", "-s", "VLESS Client");
        assertThat(call.stdin()).isNull();
    }

    @Test
    void deleteNeverThrows() {
        security.fail();

        sealer.delete("k");

        assertThat(security.calls).hasSize(1);
    }

    @Test
    void availabilityIsProbedWithOneCanaryRoundTripAndThenCached() {
        security.reply("")                          // add-generic-password
                .reply(base64("probe") + "\n")      // find-generic-password
                .reply("");                         // delete-generic-password

        assertThat(sealer.isAvailable()).isTrue();
        assertThat(sealer.isAvailable()).isTrue();

        assertThat(security.calls).hasSize(3);
        assertThat(security.calls.get(0).stdin()).contains("-a \"vlessclient-probe-");
        assertThat(security.calls.get(1).command()).contains("find-generic-password");
        assertThat(security.calls.get(2).command()).contains("delete-generic-password");
        String canary = security.calls.get(1).command().get(3);
        assertThat(canary).startsWith("vlessclient-probe-");
        assertThat(security.calls.get(2).command().get(3))
                .as("the canary is deleted under the key it was stored with")
                .isEqualTo(canary);
    }

    @Test
    void availabilityIsFalseWhenTheKeychainRefusesTheCanary() {
        security.fail();

        assertThat(sealer.isAvailable()).isFalse();
        assertThat(security.calls).hasSize(1);
    }

    @Test
    void availabilityIsFalseWhenTheCanaryComesBackChanged() {
        security.reply("").reply(base64("something-else") + "\n").reply("");

        assertThat(sealer.isAvailable()).isFalse();
    }
}
