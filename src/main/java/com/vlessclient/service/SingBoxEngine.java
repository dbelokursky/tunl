package com.vlessclient.service;

import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.platform.SecureFiles;
import com.vlessclient.platform.SystemProxyGuard;
import com.vlessclient.platform.TunLauncher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manages the sing-box process lifecycle: starting, stopping, and monitoring
 * the external sing-box binary as a child process.
 *
 * <p>This service writes a temporary configuration file, launches sing-box via
 * {@link ProcessBuilder}, captures log output, and exposes connection state
 * as JavaFX observable properties suitable for UI binding.</p>
 */
public class SingBoxEngine {

    private static final Logger log = LoggerFactory.getLogger(SingBoxEngine.class);

    private static final int MAX_LOG_LINES = 1000;
    private static final int STOP_TIMEOUT_SECONDS = 5;

    private final Path singBoxBinary;
    private final ObservableList<String> logLines;
    private final ReadOnlyObjectWrapper<ConnectionState> connectionState;
    private final ReadOnlyStringWrapper errorMessage;

    /**
     * Serializes the process lifecycle. start(), stop() and forceStop() run
     * their whole body under this monitor so the check-and-launch in start()
     * is atomic. Three threads call start()/stop() — the dashboard's virtual
     * thread, the tray's FX thread and the MCP worker — and an unguarded
     * isRunning() check-then-act let two of them both observe "not running",
     * both launch a core, and orphan the first: it would keep the SOCKS/HTTP
     * ports and, in SYSTEM_PROXY mode, the OS proxy registration, invisible to
     * stop() and the shutdown hook (both only ever act on the tracked process).
     * Holding the lock also publishes the non-volatile session fields below
     * across the caller threads.
     */
    private final Object lifecycle = new Object();

    // Written by start()/stop()/forceStop() while holding `lifecycle` and read
    // from the monitor/watchdog daemon threads. `process` is volatile so those
    // threads (and the lock-free isRunning()) see the current session's process
    // or its absence; the rest are captured into locals under the lock before
    // any monitor thread is spawned, so they need no separate publication.
    private volatile Process process;
    private Path tempConfigFile;
    private Path stopSignalFile;
    private LogReader logReader;
    private ProxyMode activeProxyMode;

    /**
     * The local endpoint sing-box registered as the OS proxy
     * ({@code set_system_proxy}), or null when the active config doesn't use
     * it. After the process dies, {@link SystemProxyGuard} checks this
     * endpoint and clears a stale OS proxy entry the core couldn't restore
     * (Windows kills are always hard; crashes skip cleanup on any OS).
     */
    private volatile SystemProxyTarget systemProxyTarget;
    private SystemProxyGuard systemProxyGuard = SystemProxyGuard.current();
    private TunLauncher tunLauncher = TunLauncher.current();

    /** Listen endpoint of the inbound that carries {@code set_system_proxy}. */
    record SystemProxyTarget(String host, int port) {
    }

    /**
     * Set before tearing the process down so the process monitor can tell a
     * user-initiated stop from a crash. Without it, the monitor's exit
     * handler can observe the still-CONNECTING/CONNECTED state before stop()
     * publishes DISCONNECTED and misreport the shutdown as ERROR.
     */
    private volatile boolean stopRequested;

    /**
     * Creates a new SingBoxEngine.
     *
     * @param singBoxBinary path to the sing-box executable
     */
    public SingBoxEngine(Path singBoxBinary) {
        this.singBoxBinary = singBoxBinary;
        this.logLines = FXCollections.observableArrayList();
        this.connectionState = new ReadOnlyObjectWrapper<>(ConnectionState.DISCONNECTED);
        this.errorMessage = new ReadOnlyStringWrapper("");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (isRunning()) {
                forceStop();
            }
        }, "singbox-shutdown-hook"));
    }

    /**
     * Starts sing-box with the given configuration JSON.
     *
     * <p>The configuration is written to a temporary file and sing-box is launched
     * with {@code run -c <config-path>}. Log output is captured in a background
     * thread and appended to the observable log lines list.</p>
     *
     * <p>When proxy mode is TUN, sing-box is started with elevated privileges
     * through the platform {@link TunLauncher} (sudo/osascript on macOS, UAC
     * on Windows), since creating a TUN device requires administrator
     * rights.</p>
     *
     * @param configJson the sing-box configuration in JSON format
     * @param proxyMode  the proxy mode determining how sing-box is started
     * @throws IOException          if the config file cannot be written or the process cannot start
     * @throws IllegalStateException if sing-box is already running
     */
    public void start(String configJson, ProxyMode proxyMode) throws IOException {
        // The whole check-and-launch runs under `lifecycle`: without it two of
        // the three caller threads could both pass the isRunning() guard and
        // launch a second core, orphaning the first.
        synchronized (lifecycle) {
            startLocked(configJson, proxyMode);
        }
    }

    /**
     * Starts sing-box with the given configuration JSON using SYSTEM_PROXY mode.
     *
     * @param configJson the sing-box configuration in JSON format
     * @throws IOException          if the config file cannot be written or the process cannot start
     * @throws IllegalStateException if sing-box is already running
     */
    public void start(String configJson) throws IOException {
        start(configJson, ProxyMode.SYSTEM_PROXY);
    }

    private void startLocked(String configJson, ProxyMode proxyMode) throws IOException {
        if (isRunning()) {
            throw new IllegalStateException("sing-box is already running");
        }

        // Retire the previous session's identity token FIRST. A crashed
        // process leaves the field populated (stop() never ran), and the
        // resources below are created before the new process exists — a
        // stale monitor waking inside that window must see that its session
        // is over, or it would fire a spurious ERROR into this one.
        this.process = null;

        this.activeProxyMode = proxyMode;
        this.stopRequested = false;

        Platform.runLater(() -> {
            connectionState.set(ConnectionState.CONNECTING);
            errorMessage.set("");
            logLines.clear();
        });

        tempConfigFile = Files.createTempFile(
                Path.of(System.getProperty("java.io.tmpdir")),
                "singbox-",
                ".json"
        );
        Files.writeString(tempConfigFile, configJson);
        systemProxyTarget = extractSystemProxyTarget(configJson);

        // A launch that throws leaves no process, so the monitor below never
        // runs and nothing else would remove the config we just wrote — and it
        // carries the server's credentials. stop() cannot cover this either:
        // it returns early when nothing is running.
        try {
            if (proxyMode == ProxyMode.TUN) {
                startWithPrivileges();
            } else {
                startDirect();
            }
        } catch (IOException | RuntimeException e) {
            cleanupConfigFile();
            if (proxyMode == ProxyMode.TUN) {
                // startWithPrivileges may have published its copy before failing.
                tunLauncher.cleanupSession();
            }
            throw e;
        }

        // The "started" promotion only applies while THIS session is still
        // coming up: a buffered log line delivered after a stop() or crash
        // must not flip a DISCONNECTED/ERROR UI back to CONNECTED.
        Process sessionProcess = process;
        logReader = new LogReader(
                sessionProcess.getInputStream(),
                logLines,
                MAX_LOG_LINES,
                line -> Platform.runLater(() -> {
                    if (!stopRequested && sessionProcess == process
                            && connectionState.get() == ConnectionState.CONNECTING) {
                        connectionState.set(ConnectionState.CONNECTED);
                    }
                })
        );
        logReader.start();

        // In TUN mode the launcher's wrapper may buffer or delay the core's
        // stdout (osascript buffers until the script exits; the Windows
        // outer script polls log files). LogReader may never see the
        // "started" line in real time, so the UI would otherwise be stuck on
        // CONNECTING forever. Promote to CONNECTED after a short delay as
        // long as the process is still alive.
        if (proxyMode == ProxyMode.TUN) {
            startTunConnectedWatchdog();
        }

        startProcessMonitor();
    }

    private static final long TUN_CONNECTED_DELAY_MS = 1800;

    private void startTunConnectedWatchdog() {
        // Same session-capture discipline as the process monitor: a stale
        // watchdog outliving its session must not promote the next one.
        Process proc = process;
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(TUN_CONNECTED_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Platform.runLater(() -> {
                if (proc != null && proc == process && proc.isAlive()
                        && connectionState.get() == ConnectionState.CONNECTING) {
                    connectionState.set(ConnectionState.CONNECTED);
                }
            });
        }, "singbox-tun-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /**
     * Starts sing-box directly without privilege elevation.
     */
    private void startDirect() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                singBoxBinary.toAbsolutePath().toString(),
                "run",
                "-c",
                tempConfigFile.toAbsolutePath().toString()
        );
        pb.directory(SecureFiles.parentDirectory(singBoxBinary).toFile());
        pb.redirectErrorStream(true);

        process = pb.start();
    }

    /**
     * Starts sing-box with the elevated privileges TUN mode needs, through
     * the platform's {@link TunLauncher} (sudo-NOPASSWD/osascript on macOS,
     * UAC elevation on Windows). The launcher hands back an unprivileged
     * observer process — its stdout carries the core's logs and its lifetime
     * mirrors the core's — plus the stop-signal file that asks the
     * privileged side to shut sing-box down.
     */
    private void startWithPrivileges() throws IOException {
        TunLauncher.Launched launched = tunLauncher.launch(singBoxBinary, tempConfigFile);
        process = launched.process();
        stopSignalFile = launched.stopSignalFile();
    }

    /**
     * Stops the running sing-box process gracefully.
     *
     * <p>Sends SIGTERM via {@link Process#destroy()}, waits up to 5 seconds for
     * the process to exit, then force-kills it if still running. Cleans up the
     * temporary configuration file.</p>
     */
    public void stop() {
        synchronized (lifecycle) {
            stopLocked();
        }
    }

    private void stopLocked() {
        stopRequested = true;
        if (!isRunning()) {
            // A crashed core leaves the dead process in the field; retire it
            // so the next start() doesn't inherit a stale session token.
            process = null;
            Platform.runLater(() -> connectionState.set(ConnectionState.DISCONNECTED));
            return;
        }

        if (logReader != null) {
            logReader.stop();
            logReader = null;
        }

        Process p = process;
        if (activeProxyMode == ProxyMode.TUN) {
            stopPrivilegedProcess();
        } else if (p != null) {
            p.destroy();
            try {
                if (!p.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    p.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }

        process = null;
        activeProxyMode = null;
        cleanupConfigFile();
        Platform.runLater(() -> connectionState.set(ConnectionState.DISCONNECTED));
    }

    /**
     * Stops a sing-box process that was started with administrator privileges.
     *
     * <p>Instead of shelling out to {@code pkill} with another
     * privilege-escalation prompt, we signal the root-owned wrapper shell by
     * creating the stop-signal file. The wrapper's watch loop sees it and
     * terminates sing-box, then the outer osascript process exits on its own.
     * No password prompt.</p>
     */
    private void stopPrivilegedProcess() {
        Process p = process;
        try {
            if (stopSignalFile != null) {
                try {
                    Files.createFile(stopSignalFile);
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // Already signalled — the wrapper will notice regardless.
                }
            }
            if (p != null) {
                if (!p.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    p.waitFor(2, TimeUnit.SECONDS);
                }
            }
        } catch (IOException | InterruptedException e) {
            if (p != null) {
                p.destroyForcibly();
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            if (stopSignalFile != null) {
                try {
                    Files.deleteIfExists(stopSignalFile);
                } catch (IOException ignored) {
                    // best effort
                }
                stopSignalFile = null;
            }
        }
    }

    /**
     * Checks whether the sing-box process is currently alive.
     *
     * @return true if the process is running
     */
    public boolean isRunning() {
        // Single read of the volatile field: a concurrent stop() nulling it
        // between a check and a use must not turn this into an NPE.
        Process p = process;
        return p != null && p.isAlive();
    }

    /**
     * Blocks until the core is no longer running, or {@code timeout} elapses.
     *
     * <p>A stop can take seconds — a SIGTERM grace period, then a force-kill —
     * so a reconnect (server switch, health-check auto-reconnect) that started
     * immediately after {@link #stop()} would hit "already running". Callers on
     * the connect path wait here first. Must not be called on the JavaFX thread:
     * it sleeps.</p>
     *
     * @param timeout how long to wait
     * @return true if the core is stopped by the deadline
     */
    public boolean awaitStopped(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (isRunning() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return !isRunning();
            }
        }
        return !isRunning();
    }

    /**
     * Returns the observable list of log lines captured from sing-box stdout/stderr.
     * The list retains at most 1000 lines (oldest lines are removed first).
     *
     * @return the observable log lines list
     */
    public ObservableList<String> getLogLines() {
        return logLines;
    }

    /**
     * Returns a read-only property reflecting the current connection state.
     *
     * @return the connection state property
     */
    public ReadOnlyObjectProperty<ConnectionState> connectionStateProperty() {
        return connectionState.getReadOnlyProperty();
    }

    /**
     * Returns a read-only property containing the last error message, if any.
     *
     * @return the error message property
     */
    public ReadOnlyStringProperty errorMessageProperty() {
        return errorMessage.getReadOnlyProperty();
    }

    /**
     * Starts a daemon thread that monitors the sing-box process and detects
     * unexpected exits (crashes). On unexpected exit, sets the connection state
     * to ERROR with the last log line as the error message.
     */
    private void startProcessMonitor() {
        // Capture THIS session's state up front. The fields are cleared by
        // stop() and reassigned by the next start(), and this thread may get
        // its first CPU slice only after that: re-reading them from the
        // thread body was an NPE (dead monitor, no ERROR transition), and a
        // stale monitor's cleanup could delete the NEW session's config file
        // or clear its OS proxy. A monitor that only ever touches its own
        // captured session cannot race the field lifecycle at all.
        Process proc = process;
        Path sessionConfigFile = tempConfigFile;
        SystemProxyTarget sessionProxyTarget = systemProxyTarget;
        boolean sessionUsedTun = activeProxyMode == ProxyMode.TUN;
        if (proc == null) {
            return;
        }
        Thread monitor = new Thread(() -> {
            try {
                int exitCode = proc.waitFor();
                Platform.runLater(() -> {
                    if (!stopRequested
                            && proc == process
                            && connectionState.get() != ConnectionState.DISCONNECTED) {
                        String lastLine = logLines.isEmpty()
                                ? "sing-box exited with code " + exitCode
                                : logLines.getLast();
                        // Message before state: state listeners fire
                        // synchronously inside set(), and they read the
                        // message the moment they see ERROR.
                        errorMessage.set("Process exited unexpectedly (code "
                                + exitCode + "): " + lastLine);
                        connectionState.set(ConnectionState.ERROR);
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                // Every exit path of OUR process (stop, hard kill, crash)
                // funnels through here. Deleting our own config file is
                // always safe — temp names are unique per session. The OS
                // proxy is cleared only while no newer session has taken
                // over: a reconnect typically reuses the same local port, and
                // the successor's live proxy must not be ripped out from
                // under it.
                try {
                    Files.deleteIfExists(sessionConfigFile);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
                Process current = process;
                boolean noSuccessor = current == null || current == proc;
                if (sessionProxyTarget != null && noSuccessor) {
                    systemProxyGuard.clearIfPointsAt(
                            sessionProxyTarget.host(), sessionProxyTarget.port());
                }
                // The launcher's published config carries credentials and,
                // unlike sessionConfigFile above, lives at a fixed path — so
                // it needs the same successor guard as the OS proxy: a stale
                // monitor must not delete the config a newer session is
                // running on.
                if (sessionUsedTun && noSuccessor) {
                    tunLauncher.cleanupSession();
                }
            }
        }, "singbox-process-monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    /**
     * Clears an OS proxy entry still pointing at the dead core's inbound.
     * No-op when the config didn't use {@code set_system_proxy} or when
     * sing-box already restored the previous state on a graceful exit.
     */
    private void restoreSystemProxyIfNeeded() {
        SystemProxyTarget target = systemProxyTarget;
        if (target == null) {
            return;
        }
        systemProxyTarget = null;
        systemProxyGuard.clearIfPointsAt(target.host(), target.port());
    }

    /**
     * Finds the listen endpoint of the first inbound carrying
     * {@code set_system_proxy: true}, or null when the config has none.
     */
    static SystemProxyTarget extractSystemProxyTarget(String configJson) {
        try {
            JsonNode inbounds = JsonMapper.builder().build().readTree(configJson).path("inbounds");
            for (JsonNode inbound : inbounds) {
                if (inbound.path("set_system_proxy").asBoolean(false)) {
                    return new SystemProxyTarget(
                            inbound.path("listen").asString("127.0.0.1"),
                            inbound.path("listen_port").asInt());
                }
            }
        } catch (JacksonException e) {
            log.debug("Could not parse config for set_system_proxy", e);
        }
        return null;
    }

    /**
     * Clears an OS proxy left pointing at our local endpoint by a previous
     * run that died before it could restore it — a hard app crash, SIGKILL or
     * power loss runs no shutdown hook, and unlike a TUN interface (reclaimed
     * by the kernel) the proxy setting persists in the registry/gsettings/
     * networksetup across reboots, stranding the machine behind a dead proxy.
     *
     * <p>Call once at startup, before any auto-connect. Safe: the guard only
     * acts when the OS proxy still points at {@code host:port}, so a user or
     * corporate proxy is never touched, and it is skipped while the core is
     * running so a live proxy is never disabled.</p>
     */
    public void clearStaleSystemProxyOnStartup(String host, int port) {
        if (isRunning()) {
            return;
        }
        systemProxyGuard.clearIfPointsAt(host, port);
    }

    /** Test seam: replaces the OS proxy guard. */
    void setSystemProxyGuard(SystemProxyGuard guard) {
        this.systemProxyGuard = guard;
    }

    /** Test seam: replaces the privileged TUN launcher. */
    void setTunLauncher(TunLauncher launcher) {
        this.tunLauncher = launcher;
    }

    /**
     * Force-stops the sing-box process without state transitions.
     * Used by the JVM shutdown hook.
     */
    private void forceStop() {
        // Runs from the JVM shutdown hook. Taking `lifecycle` keeps it from
        // racing a start()/stop() in flight; the wait is bounded (stop() blocks
        // at most STOP_TIMEOUT_SECONDS+2), and the external quit killers halt
        // the JVM if a start() is parked on an elevation prompt.
        synchronized (lifecycle) {
            forceStopLocked();
        }
    }

    private void forceStopLocked() {
        stopRequested = true;
        // TUN mode: signal the wrapper via the stop file so it kills the
        // root-owned sing-box gracefully. Parent-PID watch in the wrapper
        // also catches this case, but touching the file is faster.
        if (stopSignalFile != null) {
            try {
                Files.createFile(stopSignalFile);
            } catch (IOException ignored) {
                // already exists or can't create — best effort
            }
        }
        Process p = process;
        try {
            if (p != null && p.isAlive()) {
                p.destroy();
                if (!p.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        if (stopSignalFile != null) {
            try {
                Files.deleteIfExists(stopSignalFile);
            } catch (IOException ignored) {
                // best effort
            }
            stopSignalFile = null;
        }
        cleanupConfigFile();
        // The JVM is going down: the daemon process monitor may never get to
        // run its own restore, so clear a stale OS proxy entry synchronously.
        restoreSystemProxyIfNeeded();
    }

    /**
     * Deletes the temporary configuration file if it exists.
     */
    private void cleanupConfigFile() {
        if (tempConfigFile != null) {
            try {
                Files.deleteIfExists(tempConfigFile);
            } catch (IOException e) {
                // Best effort cleanup; ignore
            }
            tempConfigFile = null;
        }
    }
}
