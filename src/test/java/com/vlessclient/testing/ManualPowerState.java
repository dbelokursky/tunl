package com.vlessclient.testing;

import com.vlessclient.platform.PowerState;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A machine whose sleep the test decides: {@link #sleep()} takes it out of
 * its full wake, as a lid closed or a maintenance wake does, and
 * {@link #wake()} brings the user back and tells the listeners, on the
 * test's thread.
 */
public final class ManualPowerState implements PowerState {

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean awake = true;
    private volatile long sleeps;

    @Override
    public boolean isAwake() {
        return awake;
    }

    @Override
    public long sleeps() {
        return sleeps;
    }

    @Override
    public void onWake(Runnable listener) {
        listeners.add(listener);
    }

    /** Leaves the full wake: asleep, in a dark wake, or on its way into sleep. */
    public void sleep() {
        if (awake) {
            awake = false;
            sleeps++;
        }
    }

    /**
     * Comes back to the full wake before anyone says so, as when a timer
     * fires first on the wake.
     */
    public void wakeUnannounced() {
        awake = true;
    }

    /** Comes back to the full wake, and says so. */
    public void wake() {
        if (!awake) {
            awake = true;
            listeners.forEach(Runnable::run);
        }
    }
}
