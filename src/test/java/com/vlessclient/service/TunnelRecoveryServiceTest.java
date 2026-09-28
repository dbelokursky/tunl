package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.TunnelHealth;
import com.vlessclient.testing.ManualPowerState;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TunnelRecoveryServiceTest {
    private final AppSettings settings = new AppSettings();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicBoolean restartPrompts = new AtomicBoolean();
    /** Awake unless a test puts it to sleep, so every test goes through the power seam. */
    private final ManualPowerState power = new ManualPowerState();
    private TunnelRecoveryService recovery;

    @BeforeEach
    void setUp() {
        settings.setHealthCheckEnabled(true);
        settings.setHealthCheckAutoReconnect(true);
        settings.setHealthCheckDelaySeconds(10);
        recovery = new TunnelRecoveryService(() -> settings, guard -> {
            if (guard.getAsBoolean()) {
                starts.incrementAndGet();
            }
            return false;
        }, restartPrompts::get, () -> true, power, scheduler);
        recovery.connectionRequested();
    }

    @AfterEach
    void tearDown() {
        recovery.close();
    }

    @Test
    void crashRetriesWithoutAViewAndUsesBoundedBackoff() {
        recovery.onConnectionState(ConnectionState.ERROR);
        for (int i = 0; i < 7; i++) {
            scheduler.jobs.get(i).run();
        }
        assertThat(starts).hasValue(7);
        assertThat(scheduler.jobs).extracting(job -> job.seconds)
                .containsExactly(10L, 20L, 40L, 80L, 160L, 300L, 300L, 300L);
    }

    @Test
    void restoredReachabilityCancelsThePendingRetryAndResetsBackoff() {
        recovery.onHealth(TunnelHealth.BROKEN);
        scheduler.jobs.getFirst().run();
        recovery.onHealth(TunnelHealth.HEALTHY);
        assertThat(scheduler.jobs.get(1).isCancelled()).isTrue();
        recovery.onHealth(TunnelHealth.BROKEN);
        assertThat(scheduler.jobs.get(2).seconds).isEqualTo(10);
    }

    @Test
    void manualDisconnectCancelsPendingAndFutureCrashRetries() {
        recovery.onConnectionState(ConnectionState.ERROR);
        recovery.cancel();
        scheduler.jobs.getFirst().raw.run();
        recovery.onConnectionState(ConnectionState.ERROR);
        assertThat(starts).hasValue(0);
        assertThat(scheduler.jobs).hasSize(1);
    }

    @Test
    void staleTimerCannotCancelANewerUserRequest() {
        recovery.onConnectionState(ConnectionState.ERROR);
        recovery.connectionRequested();
        recovery.onConnectionState(ConnectionState.ERROR);
        scheduler.jobs.getFirst().raw.run();
        assertThat(scheduler.jobs.get(1).isCancelled()).isFalse();
        scheduler.jobs.get(1).run();
        assertThat(starts).hasValue(1);
    }

    @Test
    void disablingRecoveryBeforeTheTimerFiresPreventsTheRestart() {
        recovery.onHealth(TunnelHealth.BROKEN);
        settings.setHealthCheckAutoReconnect(false);
        scheduler.jobs.getFirst().run();
        assertThat(starts).hasValue(0);
        recovery.onConnectionState(ConnectionState.ERROR);
        assertThat(scheduler.jobs).hasSize(1);
    }

    @Test
    void cancelDuringStopInvalidatesTheGuardBeforeStart() throws Exception {
        recovery.close();
        ManualScheduler other = new ManualScheduler();
        CountDownLatch stopping = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicBoolean started = new AtomicBoolean();
        recovery = new TunnelRecoveryService(() -> settings, guard -> {
            stopping.countDown();
            try {
                if (!stopped.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("stop was not released");
                }
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            started.set(guard.getAsBoolean());
            return started.get();
        }, () -> false, other);
        recovery.connectionRequested();
        recovery.onConnectionState(ConnectionState.ERROR);
        Thread worker = Thread.startVirtualThread(other.jobs.getFirst());
        try {
            assertThat(stopping.await(5, TimeUnit.SECONDS)).isTrue();
            recovery.cancel();
        } finally {
            stopped.countDown();
            worker.join(5000);
        }
        assertThat(worker.isAlive()).isFalse();
        assertThat(started).isFalse();
        assertThat(other.jobs).hasSize(1);
    }

    @Test
    void shutdownPreventsQueuedWorkFromStarting() {
        recovery.onConnectionState(ConnectionState.ERROR);
        recovery.close();
        scheduler.jobs.getFirst().raw.run();
        assertThat(starts).hasValue(0);
        assertThat(scheduler.isShutdown()).isTrue();
    }

    /**
     * Where a restart raises an elevation prompt again (UAC always, macOS and
     * Linux without their one-time grant), recovery used to restart anyway: a
     * prompt nobody asked for, again at every backoff step. A tunnel that was
     * up waits for the user's reconnect instead.
     */
    @Test
    void aTunnelThatWasUpWaitsForTheUserWhereARestartWouldPrompt() {
        restartPrompts.set(true);
        recovery.onConnectionState(ConnectionState.CONNECTED);
        recovery.onConnectionState(ConnectionState.ERROR);

        assertThat(scheduler.jobs).as("no restart raises the prompt unasked").isEmpty();
        assertThat(recovery.isReconnectNeeded()).isTrue();

        recovery.onHealth(TunnelHealth.BROKEN);
        assertThat(scheduler.jobs).isEmpty();
        assertThat(starts).hasValue(0);
    }

    @Test
    void aStartThatNeverConnectedIsNotOfferedAgainWhereARestartWouldPrompt() {
        // A declined prompt ends the launch before the tunnel was ever up.
        restartPrompts.set(true);
        recovery.onConnectionState(ConnectionState.ERROR);

        assertThat(scheduler.jobs).isEmpty();
        assertThat(recovery.isReconnectNeeded())
                .as("the user has just said no; the error keeps its own Retry")
                .isFalse();
    }

    @Test
    void brokenReachabilityWaitsForTheUserWhereARestartWouldPrompt() {
        restartPrompts.set(true);
        recovery.onConnectionState(ConnectionState.CONNECTED);
        recovery.onHealth(TunnelHealth.BROKEN);

        assertThat(scheduler.jobs).isEmpty();
        assertThat(recovery.isReconnectNeeded()).isTrue();

        recovery.onHealth(TunnelHealth.HEALTHY);
        assertThat(recovery.isReconnectNeeded()).as("the tunnel came back by itself").isFalse();
    }

    @Test
    void aNewConnectOrADisconnectWithdrawsTheOffer() {
        restartPrompts.set(true);
        recovery.onConnectionState(ConnectionState.CONNECTED);
        recovery.onConnectionState(ConnectionState.ERROR);
        assertThat(recovery.isReconnectNeeded()).isTrue();

        recovery.connectionRequested();
        assertThat(recovery.isReconnectNeeded()).isFalse();
        recovery.onConnectionState(ConnectionState.ERROR);
        assertThat(recovery.isReconnectNeeded()).as("the new start never connected").isFalse();

        recovery.onConnectionState(ConnectionState.CONNECTED);
        recovery.onConnectionState(ConnectionState.ERROR);
        assertThat(recovery.isReconnectNeeded()).isTrue();
        recovery.cancel();
        assertThat(recovery.isReconnectNeeded()).isFalse();
        assertThat(scheduler.jobs).isEmpty();
    }

    /**
     * A configuration the core refuses is refused again by every retry. The
     * refusal used to be logged and the same restart tried again at every
     * backoff step, for as long as the app ran.
     */
    @Test
    void aConfigurationTheCoreRefusesIsNotRetried() {
        recovery.close();
        ManualScheduler other = new ManualScheduler();
        AtomicInteger tries = new AtomicInteger();
        recovery = new TunnelRecoveryService(() -> settings, guard -> {
            tries.incrementAndGet();
            throw new ConfigRejectedException("unknown field \"download_detour\"");
        }, () -> false, other);
        recovery.connectionRequested();
        recovery.onConnectionState(ConnectionState.ERROR);

        other.jobs.getFirst().run();
        recovery.onConnectionState(ConnectionState.ERROR);
        recovery.onHealth(TunnelHealth.BROKEN);

        assertThat(tries).as("restarts tried").hasValue(1);
        assertThat(other.jobs)
                .as("retries scheduled once the core refused the configuration")
                .hasSize(1);
    }

    /**
     * Recovery giving up is not the user giving up. It used to withdraw the
     * user's request for a tunnel with it, so the next subscription refresh
     * went out direct, with its token and the user's address: the leak #394
     * closed for a tunnel that is down or restarting. The request now stands
     * until the user connects, disconnects or cancels, and nothing restarts
     * the refused configuration meanwhile.
     */
    @Test
    void aRefusalStopsRecoveryButNotTheUsersRequest() {
        recovery.close();
        ManualScheduler other = new ManualScheduler();
        recovery = new TunnelRecoveryService(() -> settings, guard -> {
            throw new ConfigRejectedException("unknown field \"download_detour\"");
        }, () -> false, other);
        recovery.connectionRequested();
        recovery.onConnectionState(ConnectionState.ERROR);
        other.jobs.getFirst().run();

        assertThat(recovery.isTunnelWanted())
                .as("the user's request once recovery stopped").isTrue();
        recovery.onConnectionState(ConnectionState.ERROR);
        recovery.onHealth(TunnelHealth.BROKEN);
        recovery.onHealth(TunnelHealth.HEALTHY);
        assertThat(other.jobs).as("restarts scheduled after the stop").hasSize(1);
        assertThat(recovery.stopReason())
                .as("the reason, which a later verdict does not clear").isNotNull();

        recovery.cancel();
        assertThat(recovery.isTunnelWanted()).as("once the user cancels").isFalse();
        assertThat(recovery.stopReason()).isNull();
    }

    /** The refusal stays on record until the user's next request, which clears it. */
    @Test
    void aRefusalIsKeptAsTheReasonUntilTheUserConnectsAgain() {
        recovery.close();
        ManualScheduler other = new ManualScheduler();
        ConfigRejectedException refusal =
                new ConfigRejectedException("unknown field \"download_detour\"");
        recovery = new TunnelRecoveryService(() -> settings, guard -> {
            throw refusal;
        }, () -> false, other);
        recovery.connectionRequested();
        recovery.onConnectionState(ConnectionState.ERROR);
        other.jobs.getFirst().run();

        assertThat(recovery.stopReason()).as("why recovery stopped").isEqualTo(refusal.getMessage());

        recovery.connectionRequested();
        assertThat(recovery.stopReason()).as("the reason once the user connects again").isNull();
    }

    /**
     * With the Wi-Fi gone nothing answers, and a restart only cut what still
     * worked and grew the backoff toward five minutes. It waits for a network,
     * at the same delay, and the banner says so.
     */
    @Test
    void withoutANetworkTheRestartWaitsForOne() throws Exception {
        AtomicBoolean networkUp = new AtomicBoolean();
        recovery.close();
        recovery = new TunnelRecoveryService(() -> settings, guard -> {
            if (guard.getAsBoolean()) {
                starts.incrementAndGet();
            }
            return false;
        }, restartPrompts::get, networkUp::get, scheduler);
        recovery.connectionRequested();

        recovery.onHealth(TunnelHealth.BROKEN);
        scheduler.jobs.get(0).run();
        scheduler.jobs.get(1).run();

        assertThat(starts).as("no restart without a network").hasValue(0);
        assertThat(published().reason()).isEqualTo(TunnelRecoveryService.Reason.NO_NETWORK);
        assertThat(scheduler.jobs).extracting(job -> job.seconds)
                .as("the wait does not grow").containsExactly(10L, 10L, 10L);

        networkUp.set(true);
        scheduler.jobs.get(2).run();
        assertThat(starts).hasValue(1);
    }

    /** Every retry read "all services unreachable", a crash of the core included. */
    @Test
    void aRetrySaysWhyItWasScheduled() throws Exception {
        recovery.onConnectionState(ConnectionState.ERROR);
        assertThat(published().reason()).isEqualTo(TunnelRecoveryService.Reason.CORE_STOPPED);

        scheduler.jobs.getFirst().run();

        assertThat(published().reason()).isEqualTo(TunnelRecoveryService.Reason.RESTART_FAILED);
    }

    // ===== the machine's sleep =====

    /**
     * The reported restarts: a Mac in a maintenance wake, the display off, the
     * probes timing out through a tunnel with nothing wrong with it. Each
     * verdict restarted the tunnel, tearing the TUN device and its routes down
     * and climbing the backoff.
     */
    @Test
    void aFailedCheckOutsideTheFullWakeRestartsNothing() throws Exception {
        recovery.onConnectionState(ConnectionState.CONNECTED);
        power.sleep();

        recovery.onHealth(TunnelHealth.BROKEN);

        assertThat(scheduler.jobs).as("restarts scheduled in a dark wake").isEmpty();
        assertThat(published()).as("the countdown the banner shows").isNull();
    }

    /** Nor is the user asked to reconnect for a verdict the sleep made. */
    @Test
    void aFailedCheckOutsideTheFullWakeOffersNoReconnect() {
        restartPrompts.set(true);
        recovery.onConnectionState(ConnectionState.CONNECTED);
        power.sleep();

        recovery.onHealth(TunnelHealth.BROKEN);

        assertThat(recovery.isReconnectNeeded()).isFalse();
    }

    /** The restart a failed check asked for waits out its delay; the lid may close meanwhile. */
    @Test
    void aRestartAFailedCheckAskedForIsDroppedOnceTheMachineLeavesItsFullWake() {
        recovery.onHealth(TunnelHealth.BROKEN);
        power.sleep();

        scheduler.jobs.getFirst().run();

        assertThat(starts).as("restarts outside the full wake").hasValue(0);
        assertThat(scheduler.jobs).as("restarts scheduled after").hasSize(1);
    }

    /**
     * The timer can fire after the wake and before anyone says so. The check
     * it acts on ran before the sleep all the same.
     */
    @Test
    void aRestartAFailedCheckAskedForBeforeASleepIsNotRunAfterIt() {
        recovery.onHealth(TunnelHealth.BROKEN);
        power.sleep();
        power.wakeUnannounced();

        scheduler.jobs.getFirst().run();

        assertThat(starts).as("restarts for a check made before the sleep").hasValue(0);
    }

    /**
     * A crash is a fact about the core that no sleep fakes, and the tunnel is
     * gone: what macOS sends in a maintenance wake, mail and backups, would
     * leave outside it. The core restarts in the dark as in the light.
     */
    @Test
    void aCrashedCoreIsRestartedOutsideTheFullWakeToo() {
        power.sleep();

        recovery.onConnectionState(ConnectionState.ERROR);
        scheduler.jobs.getFirst().run();

        assertThat(starts).hasValue(1);
    }

    /**
     * Restarts that kept failing in the dark grow the backoff to minutes, and
     * the user who opens the lid would wait them out. The wake starts it over.
     */
    @Test
    void theWakeStartsTheBackoffOver() throws Exception {
        recovery.onConnectionState(ConnectionState.ERROR);
        scheduler.jobs.get(0).run();
        scheduler.jobs.get(1).run();
        assertThat(scheduler.jobs).extracting(job -> job.seconds)
                .as("precondition: the backoff grew").containsExactly(10L, 20L, 40L);

        power.sleep();
        power.wake();

        assertThat(scheduler.jobs.get(2).isCancelled())
                .as("the restart at the end of the grown backoff").isTrue();
        assertThat(scheduler.jobs).extracting(job -> job.seconds)
                .as("the restart in its place").containsExactly(10L, 20L, 40L, 10L);
        assertThat(published().attempt()).as("its attempt").isEqualTo(1);
    }

    /**
     * A restart a failed check asked for before the sleep is dropped on the
     * wake, which checks again; the verdict that follows starts at the first
     * step.
     */
    @Test
    void theWakeDropsARestartAFailedCheckAskedForAndTheNextVerdictStartsOver() {
        recovery.close();
        recovery = new TunnelRecoveryService(() -> settings, guard -> true,
                restartPrompts::get, () -> true, power, scheduler);
        recovery.connectionRequested();
        recovery.onHealth(TunnelHealth.BROKEN);
        // The restart brings a core up, whose check fails too.
        scheduler.jobs.getFirst().run();
        recovery.onHealth(TunnelHealth.CHECKING);
        recovery.onHealth(TunnelHealth.BROKEN);
        assertThat(scheduler.jobs).extracting(job -> job.seconds)
                .as("precondition: the backoff grew").containsExactly(10L, 20L);

        power.sleep();
        power.wake();

        assertThat(scheduler.jobs.get(1).isCancelled())
                .as("the restart the check before the sleep asked for").isTrue();
        assertThat(scheduler.jobs).as("restarts the wake itself scheduled").hasSize(2);

        recovery.onHealth(TunnelHealth.CHECKING);
        recovery.onHealth(TunnelHealth.BROKEN);

        assertThat(scheduler.jobs).extracting(job -> job.seconds)
                .as("the restart the verdict after the wake asks for")
                .containsExactly(10L, 20L, 10L);
    }

    /** Awake, a failed check restarts the tunnel exactly as before. */
    @Test
    void awakeAFailedCheckRestartsAsBefore() {
        recovery.onHealth(TunnelHealth.BROKEN);
        scheduler.jobs.getFirst().run();

        assertThat(starts).hasValue(1);
        assertThat(scheduler.jobs).extracting(job -> job.seconds).containsExactly(10L, 20L);
    }

    /** The retry as published, once an update queued to the FX thread has run. */
    private TunnelRecoveryService.Retry published() throws InterruptedException {
        try {
            CountDownLatch flushed = new CountDownLatch(1);
            Platform.runLater(flushed::countDown);
            assertThat(flushed.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (IllegalStateException toolkitNotRunning) {
            // Published in place.
        }
        return recovery.retryProperty().get();
    }

    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        final List<Job> jobs = new ArrayList<>();

        ManualScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            Job job = new Job(command, unit.toSeconds(delay));
            jobs.add(job);
            return job;
        }
    }

    private static final class Job extends FutureTask<Void> implements ScheduledFuture<Void> {
        final Runnable raw;
        final long seconds;

        Job(Runnable command, long seconds) {
            super(command, null);
            raw = command;
            this.seconds = seconds;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(seconds, TimeUnit.SECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }
    }
}
