package com.vlessclient.platform;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seals secrets into the macOS login Keychain via {@code security}'s
 * interactive mode (commands over stdin, so the secret never appears in a
 * process argument list). The persisted value is a bare tag; the secret
 * itself lives in the Keychain under service {@value #SERVICE} and the
 * caller's key as the account name.
 */
final class MacKeychainSecretSealer implements SecretSealer {

    private static final Logger log = LoggerFactory.getLogger(MacKeychainSecretSealer.class);
    private static final String SERVICE = "VLESS Client";
    private static final String TAG = SEAL_PREFIX + "keychain:v1";

    private final SecretSealers.Subprocess subprocess;
    private volatile Boolean available;

    MacKeychainSecretSealer() {
        this(SecretSealers::run);
    }

    /** Test seam: runs {@code security} through the given subprocess runner. */
    MacKeychainSecretSealer(SecretSealers.Subprocess subprocess) {
        this.subprocess = subprocess;
    }

    @Override
    public boolean isAvailable() {
        Boolean probed = available;
        if (probed == null) {
            probed = SecretSealers.probe(this);
            available = probed;
            log.info("macOS Keychain secret backend available: {}", probed);
        }
        return probed;
    }

    @Override
    public String seal(String key, String plaintext) {
        if (hasControlChars(key)) {
            log.warn("Refusing to seal under a key with control characters");
            return null;
        }
        // The keychain item holds base64: `find-generic-password -w` prints
        // non-ASCII passwords as hex, so only an ASCII payload round-trips
        // verbatim. -U updates an existing item in place instead of failing.
        String payload = Base64.getEncoder()
                .encodeToString(plaintext.getBytes(StandardCharsets.UTF_8));
        String command = "add-generic-password -U -a " + quote(key)
                + " -s " + quote(SERVICE) + " -w " + quote(payload);
        return subprocess.run(new String[] {"security", "-i"}, command + "\n")
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
                            "security", "find-generic-password",
                            "-a", key, "-s", SERVICE, "-w"
                        },
                        null)
                .map(MacKeychainSecretSealer::stripTrailingNewline)
                .flatMap(MacKeychainSecretSealer::decodePayload);
    }

    @Override
    public void delete(String key) {
        subprocess.run(
                new String[] {
                    "security", "delete-generic-password", "-a", key, "-s", SERVICE
                },
                null);
    }

    /** Quotes a value for {@code security -i}'s line parser. */
    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static boolean hasControlChars(String value) {
        return value.chars().anyMatch(c -> c < 0x20 || c == 0x7f);
    }

    /** {@code find-generic-password -w} appends a newline to the secret. */
    private static String stripTrailingNewline(String out) {
        return out.replaceAll("[\r\n]+$", "");
    }

    private static Optional<String> decodePayload(String payload) {
        try {
            return Optional.of(new String(
                    Base64.getDecoder().decode(payload), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            log.warn("Keychain payload is not valid base64; treating as missing");
            return Optional.empty();
        }
    }
}
