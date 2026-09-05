package com.vlessclient.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory and shared plumbing for {@link SecretSealer} backends: the macOS
 * Keychain ({@code security}), Windows DPAPI (PowerShell), and the Linux
 * Secret Service ({@code secret-tool}), with a no-op fallback when nothing is
 * available. Secrets are always passed over stdin/stdout, never in argv.
 */
public final class SecretSealers {

    private static final Logger log = LoggerFactory.getLogger(SecretSealers.class);
    private static final int SUBPROCESS_TIMEOUT_SECONDS = 15;

    private SecretSealers() {
    }

    /** The sealer for the current OS; probing happens lazily on first use. */
    public static SecretSealer forCurrentPlatform() {
        return switch (Platform.current()) {
            case WINDOWS -> new WindowsDpapiSecretSealer();
            case LINUX -> new LinuxSecretToolSecretSealer();
            default -> new MacKeychainSecretSealer();
        };
    }

    /** A sealer that never seals; loads legacy plaintext only. */
    public static SecretSealer disabled() {
        return new SecretSealer() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public String seal(String key, String plaintext) {
                return null;
            }

            @Override
            public Optional<String> unseal(String key, String stored) {
                return Optional.empty();
            }

            @Override
            public void delete(String key) {
            }
        };
    }

    /**
     * The seam every backend shells out through: one command, an optional
     * stdin payload, and its stdout when it exited 0. {@link #run} is the
     * production implementation; the sealer tests substitute a recording fake
     * so nothing reaches a real keychain.
     */
    @FunctionalInterface
    interface Subprocess {
        Optional<String> run(String[] command, String stdin);
    }

    /**
     * Runs a command, feeding {@code stdin} (may be null) and capturing
     * stdout. Returns empty on non-zero exit, timeout, or launch failure.
     */
    static Optional<String> run(String[] command, String stdin) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process process = pb.start();
            if (stdin != null) {
                try (var out = process.getOutputStream()) {
                    out.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                process.getOutputStream().close();
            }
            String stdout;
            try (var in = process.getInputStream()) {
                stdout = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(SUBPROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("Secret backend command timed out: {}", command[0]);
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                return Optional.empty();
            }
            return Optional.of(stdout);
        } catch (IOException e) {
            log.debug("Secret backend command failed to run: {} ({})",
                    command[0], e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Probes a backend with a canary round-trip (seal, unseal, delete) so
     * availability reflects reality (binary present, daemon reachable,
     * keychain unlocked), not just OS detection.
     */
    static boolean probe(SecretSealer sealer) {
        String canaryKey = "vlessclient-probe-" + UUID.randomUUID();
        String canaryValue = "probe";
        try {
            String sealed = sealer.seal(canaryKey, canaryValue);
            if (sealed == null) {
                return false;
            }
            boolean ok = sealer.unseal(canaryKey, sealed)
                    .map(canaryValue::equals)
                    .orElse(false);
            sealer.delete(canaryKey);
            return ok;
        } catch (RuntimeException e) {
            log.debug("Secret backend probe failed", e);
            return false;
        }
    }
}
