package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * How much the sing-box core writes to its log.
 *
 * <p>The Logs tab has filtered by level since it existed, but the generated
 * config pinned the core to {@code info}, so filtering down to debug showed
 * nothing: those lines were never emitted in the first place. The filter can
 * only narrow what the core already wrote, which left a user diagnosing a
 * failing handshake with no way to see more short of hand-editing a generated
 * file that the next connect overwrites.</p>
 *
 * <p>Each value is a sing-box {@code log.level} string verbatim. The core
 * accepts more levels than these (it also knows {@code trace}, {@code fatal}
 * and {@code panic}); the four here are the ones worth offering — below
 * {@code error} nothing user-visible survives, and {@code trace} is noise
 * measured in megabytes per minute.</p>
 */
public enum CoreLogLevel {

    /** Per-connection detail: DNS answers, dial attempts, handshake errors. */
    DEBUG("debug"),

    /** Start-up, inbound/outbound setup and failures. The default. */
    INFO("info"),

    /** Only what went wrong but was recovered from. */
    WARN("warn"),

    /** Only outright failures. */
    ERROR("error");

    private final String value;

    CoreLogLevel(String value) {
        this.value = value;
    }

    /** The sing-box {@code log.level} string for this level. */
    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * Parses a persisted value, falling back to {@link #INFO} for anything
     * unknown — a settings file from a newer build must not stop the app from
     * starting, and the level the core ran at before this setting existed is
     * the safe reading.
     */
    @JsonCreator
    public static CoreLogLevel fromValue(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (CoreLogLevel level : values()) {
                if (level.value.equals(normalized)) {
                    return level;
                }
            }
        }
        return INFO;
    }
}
