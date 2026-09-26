package com.vlessclient.service;

import com.vlessclient.app.I18n;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.platform.CoreRecord;
import com.vlessclient.platform.SecureFiles;
import com.vlessclient.platform.SystemProxyGuard;
import com.vlessclient.platform.TunLauncher;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
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

    /**
     * The core's executable, or null until one is installed. Written under
     * {@link #lifecycle}, so that one start reads one path throughout.
     */
    private volatile Path singBoxBinary;

    /** Where a direct core is written down for the next run to find; null writes nothing. */
    private final CoreRecord coreRecord;

    private final ObservableList<String> logLines;
    private final ReadOnlyObjectWrapper<ConnectionState> connectionState;
    private final ReadOnlyStringWrapper errorMessage;
    private final ReadOnlyStringWrapper errorDetail = new ReadOnlyStringWrapper("");

    /**
     * What the core logs when a REALITY server answered as the site it poses
     * as: current Xray (26.7 and later) turns away a client that is not a
     * recent Xray, and 26.9 also one without the post-quantum key share, and
     * sing-box sends neither. Counted on the tunnel's own traffic only, a
     * "connection: open connection … using …" line, and not on a probe of a
     * group member, so a "Fastest" group carrying traffic on a member that
     * works raises nothing.
     */
    private static final java.util.regex.Pattern REALITY_REFUSED = java.util.regex.Pattern
            .compile("connection: open connection .* reality verification failed");

    private final ReadOnlyBooleanWrapper realityRefused = new ReadOnlyBooleanWrapper();

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
     * across the caller threads. The process monitor takes it too, to decide
     * whether a newer session has started and to clean up after its own, so no
     * start runs in between.
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
    /** This session's entry in {@link #coreRecord}, removed once its core has stopped. */
    private CoreRecord.Entry recordedCore;

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
    private SingBoxConfigCheck configCheck =
            new SingBoxConfigCheck(SingBoxConfigCheck.DEFAULT_TIMEOUT);

    /** Listen endpoint of the inbound that carries {@code set_system_proxy}. */
    record SystemProxyTarget(String host, int port) {
    }

    /** The core's clash API {@code /version} endpoint and the secret it expects. */
    record Controller(URI version, String secret) {
    }

    /**
     * Set before tearing the process down so the process monitor can tell a
     * user-initiated stop from a crash. Without it, the monitor's exit
     * handler can observe the still-CONNECTING/CONNECTED state before stop()
     * publishes DISCONNECTED and misreport the shutdown as ERROR.
     */
    private volatile boolean stopRequested;

    /**
     * The last start was a TUN launch through an elevation prompt that every
     * launch raises again; see {@link #restartNeedsElevationPrompt()}.
     */
    private volatile boolean launchPrompts;

    /** The last run ended with the user dismissing its administrator prompt. */
    private volatile boolean exitDeclined;

    /**
     * Creates a new SingBoxEngine that writes its core down nowhere.
     *
     * @param singBoxBinary path to the sing-box executable, or null for an
     *                      engine with no core yet
     */
    public SingBoxEngine(Path singBoxBinary) {
        this(singBoxBinary, null);
    }

    /**
     * Creates a new SingBoxEngine.
     *
     * <p>One engine lasts the whole run. When no core is installed the app
     * starts with an engine that has none, and the installer gives it the
     * core it downloads ({@link #setBinary}). The tray, the dashboard, the
     * log page and the MCP server keep following the same engine throughout.
     * They used to be moved one by one to a new engine made after the
     * install, and each one missed was a view stuck on an engine without a
     * core.</p>
     *
     * @param singBoxBinary path to the sing-box executable, or null for an
     *                      engine with no core yet
     * @param coreRecord    where each direct core is written down, so the next
     *                      run can end one this run leaves running; null for
     *                      nowhere
     */
    public SingBoxEngine(Path singBoxBinary, CoreRecord coreRecord) {
        this.singBoxBinary = singBoxBinary;
        this.coreRecord = coreRecord;
        this.logLines = FXCollections.observableArrayList();
        this.connectionState = new ReadOnlyObjectWrapper<>(ConnectionState.DISCONNECTED);
        this.errorMessage = new ReadOnlyStringWrapper("");
        // The list is written on the FX thread, one batch of the reader at a
        // time, and cleared at each start, so this reads one run's lines.
        logLines.addListener((ListChangeListener<String>) change -> {
            while (!realityRefused.get() && change.next()) {
                if (change.wasAdded() && change.getAddedSubList().stream()
                        .anyMatch(SingBoxEngine::isRealityRefusal)) {
                    realityRefused.set(true);
                }
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (isRunning()) {
                forceStop();
            }
        }, "singbox-shutdown-hook"));
    }

    /**
     * An engine with no core, which cannot start: what the app has until the
     * installer gives it one, and a view's stand-in when no engine is
     * registered.
     *
     * @return a new engine without a core
     */
    public static SingBoxEngine withoutCore() {
        return new SingBoxEngine(null, null);
    }

    /**
     * Whether the engine has a core to start.
     *
     * @return false until a core is installed
     */
    public boolean hasBinary() {
        return singBoxBinary != null;
    }

    /**
     * Gives the engine the core the installer downloaded, for every start from
     * now on.
     *
     * @param binary path to the sing-box executable
     */
    public void setBinary(Path binary) {
        Objects.requireNonNull(binary, "binary");
        synchronized (lifecycle) {
            singBoxBinary = binary;
        }
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
     * <p>Before either launch, {@code sing-box check} validates the written file
     * ({@code SingBoxConfigCheck}): a configuration the core refuses is never
     * launched, so no elevation prompt appears for it.</p>
     *
     * @param configJson the sing-box configuration in JSON format
     * @param proxyMode  the proxy mode determining how sing-box is started
     * @throws IOException          if the config file cannot be written, the core refuses it
     *                              ({@link ConfigRejectedException}), the calling thread is
     *                              interrupted before the launch
     *                              ({@link InterruptedIOException}), or the process cannot
     *                              start
     * @throws IllegalStateException if sing-box is already running, or there is
     *                               no core to start ({@link #hasBinary})
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
     * @throws IOException          if the config file cannot be written, the core refuses it
     *                              ({@link ConfigRejectedException}), the calling thread is
     *                              interrupted before the launch
     *                              ({@link InterruptedIOException}), or the process cannot
     *                              start
     * @throws IllegalStateException if sing-box is already running
     */
    public void start(String configJson) throws IOException {
        start(configJson, ProxyMode.SYSTEM_PROXY);
    }

    private void startLocked(String configJson, ProxyMode proxyMode) throws IOException {
        if (isRunning()) {
            throw new IllegalStateException("sing-box is already running");
        }
        if (singBoxBinary == null) {
            throw new IllegalStateException("no sing-box core is installed");
        }

        // Retire the previous session's identity token FIRST. A crashed
        // process leaves the field populated (stop() never ran), and the
        // resources below are created before the new process exists — a
        // stale monitor waking inside that window must see that its session
        // is over, or it would fire a spurious ERROR into this one.
        this.process = null;
        this.recordedCore = null;

        this.activeProxyMode = proxyMode;
        this.stopRequested = false;
        this.launchPrompts = false;
        this.exitDeclined = false;

        Platform.runLater(() -> {
            connectionState.set(ConnectionState.CONNECTING);
            errorMessage.set("");
            errorDetail.set("");
            logLines.clear();
            realityRefused.set(false);
        });

        tempConfigFile = Files.createTempFile(
                Path.of(System.getProperty("java.io.tmpdir")),
                "singbox-",
                ".json"
        );
        Files.writeString(tempConfigFile, configJson);

        // Ask the core first: a configuration it refuses has to fail here,
        // before a TUN start raises the administrator or UAC prompt for nothing.
        try {
            String refusal = configCheck.rejection(singBoxBinary, tempConfigFile).orElse(null);
            if (refusal != null) {
                throw new ConfigRejectedException(refusal);
            }
            // The check hides an interrupt that cut it short. Whoever
            // interrupted this start is abandoning it (at quit the recovery
            // scheduler and the MCP server interrupt their threads), and a
            // core launched now would run on with nothing left to stop it.
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("interrupted before sing-box was launched");
            }
        } catch (IOException | RuntimeException e) {
            cleanupConfigFile();
            publishNotStarted();
            throw e;
        }
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
            publishNotStarted();
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

        // The "started" line is only the fast path. In TUN mode the launcher's
        // wrapper may buffer or delay the core's stdout (osascript buffers
        // until the script exits; the Windows outer script polls log files),
        // and in any mode a core logging at "warn" or "error" never prints
        // the line at all: it is an INFO line. The UI would otherwise be stuck
        // on CONNECTING for the whole session; the watchdog promotes the
        // session once the core answers instead.
        startConnectedWatchdog(extractController(configJson));

        startProcessMonitor();
    }

    private static final long CONNECTED_FALLBACK_DELAY_MS = 1800;
    /** How long an exited core's last output may still take to be read. */
    private static final Duration LAST_LINES_WAIT = Duration.ofSeconds(2);
    private static final Duration CONTROLLER_PROBE_TIMEOUT = Duration.ofSeconds(1);
    private static final long CONTROLLER_PROBE_INTERVAL_MS = 200;
    private static final ObjectMapper CONTROLLER_JSON = JsonMapper.builder().build();

    /**
     * Promotes the session to CONNECTED once its core answers, whatever the
     * core writes to its log.
     *
     * <p>A process being alive says nothing about the tunnel. In TUN mode the
     * process is the launcher, not the core: on Windows it is the wrapper
     * waiting on the UAC prompt, alive for as long as the prompt stays open.
     * And a core at log level "warn" or "error" prints no "started" line, so
     * outside TUN the log is no answer either. The watchdog asks the core's
     * clash API controller instead ({@link #coreAnswers}). The controller
     * opens only once every inbound, the TUN adapter included, has started,
     * and the requests leave nothing in the core's log, where a bare connect
     * to the http inbound logs an error. A config without a controller falls
     * back to the process still being alive after
     * {@code CONNECTED_FALLBACK_DELAY_MS}.</p>
     */
    private void startConnectedWatchdog(Controller controller) {
        // Same session-capture discipline as the process monitor: a stale
        // watchdog outliving its session must not promote the next one.
        Process proc = process;
        Thread watchdog = new Thread(() -> {
            try {
                if (controller == null) {
                    Thread.sleep(CONNECTED_FALLBACK_DELAY_MS);
                } else if (!awaitController(proc, controller)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Platform.runLater(() -> {
                if (!stopRequested && proc != null && proc == process && proc.isAlive()
                        && connectionState.get() == ConnectionState.CONNECTING) {
                    connectionState.set(ConnectionState.CONNECTED);
                }
            });
        }, "singbox-connected-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /**
     * Polls the controller while {@code proc} is this engine's session and no
     * stop was asked for.
     *
     * @return true once {@link #coreAnswers} does
     */
    private boolean awaitController(Process proc, Controller controller)
            throws InterruptedException {
        try (HttpClient client = controllerProbeClient()) {
            while (!stopRequested && proc != null && proc == process && proc.isAlive()) {
                if (coreAnswers(client, controller)) {
                    return true;
                }
                Thread.sleep(CONTROLLER_PROBE_INTERVAL_MS);
            }
        }
        return false;
    }

    /** The client the watchdog probes with: the controller is on loopback, so no proxy. */
    static HttpClient controllerProbeClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(CONTROLLER_PROBE_TIMEOUT)
                .build();
    }

    /**
     * Asks the controller once whether this config's core is up.
     *
     * <p>Only a 200 for the config's secret that names a sing-box version
     * counts, so a program already on the port that checks another secret, or
     * is not sing-box, does not; nor does this app run's previous core while
     * it shuts down, since every core gets a secret of its own. One can still
     * pass for the core: a sing-box without a secret. It holds the port, so
     * this core cannot bind it and the session ends in ERROR moments later.</p>
     *
     * @return true when the core answers
     */
    static boolean coreAnswers(HttpClient client, Controller controller)
            throws InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(controller.version())
                .timeout(CONTROLLER_PROBE_TIMEOUT);
        if (!controller.secret().isEmpty()) {
            request.header("Authorization", "Bearer " + controller.secret());
        }
        try {
            HttpResponse<String> answer =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return answer.statusCode() == 200
                    && CONTROLLER_JSON.readTree(answer.body()).path("version").asString("")
                            .startsWith("sing-box");
        } catch (IOException | JacksonException notTheCore) {
            // Not listening yet, or not a clash API.
            return false;
        }
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

        Process started = pb.start();
        process = started;
        if (coreRecord != null) {
            recordedCore = coreRecord.write(started.toHandle());
        }
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
        TunLauncher.Launched launched = tunLauncher.launch(singBoxBinary, tempConfigFile,
                new TunLauncher.Prompt(I18n.get("tun.prompt.setup"),
                        I18n.get("tun.prompt.connect")));
        process = launched.process();
        stopSignalFile = launched.stopSignalFile();
        launchPrompts = launched.promptsEachLaunch();
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
            forgetRecordedCore();
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
        forgetRecordedCore();
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
     * Whether a stop has been asked for and the core has not exited yet: the
     * one case in which a connect should wait for this process to go away.
     *
     * @return true while a stop is under way
     */
    public boolean isStopping() {
        return stopRequested && isRunning();
    }

    /**
     * Whether the last run ended because the user dismissed the administrator
     * prompt its start raised, which leaves the engine DISCONNECTED rather
     * than in ERROR. Set before that state change, and cleared by the next
     * start.
     *
     * @return true when the last exit was a dismissed prompt
     */
    public boolean lastExitWasDeclined() {
        return exitDeclined;
    }

    /**
     * Whether starting the core again would ask the user for elevation: the
     * last start was a TUN launch through a prompt that every launch raises
     * again ({@link TunLauncher.Launched#promptsEachLaunch()}).
     *
     * @return true when a restart would raise the prompt again
     */
    public boolean restartNeedsElevationPrompt() {
        return launchPrompts;
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
        // Single read of the volatile field, then the process's own wait: it
        // returns the instant the core exits, where a 100 ms poll added up to
        // a tenth of a second to every reconnect.
        Process running = process;
        if (running == null || !running.isAlive()) {
            return true;
        }
        try {
            return running.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !running.isAlive();
        }
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
     * Whether this run's traffic met a REALITY server that turned the core
     * away. The Dashboard says why: to the user it looked like a dead server
     * or a blocked network, "All services unreachable" and a reconnect loop.
     * Cleared at each start and by {@link #forgetRealityRefusal()}.
     *
     * @return the property, changed on the FX thread
     */
    public ReadOnlyBooleanProperty realityRefusedProperty() {
        return realityRefused.getReadOnlyProperty();
    }

    /**
     * Forgets a REALITY refusal: the running core was pointed at another
     * server without a restart, so the lines seen so far no longer describe
     * the tunnel. Callable from any thread.
     */
    public void forgetRealityRefusal() {
        Platform.runLater(() -> realityRefused.set(false));
    }

    static boolean isRealityRefusal(String line) {
        return line != null && line.contains("reality verification failed")
                && REALITY_REFUSED.matcher(line).find();
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
     * The core's own words for the last error: its exit code and the last line
     * of its log, for an agent and for anyone reading more than the sentence.
     *
     * @return the error detail property, empty while there is no error
     */
    public ReadOnlyStringProperty errorDetailProperty() {
        return errorDetail.getReadOnlyProperty();
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
        CoreRecord.Entry sessionRecord = recordedCore;
        LogReader sessionReader = logReader;
        if (proc == null) {
            return;
        }
        Thread monitor = new Thread(() -> {
            try {
                int exitCode = proc.waitFor();
                // The exit can be seen before the reader has read the core's
                // last line, the one that says why: the card then said only
                // "exited unexpectedly" where the line named the port in use.
                // The reader hands every line over before the stream ends.
                if (sessionReader != null) {
                    sessionReader.awaitEnd(LAST_LINES_WAIT);
                }
                Platform.runLater(() -> {
                    if (!stopRequested
                            && proc == process
                            && connectionState.get() != ConnectionState.DISCONNECTED) {
                        String lastLine = logLines.isEmpty() ? null : logLines.getLast();
                        // A dismissed administrator prompt is the user
                        // cancelling the connect, not the tunnel failing: it
                        // was an ERROR, with a "Tunnel stopped" notification.
                        boolean declined = CoreExitReason.declined(lastLine);
                        // Message before state: state listeners fire
                        // synchronously inside set(), and they read the
                        // message the moment they see ERROR.
                        errorDetail.set("sing-box exited with code " + exitCode
                                + (lastLine != null ? ": " + lastLine : ""));
                        errorMessage.set(CoreExitReason.describe(exitCode, lastLine));
                        exitDeclined = declined;
                        connectionState.set(declined
                                ? ConnectionState.DISCONNECTED : ConnectionState.ERROR);
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
                // Decided and done under the lifecycle lock. The guard runs
                // slow platform commands, and without the lock a reconnect's
                // core could start and register the same proxy between the
                // decision and the clear. Now a start that comes first is seen
                // here as the successor, and one that comes second waits.
                synchronized (lifecycle) {
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
                // No successor guard here: the record is cleared only while
                // it still names this session's core.
                if (coreRecord != null) {
                    coreRecord.clear(sessionRecord);
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
     * Finds the core's clash API controller
     * ({@code experimental.clash_api.external_controller}) and its secret, or
     * null when the config has none.
     */
    static Controller extractController(String configJson) {
        try {
            JsonNode api = CONTROLLER_JSON.readTree(configJson)
                    .path("experimental").path("clash_api");
            URI version = URI.create(
                    "http://" + api.path("external_controller").asString("") + "/version");
            if (version.getHost() != null && version.getPort() > 0) {
                return new Controller(version, api.path("secret").asString(""));
            }
        } catch (JacksonException | IllegalArgumentException e) {
            log.debug("Could not parse config for the clash API controller", e);
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

    /** Test seam: replaces the check the core runs before every launch. */
    void setConfigCheck(SingBoxConfigCheck check) {
        this.configCheck = check;
    }

    /**
     * Takes back the CONNECTING a start published once nothing was launched.
     * Left in place, the status keeps saying "Connecting" and the dashboard's
     * Connect button stops a connection that never began. It is queued while
     * the lifecycle lock is held, so the next start's CONNECTING lands after it.
     */
    private void publishNotStarted() {
        Platform.runLater(() -> connectionState.set(ConnectionState.DISCONNECTED));
    }

    /** Removes this session's core from the record once it has stopped. */
    private void forgetRecordedCore() {
        if (coreRecord != null) {
            coreRecord.clear(recordedCore);
        }
        recordedCore = null;
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
        forgetRecordedCore();
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
