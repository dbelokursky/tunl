package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A remote subscription URL that supplies a set of servers, along with its
 * refresh interval and the ids of the servers it has imported.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Subscription {

    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("url")
    private String url;

    @JsonProperty("refreshIntervalHours")
    private long refreshIntervalHours = 24;

    @JsonProperty("lastRefreshedAt")
    private long lastRefreshedAt;

    @JsonProperty("serverIds")
    private List<String> serverIds = new ArrayList<>();

    /**
     * Why the last refresh failed, or null when it succeeded. Persisted so the
     * failure is still visible after a restart: a subscription that stops
     * updating (dead URL, expired token, unsupported body) otherwise looks
     * identical to a healthy one, just with an older timestamp.
     */
    @JsonProperty("lastError")
    private String lastError;

    /**
     * The provider's quota, from the {@code subscription-userinfo} response
     * header: bytes used in each direction, the plan's total, and the expiry
     * as Unix seconds. Zero means the provider did not say.
     */
    @JsonProperty("uploadBytes")
    private long uploadBytes;

    @JsonProperty("downloadBytes")
    private long downloadBytes;

    @JsonProperty("totalBytes")
    private long totalBytes;

    @JsonProperty("expiresAt")
    private long expiresAt;

    public Subscription() {
        this.id = UUID.randomUUID().toString();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public long getRefreshIntervalHours() {
        return refreshIntervalHours;
    }

    public void setRefreshIntervalHours(long refreshIntervalHours) {
        this.refreshIntervalHours = refreshIntervalHours;
    }

    public long getLastRefreshedAt() {
        return lastRefreshedAt;
    }

    public void setLastRefreshedAt(long lastRefreshedAt) {
        this.lastRefreshedAt = lastRefreshedAt;
    }

    public List<String> getServerIds() {
        return serverIds;
    }

    public void setServerIds(List<String> serverIds) {
        this.serverIds = serverIds == null ? new ArrayList<>() : new ArrayList<>(serverIds);
    }

    /** Why the last refresh failed, or null when it succeeded. */
    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public long getUploadBytes() {
        return uploadBytes;
    }

    public void setUploadBytes(long uploadBytes) {
        this.uploadBytes = uploadBytes;
    }

    public long getDownloadBytes() {
        return downloadBytes;
    }

    public void setDownloadBytes(long downloadBytes) {
        this.downloadBytes = downloadBytes;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
    }

    /** The plan's expiry as Unix seconds, or 0 when the provider did not say. */
    public long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }

    @Override
    public String toString() {
        return name != null ? name : url;
    }
}
