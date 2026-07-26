package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * How the proxy group picks which server carries the traffic.
 *
 * <p>Deliberately a mode rather than a stored group of members. A group whose
 * membership is persisted has to answer two questions this app has no good
 * answer for: what "the active server" means once a group exists, and what a
 * subscription refresh does to a membership list holding server ids it is
 * about to replace. Modelling the choice as a rule — "this one server", "all of
 * them, fastest first" — means membership is derived at connect time and
 * neither question arises.</p>
 *
 * <p>Each value maps onto a sing-box group type; {@link #SINGLE} keeps the
 * historical behaviour of pinning one server.</p>
 */
public enum ServerSelection {

    /** The active server, and only it. A {@code selector} over one member. */
    SINGLE("single", "selector"),

    /**
     * Every configured server, lowest latency wins ({@code urltest}). The core
     * re-measures on an interval and moves traffic on its own, which is the
     * point: a dead server stops being chosen without user action.
     *
     * <p>This covers the "fallback" case too, so there is no separate mode for
     * it: urltest only ever picks among members that answered the probe, so an
     * unreachable server is already excluded rather than merely ranked last.
     * A distinct fallback mode was designed and then dropped — sing-box has no
     * such outbound type (it is a Clash concept), which the real core reported
     * as "unknown outbound type: fallback".</p>
     */
    AUTO_BEST("auto_best", "urltest");

    private final String value;
    private final String singBoxType;

    ServerSelection(String value, String singBoxType) {
        this.value = value;
        this.singBoxType = singBoxType;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /** The sing-box outbound type implementing this mode. */
    public String singBoxType() {
        return singBoxType;
    }

    /** True when the core, not the user, picks the member. */
    public boolean isAutomatic() {
        return this != SINGLE;
    }

    /**
     * Parses a persisted value, falling back to {@link #SINGLE} for anything
     * unknown — a settings file from a newer build must not stop the app from
     * starting, and pinning one server is the safe reading.
     */
    @JsonCreator
    public static ServerSelection fromValue(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (ServerSelection mode : values()) {
                if (mode.value.equals(normalized)) {
                    return mode;
                }
            }
        }
        return SINGLE;
    }
}
