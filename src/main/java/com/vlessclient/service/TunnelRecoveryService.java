package com.vlessclient.service;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.TunnelHealth;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns cancelable recovery after a core crash or a failed reachability verdict. */
public final class TunnelRecoveryService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TunnelRecoveryService.class);
    private final Supplier<AppSettings> settings;
    private final Attempt attempt;
    private final ScheduledExecutorService scheduler;
    private final ReadOnlyObjectWrapper<Retry> retry = new ReadOnlyObjectWrapper<>();
    private ScheduledFuture<?> pending;
    private long generation;
    private long publication;
    private int attempts;
    private boolean wanted;
    private boolean running;
    private boolean closed;
    private ConnectionState lastState = ConnectionState.DISCONNECTED;

    /** The retry displayed by the UI; a null property value means no retry is pending. */
    public record Retry(int attempt, int delaySeconds) {
    }

    /** A restart must recheck the supplied guard immediately before starting a new core. */
    @FunctionalInterface
    public interface Attempt {
        /** Returns true when a core was started; false when the attempt should be retried. */
        boolean reconnect(BooleanSupplier stillWanted) throws IOException;
    }

    /** Creates a recovery loop. Its daemon scheduler starts only when a retry is needed. */
    public TunnelRecoveryService(Supplier<AppSettings> settings, Attempt attempt) {
        this(settings, attempt, Executors.newSingleThreadScheduledExecutor(
                DaemonThreads.factory("tunnel-recovery")));
    }

    TunnelRecoveryService(Supplier<AppSettings> settings, Attempt attempt,
                          ScheduledExecutorService scheduler) {
        this.settings = settings;
        this.attempt = attempt;
        this.scheduler = scheduler;
    }

    /** Observable pending retry, for rendering only. */
    public ReadOnlyObjectProperty<Retry> retryProperty() {
        return retry.getReadOnlyProperty();
    }

    /** Records a new user connect/reconnect intent and supersedes any older attempt. */
    public synchronized long connectionRequested() {
        cancelPending();
        wanted = !closed;
        attempts = 0;
        return ++generation;
    }

    /** Whether a captured request still represents the user's intent. */
    public synchronized boolean isWanted(long request) {
        return wanted && !closed && generation == request;
    }

    /** Cancels automatic recovery, including a restart that has not reached start() yet. */
    public synchronized void cancel() {
        wanted = false;
        generation++;
        attempts = 0;
        cancelPending();
    }

    /** Whether recovery currently owns a stop/start operation. */
    public synchronized boolean isRecovering() {
        return wanted && running;
    }

    /** Receives the engine's state independently of any view being loaded. */
    public synchronized void onConnectionState(ConnectionState state) {
        lastState = state;
        if (state == ConnectionState.ERROR) {
            schedule();
        } else if (state == ConnectionState.CONNECTED && !settings.get().isHealthCheckEnabled()) {
            attempts = 0;
            cancelPending();
        }
    }

    /** Receives the shared reachability verdict. A successful verdict resets the backoff. */
    public synchronized void onHealth(TunnelHealth health) {
        if (health == TunnelHealth.BROKEN) {
            schedule();
        } else if (health == TunnelHealth.HEALTHY || health == TunnelHealth.DEGRADED) {
            attempts = 0;
            cancelPending();
        }
    }

    private void schedule() {
        AppSettings config = settings.get();
        if (!wanted || closed || running || pending != null
                || !config.isHealthCheckAutoReconnect()) {
            return;
        }
        int base = Math.max(1, config.getHealthCheckDelaySeconds());
        int seconds = (int) Math.min(Math.max(base, 300L),
                (long) base * (1L << Math.min(attempts, 20)));
        long request = generation;
        publish(new Retry(++attempts, seconds));
        pending = scheduler.schedule(() -> retry(request), seconds, TimeUnit.SECONDS);
    }

    private void retry(long request) {
        synchronized (this) {
            if (!isWanted(request)) {
                return;
            }
            if (!settings.get().isHealthCheckAutoReconnect()) {
                cancelPending();
                return;
            }
            pending = null;
            running = true;
            publish(null);
        }
        boolean started = false;
        try {
            started = attempt.reconnect(() -> isWanted(request));
        } catch (IOException | RuntimeException e) {
            log.warn("Automatic tunnel recovery failed", e);
        } finally {
            synchronized (this) {
                running = false;
                if ((!started && isWanted(request)) || lastState == ConnectionState.ERROR) {
                    schedule();
                }
            }
        }
    }

    private void cancelPending() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
        publish(null);
    }

    private void publish(Retry value) {
        // Never wait for FX while holding this monitor: a UI Cancel calls back here.
        long version = ++publication;
        Runnable update = () -> {
            synchronized (this) {
                if (version == publication) {
                    retry.set(value);
                }
            }
        };
        try {
            if (Platform.isFxApplicationThread()) {
                update.run();
            } else {
                Platform.runLater(update);
            }
        } catch (IllegalStateException toolkitNotRunning) {
            update.run();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        cancel();
        scheduler.shutdownNow();
    }
}
