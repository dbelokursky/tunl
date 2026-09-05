package com.vlessclient.platform;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Round-trips a secret through the real platform backend where one is
 * usable (macOS Keychain, Windows DPAPI, Linux Secret Service). Skipped on
 * machines without a working backend — availability itself is probed, not
 * assumed from the OS.
 *
 * <p>The round-trip runs on CI only. On a developer's Mac it writes into the
 * real login Keychain under the production service name, which is exactly
 * what a test run must not do; the commands each backend issues are pinned
 * by the per-backend unit tests, which never leave the JVM.</p>
 */
class SecretSealerPlatformTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "CI", matches = "true")
    void roundTripThroughThePlatformBackend() {
        SecretSealer sealer = SecretSealers.forCurrentPlatform();
        assumeTrue(sealer.isAvailable(), "no usable secret backend on this machine");

        String key = "vlessclient-test-" + UUID.randomUUID() + ".uuid";
        String secret = "s3cr3t-äöü-" + UUID.randomUUID();
        try {
            String sealed = sealer.seal(key, secret);
            assertThat(sealed).isNotNull().startsWith(SecretSealer.SEAL_PREFIX);
            assertThat(sealed).doesNotContain(secret);

            Optional<String> unsealed = sealer.unseal(key, sealed);
            assertThat(unsealed).contains(secret);
        } finally {
            sealer.delete(key);
        }
    }

    @Test
    void sealedDetectionAndDisabledSealerContract() {
        assertThat(SecretSealer.isSealed(null)).isFalse();
        assertThat(SecretSealer.isSealed("plain-uuid")).isFalse();
        assertThat(SecretSealer.isSealed("@sealed:keychain:v1")).isTrue();

        SecretSealer disabled = SecretSealers.disabled();
        assertThat(disabled.isAvailable()).isFalse();
        assertThat(disabled.seal("k", "v")).isNull();
        assertThat(disabled.unseal("k", "@sealed:fake:v1")).isEmpty();
    }
}
