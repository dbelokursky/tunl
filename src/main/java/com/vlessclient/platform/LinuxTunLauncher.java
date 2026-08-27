package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 * <p>Both paths spawn the same shell wrapper honoring the
 * {@link TunLauncher} contract: it forwards the core's output, watches the
 * stop-signal file and the app's pid, and terminates sing-box when either
 * fires. pkexec forwards stdio, so live logs work in the fallback too.</p>
 */
public final class LinuxTunLauncher implements TunLauncher {

    private static final Logger log = LoggerFactory.getLogger(LinuxTunLauncher.class);

    private final CommandRunner runner;
    private final String elevator;

    public LinuxTunLauncher() {
        this(CommandRunner.system(), "pkexec");
    }

    /** Test seam: inject the capability-command runner and the elevation binary. */
    LinuxTunLauncher(CommandRunner runner, String elevator) {
        this.runner = runner;
        this.elevator = elevator;
    }

    @Override
    public Launched launch(Path binary, Path configFile) throws IOException {
        Path stopSignalFile = Path.of(System.getProperty("java.io.tmpdir"),
                "vless-client-stop-" + System.nanoTime() + ".signal");
        Files.deleteIfExists(stopSignalFile);

        if (!hasNetAdminCapability(binary)) {
            grantNetAdminCapability(binary);
        }

        boolean direct = hasNetAdminCapability(binary);
        String wrapper = wrapperCommand(binary, configFile, stopSignalFile);
        ProcessBuilder pb = direct
                ? new ProcessBuilder("/bin/sh", "-c", wrapper)
                : new ProcessBuilder(elevator, "/bin/sh", "-c", wrapper);
        pb.directory(SecureFiles.parentDirectory(binary).toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        log.info("Started sing-box TUN via {}", direct
                ? "cap_net_admin fast path (no elevation prompt)"
                : elevator + " wrapper (PolicyKit prompt expected)");
        return new Launched(process, stopSignalFile);
    }

    /**
     * True when the binary already carries {@code cap_net_admin} — the xattr
     * survives restarts but not file replacement (core updates).
     */
    boolean hasNetAdminCapability(Path binary) {
        try {
            CommandRunner.Result result = runner.run(List.of(
                    "getcap", binary.toAbsolutePath().toString()));
            return result.exitCode() == 0 && result.output().contains("cap_net_admin");
        } catch (IOException e) {
            log.debug("getcap unavailable: {}", e.getMessage());
            return false;
        }
    }

    /**
     * One-time grant via PolicyKit; declining just leaves the fallback path.
     */
    private void grantNetAdminCapability(Path binary) {
        try {
            CommandRunner.Result result = runner.run(List.of(
                    elevator, "setcap", "cap_net_admin+ep",
                    binary.toAbsolutePath().toString()));
            if (result.exitCode() == 0) {
                log.info("Granted cap_net_admin to {} (one-time setup)", binary);
            } else {
                log.warn("setcap declined or failed (exit {}): falling back to "
                        + "per-connect elevation", result.exitCode());
            }
        } catch (IOException e) {
            log.warn("Could not run {} setcap: {}", elevator, e.getMessage());
        }
    }

    /**
     * The wrapper both paths share: start the core, watch the stop file and
     * the app's pid, terminate the core when either fires. Mirrors the macOS
     * wrappers' contract.
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
        // first, then the shell exits through the loop as usual.
        return String.format(
                "%s run -c %s & SBPID=$!; "
                        + "trap 'kill $SBPID 2>/dev/null; exit 0' EXIT INT TERM; "
                        + "while kill -0 $SBPID 2>/dev/null "
                        + "&& kill -0 %d 2>/dev/null "
                        + "&& [ ! -f %s ]; do sleep 0.3; done; "
                        + "kill $SBPID 2>/dev/null; "
                        + "wait $SBPID 2>/dev/null; "
                        + "rm -f %s",
                singBoxCmd, configPath, parentPid, stopPath, stopPath);
    }

    /**
     * Wraps {@code s} in single quotes, escaping any embedded single quotes.
     * Safe for embedding into an {@code sh -c} command.
     */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
