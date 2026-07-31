package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * macOS TUN launcher. Two code paths:
 *
 * <ol>
 *   <li>Preferred: sudoers NOPASSWD is already installed (one-time setup by
 *       {@link PrivilegeHelper}). The wrapper is spawned directly as the
 *       current user and invokes {@code sudo -n sing-box run -c ...}, so no
 *       password prompt appears. Stop is signalled via the user-writable
 *       stop file.</li>
 *   <li>Fallback: if NOPASSWD is not available (e.g. user declined the
 *       one-time configure step), spawn the wrapper inside
 *       {@code osascript ... with administrator privileges}. A password
 *       prompt appears on every Connect.</li>
 * </ol>
 */
public final class MacTunLauncher implements TunLauncher {

    private static final Logger log = LoggerFactory.getLogger(MacTunLauncher.class);

    @Override
    public Launched launch(Path binary, Path configFile) throws IOException {
        // Use /tmp so both root and the user can read/write the signal file.
        Path stopSignalFile = Path.of("/tmp",
                "vless-client-stop-" + System.nanoTime() + ".signal");
        Files.deleteIfExists(stopSignalFile);

        // Try to install the sudoers rule on first run (one password prompt,
        // ever). If it's already installed this is a fast no-op.
        if (!PrivilegeHelper.isConfigured(binary)) {
            try {
                PrivilegeHelper.configure(binary);
            } catch (IOException e) {
                log.warn("Could not install sudoers NOPASSWD rule, "
                        + "falling back to osascript prompt: {}", e.getMessage());
            }
        }

        Process process = PrivilegeHelper.isConfigured(binary)
                ? startViaSudoNoPassword(binary, configFile, stopSignalFile)
                : startViaOsascriptPrompt(binary, configFile, stopSignalFile);
        return new Launched(process, stopSignalFile);
    }

    /**
     * Deletes the published config. It lives at a fixed path so the sudoers
     * rule can pin it, which means it is not self-cleaning the way a
     * per-session temp file is: without this it would sit on disk with the
     * server's credentials until the next connect overwrote it — surviving
     * disconnect, app exit and reboot.
     *
     * <p>The run dir is user-owned, so no privileges are needed to remove it.</p>
     */
    @Override
    public void cleanupSession() {
        Path published = PrivilegeHelper.elevatedConfig();
        try {
            if (Files.deleteIfExists(published)) {
                log.debug("Removed the published TUN config at {}", published);
            }
        } catch (IOException e) {
            log.warn("Could not remove the published TUN config at {}: {}",
                    published, e.getMessage());
        }
    }

    /**
     * Starts sing-box via {@code sudo -n} — no password prompt. Requires the
     * sudoers NOPASSWD rule installed by {@link PrivilegeHelper#configure}.
     * The wrapper itself runs as the current user; only the sing-box child
     * process is root, which means we can still observe its output through
     * the normal Process pipes and stop it by signalling the user-owned
     * wrapper (who forwards SIGTERM to the root-owned sing-box via sudo).
     */
    private Process startViaSudoNoPassword(Path binary, Path configFile,
                                           Path stopSignalFile) throws IOException {
        // The NOPASSWD rule authorizes the root-owned copy, not the
        // user-writable binary — invoke that path under sudo -n.
        String singBoxCmd = shellQuote(
                PrivilegeHelper.elevatedBinary().toAbsolutePath().toString());
        // The rule pins the config path as well, so the generated config has to
        // be published at that exact location; any other path is refused by
        // sudo. Written 0600 in the user-owned run dir — it carries the
        // server's credentials.
        Path published = publishConfig(configFile);
        String configPath = shellQuote(published.toString());
        String stopPath = shellQuote(stopSignalFile.toAbsolutePath().toString());

        // sudo forwards TERM/INT to its child (sing-box), so killing the
        // user-owned sudo propagates to root-owned sing-box cleanly. Trap
        // TERM/INT (not just EXIT): the engine stops us with SIGTERM, and a
        // signal-killed shell skips an EXIT-only trap, orphaning the core.
        String shellCommand = String.format(
                "sudo -n %s run -c %s & SBPID=$!; "
                        + "trap 'kill $SBPID 2>/dev/null; exit 0' EXIT INT TERM; "
                        + "while kill -0 $SBPID 2>/dev/null "
                        + "&& [ ! -f %s ]; do sleep 0.3; done; "
                        + "kill $SBPID 2>/dev/null; "
                        + "wait $SBPID 2>/dev/null; "
                        + "rm -f %s",
                singBoxCmd, configPath, stopPath, stopPath);

        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", shellCommand);
        pb.directory(binary.getParent().toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        log.info("Started sing-box via sudo -n (no password prompt)");
        return process;
    }

    /**
     * Legacy fallback: launch sing-box inside an osascript admin-privileges
     * context. Prompts for a password on every Connect. Used only when
     * NOPASSWD configuration is unavailable (e.g. the user cancelled the
     * one-time install dialog).
     */
    private Process startViaOsascriptPrompt(Path binary, Path configFile,
                                            Path stopSignalFile) throws IOException {
        long parentPid = ProcessHandle.current().pid();

        String singBoxCmd = shellQuote(binary.toAbsolutePath().toString());
        String configPath = shellQuote(configFile.toAbsolutePath().toString());
        String stopPath = shellQuote(stopSignalFile.toAbsolutePath().toString());

        String shellCommand = String.format(
                "%s run -c %s & SBPID=$!; "
                        + "trap 'kill $SBPID 2>/dev/null; exit 0' EXIT INT TERM; "
                        + "while kill -0 $SBPID 2>/dev/null "
                        + "&& kill -0 %d 2>/dev/null "
                        + "&& [ ! -f %s ]; do sleep 0.3; done; "
                        + "kill $SBPID 2>/dev/null; "
                        + "wait $SBPID 2>/dev/null; "
                        + "rm -f %s",
                singBoxCmd, configPath, parentPid, stopPath, stopPath);

        ProcessBuilder pb = new ProcessBuilder(
                "osascript",
                "-e",
                "do shell script \"" + shellCommand.replace("\\", "\\\\")
                        .replace("\"", "\\\"") + "\" with administrator privileges"
        );
        pb.directory(binary.getParent().toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        log.info("Started sing-box via osascript (password prompt expected)");
        return process;
    }

    /**
     * Copies the generated config to the one path the sudoers rule authorizes,
     * replacing whatever the previous connection left there.
     *
     * <p>The destination directory is created by the privileged setup step as
     * user-owned 0700; it is recreated here only as a best-effort fallback so a
     * missing directory surfaces as a normal start failure (and the osascript
     * path) rather than an opaque sudo refusal.</p>
     *
     * @return the published config path, ready to pass to {@code sudo -n}
     */
    private static Path publishConfig(Path configFile) throws IOException {
        Path target = PrivilegeHelper.elevatedConfig();
        SecureFiles.createPrivateDir(target.getParent());
        SecureFiles.writePrivately(target, Files.readAllBytes(configFile));
        return target;
    }

    /**
     * Wraps {@code s} in single quotes, escaping any embedded single quotes.
     * Safe for embedding into an {@code sh -c} command.
     */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
