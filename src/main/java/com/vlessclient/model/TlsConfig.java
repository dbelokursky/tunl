package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * TLS settings for a server connection, including SNI, ALPN, fingerprint, and
 * REALITY parameters.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TlsConfig {

    @JsonProperty("enabled")
    private boolean enabled;

    @JsonProperty("server_name")
    private String serverName;

    @JsonProperty("alpn")
    private String alpn;

    @JsonProperty("fingerprint")
    private String fingerprint;

    @JsonProperty("allow_insecure")
    private boolean allowInsecure;

    @JsonProperty("reality")
    private boolean reality;

    @JsonProperty("reality_public_key")
    private String realityPublicKey;

    @JsonProperty("reality_short_id")
    private String realityShortId;

    public TlsConfig() {
    }

    TlsConfig(TlsConfig source) {
        this.enabled = source.enabled;
        this.serverName = source.serverName;
        this.alpn = source.alpn;
        this.fingerprint = source.fingerprint;
        this.allowInsecure = source.allowInsecure;
        this.reality = source.reality;
        this.realityPublicKey = source.realityPublicKey;
        this.realityShortId = source.realityShortId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getAlpn() {
        return alpn;
    }

    public void setAlpn(String alpn) {
        this.alpn = alpn;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public boolean isAllowInsecure() {
        return allowInsecure;
    }

    public void setAllowInsecure(boolean allowInsecure) {
        this.allowInsecure = allowInsecure;
    }

    public boolean isReality() {
        return reality;
    }

    public void setReality(boolean reality) {
        this.reality = reality;
    }

    public String getRealityPublicKey() {
        return realityPublicKey;
    }

    public void setRealityPublicKey(String realityPublicKey) {
        this.realityPublicKey = realityPublicKey;
    }

    public String getRealityShortId() {
        return realityShortId;
    }

    public void setRealityShortId(String realityShortId) {
        this.realityShortId = realityShortId;
    }
}
