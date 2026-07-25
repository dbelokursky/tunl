package com.vlessclient.platform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages a one-time {@code sudoers} NOPASSWD rule so sing-box can be
 * launched with root privileges (required for TUN mode) without prompting
 * for the admin password on every Connect.
 *
 * <p><b>What the rule authorizes.</b> The NOPASSWD rule points at a
 * <em>root-owned</em> copy of sing-box at {@link #ELEVATED_BINARY}
 * ({@code root:wheel}, mode 0755), not the user-writable binary under
 * {@code ~/Library}. The one-time privileged setup copies the current
 * sing-box there and writes:</p>
 *
 * <pre>{@code
 * dima ALL=(root) NOPASSWD: /usr/local/libexec/vless-client/sing-box
 * }</pre>
 *
 * <p><b>Why root-owned.</b> A rule pointing at a user-writable binary is a
 * silent local root escalation: any code running as the user could overwrite
 * that binary (or just pass their own arguments) and get arbitrary root via
 * {@code sudo -n}. Pinning the rule to a root-owned copy the user cannot
 * modify closes that. A changed sing-box (e.g. after an in-app core update)
 * no longer matches the root copy, so {@link #isConfigured} returns false and
 * the next Connect re-runs {@link #configure} — one admin prompt, which is
 * also the human checkpoint that gates <em>what</em> becomes root-runnable.</p>
 *
 * <p><b>Why the argument list is pinned too.</b> Authorizing the binary alone
 * ({@code NOPASSWD: .../sing-box}) lets any process running as the user pass
 * their <em>own</em> arguments — including {@code run -c attacker.json}, whose
 * {@code log.output} names any path on disk. That is a general
 * write-a-file-as-root primitive available without interaction once TUN has
 * been used once. The rule therefore pins the full command line, down to a
 * fixed config path ({@link #ELEVATED_CONFIG}); {@code sudo} matches arguments
 * literally, so any other invocation is refused and falls back to a password
 * prompt.</p>
 *
 * <p>The config lives in {@link #ELEVATED_RUN_DIR} — a user-owned 0700
 * directory inside the root-owned parent — so the app can still rewrite it per
 * connection without a prompt, but its <em>path</em> is not caller-controlled
 * and contains no spaces to escape in the sudoers line.</p>
 *
 * <p><b>Residual.</b> The user still controls the <em>contents</em> of that one
 * config, so code already running as the user can influence what the root
 * sing-box does while a TUN connection is being started. Closing that needs a
 * code-signed privileged helper that owns the config outright, which requires
 * notarization and is tracked separately. If installation is declined or fails,
 * the caller falls back to the osascript-per-connect path (password each time,
 * no standing rule).</p>
 */
public final class PrivilegeHelper {

    private static final Logger log = LoggerFactory.getLogger(PrivilegeHelper.class);

    private static final Path SUDOERS_FILE = Path.of("/etc/sudoers.d/vless-client");

    /** Root-owned copy of sing-box the sudoers rule authorizes for {@code sudo -n}. */
    static final Path ELEVATED_BINARY = Path.of("/usr/local/libexec/vless-client/sing-box");

    /** User-owned 0700 directory holding the one config the rule allows. */
    static final Path ELEVATED_RUN_DIR = Path.of("/usr/local/libexec/vless-client/run");

    /**
     * The only config path {@code sudo -n} will accept. Deliberately fixed and
     * space-free: it is written verbatim into the sudoers line, and pinning it
     * is what stops a caller from pointing root at a config of their choosing.
     */
    static final Path ELEVATED_CONFIG = ELEVATED_RUN_DIR.resolve("tun-config.json");

    private static final int CANARY_TIMEOUT_SECONDS = 3;
    private static final int CONFIGURE_TIMEOUT_SECONDS = 60;

    private PrivilegeHelper() {
    }

    /** The root-owned binary to invoke with {@code sudo -n} once configured. */
    public static Path elevatedBinary() {
        return ELEVATED_BINARY;
    }

    /**
     * The fixed config path the sudoers rule authorizes. Callers must copy the
     * generated config here before {@code sudo -n}; any other path is refused.
     */
    public static Path elevatedConfig() {
        return ELEVATED_CONFIG;
    }

    /**
     * Whether the NOPASSWD rule is active <em>and</em> the root-owned copy
     * still matches {@code userBinary}. A content mismatch (first run, or a
     * core update that rewrote the user binary) reports not-configured so the
     * caller re-runs {@link #configure} to refresh the privileged copy.
     *
     * @param userBinary the current (user-writable) sing-box binary
     * @return true if {@code sudo -n} can run the up-to-date root copy
     */
    public static boolean isConfigured(Path userBinary) {
        if (userBinary == null || !sudoCanRun(ELEVATED_BINARY)) {
            return false;
        }
        return sameContent(userBinary, ELEVATED_BINARY);
    }

    /**
     * Whether {@code sudo -n} would accept the exact TUN command line, asked
     * with {@code -l} so nothing is executed. A plain canary like
     * {@code sudo -n <binary> version} cannot be used any more: the rule pins
     * the argument list, so {@code version} is (correctly) refused.
     */
    private static boolean sudoCanRun(Path binary) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "sudo", "-n", "-l",
                    binary.toAbsolutePath().toString(),
                    "run", "-c", ELEVATED_CONFIG.toString());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            if (!proc.waitFor(CANARY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return false;
            }
            return proc.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * Installs the root-owned sing-box copy and the NOPASSWD rule pointing at
     * it, in one {@code osascript ... with administrator privileges} prompt.
     *
     * @param userBinary absolute path to the current sing-box executable
     * @throws IOException if the privileged step failed or sudoers validation
     *                     rejected the rule
     */
    public static void configure(Path userBinary) throws IOException {
        if (userBinary == null) {
            throw new IOException("null binary path");
        }
        String user = System.getProperty("user.name");
        if (user == null || user.isBlank()) {
            throw new IOException("cannot determine current user");
        }

        Path stage = Files.createTempFile("vless-sudoers-", ".tmp");
        try {
            Files.writeString(stage, sudoersRule(user));
            String shellCommand = configureShellCommand(userBinary, stage, user);

            ProcessBuilder pb = new ProcessBuilder(
                    "osascript",
                    "-e",
                    "do shell script \""
                            + shellCommand.replace("\\", "\\\\").replace("\"", "\\\"")
                            + "\" with administrator privileges");
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            boolean finished;
            try {
                finished = proc.waitFor(CONFIGURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                proc.destroyForcibly();
                throw new IOException("Interrupted while installing sudoers rule", e);
            }
            if (!finished) {
                proc.destroyForcibly();
                throw new IOException("Timed out installing sudoers rule");
            }
            int code = proc.exitValue();
            if (code != 0) {
                String output = new String(proc.getInputStream().readAllBytes());
                throw new IOException("osascript exited with code " + code + ": " + output);
            }
            log.info("Installed root-owned sing-box and NOPASSWD rule at {}", ELEVATED_BINARY);
        } finally {
            try {
                Files.deleteIfExists(stage);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /**
     * The sudoers line. Both the binary and its full argument list are fixed
     * constants — no user-controlled path is interpolated (so nothing needs
     * space-escaping), and sudo matches the arguments literally, so this
     * authorizes exactly one command and nothing else.
     */
    static String sudoersRule(String user) {
        return user + " ALL=(root) NOPASSWD: "
                + ELEVATED_BINARY + " run -c " + ELEVATED_CONFIG + "\n";
    }

    /**
     * The privileged shell command: install the current sing-box as a
     * root-owned copy, create the user-owned run directory that holds the one
     * authorized config, install the validated rule, and syntax-check it —
     * removing the rule if the check fails so sudo never gets wedged.
     *
     * <p>The run directory is 0700 and owned by {@code user} so the app can
     * rewrite the config per connection without another prompt, while its
     * root-owned parent keeps anyone else from swapping the directory itself.</p>
     */
    static String configureShellCommand(Path userBinary, Path stagedRule, String user) {
        String src = singleQuote(userBinary.toAbsolutePath().toString());
        String dst = singleQuote(ELEVATED_BINARY.toString());
        String dstDir = singleQuote(ELEVATED_BINARY.getParent().toString());
        String runDir = singleQuote(ELEVATED_RUN_DIR.toString());
        String owner = singleQuote(user);
        String rule = singleQuote(stagedRule.toAbsolutePath().toString());
        String target = singleQuote(SUDOERS_FILE.toString());
        return "mkdir -p " + dstDir
                + " && install -m 0755 -o root -g wheel " + src + " " + dst
                + " && install -d -m 0700 -o " + owner + " -g staff " + runDir
                + " && install -m 0440 -o root -g wheel " + rule + " " + target
                + " && visudo -c -f " + target
                + " || { rm -f " + target + "; exit 1; }";
    }

    /** SHA-256 equality of two files; false if either can't be read. */
    static boolean sameContent(Path a, Path b) {
        try {
            return Arrays.equals(sha256(a), sha256(b));
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return md.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static String singleQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
