package com.vlessclient.platform;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seals secrets into the freedesktop Secret Service (GNOME Keyring, KWallet
 * with the bridge) via {@code secret-tool}, which reads the secret from
 * stdin. Availability depends on the binary being installed and a reachable
 * D-Bus session — the canary probe covers both; headless systems typically
 * report unavailable and the app keeps writing plaintext.
 */
final class LinuxSecretToolSecretSealer implements SecretSealer {

    private static final Logger log = LoggerFactory.getLogger(LinuxSecretToolSecretSealer.class);
    private static final String ATTRIBUTE = "application";
    private static final String ATTRIBUTE_VALUE = "vless-client";
    private static final String TAG = SEAL_PREFIX + "secretservice:v1";

    private final SecretSealers.Subprocess subprocess;
    private volatile Boolean available;

    LinuxSecretToolSecretSealer() {
        this(SecretSealers::run);
    }

    /** Test seam: runs {@code secret-tool} through the given subprocess runner. */
    LinuxSecretToolSecretSealer(SecretSealers.Subprocess subprocess) {
        this.subprocess = subprocess;
    }

    @Override
    public boolean isAvailable() {
        Boolean probed = available;
        if (probed == null) {
            probed = SecretSealers.probe(this);
            available = probed;
            log.info("Secret Service backend available: {}", probed);
        }
        return probed;
    }

    @Override
    public String seal(String key, String plaintext) {
        // Stored as base64 so the stdin/stdout round-trip is byte-exact
        // regardless of encoding or trailing-newline handling.
        String payload = Base64.getEncoder()
                .encodeToString(plaintext.getBytes(StandardCharsets.UTF_8));
        return subprocess.run(
                        new String[] {
                            "secret-tool", "store", "--label=VLESS Client " + key,
                            ATTRIBUTE, ATTRIBUTE_VALUE, "key", key
                        },
                        payload)
                .map(out -> TAG)
                .orElse(null);
    }

    @Override
    public Optional<String> unseal(String key, String stored) {
        if (!TAG.equals(stored)) {
            return Optional.empty();
        }
        return subprocess.run(
                        new String[] {
                            "secret-tool", "lookup", ATTRIBUTE, ATTRIBUTE_VALUE, "key", key
                        },
                        null)
                .flatMap(LinuxSecretToolSecretSealer::decodePayload);
    }

    private static Optional<String> decodePayload(String payload) {
        try {
            return Optional.of(new String(
                    Base64.getDecoder().decode(payload.trim()), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            log.warn("Secret Service payload is not valid base64; treating as missing");
            return Optional.empty();
        }
    }

    @Override
    public void delete(String key) {
        subprocess.run(
                new String[] {
                    "secret-tool", "clear", ATTRIBUTE, ATTRIBUTE_VALUE, "key", key
                },
                null);
    }
}
