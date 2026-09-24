package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A model persisted as JSON that keeps the fields it does not know.
 *
 * <p>The app reads files a newer build wrote, best-effort, rather than drop
 * the user's servers. But each model ignored what it did not know, and saves
 * rewrite whole files: at the first save after a downgrade (dev-latest back to
 * a release) a newer build's fields were gone, and after the upgrade again
 * they came back as defaults, a switch the user had turned off turned on.
 * Unknown fields now ride along and are written back as they came.</p>
 */
public abstract class KeepsUnknownFields {

    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    @JsonAnySetter
    void keepUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    @JsonAnyGetter
    Map<String, Object> unknownFields() {
        return Collections.unmodifiableMap(unknownFields);
    }
}
