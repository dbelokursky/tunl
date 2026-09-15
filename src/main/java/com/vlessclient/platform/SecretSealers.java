package com.vlessclient.platform;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory and shared plumbing for {@link SecretSealer} backends: the macOS
 * Keychain ({@code security}), Windows DPAPI (in-process, through
 * {@code crypt32}), and the Linux Secret Service ({@code secret-tool}), with a
 * no-op fallback when nothing is available. Whenever a backend runs a command,
 * secrets travel over stdin/stdout, never in argv.
 */
public final class SecretSealers {

    private static final Logger log = LoggerFactory.getLogger(SecretSealers.class);

    /**
     * A backend command can wait on an unlock prompt for a locked keychain or
     * keyring, and the person it asks may first have to come back to the
     * machine: the app often starts along with the login session.
     */
    private static final Duration SUBPROCESS_TIMEOUT = Duration.ofMinutes(2);

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
     * stdin payload, and its stdout when it exited 0. {@link #system()} is the
     * production implementation; the sealer tests substitute a recording fake
     * so nothing reaches a real keychain.
     */
    @FunctionalInterface
    interface Subprocess {
        Optional<String> run(String[] command, String stdin);
    }

    /**
     * The production {@link Subprocess}. Each command runs through
     * {@link TimedProcess} with its standard error discarded, and comes back
     * empty on a non-zero exit, a timeout or a launch failure.
     *
     * <p>Once a command has timed out, later ones come back empty without
     * running. The app unseals every stored credential in turn before its
     * window first appears, and a store that never answers would otherwise
     * cost a full timeout for each of them. The price is that an unlock
     * prompt answered after the timeout takes effect on the next start.
     * Nothing is lost meanwhile: a value that stays sealed loads then, and a
     * value that cannot be sealed is kept as plaintext, as it is whenever no
     * backend is available.</p>
     */
    static Subprocess system() {
        return system(SUBPROCESS_TIMEOUT);
    }

    /** {@link #system()} with the given timeout. */
    static Subprocess system(Duration timeout) {
        AtomicBoolean timedOut = new AtomicBoolean();
        return (command, stdin) -> timedOut.get()
                ? Optional.empty()
                : run(command, stdin, timeout, timedOut);
    }

    private static Optional<String> run(String[] command, String stdin, Duration timeout,
                                        AtomicBoolean timedOut) {
        // Nothing reads standard error, and a full pipe would stall the command.
        ProcessBuilder pb = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            TimedProcess.Exit exit = TimedProcess.run(pb, stdin, timeout);
            return exit.code() == 0 ? Optional.of(exit.output()) : Optional.empty();
        } catch (TimeoutException e) {
            timedOut.set(true);
            log.warn("Secret backend command {} did not finish within {}; "
                    + "not running backend commands again until the app restarts",
                    command[0], timeout);
            return Optional.empty();
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
