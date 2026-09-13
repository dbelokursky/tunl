package com.vlessclient.ui.view;

import com.vlessclient.service.DaemonThreads;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;

/**
 * Runs work on the FX thread later, without playing an animation.
 *
 * <p>{@code PauseTransition} and {@code Timeline} are JavaFX's own timers and
 * the wrong ones for a wait measured in seconds. The toolkit's pulse timer
 * pauses only after 250 ms in which no animation ran, so a transition waiting
 * out a health-check interval keeps the app pulsing at the display refresh
 * rate for the whole wait. That goes on with the window hidden to the tray,
 * where nothing is drawn but every pulse is still handed to the platform's
 * main thread, and the dashboard's health re-check and history refresh were
 * between them practically never both idle.</p>
 *
 * <p>Here one daemon thread does the waiting and the FX thread only sees the
 * work. Cancelling on the FX thread is final, as {@code Animation.stop()} was:
 * a run the timer has already handed to {@link Platform#runLater} but the FX
 * thread has not reached yet is dropped as well, so callers need no token of
 * their own to recognise a stale run.</p>
 */
public final class FxTimer {

    /**
     * One thread for every timer. The work runs on the FX thread, so this one
     * only ever sleeps and posts.
     */
    private static final ScheduledThreadPoolExecutor SCHEDULER = createScheduler();

    private FxTimer() {
    }

    /** A scheduled run, one-off or repeating. */
    public interface Cancellable {

        /**
         * Stops the run for good. On the FX thread this also drops a run that
         * is already on its way there.
         */
        void cancel();
    }

    /**
     * Runs {@code action} once on the FX thread after {@code delay}.
     *
     * @param delay how long to wait
     * @param action the work, run on the FX thread
     * @return the handle that cancels it
     */
    public static Cancellable after(Duration delay, Runnable action) {
        return new Run(action, null).arm(delay);
    }

    /**
     * Runs {@code action} on the FX thread every {@code period}, the first
     * time one period from now. Each wait starts when the previous run ends,
     * so a busy FX thread delays the runs instead of queueing them up.
     *
     * @param period the wait before each run
     * @param action the work, run on the FX thread
     * @return the handle that cancels it
     */
    public static Cancellable every(Duration period, Runnable action) {
        return new Run(action, Objects.requireNonNull(period, "period")).arm(period);
    }

    private static ScheduledThreadPoolExecutor createScheduler() {
        ScheduledThreadPoolExecutor scheduler =
                new ScheduledThreadPoolExecutor(1, DaemonThreads.factory("fx-timer"));
        // A health check is re-armed on every manual re-check and every state
        // change; without this each superseded wait would sit in the queue
        // until the moment it would have fired.
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /** One timer: its waits happen on the scheduler, its runs on the FX thread. */
    private static final class Run implements Cancellable, Runnable {

        private final Runnable action;

        /** The wait between runs, or null for a one-off. */
        private final Duration period;

        private volatile ScheduledFuture<?> pending;
        private volatile boolean cancelled;

        Run(Runnable action, Duration period) {
            this.action = Objects.requireNonNull(action, "action");
            this.period = period;
        }

        Run arm(Duration delay) {
            pending = SCHEDULER.schedule(() -> Platform.runLater(this),
                    delay.toNanos(), TimeUnit.NANOSECONDS);
            return this;
        }

        @Override
        public void run() {
            if (cancelled) {
                return;
            }
            try {
                action.run();
            } finally {
                // Re-armed even when the work threw, the way a Timeline goes
                // on to its next cycle: one bad read must not end the polling.
                if (period != null && !cancelled) {
                    arm(period);
                }
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            ScheduledFuture<?> wait = pending;
            if (wait != null) {
                wait.cancel(false);
            }
        }
    }
}
