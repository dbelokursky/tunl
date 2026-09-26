package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A remote subscription URL that supplies a set of servers, along with its
 * refresh interval and the ids of the servers it has imported.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Subscription extends KeepsUnknownFields {

    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("url")
    private String url;

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
     * The message key of the last failure, when the app worded that failure
     * itself. Stored instead of the sentence so the row reads in whatever
     * language the app is in now -- a sentence written into the file stays in
     * the language it was written in, on disk, forever.
     */
    @JsonProperty("lastErrorKey")
    private String lastErrorKey;

    /** Arguments for {@link #lastErrorKey}, in order. */
    @JsonProperty("lastErrorArgs")
    private List<String> lastErrorArgs = new ArrayList<>();

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

    /**
     * What the provider tells the user, from its {@code announce} header:
     * one line, read again on every refresh. Empty when it sends none.
     */
    @JsonProperty("announce")
    private String announce = "";

    /**
     * Links the provider's last list held that this client cannot run, and a
     * refresh therefore left out: how many, and what they ask for. A mixed
     * list used to lose them with only an INFO line in the log.
     */
    @JsonProperty("leftOutLinks")
    private int leftOutLinks;

    @JsonProperty("leftOutSummary")
    private String leftOutSummary = "";

    /**
     * How often the provider asks to be refreshed, in hours, from its
     * {@code profile-update-interval} header; 0 when it names none.
     */
    @JsonProperty("updateIntervalHours")
    private int updateIntervalHours;

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

    /** The key of a failure the app worded itself, or null for a technical one. */
    public String getLastErrorKey() {
        return lastErrorKey;
    }

    public void setLastErrorKey(String lastErrorKey) {
        this.lastErrorKey = lastErrorKey;
    }

    /** Arguments for the keyed failure; empty when there are none. */
    public List<String> getLastErrorArgs() {
        return lastErrorArgs;
    }

    public void setLastErrorArgs(List<String> lastErrorArgs) {
        this.lastErrorArgs = lastErrorArgs == null
                ? new ArrayList<>() : new ArrayList<>(lastErrorArgs);
    }

    /**
     * Records a failure the app words itself: the key and its arguments are
     * stored, the free-text reason cleared.
     */
    public void recordFailure(String key, List<String> args) {
        this.lastErrorKey = key;
        setLastErrorArgs(args);
        this.lastError = null;
    }

    /** Records a technical failure verbatim, such as an HTTP status. */
    public void recordFailure(String reason) {
        this.lastError = reason;
        this.lastErrorKey = null;
        this.lastErrorArgs = new ArrayList<>();
    }

    /** Clears both forms after a refresh that worked. */
    public void clearLastError() {
        this.lastError = null;
        this.lastErrorKey = null;
        this.lastErrorArgs = new ArrayList<>();
    }

    /** Whether the last refresh failed, in either form. */
    public boolean hasLastError() {
        return (lastError != null && !lastError.isBlank())
                || (lastErrorKey != null && !lastErrorKey.isBlank());
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

    /**
     * The last expiry a date can show: 9999-12-31T23:59:59Z. "expire" comes
     * from the provider as Unix seconds and nothing bounds it; past this the
     * page's date has no four-digit year to write, and past about 3.2e16 an
     * {@link Instant} cannot hold it at all.
     */
    public static final long LAST_EXPIRY = 253_402_300_799L;

    /** The plan's expiry as Unix seconds, or 0 when the provider did not say. */
    public long getExpiresAt() {
        return expiresAt;
    }

    /**
     * The plan's expiry when the provider named one a date can show.
     *
     * @return the expiry, or empty for none or for a value out of range
     */
    public Optional<Instant> expiry() {
        return expiresAt > 0 && expiresAt <= LAST_EXPIRY
                ? Optional.of(Instant.ofEpochSecond(expiresAt)) : Optional.empty();
    }

    public void setExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getAnnounce() {
        return announce == null ? "" : announce;
    }

    public void setAnnounce(String announce) {
        this.announce = announce == null ? "" : announce;
    }

    /** How many links the last list held that this client cannot run. */
    public int getLeftOutLinks() {
        return leftOutLinks;
    }

    /** What those links ask for, comma-separated; empty when none were left out. */
    public String getLeftOutSummary() {
        return leftOutSummary == null ? "" : leftOutSummary;
    }

    /**
     * Records what the last list left out.
     *
     * @param links   how many links it held that this client cannot run
     * @param summary what they ask for; ignored when {@code links} is 0
     */
    public void setLeftOut(int links, String summary) {
        this.leftOutLinks = Math.max(0, links);
        this.leftOutSummary = leftOutLinks == 0 || summary == null ? "" : summary;
    }

    /** The provider's refresh interval in hours, or 0 when it names none. */
    public int getUpdateIntervalHours() {
        return updateIntervalHours;
    }

    public void setUpdateIntervalHours(int updateIntervalHours) {
        this.updateIntervalHours = Math.max(0, updateIntervalHours);
    }

    @Override
    public String toString() {
        return name != null ? name : url;
    }
}
