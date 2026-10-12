package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single service whose reachability is probed after the tunnel comes up.
 * The {@code name} is shown in the dashboard; the {@code url} is requested
 * through the local proxy to decide whether the service is reachable.
 *
 * <p>It keeps the fields it does not know, as the other persisted models do:
 * a field a newer build wrote here (say, a target the user switched off) used
 * to be dropped by this build's first settings save and come back as its
 * default after the next upgrade.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class HealthCheckTarget extends KeepsUnknownFields {

    @JsonProperty("name")
    private String name;

    @JsonProperty("url")
    private String url;

    public HealthCheckTarget() {
    }

    public HealthCheckTarget(String name, String url) {
        this.name = name;
        this.url = url;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
