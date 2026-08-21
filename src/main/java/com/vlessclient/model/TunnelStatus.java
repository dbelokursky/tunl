package com.vlessclient.model;

/**
 * The single status the user is shown, combining the sing-box process state
 * ({@link ConnectionState}) with what the reachability probes found
 * ({@link TunnelHealth}).
 *
 * <p>The two indicators that render it — the menu-bar icon in
 * {@link com.vlessclient.service.TrayIconService} and the Dashboard's hero
 * card — both derive from {@link #of}, so a connected-but-dead tunnel can
 * never look green in one place and red in the other.</p>
 *
 * <p>Each constant carries a {@link Tone}, which is the only thing the colour
 * of a dot, halo or icon is allowed to depend on. Wording stays with the
 * renderers, spelled out as string literals, so the i18n bundle guard can see
 * every key that is actually used.</p>
 */
public enum TunnelStatus {

    /** The core is not running. */
    DISCONNECTED(Tone.IDLE),

    /** The core is starting; it has not announced its inbounds yet. */
    CONNECTING(Tone.PENDING),

    /** The core is up and the first reachability probe has not answered yet. */
    VERIFYING(Tone.PENDING),

    /** The core is up and traffic verifiably reaches every probed service. */
    CONNECTED(Tone.OK),

    /** The core is up but only some probed services answer. */
    DEGRADED(Tone.PENDING),

    /** The core is up and no probed service answers: nothing gets through. */
    NO_TRAFFIC(Tone.BAD),

    /** The core is up but the check itself could not produce a verdict. */
    UNVERIFIED(Tone.PENDING),

    /** The core failed or exited unexpectedly. */
    ERROR(Tone.BAD);

    /**
     * The colour family a status is shown in. Kept separate from the status
     * itself because several statuses share one: "verifying", "degraded" and
     * "unverified" are all amber, all meaning <em>do not trust this yet</em>.
     */
    public enum Tone {
        /** Nothing is running (grey). */
        IDLE,
        /** Running but unproven, or on its way (amber). */
        PENDING,
        /** Running and proven (green). */
        OK,
        /** Running but broken, or failed outright (red). */
        BAD
    }

    private final Tone tone;

    TunnelStatus(Tone tone) {
        this.tone = tone;
    }

    /** The colour family this status is drawn in. */
    public Tone tone() {
        return tone;
    }

    /**
     * Combines process state and probe verdict into the one status to display.
     *
     * <p>Health only refines {@code CONNECTED}: while the core is starting,
     * stopped or failed, that is the whole story and a stale verdict must not
     * override it. A {@code CONNECTED} core with nothing to check
     * ({@link TunnelHealth#UNMONITORED}, or no health signal wired up at all)
     * stays plain {@link #CONNECTED} — the checks are optional, and switching
     * them off must not leave the user permanently amber.</p>
     *
     * @param state  process state, {@code null} treated as disconnected
     * @param health probe verdict, {@code null} treated as unmonitored
     */
    public static TunnelStatus of(ConnectionState state, TunnelHealth health) {
        if (state == null) {
            return DISCONNECTED;
        }
        return switch (state) {
            case DISCONNECTED -> DISCONNECTED;
            case CONNECTING -> CONNECTING;
            case ERROR -> ERROR;
            case CONNECTED -> ofHealth(health);
        };
    }

    private static TunnelStatus ofHealth(TunnelHealth health) {
        if (health == null) {
            return CONNECTED;
        }
        return switch (health) {
            case UNMONITORED, HEALTHY -> CONNECTED;
            case CHECKING -> VERIFYING;
            case DEGRADED -> DEGRADED;
            case BROKEN -> NO_TRAFFIC;
            case UNKNOWN -> UNVERIFIED;
        };
    }
}
