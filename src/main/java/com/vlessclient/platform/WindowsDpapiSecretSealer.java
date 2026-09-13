package com.vlessclient.platform;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seals secrets with Windows DPAPI (user scope). Unlike the Keychain/Secret
 * Service backends the ciphertext is self-contained: the persisted value
 * embeds the DPAPI blob, and only the same Windows user on the same machine
 * can decrypt it.
 *
 * <p>DPAPI runs in-process through {@link WindowsDpapi}. Earlier builds piped
 * every value through PowerShell's {@code ProtectedData}, which read stdin in
 * the console code page rather than UTF-8, so the bytes they protected equal
 * the UTF-8 bytes only for ASCII text. The tag says which bytes a blob holds:</p>
 * <ul>
 *   <li>{@code dpapi:v1:} ASCII text, written identically by both kinds of
 *       build, so a PowerShell build still reads what this one wrote; or a
 *       non-ASCII value from a PowerShell build, which only the same script
 *       maps back to the text that was sealed, so that one case still runs
 *       it.</li>
 *   <li>{@code dpapi:v2:} the UTF-8 bytes of non-ASCII text, written only
 *       in-process.</li>
 * </ul>
 */
final class WindowsDpapiSecretSealer implements SecretSealer {

    private static final Logger log = LoggerFactory.getLogger(WindowsDpapiSecretSealer.class);
    private static final String TAG_V1 = SEAL_PREFIX + "dpapi:v1:";
    private static final String TAG_V2 = SEAL_PREFIX + "dpapi:v2:";

    /** What PowerShell builds ran to unseal, kept for their non-ASCII v1 values. */
    private static final String LEGACY_UNPROTECT_SCRIPT =
            "Add-Type -AssemblyName System.Security;"
            + "$in=[Console]::In.ReadToEnd();"
            + "$p=[Convert]::FromBase64String($in);"
            + "$b=[Security.Cryptography.ProtectedData]::Unprotect($p,$null,'CurrentUser');"
            + "[Console]::Out.Write([Text.Encoding]::UTF8.GetString($b))";

    /** The two DPAPI calls the sealer makes; {@link WindowsDpapi} is the real pair. */
    interface Dpapi {

        /** The DPAPI blob for these bytes, or empty when DPAPI refused. */
        Optional<byte[]> protect(byte[] plaintext);

        /** The bytes inside this blob, or empty when it does not open for this user. */
        Optional<byte[]> unprotect(byte[] blob);
    }

    private final Dpapi dpapi;
    private final SecretSealers.Subprocess subprocess;
    private volatile Boolean available;

    WindowsDpapiSecretSealer() {
        this(new WindowsDpapi(), SecretSealers::run);
    }

    /**
     * Test seam: DPAPI through {@code dpapi}, and PowerShell, which only a
     * legacy non-ASCII value still needs, through {@code subprocess}.
     */
    WindowsDpapiSecretSealer(Dpapi dpapi, SecretSealers.Subprocess subprocess) {
        this.dpapi = dpapi;
        this.subprocess = subprocess;
    }

    @Override
    public boolean isAvailable() {
        Boolean probed = available;
        if (probed == null) {
            probed = SecretSealers.probe(this);
            available = probed;
            log.info("Windows DPAPI secret backend available: {}", probed);
        }
        return probed;
    }

    @Override
    public String seal(String key, String plaintext) {
        byte[] bytes = plaintext.getBytes(StandardCharsets.UTF_8);
        String tag = isAscii(bytes) ? TAG_V1 : TAG_V2;
        return dpapi.protect(bytes)
                .map(blob -> tag + Base64.getEncoder().encodeToString(blob))
                .orElse(null);
    }

    @Override
    public Optional<String> unseal(String key, String stored) {
        if (stored == null) {
            return Optional.empty();
        }
        if (stored.startsWith(TAG_V2)) {
            return open(stored.substring(TAG_V2.length()))
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8));
        }
        if (!stored.startsWith(TAG_V1)) {
            return Optional.empty();
        }
        String payload = stored.substring(TAG_V1.length());
        Optional<byte[]> bytes = open(payload);
        if (bytes.isPresent() && !isAscii(bytes.get())) {
            // A PowerShell build protected these in its console code page, and
            // only the same script turns them back into the text it was given.
            return subprocess.run(
                    new String[] {
                        "powershell", "-NoProfile", "-NonInteractive", "-Command",
                        LEGACY_UNPROTECT_SCRIPT
                    },
                    payload);
        }
        return bytes.map(ascii -> new String(ascii, StandardCharsets.UTF_8));
    }

    @Override
    public void delete(String key) {
        // Self-contained ciphertext: nothing to clean up outside the file.
    }

    private Optional<byte[]> open(String base64) {
        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            log.warn("DPAPI payload is not valid base64; treating as missing");
            return Optional.empty();
        }
        return dpapi.unprotect(blob);
    }

    private static boolean isAscii(byte[] bytes) {
        for (byte b : bytes) {
            if (b < 0) {
                return false;
            }
        }
        return true;
    }
}
