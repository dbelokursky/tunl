package com.vlessclient.service;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.TunnelHealth;
import com.vlessclient.platform.NetworkPresence;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns cancelable recovery after a core crash or a failed reachability verdict. */
public final class TunnelRecoveryService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TunnelRecoveryService.class);
    private final Supplier<AppSettings> settings;
    private final Attempt attempt;
    private final BooleanSupplier restartNeedsTheUser;
    private final BooleanSupplier networkUp;
    private final ScheduledExecutorService scheduler;
    private final ReadOnlyObjectWrapper<Retry> retry = new ReadOnlyObjectWrapper<>();
    private final ReadOnlyBooleanWrapper reconnectNeeded = new ReadOnlyBooleanWrapper();
    private final ReadOnlyStringWrapper stopReason = new ReadOnlyStringWrapper();
    private ScheduledFuture<?> pending;
    private long generation;
    private long publication;
    private int attempts;
    private boolean wanted;
    private boolean running;
    private boolean closed;
    /** The tunnel reached CONNECTED since the last user request. */
    private boolean connectedSinceRequest;
    /** A dropped tunnel waits for the user, because a restart would prompt. */
    private boolean waitingForTheUser;
    /** Why recovery stopped for the current request, or null while it has not. */
    private String stoppedBecause;
    private ConnectionState lastState = ConnectionState.DISCONNECTED;
    /** The last reachability verdict, to re-arm a retry a request cancelled. */
    private TunnelHealth lastHealth = TunnelHealth.UNMONITORED;

    /**
     * The retry displayed by the UI; a null property value means no retry is
     * pending.
     *
     * @param attempt      the restart's number since the tunnel was last healthy
     * @param delaySeconds how long until it runs
     * @param reason       why it was scheduled, which the banner says: every
     *                     retry used to read "all services unreachable", a
     *                     crash of the core included
     */
    public record Retry(int attempt, int delaySeconds, Reason reason) {

        /** A retry because no health-check target answered. */
        public Retry(int attempt, int delaySeconds) {
            this(attempt, delaySeconds, Reason.UNREACHABLE);
        }
    }

    /** Why a restart was scheduled. */
    public enum Reason {
        /** No health-check target answered through the tunnel. */
        UNREACHABLE("no health-check target answered through the tunnel"),
        /** The core stopped on its own. */
        CORE_STOPPED("the core stopped"),
        /** The previous restart did not bring the tunnel up. */
        RESTART_FAILED("the restart did not bring the tunnel up"),
        /** The host has no network; the restart waits for one. */
        NO_NETWORK("there is no network to restart it over");

        private final String described;

        Reason(String described) {
            this.described = described;
        }

        /** The reason as the log says it. */
        public String described() {
            return described;
        }
    }

    /** A restart must recheck the supplied guard immediately before starting a new core. */
    @FunctionalInterface
    public interface Attempt {
        /** Returns true when a core was started; false when the attempt should be retried. */
        boolean reconnect(BooleanSupplier stillWanted) throws IOException;
    }

    /**
     * Creates a recovery loop. Its daemon scheduler starts only when a retry is needed.
     *
     * @param settings            the live settings
     * @param attempt             restarts the core
     * @param restartNeedsTheUser whether a restart would raise an elevation prompt, so
     *                            the reconnect is left to the user; must not block
     */
    public TunnelRecoveryService(Supplier<AppSettings> settings, Attempt attempt,
                                 BooleanSupplier restartNeedsTheUser) {
        this(settings, attempt, restartNeedsTheUser, NetworkPresence.current()::isUp,
                Executors.newSingleThreadScheduledExecutor(
                        DaemonThreads.factory("tunnel-recovery")));
    }

    TunnelRecoveryService(Supplier<AppSettings> settings, Attempt attempt,
                          BooleanSupplier restartNeedsTheUser,
                          ScheduledExecutorService scheduler) {
        this(settings, attempt, restartNeedsTheUser, () -> true, scheduler);
    }

    TunnelRecoveryService(Supplier<AppSettings> settings, Attempt attempt,
                          BooleanSupplier restartNeedsTheUser, BooleanSupplier networkUp,
                          ScheduledExecutorService scheduler) {
        this.settings = settings;
        this.attempt = attempt;
        this.restartNeedsTheUser = restartNeedsTheUser;
        this.networkUp = networkUp;
        this.scheduler = scheduler;
    }

    /** Observable pending retry, for rendering only. */
    public ReadOnlyObjectProperty<Retry> retryProperty() {
        return retry.getReadOnlyProperty();
    }

    /** Observable offer of a reconnect only the user can make, for rendering only. */
    public ReadOnlyBooleanProperty reconnectNeededProperty() {
        return reconnectNeeded.getReadOnlyProperty();
    }

    /**
     * Why automatic recovery stopped, for rendering only: null while it has
     * not, and again once the user connects, reconnects or cancels.
     */
    public ReadOnlyStringProperty stopReasonProperty() {
        return stopReason.getReadOnlyProperty();
    }

    /**
     * Why automatic recovery stopped, as of now.
     *
     * @return the core's refusal of the configuration, or null while recovery
     *     has not stopped
     */
    public synchronized String stopReason() {
        return stoppedBecause;
    }

    /**
     * Whether a tunnel that dropped waits for the user's reconnect, because
     * restarting it would raise an elevation prompt nobody asked for.
     *
     * @return true while the reconnect is offered to the user
     */
    public synchronized boolean isReconnectNeeded() {
        return waitingForTheUser;
    }

    /** Records a new user connect/reconnect intent and supersedes any older attempt. */
    public synchronized long connectionRequested() {
        cancelPending();
        wanted = !closed;
        attempts = 0;
        connectedSinceRequest = false;
        return ++generation;
    }

    /**
     * Records that a request left the tunnel up without restarting it: a
     * server switched through the running core, or a connect to a core that
     * was already running.
     *
     * <p>No state change follows such a request, and taking it cleared both
     * "connected since the request" and any retry that was waiting. So a
     * tunnel that dropped afterwards neither retried nor offered the
     * reconnect where a restart needs an elevation prompt, and a tunnel whose
     * verdict was already broken lost its retry for good.</p>
     *
     * @param request the request, as {@link #connectionRequested()} returned it
     */
    public synchronized void keptUp(long request) {
        if (generation != request || lastState != ConnectionState.CONNECTED) {
            return;
        }
        connectedSinceRequest = true;
        if (lastHealth == TunnelHealth.BROKEN) {
            schedule(Reason.UNREACHABLE);
        }
    }

    /** Whether a captured request still represents the user's intent. */
    public synchronized boolean isWanted(long request) {
        return wanted && !closed && generation == request;
    }

    /**
     * The user's current connection request. A connect, reconnect, cancel or
     * disconnect moves it on; automatic retries keep it.
     *
     * @return an id that changes with every request the user makes
     */
    public synchronized long currentRequest() {
        return generation;
    }

    /** Cancels automatic recovery, including a restart that has not reached start() yet. */
    public synchronized void cancel() {
        wanted = false;
        generation++;
        attempts = 0;
        connectedSinceRequest = false;
        cancelPending();
    }

    /**
     * Whether the user wants a tunnel: asked for one and has not disconnected
     * since, whatever the core is doing now.
     *
     * @return whether a tunnel is wanted
     */
    public synchronized boolean isTunnelWanted() {
        return wanted && !closed;
    }

    /** Whether recovery currently owns a stop/start operation. */
    public synchronized boolean isRecovering() {
        return wanted && running;
    }

    /** Receives the engine's state independently of any view being loaded. */
    public synchronized void onConnectionState(ConnectionState state) {
        lastState = state;
        if (state == ConnectionState.CONNECTED) {
            connectedSinceRequest = true;
        }
        if (state == ConnectionState.ERROR) {
            schedule(Reason.CORE_STOPPED);
        } else if (state == ConnectionState.CONNECTED && !settings.get().isHealthCheckEnabled()) {
            attempts = 0;
            cancelPending();
        }
    }

    /** Receives the shared reachability verdict. A successful verdict resets the backoff. */
    public synchronized void onHealth(TunnelHealth health) {
        lastHealth = health;
        if (health == TunnelHealth.BROKEN) {
            schedule(Reason.UNREACHABLE);
        } else if (health == TunnelHealth.HEALTHY || health == TunnelHealth.DEGRADED) {
            attempts = 0;
            cancelPending();
        }
    }

    /**
     * Schedules a restart, saying why in the log: a tunnel torn down seconds
     * after it came up left only "Disconnecting" there, from a thread name,
     * with nothing to tell a crash from a failed probe.
     */
    private void schedule(Reason why) {
        AppSettings config = settings.get();
        if (!wanted || closed || running || pending != null
                || !config.isHealthCheckAutoReconnect()) {
            return;
        }
        if (restartNeedsTheUser.getAsBoolean()) {
            // A restart would raise the elevation prompt again with nobody
            // asking for it. A tunnel that was up offers the reconnect instead;
            // a start that never connected, a declined prompt included, keeps
            // the Retry of its own error.
            if (connectedSinceRequest) {
                publish(null, true);
            }
            return;
        }
        int base = Math.max(1, config.getHealthCheckDelaySeconds());
        int seconds = (int) Math.min(Math.max(base, 300L),
                (long) base * (1L << Math.min(attempts, 20)));
        long request = generation;
        publish(new Retry(++attempts, seconds, why));
        log.info("Restarting the tunnel in {} s (attempt {}): {}",
                seconds, attempts, why.described());
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
            if (!networkUp.getAsBoolean()) {
                // A roam, the lid closed: with no network nothing answers, and
                // a restart only cut what still worked and grew the backoff.
                // Asked again after the same delay, which does not grow.
                int seconds = Math.max(1, settings.get().getHealthCheckDelaySeconds());
                publish(new Retry(attempts, seconds, Reason.NO_NETWORK));
                log.info("Not restarting the tunnel: {}; asking again in {} s",
                        Reason.NO_NETWORK.described(), seconds);
                pending = scheduler.schedule(() -> retry(request), seconds, TimeUnit.SECONDS);
                return;
            }
            pending = null;
            running = true;
            publish(null);
        }
        boolean started = false;
        String refused = null;
        try {
            started = attempt.reconnect(() -> isWanted(request));
        } catch (ConfigRejectedException e) {
            // The core refuses the configuration, and a retry would hand it the
            // same one: the refusal used to be retried at every backoff step for
            // as long as the app ran. Stop, and say why.
            refused = e.getMessage();
            log.warn("Automatic tunnel recovery stopped: {}", e.getMessage());
        } catch (IOException | RuntimeException e) {
            log.warn("Automatic tunnel recovery failed", e);
        } finally {
            synchronized (this) {
                running = false;
                if (refused != null) {
                    if (isWanted(request)) {
                        stop(refused);
                    }
                } else if ((!started && isWanted(request)) || lastState == ConnectionState.ERROR) {
                    schedule(Reason.RESTART_FAILED);
                }
            }
        }
    }

    /** Gives recovery up until the user's next request, and publishes why. */
    private void stop(String reason) {
        wanted = false;
        generation++;
        attempts = 0;
        connectedSinceRequest = false;
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
        publish(null, false, reason);
    }

    private void cancelPending() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
        publish(null);
    }

    private void publish(Retry value) {
        publish(value, false, null);
    }

    private void publish(Retry value, boolean userReconnect) {
        publish(value, userReconnect, null);
    }

    private void publish(Retry value, boolean userReconnect, String stopped) {
        waitingForTheUser = userReconnect;
        stoppedBecause = stopped;
        // Never wait for FX while holding this monitor: a UI Cancel calls back here.
        long version = ++publication;
        Runnable update = () -> {
            synchronized (this) {
                if (version == publication) {
                    retry.set(value);
                    reconnectNeeded.set(userReconnect);
                    stopReason.set(stopped);
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
