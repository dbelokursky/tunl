package com.vlessclient.platform;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Linux TUN launcher. Preference order:
 *
 * <ol>
 *   <li><b>Fast path: file capabilities.</b> One-time
 *       {@code pkexec setcap cap_net_admin+ep <managed sing-box>} (a single
 *       PolicyKit prompt, ever) lets the core create its TUN device and
 *       install routes as a normal user process — no wrapper privileges, live
 *       logs, graceful termination. The capability is an xattr and vanishes
 *       when a core update replaces the file, so it is re-checked (and
 *       re-granted with one new prompt) on every TUN connect.</li>
 *   <li><b>Fallback: {@code pkexec} per connect.</b> If the grant is declined
 *       or unavailable, the whole wrapper runs under pkexec — a PolicyKit
 *       prompt on every Connect, same trade-off as the macOS osascript
 *       fallback.</li>
 * </ol>
 *
 * <p>Both paths spawn a shell wrapper honoring the {@link TunLauncher}
 * contract: it forwards the core's output and terminates sing-box when the app
 * stops it or dies. The fast path's wrapper runs as the user and watches its
 * stdin ({@link StdinWatch}); the fallback's runs as root, and polls the
 * stop-signal file and the app's pid. pkexec forwards stdio, so live logs work
 * in the fallback too.</p>
 */
public final class LinuxTunLauncher implements TunLauncher {

    private static final Logger log = LoggerFactory.getLogger(LinuxTunLauncher.class);

    /**
     * The capability grant waits on a PolicyKit password prompt. Connecting
     * runs off the JavaFX thread, so a long wait holds no window, while a grant
     * cut short only brings the fallback's own prompt right after it.
     */
    private static final Duration PROMPT_TIMEOUT = Duration.ofMinutes(5);

    /**
     * pkexec's exit status for an authentication dialog the user dismissed,
     * apart from 127 for one that failed or was not allowed.
     */
    public static final int PKEXEC_DISMISSED = 126;

    /**
     * Where getcap and setcap are looked for. They live in /usr/sbin (libcap2-bin),
     * which a desktop user's PATH on Debian does not hold: found by name, getcap
     * did not run, the core read as having no capability, pkexec could not find
     * setcap either, and every connect raised a PolicyKit prompt.
     */
    private static final List<String> TOOL_DIRS = List.of("/usr/sbin", "/sbin", "/usr/bin", "/bin");

    private final CommandRunner runner;
    private final String elevator;
    private final List<Path> toolDirs;

    public LinuxTunLauncher() {
        this(CommandRunner.system(PROMPT_TIMEOUT), "pkexec");
    }

    /** Test seam: inject the capability-command runner and the elevation binary. */
    LinuxTunLauncher(CommandRunner runner, String elevator) {
        this(runner, elevator, TOOL_DIRS.stream().map(Path::of).toList());
    }

    /** Test seam: also where getcap and setcap are looked for. */
    LinuxTunLauncher(CommandRunner runner, String elevator, List<Path> toolDirs) {
        this.runner = runner;
        this.elevator = elevator;
        this.toolDirs = List.copyOf(toolDirs);
    }

    /**
     * {@inheritDoc}
     *
     * <p>PolicyKit shows a text of its own, so {@code prompt} goes unused.</p>
     */
    @Override
    public Launched launch(Path binary, Path configFile, Prompt prompt) throws IOException {
        // Owner-only and unguessable, where java.io.tmpdir is usually the
        // shared /tmp; the root-side wrapper can read it there.
        Path stopSignalFile = StopSignals.newStopSignalFile();

        // Granted only to a core this user owns and alone can run. The binary
        // can be a system-wide one found on the PATH, root-owned and 0755:
        // the chmod fails there, and setcap as root then made CAP_NET_ADMIN
        // available to every account on the machine, with no prompt. A grant
        // whose result getcap cannot show would be a prompt for nothing.
        if (restrictToOwner(binary) && tool("getcap").isPresent()
                && !hasNetAdminCapability(binary)) {
            grantNetAdminCapability(binary);
        }

        boolean direct = hasNetAdminCapability(binary);
        // An interrupt is the start being called off (at quit the recovery
        // scheduler and the MCP server interrupt their threads). It used to
        // fail getcap and the grant, which read as "no capability", and the
        // launch went on to the fallback: a PolicyKit prompt as the app quit.
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("the TUN start was called off");
        }
        String wrapper = direct
                ? directWrapperCommand(binary, configFile)
                : wrapperCommand(binary, configFile, stopSignalFile);
        ProcessBuilder pb = direct
                ? new ProcessBuilder("/bin/sh", "-c", wrapper)
                : new ProcessBuilder(elevator, "/bin/sh", "-c", wrapper);
        pb.directory(SecureFiles.parentDirectory(binary).toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        log.info("Started sing-box TUN via {}", direct
                ? "cap_net_admin fast path (no elevation prompt)"
                : elevator + " wrapper (PolicyKit prompt expected)");
        return new Launched(process, stopSignalFile, !direct);
    }

    /**
     * Keeps the core executable by its owner alone. The capability the
     * one-time setcap grants goes to whoever runs the binary, and it was
     * world-executable: on a shared machine another user could run it with a
     * config of their own and hold CAP_NET_ADMIN, with no prompt. A chmod
     * leaves the capability in place, so an install from before is closed too.
     *
     * @param binary the core
     * @return false when the core could not be restricted, as for one this
     *         user does not own; a file system with no permissions to set
     *         counts as restricted
     */
    static boolean restrictToOwner(Path binary) {
        try {
            Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
            return true;
        } catch (UnsupportedOperationException e) {
            return true;
        } catch (IOException e) {
            log.warn("Could not restrict {} to its owner, so it gets no capability: {}",
                    binary, e.getMessage());
            return false;
        }
    }

    /**
     * True when the binary already carries {@code cap_net_admin} — the xattr
     * survives restarts but not file replacement (core updates).
     */
    boolean hasNetAdminCapability(Path binary) throws InterruptedIOException {
        Optional<Path> getcap = tool("getcap");
        if (getcap.isEmpty()) {
            log.debug("getcap not found in {}", toolDirs);
            return false;
        }
        try {
            CommandRunner.Result result = runner.run(List.of(
                    getcap.get().toString(), binary.toAbsolutePath().toString()));
            return result.exitCode() == 0 && result.output().contains("cap_net_admin");
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            log.debug("getcap unavailable: {}", e.getMessage());
            return false;
        }
    }

    /** {@code name} in the first of the tool directories that has it, by absolute path. */
    private Optional<Path> tool(String name) {
        return toolDirs.stream()
                .map(dir -> dir.resolve(name))
                .filter(Files::isExecutable)
                .findFirst();
    }

    /**
     * One-time grant via PolicyKit. A failure leaves the fallback path; a
     * dismissed dialog is the user cancelling the connect, which the
     * fallback's own prompt used to follow at once.
     */
    private void grantNetAdminCapability(Path binary) throws IOException {
        Optional<Path> setcap = tool("setcap");
        if (setcap.isEmpty()) {
            log.warn("setcap not found in {}: falling back to per-connect elevation", toolDirs);
            return;
        }
        try {
            CommandRunner.Result result = runner.run(List.of(
                    elevator, setcap.get().toString(), "cap_net_admin+ep",
                    binary.toAbsolutePath().toString()));
            if (result.exitCode() == 0) {
                log.info("Granted cap_net_admin to {} (one-time setup)", binary);
            } else if (result.exitCode() == PKEXEC_DISMISSED) {
                throw new ElevationDeclinedException("the PolicyKit prompt was dismissed");
            } else {
                log.warn("setcap failed (exit {}): falling back to per-connect elevation",
                        result.exitCode());
            }
        } catch (ElevationDeclinedException | InterruptedIOException calledOff) {
            throw calledOff;
        } catch (IOException e) {
            log.warn("Could not run {} setcap: {}", elevator, e.getMessage());
        }
    }

    /**
     * The fast path's wrapper: the core runs as the user, with the capability,
     * under a wrapper that waits on it and watches its own stdin instead of
     * polling.
     */
    static String directWrapperCommand(Path binary, Path configFile) {
        return StdinWatch.around(shellQuote(binary.toAbsolutePath().toString())
                + " run -c " + shellQuote(configFile.toAbsolutePath().toString()));
    }

    /**
     * The fallback's wrapper, run as root under pkexec: start the core, watch
     * the stop file and the app's pid, terminate the core when either fires.
     * Mirrors the macOS osascript wrapper.
     */
    static String wrapperCommand(Path binary, Path configFile, Path stopSignalFile) {
        long parentPid = ProcessHandle.current().pid();
        String singBoxCmd = shellQuote(binary.toAbsolutePath().toString());
        String configPath = shellQuote(configFile.toAbsolutePath().toString());
        String stopPath = shellQuote(stopSignalFile.toAbsolutePath().toString());

        // Trap TERM/INT as well as EXIT: the engine stops us with
        // Process.destroy() (SIGTERM), and dash does NOT run an EXIT-only trap
        // when killed by a signal — it would die mid-`sleep`, leaving sing-box
        // orphaned with the TUN still up. Handling TERM/INT kills the core
        // first, then the shell exits through the loop as usual. The trap is
        // set before the core starts: a signal between the two used to kill
        // the shell with the core already running.
        // The core gets the app user's ids, as sudo hands them over: sing-box
        // then gives what it creates (its cache file) to that user. pkexec
        // sets none, so the root core's cache file was root's, and every
        // later start through the capability, as the user, failed on it with
        // "permission denied" until the file was removed by hand. It exits
        // with the core's status and waits for the core on a signal; see
        // StdinWatch#around.
        // The stop file is named through SBSTOP: written into the trap, its
        // quoted path closed the trap's own quotes, and a space in it
        // ($XDG_DATA_HOME or $HOME) split the trap command. dash refused the
        // whole trap, and a SIGTERM then killed the shell with the root core
        // still running.
        return String.format(
                "SBSTOP=%s; "
                        + StdinWatch.SIGNAL_TRAP + "rm -f \"$SBSTOP\"; exit 143' INT TERM; "
                        + StdinWatch.EXIT_TRAP
                        + "%s%s run -c %s & SBPID=$!; "
                        + "while kill -0 $SBPID 2>/dev/null "
                        + "&& kill -0 %d 2>/dev/null "
                        + "&& [ ! -f \"$SBSTOP\" ]; do sleep 0.3; done; "
                        + "kill $SBPID 2>/dev/null; "
                        + "wait $SBPID 2>/dev/null; RC=$?; "
                        + "rm -f \"$SBSTOP\"; exit $RC",
                stopPath, appUserIds(), singBoxCmd, configPath, parentPid);
    }

    /**
     * The app user's ids as sudo would hand them to the core, for a root core
     * started by pkexec: {@code "SUDO_UID=1000 SUDO_GID=1000 "}, or empty when
     * they cannot be read.
     */
    static String appUserIds() {
        return userIds(Path.of("/proc/" + ProcessHandle.current().pid() + "/status"));
    }

    /**
     * The real user and group ids in a {@code /proc/PID/status} file, as
     * {@link #appUserIds()} gives them.
     *
     * @param status the file to read
     * @return the assignments, or empty when the file does not hold both ids
     */
    static String userIds(Path status) {
        String uid = null;
        String gid = null;
        try {
            for (String line : Files.readAllLines(status)) {
                if (line.startsWith("Uid:")) {
                    uid = firstId(line);
                } else if (line.startsWith("Gid:")) {
                    gid = firstId(line);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read the app's ids from {}: {}", status, e.toString());
        }
        return uid != null && gid != null ? "SUDO_UID=" + uid + " SUDO_GID=" + gid + " " : "";
    }

    /** The first id on a "Uid:" or "Gid:" line, the real one; null when it is not a number. */
    private static String firstId(String line) {
        String[] ids = line.substring(4).strip().split("\\s+");
        return ids[0].matches("\\d+") ? ids[0] : null;
    }

    /**
     * Wraps {@code s} in single quotes, escaping any embedded single quotes.
     * Safe for embedding into an {@code sh -c} command.
     */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
