package com.vlessclient.platform;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seals secrets with Windows DPAPI (user scope) through PowerShell. Unlike
 * the Keychain/Secret Service backends the ciphertext is self-contained: the
 * persisted value embeds the DPAPI blob, and only the same Windows user on
 * the same machine can decrypt it. Secrets travel over stdin/stdout only.
 */
final class WindowsDpapiSecretSealer implements SecretSealer {

    private static final Logger log = LoggerFactory.getLogger(WindowsDpapiSecretSealer.class);
    private static final String TAG = SEAL_PREFIX + "dpapi:v1:";

    private static final String PROTECT_SCRIPT =
            "Add-Type -AssemblyName System.Security;"
            + "$in=[Console]::In.ReadToEnd();"
            + "$b=[Text.Encoding]::UTF8.GetBytes($in);"
            + "$p=[Security.Cryptography.ProtectedData]::Protect($b,$null,'CurrentUser');"
            + "[Console]::Out.Write([Convert]::ToBase64String($p))";

    private static final String UNPROTECT_SCRIPT =
            "Add-Type -AssemblyName System.Security;"
            + "$in=[Console]::In.ReadToEnd();"
            + "$p=[Convert]::FromBase64String($in);"
            + "$b=[Security.Cryptography.ProtectedData]::Unprotect($p,$null,'CurrentUser');"
            + "[Console]::Out.Write([Text.Encoding]::UTF8.GetString($b))";

    private final SecretSealers.Subprocess subprocess;
    private volatile Boolean available;

    WindowsDpapiSecretSealer() {
        this(SecretSealers::run);
    }

    /** Test seam: runs {@code powershell} through the given subprocess runner. */
    WindowsDpapiSecretSealer(SecretSealers.Subprocess subprocess) {
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
        return powershell(PROTECT_SCRIPT, plaintext)
                .map(b64 -> TAG + b64.trim())
                .orElse(null);
    }

    @Override
    public Optional<String> unseal(String key, String stored) {
        if (stored == null || !stored.startsWith(TAG)) {
            return Optional.empty();
        }
        return powershell(UNPROTECT_SCRIPT, stored.substring(TAG.length()));
    }

    @Override
    public void delete(String key) {
        // Self-contained ciphertext: nothing to clean up outside the file.
    }

    private Optional<String> powershell(String script, String stdin) {
        return subprocess.run(
                new String[] {
                    "powershell", "-NoProfile", "-NonInteractive", "-Command", script
                },
                stdin);
    }
}
