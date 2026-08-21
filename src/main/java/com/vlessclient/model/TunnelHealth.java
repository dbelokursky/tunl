package com.vlessclient.model;

/**
 * What the reachability probes have to say about a <em>running</em> tunnel.
 *
 * <p>{@link ConnectionState} only tracks the sing-box process: {@code CONNECTED}
 * means the core started and announced its inbounds, which is not the same as
 * traffic actually reaching the internet. A tunnel can sit in {@code CONNECTED}
 * while the server refuses every connection. This enum carries the second half
 * of the answer, produced by
 * {@link com.vlessclient.service.ServiceReachabilityChecker} and published
 * through {@link com.vlessclient.service.TunnelHealthState}.</p>
 *
 * <p>Combine the two with {@link TunnelStatus#of} rather than by hand, so the
 * tray icon and the dashboard never disagree about what the user is looking
 * at.</p>
 */
public enum TunnelHealth {

    /**
     * Nothing to say: the checks are switched off, no targets are configured,
     * or the tunnel is not up. A connected tunnel in this state is shown as
     * plain "connected" — there is no verdict to contradict it.
     */
    UNMONITORED,

    /**
     * A probe is in flight and this connection has produced no verdict yet.
     * Only the first probe after a connect reports this; later periodic
     * re-checks keep the previous verdict until they replace it, so the
     * indicator does not blink every {@code health_check_interval_seconds}.
     */
    CHECKING,

    /** Every probed service answered through the tunnel. */
    HEALTHY,

    /** Some services answered and some did not. */
    DEGRADED,

    /** No probed service answered: the tunnel is up but carries nothing. */
    BROKEN,

    /**
     * The check could not be run to a verdict (the probe batch itself failed).
     * Distinct from {@link #UNMONITORED}: checks are on, we simply do not know.
     */
    UNKNOWN
}
