package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** What goes through the tunnel. */
public enum RouteMode {

    /** Everything, but for what the routing rules send direct. */
    ALL("all"),

    /**
     * Only the sites and addresses blocked in Russia, by the runetfreedom
     * lists; everything else goes direct.
     */
    BLOCKED_IN_RUSSIA("blocked-ru");

    private final String value;

    RouteMode(String value) {
        this.value = value;
    }

    /**
     * The mode as routing.json keeps it.
     *
     * @return the stored name
     */
    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * The mode a stored name stands for. One this build does not know, from
     * a later one, routes everything through the tunnel.
     *
     * @param value the stored name
     * @return the mode
     */
    @JsonCreator
    public static RouteMode fromValue(String value) {
        for (RouteMode mode : values()) {
            if (mode.value.equals(value)) {
                return mode;
            }
        }
        return ALL;
    }
}
