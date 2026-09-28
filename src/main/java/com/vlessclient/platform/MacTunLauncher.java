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
 *       current user and runs the root side with {@code sudo -n}, so no
 *       password prompt appears: the launcher with the config on stdin where
 *       {@link PrivilegeHelper#usesLauncher()}, {@code sing-box run -c} on
 *       the published config otherwise. Stop is signalled by closing the
 *       wrapper's stdin, which the app holds and the system closes when the
 *       app dies.</li>
 *   <li>Fallback: if NOPASSWD is not available (e.g. user declined the
 *       one-time configure step), spawn the wrapper inside
 *       {@code osascript ... with administrator privileges}. A password
 *       prompt appears on every Connect.</li>
 * </ol>
 */
public final class MacTunLauncher implements TunLauncher {

    private static final Logger log = LoggerFactory.getLogger(MacTunLauncher.class);

    @Override
    public Launched launch(Path binary, Path configFile, Prompt prompt) throws IOException {
        // Owner-only and unguessable; the wrapper runs as this user (sudo
        // path) or as root (osascript path), and both can read it there.
        Path stopSignalFile = StopSignals.newStopSignalFile();

        // Try to install the sudoers rule on first run (one password prompt,
        // ever). If it's already installed this is a fast no-op. A dismissed
        // dialog is the user cancelling the connect: the every-connect prompt
        // used to follow it at once, asking again for what was just declined.
        if (!PrivilegeHelper.isConfigured(binary)) {
            try {
                PrivilegeHelper.configure(binary, prompt.setup());
            } catch (ElevationDeclinedException declined) {
                throw declined;
            } catch (IOException e) {
                log.warn("Could not install sudoers NOPASSWD rule, "
                        + "falling back to osascript prompt: {}", e.getMessage());
            }
        }

        boolean withoutPrompt = PrivilegeHelper.isConfigured(binary);
        Process process;
        if (!withoutPrompt) {
            process = startViaOsascriptPrompt(binary, configFile, stopSignalFile,
                    prompt.eachConnect());
        } else if (PrivilegeHelper.usesLauncher()) {
            process = startViaLauncher(binary, configFile);
        } else {
            process = startViaSudoNoPassword(binary, configFile);
        }
        return new Launched(process, stopSignalFile, !withoutPrompt);
    }

    /**
     * Deletes the published config. It lives at a fixed path so the sudoers
     * rule can pin it, which means it is not self-cleaning the way a
     * per-session temp file is: without this it would sit on disk with the
     * server's credentials until the next connect overwrote it — surviving
     * disconnect, app exit and reboot.
     *
     * <p>The run dir is user-owned, so no privileges are needed to remove it.
     * The launcher path publishes nothing; this still removes a copy an
     * earlier connection on the pinned rule left.</p>
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
     * Starts sing-box through the root-owned launcher via {@code sudo -n} — no
     * password prompt. The wrapper's shell, running as the user, opens the
     * generated config and hands it to the launcher on stdin; the launcher
     * filters it and runs the core on what is left. No copy is published: the
     * rule does not name a config path, so the engine's own file serves.
     */
    private Process startViaLauncher(Path binary, Path configFile) throws IOException {
        String shellCommand = launcherWrapperCommand(PrivilegeHelper.launcher(), configFile);

        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", shellCommand);
        pb.directory(SecureFiles.parentDirectory(binary).toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        log.info("Started sing-box via the TUN launcher (no password prompt)");
        return process;
    }

    /**
     * The wrapper for the launcher path: the same watch as
     * {@link #sudoWrapperCommand}, around {@code sudo -n <launcher> < config}.
     * The launcher replaces itself with the core, so the process sudo forwards
     * TERM/INT to is the core itself.
     */
    static String launcherWrapperCommand(Path launcher, Path configFile) {
        return StdinWatch.around("sudo -n " + shellQuote(launcher.toString())
                + " < " + shellQuote(configFile.toAbsolutePath().toString()));
    }

    /**
     * Starts sing-box via {@code sudo -n} — no password prompt. Requires the
     * sudoers NOPASSWD rule installed by {@link PrivilegeHelper#configure}.
     * The wrapper itself runs as the current user; only the sing-box child
     * process is root, which means we can still observe its output through
     * the normal Process pipes and stop it by signalling the user-owned
     * wrapper (who forwards SIGTERM to the root-owned sing-box via sudo).
     */
    private Process startViaSudoNoPassword(Path binary, Path configFile) throws IOException {
        // The rule pins the config path, so the generated config has to be
        // published at that exact location; any other path is refused by sudo.
        // Written 0600 in the user-owned run dir — it carries the server's
        // credentials.
        Path published = publishConfig(configFile);
        String shellCommand = sudoWrapperCommand(PrivilegeHelper.elevatedBinary(), published);

        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", shellCommand);
        pb.directory(SecureFiles.parentDirectory(binary).toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        log.info("Started sing-box via sudo -n (no password prompt)");
        return process;
    }

    /**
     * The wrapper for the sudo-NOPASSWD path. The NOPASSWD rule authorizes the
     * root-owned copy, not the user-writable binary, so the core is invoked
     * under {@code sudo -n}; sudo forwards TERM/INT to it, so killing the
     * user-owned sudo propagates to root-owned sing-box cleanly.
     *
     * <p>Tears the core down when the app stops it, when the app dies and when
     * the core exits on its own; see {@link StdinWatch}. A hard app
     * death (SIGKILL, a crash: no shutdown hook, no stop) must not leave a
     * root-owned core holding the TUN up. Trap TERM/INT as well as EXIT: the
     * engine's last resort is SIGTERM, and a signal-killed shell skips an
     * EXIT-only trap. The trap is set before the core starts, so no signal
     * can land between the two and leave the core orphaned.</p>
     */
    static String sudoWrapperCommand(Path elevatedBinary, Path publishedConfig) {
        return StdinWatch.around("sudo -n "
                + shellQuote(elevatedBinary.toAbsolutePath().toString())
                + " run -c " + shellQuote(publishedConfig.toString()));
    }

    /**
     * Legacy fallback: launch sing-box inside an osascript admin-privileges
     * context. Prompts for a password on every Connect. Used only when
     * NOPASSWD configuration is unavailable (e.g. the user cancelled the
     * one-time install dialog).
     */
    private Process startViaOsascriptPrompt(Path binary, Path configFile,
                                            Path stopSignalFile, String prompt)
            throws IOException {
        String shellCommand = osascriptWrapperCommand(binary, configFile, stopSignalFile);

        ProcessBuilder pb = new ProcessBuilder(
                "osascript", "-e", PrivilegeHelper.adminScript(shellCommand, prompt));
        pb.directory(SecureFiles.parentDirectory(binary).toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        log.info("Started sing-box via osascript (password prompt expected)");
        return process;
    }

    /**
     * The wrapper for the osascript fallback (the core runs as root because the
     * whole script is elevated). It still polls, see {@link #watched}: a shell
     * that {@code do shell script} starts gets no stdin from the app, and the
     * app cannot signal a root process.
     */
    static String osascriptWrapperCommand(Path binary, Path configFile,
                                          Path stopSignalFile) {
        return watched(shellQuote(binary.toAbsolutePath().toString())
                        + " run -c " + shellQuote(configFile.toAbsolutePath().toString()),
                stopSignalFile);
    }

    /**
     * Runs {@code start} in the background and tears it down when the core
     * exits, the stop file appears, or the app's pid is gone, checking every
     * 0.3 s. Only for a wrapper that runs as root with no stdin from the app;
     * see {@link StdinWatch} for the others. The trap is set before
     * the core starts, so no signal can land between the two and leave the
     * core orphaned.
     */
    private static String watched(String start, Path stopSignalFile) {
        long parentPid = ProcessHandle.current().pid();
        String stopPath = shellQuote(stopSignalFile.toAbsolutePath().toString());

        return String.format(
                "trap 'kill ${SBPID:-$!} 2>/dev/null; exit 0' EXIT INT TERM; "
                        + "%s & SBPID=$!; "
                        + "while kill -0 $SBPID 2>/dev/null "
                        + "&& kill -0 %d 2>/dev/null "
                        + "&& [ ! -f %s ]; do sleep 0.3; done; "
                        + "kill $SBPID 2>/dev/null; "
                        + "wait $SBPID 2>/dev/null; "
                        + "rm -f %s",
                start, parentPid, stopPath, stopPath);
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
        SecureFiles.createPrivateDir(SecureFiles.parentDirectory(target));
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
