package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.TunnelHealth;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TunnelRecoveryServiceTest {
    private final AppSettings settings = new AppSettings();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicBoolean restartPrompts = new AtomicBoolean();
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
        }, restartPrompts::get, scheduler);
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
