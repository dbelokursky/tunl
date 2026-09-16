package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * How the client routes traffic: as a local system proxy or via a TUN interface.
 */
public enum ProxyMode {
    SYSTEM_PROXY("system_proxy"),
    TUN("tun");

    private final String value;

    ProxyMode(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * Parses a persisted value, falling back to {@link #SYSTEM_PROXY} for
     * anything unknown. A settings file from a newer build must not cost the
     * user every other setting in it, and the system proxy is the safe
     * reading: it is the default and the mode that needs no elevation.
     */
    @JsonCreator
    public static ProxyMode fromValue(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (ProxyMode mode : values()) {
                if (mode.value.equals(normalized)) {
                    return mode;
                }
            }
        }
        return SYSTEM_PROXY;
    }
}
