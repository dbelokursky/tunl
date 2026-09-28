package com.vlessclient.platform;

/**
 * Whether the machine is in a wake its user is part of, which is the only
 * time a failed health check says something about the tunnel.
 *
 * <p>A Mac that sleeps keeps waking in the dark: for maintenance (mail,
 * backups, Find My) it runs with the display off for minutes, up to an hour,
 * and on its way into sleep it drops the display seconds before the CPU.
 * Probes through the tunnel time out there with nothing wrong with it, and
 * recovery restarted the tunnel for each such verdict: three restarts in one
 * minute of one maintenance wake, each tearing the TUN device and its routes
 * down and climbing the backoff, on a tunnel that had not failed all day.</p>
 *
 * <p>The health loop and recovery ask {@link #isAwake()} before they act on a
 * verdict, compare {@link #sleeps()} across a probe to drop one that ran into
 * a sleep, and check again {@link #onWake on the wake}.</p>
 */
public interface PowerState {

    /** A host whose power state is not read: always awake, as the app used to assume. */
    PowerState ALWAYS_AWAKE = new PowerState() {
        @Override
        public boolean isAwake() {
            return true;
        }

        @Override
        public long sleeps() {
            return 0;
        }

        @Override
        public void onWake(Runnable listener) {
            // Never leaves the full wake, so never comes back to it.
        }
    };

    /**
     * Whether the machine is in a full wake: not asleep, not in a dark or
     * maintenance wake, and not on its way into sleep. Cheap enough to ask for
     * every probe.
     *
     * @return true while the machine is fully awake
     */
    boolean isAwake();

    /**
     * How many times this run has seen the machine leave its full wake, to
     * sleep or to a dark wake. A probe that starts under one count and ends
     * under another ran into a sleep, and what it found says nothing about
     * the tunnel.
     *
     * @return a count that only grows
     */
    long sleeps();

    /**
     * Calls {@code listener} each time the machine is back in a full wake
     * after it left one: the user is back. It may run on any thread, so a
     * listener hands its work to the thread that owns it.
     *
     * @param listener what to run on each wake
     */
    void onWake(Runnable listener);

    /**
     * The power state of this host. On macOS it reads the kernel's power
     * capabilities; everywhere it tells a sleep by the clocks, and listens for
     * the system's own wake where the desktop announces one.
     *
     * @return the host's power state, which listens to the desktop from now on
     */
    static PowerState current() {
        return HostPowerState.forThisHost();
    }
}
