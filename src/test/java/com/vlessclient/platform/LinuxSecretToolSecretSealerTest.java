package com.vlessclient.platform;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the Linux sealer hands to {@code secret-tool}, and what it makes of
 * the answers. Runs on every OS: the command is recorded, not executed, so
 * no D-Bus session or keyring is involved.
 */
class LinuxSecretToolSecretSealerTest {

    private static final String TAG = "@sealed:secretservice:v1";

    private final RecordingSubprocess secretTool = new RecordingSubprocess();
    private final LinuxSecretToolSecretSealer sealer = new LinuxSecretToolSecretSealer(secretTool);

    private static String base64(String plaintext) {
        return Base64.getEncoder().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void sealStoresTheBase64PayloadUnderTheAppAttributeAndKey() {
        secretTool.reply("");

        assertThat(sealer.seal("srv-1.uuid", "pässword")).isEqualTo(TAG);

        RecordingSubprocess.Call call = secretTool.only();
        assertThat(call.command()).containsExactly(
                "secret-tool", "store", "--label=VLESS Client srv-1.uuid",
                "application", "vless-client", "key", "srv-1.uuid");
        assertThat(call.stdin()).isEqualTo(base64("pässword"));
        assertThat(call.stdin()).doesNotContain("pässword");
    }

    /**
     * secret-tool reads a secret on stdin into 8 KiB and stops: it stores
     * what it has and exits 0, saying "password is too long" only on the
     * standard error the app discards. A subscription URL over 6 KiB was
     * stored cut, the file kept only the tag, and the next start unsealed the
     * cut URL: the rest of it, the account token with it, was gone for good.
     */
    @Test
    void aValueSecretToolWouldCutIsKeptUnsealedAndOneThatFitsRoundTrips() {
        SecretToolReadingStdinAsLibsecretDoes keyring = new SecretToolReadingStdinAsLibsecretDoes();
        LinuxSecretToolSecretSealer sealing = new LinuxSecretToolSecretSealer(keyring);
        // 6,144 bytes are 8,192 in base64, all secret-tool reads.
        String fits = "https://sub.provider.example/s/" + "x".repeat(6144 - 31);
        String tooLong = fits + "&token=ACCOUNT";

        assertThat(sealing.seal("sub-1.url", tooLong))
                .as("the seal of a URL secret-tool would cut").isNull();
        assertThat(keyring.stored).as("nothing written to the keyring for it").isEmpty();

        assertThat(sealing.seal("sub-2.url", fits)).isEqualTo(TAG);
        assertThat(sealing.unseal("sub-2.url", TAG)).contains(fits);
    }

    /** A keyring behind {@code secret-tool}, whose stdin reader takes 8,192 bytes at most. */
    private static final class SecretToolReadingStdinAsLibsecretDoes
            implements SecretSealers.Subprocess {

        final java.util.Map<String, String> stored = new java.util.HashMap<>();

        @Override
        public java.util.Optional<String> run(String[] command, String stdin) {
            String key = command[command.length - 1];
            return switch (command[1]) {
                case "store" -> {
                    stored.put(key, stdin.length() > 8192 ? stdin.substring(0, 8192) : stdin);
                    yield java.util.Optional.of("");
                }
                case "lookup" -> java.util.Optional.ofNullable(stored.get(key));
                default -> java.util.Optional.of("");
            };
        }
    }

    @Test
    void sealReportsAFailedCommandAsNullSoTheCallerKeepsThePlaintext() {
        secretTool.fail();

        assertThat(sealer.seal("k", "v")).isNull();
    }

    @Test
    void unsealLooksTheSecretUpByTheSameAttributesAndDecodesIt() {
        secretTool.reply(base64("secret-value") + "\n");

        assertThat(sealer.unseal("srv-1.uuid", TAG)).contains("secret-value");

        RecordingSubprocess.Call call = secretTool.only();
        assertThat(call.command()).containsExactly(
                "secret-tool", "lookup", "application", "vless-client", "key", "srv-1.uuid");
        assertThat(call.stdin()).isNull();
    }

    @Test
    void unsealIgnoresPlaintextAndOtherBackendsWithoutRunningSecretTool() {
        assertThat(sealer.unseal("k", null)).isEmpty();
        assertThat(sealer.unseal("k", "plain-uuid")).isEmpty();
        assertThat(sealer.unseal("k", "@sealed:keychain:v1")).isEmpty();
        assertThat(sealer.unseal("k", "@sealed:dpapi:v1:AQID")).isEmpty();
        assertThat(secretTool.calls).isEmpty();
    }

    @Test
    void unsealTreatsAPayloadThatIsNotBase64AsMissing() {
        secretTool.reply("not base64 at all!\n");

        assertThat(sealer.unseal("k", TAG)).isEmpty();
    }

    @Test
    void unsealIsEmptyWhenTheLookupFails() {
        secretTool.fail();

        assertThat(sealer.unseal("k", TAG)).isEmpty();
    }

    @Test
    void deleteClearsTheEntryByTheSameAttributes() {
        secretTool.reply("");

        sealer.delete("srv-1.uuid");

        RecordingSubprocess.Call call = secretTool.only();
        assertThat(call.command()).containsExactly(
                "secret-tool", "clear", "application", "vless-client", "key", "srv-1.uuid");
        assertThat(call.stdin()).isNull();
    }

    @Test
    void deleteNeverThrows() {
        secretTool.fail();

        sealer.delete("k");

        assertThat(secretTool.calls).hasSize(1);
    }

    @Test
    void availabilityIsProbedWithOneCanaryRoundTripAndThenCached() {
        secretTool.reply("")                          // store
                .reply(base64("probe") + "\n")        // lookup
                .reply("");                           // clear

        assertThat(sealer.isAvailable()).isTrue();
        assertThat(sealer.isAvailable()).isTrue();

        assertThat(secretTool.calls).hasSize(3);
        String canary = secretTool.calls.get(0).command().get(6);
        assertThat(canary).startsWith("vlessclient-probe-");
        assertThat(secretTool.calls.get(1).command()).containsExactly(
                "secret-tool", "lookup", "application", "vless-client", "key", canary);
        assertThat(secretTool.calls.get(2).command()).containsExactly(
                "secret-tool", "clear", "application", "vless-client", "key", canary);
    }

    @Test
    void availabilityIsFalseWithoutAUsableSecretService() {
        secretTool.fail();

        assertThat(sealer.isAvailable()).isFalse();
        assertThat(secretTool.calls).hasSize(1);
    }

    @Test
    void availabilityIsFalseWhenTheCanaryComesBackChanged() {
        secretTool.reply("").reply(base64("something-else") + "\n").reply("");

        assertThat(sealer.isAvailable()).isFalse();
    }
}
