package com.vlessclient.service;

import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ProxyMode;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Fake cores are shell scripts on Unix and .cmd files on Windows, so the whole
// lifecycle runs everywhere. Windows matters most here: its Process.destroy()
// is always a hard TerminateProcess, which is the reason SystemProxyGuard
// exists, and none of that was exercised while this class was Unix-only.
class SingBoxEngineTest {

    private static final String DUMMY_CONFIG = "{\"log\":{\"level\":\"info\"}}";

    @BeforeAll
    static void initJfx() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException ignored) {
            // Already started
        }
    }

    private static void flushFxEvents() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Creates an executable shell script at the given path that prints a "started"
     * line and sleeps for the given number of seconds.
     */
    private Path createFakeSingBox(Path dir, String name, int sleepSeconds) throws Exception {
        if (WINDOWS) {
            // timeout is the stock Windows sleep; /t counts seconds and /nobreak
            // stops a stray keypress from cutting it short. Redirecting from NUL
            // keeps it from failing when stdin is not a console, which is how
            // ProcessBuilder starts it.
            return writeScript(dir, name,
                    "@echo off\r\n"
                    + "echo sing-box started\r\n"
                    + "timeout /t " + sleepSeconds + " /nobreak > NUL\r\n");
        }
        return writeScript(dir, name,
                "#!/bin/sh\n"
                + "echo 'sing-box started'\n"
                + "sleep " + sleepSeconds + "\n");
    }

    /**
     * Creates an executable shell script that exits immediately with code 1,
     * simulating a crashed sing-box.
     */
    private Path createCrashingSingBox(Path dir, String name) throws Exception {
        if (WINDOWS) {
            return writeScript(dir, name,
                    "@echo off\r\n"
                    + "echo sing-box crashing\r\n"
                    + "exit /b 1\r\n");
        }
        return writeScript(dir, name,
                "#!/bin/sh\n"
                + "echo 'sing-box crashing'\n"
                + "exit 1\n");
    }

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

    /**
     * Writes a fake core and makes it runnable. On Windows the extension is
     * what makes a file executable (and ProcessBuilder routes .cmd through the
     * shell), so the name gains .cmd and there are no POSIX bits to set.
     */
    private Path writeScript(Path dir, String name, String body) throws Exception {
        Path script = dir.resolve(WINDOWS ? name + ".cmd" : name);
        Files.writeString(script, body);
        if (!WINDOWS) {
            makeExecutable(script);
        }
        return script;
    }

    private static void makeExecutable(Path p) throws Exception {
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(p, perms);
    }

    /**
     * Generous await deadline: polling returns as soon as the state matches,
     * so a large value costs nothing on success — it only buys headroom on
     * slow shared CI runners, where wrapper teardown has been observed to
     * exceed 5s (flaky tunMode failure on macos-latest, 2026-07).
     */
    private static final long AWAIT_STATE_TIMEOUT_MS = 15_000;

    /**
     * Blocks up to timeoutMillis waiting for the engine's connection state
     * (as observed on the JavaFX thread) to equal the expected value.
     */
    private void awaitConnectionState(SingBoxEngine engine,
                                      ConnectionState expected,
                                      long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (stateOnFxThread(engine) == expected) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(stateOnFxThread(engine)).isEqualTo(expected);
    }

    /**
     * Samples the connection state ON the JavaFX thread. A plain get() from
     * the test thread is a data race: property set() writes the value before
     * notifying listeners, so a racy read can observe the new state while its
     * listeners are still mid-dispatch — any listener-dependent assertion
     * that follows would then race them. A latch-synchronized FX-thread read
     * happens-after the full set(), listeners included.
     */
    private ConnectionState stateOnFxThread(SingBoxEngine engine) throws InterruptedException {
        java.util.concurrent.atomic.AtomicReference<ConnectionState> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            seen.set(engine.connectionStateProperty().get());
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        return seen.get();
    }

    @Test
    void startLaunchesProcessAndIsRunningIsTrue(@TempDir Path tmp) throws Exception {
        Path fake = createFakeSingBox(tmp, "sing-box", 30);
        SingBoxEngine engine = new SingBoxEngine(fake);

        engine.start(DUMMY_CONFIG, ProxyMode.SYSTEM_PROXY);
        try {
            assertThat(engine.isRunning()).isTrue();
        } finally {
            engine.stop();
        }
    }

    @Test
    void stopTerminatesProcessAndFlipsRunningFalse(@TempDir Path tmp) throws Exception {
        Path fake = createFakeSingBox(tmp, "sing-box", 30);
        SingBoxEngine engine = new SingBoxEngine(fake);

        engine.start(DUMMY_CONFIG, ProxyMode.SYSTEM_PROXY);
        assertThat(engine.isRunning()).isTrue();

        engine.stop();

        assertThat(engine.isRunning()).isFalse();
        awaitConnectionState(engine, ConnectionState.DISCONNECTED, AWAIT_STATE_TIMEOUT_MS);
    }

    @Test
    void startThrowsIllegalStateWhenAlreadyRunning(@TempDir Path tmp) throws Exception {
        Path fake = createFakeSingBox(tmp, "sing-box", 30);
        SingBoxEngine engine = new SingBoxEngine(fake);

        engine.start(DUMMY_CONFIG, ProxyMode.SYSTEM_PROXY);
        try {
            assertThatThrownBy(() -> engine.start(DUMMY_CONFIG, ProxyMode.SYSTEM_PROXY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already running");
        } finally {
            engine.stop();
        }
    }

    @Test
    void connectionStateTransitionsToConnectedWhenStartedMessageSeen(@TempDir Path tmp) throws Exception {
        Path fake = createFakeSingBox(tmp, "sing-box", 30);
        SingBoxEngine engine = new SingBoxEngine(fake);

        assertThat(engine.connectionStateProperty().get()).isEqualTo(ConnectionState.DISCONNECTED);

        engine.start(DUMMY_CONFIG, ProxyMode.SYSTEM_PROXY);
        try {
            awaitConnectionState(engine, ConnectionState.CONNECTED, AWAIT_STATE_TIMEOUT_MS);
        } finally {
            engine.stop();
        }
    }

    @Test
    void connectionStateTransitionsToErrorWhenProcessExitsUnexpectedly(@TempDir Path tmp) throws Exception {
        Path fake = createCrashingSingBox(tmp, "sing-box");
        SingBoxEngine engine = new SingBoxEngine(fake);

        engine.start(DUMMY_CONFIG, ProxyMode.SYSTEM_PROXY);

        awaitConnectionState(engine, ConnectionState.ERROR, AWAIT_STATE_TIMEOUT_MS);
        assertThat(engine.errorMessageProperty().get()).contains("exited unexpectedly");
    }

    @Test
    void errorMessageIsAlreadySetWhenStateListenersSeeError(@TempDir Path tmp) throws Exception {
        // Publication-order regression: state listeners fire synchronously
        // inside connectionState.set(ERROR), so the error message must be
        // written BEFORE the state flips — a listener (status banner, or a
        // test polling the state) reads it at that exact moment.
        Path fake = createCrashingSingBox(tmp, "sing-box");
        SingBoxEngine engine = new SingBoxEngine(fake);

        java.util.concurrent.atomic.AtomicReference<String> messageAtError =
                new java.util.concurrent.atomic.AtomicReference<>();
        // Every observed transition with the message visible at that instant —
        // printed by the assertion below if this ever flakes again.
        List<String> transitions = new CopyOnWriteArrayList<>();
        CountDownLatch registered = new CountDownLatch(1);
        Platform.runLater(() -> {
            engine.connectionStateProperty().addListener((obs, oldVal, newVal) -> {
                transitions.add(oldVal + "->" + newVal
                        + " msg=" + engine.errorMessageProperty().get());
                if (newVal == ConnectionState.ERROR) {
                    messageAtError.set(engine.errorMessageProperty().get());
                }
            });
            registered.countDown();
        });
        assertThat(registered.await(5, TimeUnit.SECONDS)).isTrue();

        engine.start(DUMMY_CONFIG, ProxyMode.SYSTEM_PROXY);

        awaitConnectionState(engine, ConnectionState.ERROR, AWAIT_STATE_TIMEOUT_MS);
        assertThat(messageAtError.get())
                .as("message captured at the ERROR transition; transitions seen: %s", transitions)
                .contains("exited unexpectedly");
    }

    @Test
    void rapidStartStopCyclesCrashNoMonitorThread(@TempDir Path tmp) throws Exception {
        // NPE-race canary: a monitor thread scheduled late used to re-read
        // the process field after stop() nulled it and die on the NPE. The
        // capture-based monitor must survive any start/stop interleaving;
        // any uncaught throwable on a monitor thread fails the test.
        Path fake = createFakeSingBox(tmp, "sing-box", 30);
        SingBoxEngine engine = new SingBoxEngine(fake);

        List<Throwable> monitorCrashes = new CopyOnWriteArrayList<>();
        Thread.UncaughtExceptionHandler prior = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            if (t.getName().startsWith("singbox-process-monitor")) {
                monitorCrashes.add(e);
            } else if (prior != null) {
                prior.uncaughtException(t, e);
            }
        });
        try {
            for (int i = 0; i < 25; i++) {
                engine.start(DUMMY_CONFIG, ProxyMode.SYSTEM_PROXY);
                engine.stop();
            }
            // Give straggler monitor threads a chance to run into the bug.
            Thread.sleep(300);
            flushFxEvents();
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(prior);
        }

        assertThat(monitorCrashes).isEmpty();
        assertThat(engine.isRunning()).isFalse();
    }

    private static final String SET_SYSTEM_PROXY_CONFIG = """
            {"inbounds":[
              {"type":"socks","listen":"127.0.0.1","listen_port":1080},
              {"type":"http","listen":"127.0.0.1","listen_port":1081,"set_system_proxy":true}
            ]}""";

    @Test
    void stopRunsSystemProxyGuardForMarkedConfig(@TempDir Path tmp) throws Exception {
        Path fake = createFakeSingBox(tmp, "sing-box", 30);
        SingBoxEngine engine = new SingBoxEngine(fake);
        List<String> guardCalls = new CopyOnWriteArrayList<>();
        engine.setSystemProxyGuard((host, port) -> guardCalls.add(host + ":" + port));

        engine.start(SET_SYSTEM_PROXY_CONFIG, ProxyMode.SYSTEM_PROXY);
        engine.stop();

        // The guard runs on the monitor thread once the process is dead.
        long deadline = System.currentTimeMillis() + 5000;
        while (guardCalls.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertThat(guardCalls).containsExactly("127.0.0.1:1081");
    }

    @Test
    void crashRunsSystemProxyGuardToo(@TempDir Path tmp) throws Exception {
        Path fake = createCrashingSingBox(tmp, "sing-box");
        SingBoxEngine engine = new SingBoxEngine(fake);
        List<String> guardCalls = new CopyOnWriteArrayList<>();
        engine.setSystemProxyGuard((host, port) -> guardCalls.add(host + ":" + port));

        engine.start(SET_SYSTEM_PROXY_CONFIG, ProxyMode.SYSTEM_PROXY);

        awaitConnectionState(engine, ConnectionState.ERROR, AWAIT_STATE_TIMEOUT_MS);
        long deadline = System.currentTimeMillis() + 5000;
        while (guardCalls.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertThat(guardCalls).containsExactly("127.0.0.1:1081");
    }

    @Test
    void unmarkedConfigNeverInvokesTheGuard(@TempDir Path tmp) throws Exception {
        Path fake = createFakeSingBox(tmp, "sing-box", 30);
        SingBoxEngine engine = new SingBoxEngine(fake);
        List<String> guardCalls = new CopyOnWriteArrayList<>();
        engine.setSystemProxyGuard((host, port) -> guardCalls.add(host + ":" + port));

        engine.start(DUMMY_CONFIG, ProxyMode.SYSTEM_PROXY);
        engine.stop();

        Thread.sleep(300);
        assertThat(guardCalls).isEmpty();
    }

    @Test
    void startupCleanupClearsAStaleProxyWhenNotRunning(@TempDir Path tmp) throws Exception {
        SingBoxEngine engine = new SingBoxEngine(createFakeSingBox(tmp, "sing-box", 30));
        List<String> guardCalls = new CopyOnWriteArrayList<>();
        engine.setSystemProxyGuard((host, port) -> guardCalls.add(host + ":" + port));

        engine.clearStaleSystemProxyOnStartup("127.0.0.1", 1081);

        assertThat(guardCalls).containsExactly("127.0.0.1:1081");
    }

    @Test
    void startupCleanupIsSkippedWhileTheCoreRuns(@TempDir Path tmp) throws Exception {
        SingBoxEngine engine = new SingBoxEngine(createFakeSingBox(tmp, "sing-box", 30));
        List<String> guardCalls = new CopyOnWriteArrayList<>();
        engine.setSystemProxyGuard((host, port) -> guardCalls.add(host + ":" + port));

        engine.start(DUMMY_CONFIG, ProxyMode.SYSTEM_PROXY);
        try {
            // A live proxy must never be torn down by a stray startup call.
            engine.clearStaleSystemProxyOnStartup("127.0.0.1", 1081);
            assertThat(guardCalls).isEmpty();
        } finally {
            engine.stop();
        }
    }

    // Builds its own /bin/sh wrapper to stand in for the privileged launcher,
    // so unlike the rest of the class this one stays Unix-only.
    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void tunModeUsesTunLauncherAndStopSignalsTheWrapper(@TempDir Path tmp) throws Exception {
        // Fake privileged wrapper honoring the TunLauncher contract: streams a
        // started line and exits as soon as the stop-signal file appears.
        Path stopFile = tmp.resolve("stop.signal");
        Path wrapper = tmp.resolve("wrapper.sh");
        Files.writeString(wrapper, "#!/bin/sh\n"
                + "echo 'sing-box started'\n"
                + "while [ ! -f '" + stopFile + "' ]; do sleep 0.2; done\n");
        makeExecutable(wrapper);

        Path fakeBinary = createFakeSingBox(tmp, "sing-box", 30);
        SingBoxEngine engine = new SingBoxEngine(fakeBinary);
        java.util.concurrent.atomic.AtomicReference<Process> wrapperProc =
                new java.util.concurrent.atomic.AtomicReference<>();
        engine.setTunLauncher((binary, config) -> {
            Process p = new ProcessBuilder(wrapper.toString()).redirectErrorStream(true).start();
            wrapperProc.set(p);
            return new com.vlessclient.platform.TunLauncher.Launched(p, stopFile);
        });

        engine.start(DUMMY_CONFIG, ProxyMode.TUN);
        try {
            assertThat(engine.isRunning()).isTrue();
        } finally {
            engine.stop();
        }

        awaitConnectionState(engine, ConnectionState.DISCONNECTED, AWAIT_STATE_TIMEOUT_MS);
        assertThat(engine.isRunning()).isFalse();
        // The wrapper must have exited on its own after seeing the stop file
        // (exit 0) — a force-kill fallback would surface as a signal exit.
        assertThat(wrapperProc.get().exitValue()).isZero();
    }
}
