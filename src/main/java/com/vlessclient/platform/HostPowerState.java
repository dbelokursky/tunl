package com.vlessclient.platform;

import java.awt.Desktop;
import java.awt.desktop.ScreenSleepEvent;
import java.awt.desktop.ScreenSleepListener;
import java.awt.desktop.SystemSleepEvent;
import java.awt.desktop.SystemSleepListener;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The host's power state, from three sources: what the platform reads of it
 * now, the clocks, and the system's own wake announcements.
 *
 * <p>The monotonic clock ({@link System#nanoTime()}) stops while the machine
 * sleeps, on macOS ({@code mach_absolute_time}) and on Linux
 * ({@code CLOCK_MONOTONIC}), and the wall clock does not, so a gap between
 * the two since the last look is a sleep, announced or not. Where the
 * monotonic clock runs on through sleep, as it may on Windows, no gap shows,
 * and the app behaves there as it did before.</p>
 *
 * <p>An announcement only makes it look now: macOS says the system woke when
 * the user wakes it, and may not say so at all for a dark wake, which the
 * platform read covers instead. A wake is told to the listeners once, when a
 * look finds the machine in a full wake after one found it out of it.</p>
 */
final class HostPowerState implements PowerState {

    private static final Logger log = LoggerFactory.getLogger(HostPowerState.class);

    /**
     * A gap this wide between the clocks is a sleep. A clock adjustment is
     * seldom as large, and one that is costs a probe.
     */
    static final long SLEPT_MILLIS = 5_000;

    private final BooleanSupplier fullWake;
    private final LongSupplier wallMillis;
    private final LongSupplier monoNanos;
    private final Executor notifier;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Whether the machine was in a full wake at the last look. */
    private boolean inFullWake = true;
    private long sleeps;
    private long seenWallMillis;
    private long seenMonoNanos;

    /**
     * Creates the state over explicit sources, for a test to drive.
     *
     * @param fullWake   the platform's read of whether the machine is in a
     *                   full wake now
     * @param wallMillis the wall clock, which goes on while the machine sleeps
     * @param monoNanos  the monotonic clock, which stops while it sleeps
     * @param notifier   runs each listener's call, never on the thread that
     *                   looked: it may hold a lock the listener takes
     */
    HostPowerState(BooleanSupplier fullWake, LongSupplier wallMillis, LongSupplier monoNanos,
                   Executor notifier) {
        this.fullWake = Objects.requireNonNull(fullWake, "fullWake");
        this.wallMillis = Objects.requireNonNull(wallMillis, "wallMillis");
        this.monoNanos = Objects.requireNonNull(monoNanos, "monoNanos");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.seenWallMillis = wallMillis.getAsLong();
        this.seenMonoNanos = monoNanos.getAsLong();
    }

    /** The state of this host, listening for its wake where the desktop announces one. */
    static PowerState forThisHost() {
        BooleanSupplier fullWake = Platform.current() == Platform.MAC
                ? MacSystemCapabilities.fullWake(MacSystemCapabilities::read)
                : () -> true;
        HostPowerState state = new HostPowerState(fullWake, System::currentTimeMillis,
                System::nanoTime, task -> Thread.ofVirtual().name("power-wake").start(task));
        state.listenToTheDesktop();
        return state;
    }

    @Override
    public boolean isAwake() {
        return look();
    }

    @Override
    public long sleeps() {
        look();
        synchronized (this) {
            return sleeps;
        }
    }

    @Override
    public void onWake(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Reads the platform and the clocks, and tells the listeners when the
     * machine is back in a full wake.
     *
     * @return whether the machine is in a full wake now
     */
    private boolean look() {
        boolean awake;
        boolean left = false;
        boolean woke;
        long asleepMillis;
        // One look at a time, the platform read included: two that crossed
        // at the edge of a wake counted a sleep that was not, and a wake twice.
        synchronized (this) {
            awake = fullWake.getAsBoolean();
            long wall = wallMillis.getAsLong();
            long mono = monoNanos.getAsLong();
            asleepMillis = (wall - seenWallMillis)
                    - TimeUnit.NANOSECONDS.toMillis(mono - seenMonoNanos);
            seenWallMillis = wall;
            seenMonoNanos = mono;
            if (inFullWake && (!awake || asleepMillis > SLEPT_MILLIS)) {
                inFullWake = false;
                sleeps++;
                left = true;
            }
            woke = awake && !inFullWake;
            if (woke) {
                inFullWake = true;
            }
        }
        if (left) {
            log.info(asleepMillis > SLEPT_MILLIS
                    ? "System slept for " + TimeUnit.MILLISECONDS.toSeconds(asleepMillis) + " s"
                    : "System asleep, in a dark wake or on its way to sleep");
        }
        if (woke) {
            log.info("System awake");
            for (Runnable listener : listeners) {
                notifier.execute(listener);
            }
        }
        return awake;
    }

    /**
     * Looks whenever the system says it or its displays woke. A no-op where
     * the desktop announces neither, as on Linux.
     */
    private void listenToTheDesktop() {
        try {
            if (!Desktop.isDesktopSupported()) {
                return;
            }
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.APP_EVENT_SYSTEM_SLEEP)) {
                desktop.addAppEventListener(new SystemSleepListener() {
                    @Override
                    public void systemAboutToSleep(SystemSleepEvent e) {
                        // The platform read has it: the display goes first.
                    }

                    @Override
                    public void systemAwoke(SystemSleepEvent e) {
                        look();
                    }
                });
            }
            if (desktop.isSupported(Desktop.Action.APP_EVENT_SCREEN_SLEEP)) {
                desktop.addAppEventListener(new ScreenSleepListener() {
                    @Override
                    public void screenAboutToSleep(ScreenSleepEvent e) {
                        // A display that sleeps is not a machine that does.
                    }

                    @Override
                    public void screenAwoke(ScreenSleepEvent e) {
                        look();
                    }
                });
            }
        } catch (RuntimeException | LinkageError e) {
            log.debug("No sleep or wake announcements here: {}", e.toString());
        }
    }
}
