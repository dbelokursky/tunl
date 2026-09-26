package com.vlessclient.platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
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
 * <p><b>Why a launcher, where there is one.</b> Pinning the path leaves the
 * <em>contents</em> of that config to the user, and a config alone makes root
 * write a file anywhere ({@code log.output}, the cache file, a rule set's
 * path), run a program (a tor outbound) or read a key: code running as the
 * user still had root without a prompt. Where macOS ships {@link #JQ} (15 and
 * later) the rule authorizes the root-owned {@link #LAUNCHER} instead, with no
 * arguments. It takes the config on stdin, so root never opens a path the user
 * controls, keeps what a Tunl TUN config is made of (see
 * {@code scripts/tun-launch.sh}), and runs the core on the result from a
 * directory only root can open. The launcher's content is part of what
 * {@link #isConfigured} checks, so a build that changes it sets it up again.
 * macOS 13 and 14 keep the pinned rule.</p>
 *
 * <p><b>Residual.</b> The user still picks what the connection is: a config
 * that passes the launcher still routes every user's traffic on this Mac
 * through a server of the caller's choosing, the way the app itself does.
 * Closing that needs a code-signed privileged helper that owns the config
 * outright, which requires notarization and is tracked separately. If
 * installation is declined or fails, the caller falls back to the
 * osascript-per-connect path (password each time, no standing rule).</p>
 */
public final class PrivilegeHelper {

    private static final Logger log = LoggerFactory.getLogger(PrivilegeHelper.class);

    private static final Path SUDOERS_FILE = Path.of("/etc/sudoers.d/vless-client");

    /** Root-owned directory containing the privileged core and run directory. */
    private static final Path ELEVATED_DIR = Path.of("/usr/local/libexec/vless-client");

    /**
     * Root-owned launcher the rule authorizes, with no arguments, where
     * {@link #JQ} is there: it filters the config it reads on stdin and runs
     * the core on the result.
     */
    static final Path LAUNCHER = ELEVATED_DIR.resolve("tun-launch");

    /** Root-only 0700 directory for the filtered config and the core's cache. */
    static final Path STATE_DIR = ELEVATED_DIR.resolve("state");

    /** What the launcher filters with; part of macOS since 15. */
    static final Path JQ = Path.of("/usr/bin/jq");

    /** The launcher's source; {@code @BASE@} stands for {@link #ELEVATED_DIR}. */
    private static final String LAUNCHER_SCRIPT = "/scripts/tun-launch.sh";

    /** Root-owned copy of sing-box the sudoers rule authorizes for {@code sudo -n}. */
    static final Path ELEVATED_BINARY = ELEVATED_DIR.resolve("sing-box");

    /** User-owned 0700 directory holding the one config the rule allows. */
    static final Path ELEVATED_RUN_DIR = ELEVATED_DIR.resolve("run");

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
     * Whether TUN starts through the launcher: whether this Mac has the
     * {@code jq} it filters with. Without it (macOS 13 and 14) the rule stays
     * the pinned command line.
     */
    public static boolean usesLauncher() {
        return Files.isExecutable(JQ);
    }

    /**
     * The root-owned launcher to run with {@code sudo -n} and the config on
     * stdin, once configured where {@link #usesLauncher()}.
     */
    public static Path launcher() {
        return LAUNCHER;
    }

    /**
     * The launcher as installed under {@code base}: the script with its base
     * directory filled in. Tests install it elsewhere; the app installs it
     * under {@link #ELEVATED_DIR}.
     *
     * @param base directory holding the launcher, the core and the state dir;
     *             must not contain a single quote, which the script quotes it in
     */
    static String launcherScript(Path base) {
        String dir = base.toString();
        if (dir.indexOf('\'') >= 0) {
            throw new IllegalArgumentException("unquotable launcher base: " + dir);
        }
        try (InputStream in = PrivilegeHelper.class.getResourceAsStream(LAUNCHER_SCRIPT)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + LAUNCHER_SCRIPT);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("@BASE@", dir);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + LAUNCHER_SCRIPT, e);
        }
    }

    /**
     * Whether the <em>pinned</em> NOPASSWD rule is active and the root-owned
     * copy still matches {@code userBinary}. A content mismatch (first run, or
     * a core update that rewrote the user binary) reports not-configured so the
     * caller re-runs {@link #configure} to refresh the privileged copy.
     *
     * <p>Checking only that the TUN command line is permitted is not enough to
     * decide the rule is the pinned one: the older, unrestricted rule
     * ({@code NOPASSWD: <binary>}) authorized the binary with <em>any</em>
     * arguments, so it permits the TUN line too and would read as configured.
     * An upgraded install would then never re-run {@code configure} and would
     * keep the wide rule forever — the hardening would silently never apply.
     * So an install counts as configured only when the wide form is also
     * <em>refused</em>.</p>
     *
     * <p>Where {@link #usesLauncher()}, the rule has to be the launcher's
     * instead, and the installed launcher has to be this build's: the pinned
     * rule is what the launcher replaces, so an install carrying it is set up
     * again, and so is one whose launcher an older build wrote.</p>
     *
     * @param userBinary the current (user-writable) sing-box binary
     * @return true if the expected rule is live and the root copies are current
     */
    public static boolean isConfigured(Path userBinary) {
        if (userBinary == null) {
            return false;
        }
        String listing = sudoListing();
        boolean ruleInPlace = usesLauncher()
                ? hasLauncherRule(listing) && launcherIsCurrent()
                : hasPinnedRule(listing);
        return ruleInPlace && sameContent(userBinary, ELEVATED_BINARY);
    }

    /**
     * Whether {@code listing} grants NOPASSWD to the launcher. The rule gives
     * it no arguments ({@code ""}); the listing may spell that quoted,
     * escaped or not at all, and all three are the launcher's rule: the
     * script reads nothing from its arguments.
     *
     * @param listing raw {@code sudo -n -l} output
     */
    static boolean hasLauncherRule(String listing) {
        if (listing == null) {
            return false;
        }
        String launcher = LAUNCHER.toString();
        for (String line : listing.lines().toList()) {
            String trimmed = line.trim();
            int marker = trimmed.indexOf("NOPASSWD:");
            if (marker < 0) {
                continue;
            }
            String authorized = trimmed.substring(marker + "NOPASSWD:".length()).trim();
            if (authorized.equals(launcher)
                    || authorized.equals(launcher + " \"\"")
                    || authorized.equals(launcher + " \\\"\\\"")) {
                return true;
            }
        }
        return false;
    }

    /** Whether the installed launcher is the one this build installs. */
    private static boolean launcherIsCurrent() {
        try {
            return Files.readString(LAUNCHER).equals(launcherScript(ELEVATED_DIR));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Whether {@code listing} grants NOPASSWD to exactly the pinned TUN command
     * line — the full argument list, not just the binary.
     *
     * <p>Asking {@code sudo -l <command>} whether a command is permitted cannot
     * answer this: on an admin account the blanket {@code (ALL) ALL} entry
     * permits everything (with a password), so that check succeeds no matter
     * what — it reports the wide rule, the pinned rule, and no rule at all as
     * equally fine. Parsing the NOPASSWD entry is what actually distinguishes
     * them.</p>
     *
     * @param listing raw {@code sudo -n -l} output
     */
    static boolean hasPinnedRule(String listing) {
        if (listing == null) {
            return false;
        }
        String expected = ELEVATED_BINARY + " run -c " + ELEVATED_CONFIG;
        for (String line : listing.lines().toList()) {
            String trimmed = line.trim();
            int marker = trimmed.indexOf("NOPASSWD:");
            if (marker < 0 || !trimmed.contains(ELEVATED_BINARY.toString())) {
                continue;
            }
            String authorized = trimmed.substring(marker + "NOPASSWD:".length()).trim();
            if (authorized.equals(expected)) {
                return true;
            }
            // The pre-hardening rule authorized the bare binary, i.e. any
            // arguments. Report not-configured so the caller re-runs configure
            // and the wide rule is replaced instead of living on forever.
            log.info("Found a pre-hardening sudoers entry ({}); the privileged setup "
                    + "will re-run to pin the command line", authorized);
            return false;
        }
        return false;
    }

    /**
     * Whether an older build's rule is installed that lets anything running as
     * the user run the core as root on a config of its choosing: the
     * pre-hardening rule (the binary with any arguments) anywhere, and the
     * pinned rule where the launcher can replace it.
     *
     * <p>Distinct from {@code !isConfigured()}, which is also true when no rule
     * exists at all. Only these cases are worth interrupting the user about at
     * startup: a standing rule that grants write-a-file-as-root to anything
     * running as them, which would otherwise sit there until they next use TUN.</p>
     */
    public static boolean hasRuleToReplace() {
        return hasRuleToReplace(sudoListing(), usesLauncher());
    }

    /** Test seam for {@link #hasRuleToReplace()}. */
    static boolean hasRuleToReplace(String listing, boolean launcherAvailable) {
        return hasLegacyWideRule(listing) || (launcherAvailable && hasPinnedRule(listing));
    }

    /**
     * Whether the pre-hardening rule — the one authorizing the binary with any
     * arguments — is still installed.
     */
    static boolean hasLegacyWideRule(String listing) {
        if (listing == null) {
            return false;
        }
        for (String line : listing.lines().toList()) {
            String trimmed = line.trim();
            int marker = trimmed.indexOf("NOPASSWD:");
            if (marker < 0 || !trimmed.contains(ELEVATED_BINARY.toString())) {
                continue;
            }
            String authorized = trimmed.substring(marker + "NOPASSWD:".length()).trim();
            return authorized.equals(ELEVATED_BINARY.toString());
        }
        return false;
    }

    /** Raw {@code sudo -n -l} output, or empty when sudo declines to answer. */
    private static String sudoListing() {
        try {
            ProcessBuilder pb = new ProcessBuilder("sudo", "-n", "-l");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            if (!proc.waitFor(CANARY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return "";
            }
            return new String(proc.getInputStream().readAllBytes());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "";
        }
    }

    /**
     * The AppleScript that runs {@code shellCommand} as root after the
     * administrator dialog. The dialog shows {@code prompt} as the reason it
     * asks; without one it said that "osascript" wanted to make changes.
     *
     * @param shellCommand the command for {@code do shell script}
     * @param prompt       why the dialog asks, or null or blank for none
     * @return the script for {@code osascript -e}
     */
    static String adminScript(String shellCommand, String prompt) {
        String script = "do shell script " + appleScriptString(shellCommand);
        if (prompt != null && !prompt.isBlank()) {
            script += " with prompt " + appleScriptString(prompt);
        }
        return script + " with administrator privileges";
    }

    /** An AppleScript string literal: backslashes and quotes escaped. */
    private static String appleScriptString(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * What a failed {@code osascript} run means: the user dismissing the
     * dialog, which AppleScript reports as error -128 in the system's
     * language, or a failure.
     *
     * @param code   osascript's exit code
     * @param output what it printed
     * @return the exception to throw
     */
    static IOException failure(int code, String output) {
        if (output != null && output.contains("(-128)")) {
            return new ElevationDeclinedException("the administrator prompt was dismissed");
        }
        return new IOException("osascript exited with code " + code + ": " + output);
    }

    /**
     * Installs the root-owned sing-box copy, the launcher where
     * {@link #usesLauncher()}, and the NOPASSWD rule, in one
     * {@code osascript ... with administrator privileges} prompt.
     *
     * @param userBinary absolute path to the current sing-box executable
     * @param prompt     why the administrator dialog asks, in the UI's language
     * @throws ElevationDeclinedException if the user dismissed the dialog
     * @throws IOException if the privileged step failed or sudoers validation
     *                     rejected the rule
     */
    public static void configure(Path userBinary, String prompt) throws IOException {
        if (userBinary == null) {
            throw new IOException("null binary path");
        }
        String user = System.getProperty("user.name");
        if (user == null || user.isBlank()) {
            throw new IOException("cannot determine current user");
        }

        // No staging file. The rule is generated by root inside the privileged
        // shell (see configureShellCommand): a user-owned staged file could be
        // rewritten to `user ALL=(ALL) NOPASSWD: ALL` in the window between our
        // write and root's install, and visudo -c checks syntax, not content.
        boolean launcher = usesLauncher();
        String shellCommand = configureShellCommand(userBinary, user, launcher);

        ProcessBuilder pb = new ProcessBuilder(
                "osascript", "-e", adminScript(shellCommand, prompt));
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
            throw failure(code, new String(proc.getInputStream().readAllBytes()));
        }
        log.info("Installed root-owned sing-box and the NOPASSWD rule for {}",
                launcher ? LAUNCHER : ELEVATED_BINARY);
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
     * The sudoers line where {@link #usesLauncher()}: the launcher, with no
     * arguments ({@code ""} is how sudoers says so). The config travels on
     * stdin, which sudo leaves to the caller and the launcher filters.
     */
    static String launcherRule(String user) {
        return user + " ALL=(root) NOPASSWD: " + LAUNCHER + " \"\"\n";
    }

    /**
     * The privileged shell command: install the current sing-box as a
     * root-owned copy, create the user-owned run directory that holds the one
     * authorized config, generate the NOPASSWD rule <em>as root</em>, validate
     * it with {@code visudo -c}, and install it — removing anything half-written
     * on failure so sudo never gets wedged.
     *
     * <p><b>Why root generates the rule.</b> The rule is a fixed constant plus
     * the (single-quoted) username, so root can write it itself. Staging it as a
     * user-owned file and having root {@code install} that file blindly was a
     * TOCTOU local root escalation: any process running as the user could
     * rewrite the staged file to {@code user ALL=(ALL) NOPASSWD: ALL} in the
     * up-to-60s window before the admin prompt was approved, and {@code visudo
     * -c} validates syntax, not content. Root creates its own {@code mktemp}
     * file (unpredictable name, root-owned), writes the rule, validates that
     * temp, and only then installs it — nothing user-writable is trusted.</p>
     *
     * <p>The run directory is 0700 and owned by {@code user} so the app can
     * rewrite the config per connection without another prompt, while its
     * root-owned parent keeps anyone else from swapping the directory itself.</p>
     *
     * <p>With {@code launcher}, root also writes the launcher from the text
     * carried in the command itself (base64, so no quoting can bend it) into
     * its own {@code mktemp} file, installs it root:wheel 0755, creates the
     * root-only state directory, and the rule authorizes the launcher.</p>
     */
    static String configureShellCommand(Path userBinary, String user, boolean launcher) {
        String src = singleQuote(userBinary.toAbsolutePath().toString());
        String dst = singleQuote(ELEVATED_BINARY.toString());
        String dstDir = singleQuote(ELEVATED_DIR.toString());
        String runDir = singleQuote(ELEVATED_RUN_DIR.toString());
        String owner = singleQuote(user);
        String target = singleQuote(SUDOERS_FILE.toString());
        // echo re-adds the trailing newline the rule carries; the line has no
        // backslashes (fixed POSIX paths + username), so /bin/sh's echo emits
        // it verbatim. Single-quoting the whole line neutralizes any character
        // in the username.
        String rule = launcher ? launcherRule(user) : sudoersRule(user);
        String ruleLiteral = singleQuote(rule.stripTrailing());
        String installLauncher = "";
        String temps = "\"$STAGE\"";
        if (launcher) {
            String script = Base64.getEncoder().encodeToString(
                    launcherScript(ELEVATED_DIR).getBytes(StandardCharsets.UTF_8));
            installLauncher = " && LAUNCH=\"$(mktemp)\""
                    + " && echo " + singleQuote(script) + " | /usr/bin/base64 -D > \"$LAUNCH\""
                    + " && install -m 0755 -o root -g wheel \"$LAUNCH\" "
                    + singleQuote(LAUNCHER.toString())
                    + " && install -d -m 0700 -o root -g wheel "
                    + singleQuote(STATE_DIR.toString());
            temps += " \"$LAUNCH\"";
        }
        return "mkdir -p " + dstDir
                + " && install -m 0755 -o root -g wheel " + src + " " + dst
                + " && install -d -m 0700 -o " + owner + " -g staff " + runDir
                + " && umask 077 && STAGE=\"$(mktemp)\""
                + installLauncher
                + " && echo " + ruleLiteral + " > \"$STAGE\""
                + " && visudo -c -f \"$STAGE\""
                + " && install -m 0440 -o root -g wheel \"$STAGE\" " + target
                + " || { rm -f " + target + " " + temps + " 2>/dev/null; exit 1; }; "
                + "rm -f " + temps + " 2>/dev/null";
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
