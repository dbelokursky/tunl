package com.vlessclient.ui.view.dashboard;

/**
 * How often the dashboard probes a tunnel that keeps answering.
 *
 * <p>Every probe opens a new connection through the tunnel and makes a TLS
 * handshake with each target: at the default five seconds and two targets,
 * 24 connections a minute for as long as the tunnel is up, with the window in
 * the tray too. A tunnel that has answered every probe for a while does not
 * need that; one that has just come up, woken, failed a probe or been
 * re-checked by hand does, and the count of healthy verdicts in a row starts
 * again from zero for each of those.</p>
 */
final class ProbeCadence {

    /**
     * The longest the probes back off to. A tunnel that breaks silently goes
     * unnoticed for up to this long, and auto-reconnect waits for the probes.
     */
    static final int MAX_SECONDS = 60;

    /**
     * Healthy verdicts in a row for each doubling of the wait: from the default
     * 5 s it goes 10 s after three, 20 s after six, 40 s after nine and the
     * ceiling after twelve, some four minutes of a tunnel that kept answering.
     * One probe that is not healthy starts it over, so a failure is confirmed
     * or cleared at the configured interval.
     */
    static final int HEALTHY_PER_DOUBLING = 3;

    private ProbeCadence() {
    }

    /**
     * Seconds until the next probe.
     *
     * @param configuredSeconds the interval from the settings
     *                          ({@code health_check_interval_seconds}, 5 by
     *                          default), at least 1
     * @param healthyStreak     verdicts in a row that found every target
     *                          reachable; 0 after any other verdict, a
     *                          connect, a wake or a manual re-check
     * @return at least {@code configuredSeconds}; at most {@link #MAX_SECONDS}
     *         unless the settings ask for longer
     */
    static int nextDelaySeconds(int configuredSeconds, int healthyStreak) {
        int base = Math.max(1, configuredSeconds);
        if (base >= MAX_SECONDS) {
            return base;   // slower than the ceiling already: the user's choice stands
        }
        // Capped before the shift: six doublings take any base to the ceiling.
        int doublings = Math.min(Math.max(0, healthyStreak) / HEALTHY_PER_DOUBLING, 6);
        return Math.min(base << doublings, MAX_SECONDS);
    }
}
