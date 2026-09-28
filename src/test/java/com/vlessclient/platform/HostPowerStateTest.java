package com.vlessclient.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The host's power state over a platform read and clocks the test moves: the
 * wall clock goes on while the machine sleeps, the monotonic one does not.
 */
class HostPowerStateTest {

    private final AtomicBoolean fullWake = new AtomicBoolean(true);
    private final AtomicLong wallMillis = new AtomicLong(1_790_000_000_000L);
    private final AtomicLong monoNanos = new AtomicLong(TimeUnit.HOURS.toNanos(5));
    private final List<Runnable> notified = new ArrayList<>();
    private final AtomicInteger wakes = new AtomicInteger();
    private HostPowerState state;

    @BeforeEach
    void setUp() {
        state = new HostPowerState(fullWake::get, wallMillis::get, monoNanos::get, notified::add);
        state.onWake(wakes::incrementAndGet);
    }

    @Test
    void aDarkWakeIsNotAwakeAndTheUsersWakeIsToldOnce() {
        assertThat(state.isAwake()).as("the full wake the app starts in").isTrue();

        fullWake.set(false);
        assertThat(state.isAwake()).as("a dark wake").isFalse();
        assertThat(state.sleeps()).isEqualTo(1);

        fullWake.set(true);
        awakeFor(1);
        assertThat(state.isAwake()).as("the user's wake").isTrue();
        assertThat(state.isAwake()).isTrue();
        runNotified();

        assertThat(wakes).as("wakes told").hasValue(1);
        assertThat(state.sleeps()).as("sleeps counted").isEqualTo(1);
    }

    /**
     * A process that slept through the dark wake saw none of it: the clocks
     * show the sleep, whatever the platform reads now.
     */
    @Test
    void aSleepNobodyAnnouncedShowsInTheClocks() {
        long before = state.sleeps();
        awakeFor(15);
        asleepFor(3600);
        awakeFor(2);

        assertThat(state.isAwake()).isTrue();
        runNotified();

        assertThat(state.sleeps()).isEqualTo(before + 1);
        assertThat(wakes).hasValue(1);
    }

    @Test
    void timeSpentAwakeIsNoSleep() {
        awakeFor(3600);

        assertThat(state.isAwake()).isTrue();
        runNotified();

        assertThat(state.sleeps()).isZero();
        assertThat(wakes).hasValue(0);
    }

    /** A clock set right by a second or two is not a sleep. */
    @Test
    void aSmallClockAdjustmentIsNoSleep() {
        wallMillis.addAndGet(2_000);

        assertThat(state.sleeps()).isZero();
        runNotified();
        assertThat(wakes).hasValue(0);
    }

    /** A clock set back is not a sleep either. */
    @Test
    void aClockSetBackIsNoSleep() {
        wallMillis.addAndGet(-TimeUnit.HOURS.toMillis(1));

        assertThat(state.sleeps()).isZero();
    }

    /**
     * The listeners take locks of their own, recovery's among them, which the
     * thread that looked may hold: they are handed to the notifier, never run
     * in the look.
     */
    @Test
    void theListenersRunOnTheNotifierNotInTheLook() {
        fullWake.set(false);
        state.isAwake();
        fullWake.set(true);
        state.isAwake();

        assertThat(wakes).as("wakes told in the look").hasValue(0);
        runNotified();
        assertThat(wakes).hasValue(1);
    }

    /** Where no platform read tells a dark wake, the machine counts as awake. */
    @Test
    void theAlwaysAwakeStateNeverSleeps() {
        AtomicInteger told = new AtomicInteger();
        PowerState.ALWAYS_AWAKE.onWake(told::incrementAndGet);

        assertThat(PowerState.ALWAYS_AWAKE.isAwake()).isTrue();
        assertThat(PowerState.ALWAYS_AWAKE.sleeps()).isZero();
        assertThat(told).hasValue(0);
    }

    private void awakeFor(long seconds) {
        wallMillis.addAndGet(TimeUnit.SECONDS.toMillis(seconds));
        monoNanos.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
    }

    private void asleepFor(long seconds) {
        wallMillis.addAndGet(TimeUnit.SECONDS.toMillis(seconds));
    }

    private void runNotified() {
        List<Runnable> due = new ArrayList<>(notified);
        notified.clear();
        due.forEach(Runnable::run);
    }
}
