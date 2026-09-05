package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-day traffic totals, split by the server that carried them, as persisted
 * in {@code traffic-history.json}.
 *
 * <p>Days are buckets rather than a sample log on purpose: the question this
 * answers is "how much this week, how much this month", and a day is the
 * coarsest bucket that still answers it. A year of daily rows for a handful of
 * servers is a few tens of kilobytes, so nothing is pruned — the file is
 * cleared only when the user asks for it.</p>
 *
 * <p>The numbers are approximate by construction: they come from summing the
 * per-second samples {@link com.vlessclient.service.TrafficMonitor} receives
 * from the core, so whatever passes while that stream is reconnecting is not
 * counted. They are a record of what this client saw, not an accounting
 * ledger, and must never be presented as the provider's own quota figure.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TrafficHistory {

    /**
     * Schema version, so a later incompatible change can be migrated rather
     * than silently misread. Bump it together with a migration in the store.
     */
    @JsonProperty("version")
    private int version = 1;

    @JsonProperty("days")
    private List<Day> days = new ArrayList<>();

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public List<Day> getDays() {
        return days;
    }

    public void setDays(List<Day> days) {
        this.days = days != null ? days : new ArrayList<>();
    }

    /** One calendar day's traffic, split by server. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Day {

        /** The local date this bucket covers, ISO-8601 ({@code 2026-09-05}). */
        @JsonProperty("date")
        private String date;

        @JsonProperty("servers")
        private List<ServerUsage> servers = new ArrayList<>();

        public String getDate() {
            return date;
        }

        public void setDate(String date) {
            this.date = date;
        }

        public List<ServerUsage> getServers() {
            return servers;
        }

        public void setServers(List<ServerUsage> servers) {
            this.servers = servers != null ? servers : new ArrayList<>();
        }
    }

    /** One server's share of a day. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ServerUsage {

        @JsonProperty("serverId")
        private String serverId;

        /**
         * The server's name as it was when the bytes flowed. Stored rather
         * than resolved at read time so a renamed or deleted server does not
         * turn last month's rows into blanks.
         */
        @JsonProperty("serverName")
        private String serverName;

        @JsonProperty("upload")
        private long upload;

        @JsonProperty("download")
        private long download;

        public String getServerId() {
            return serverId;
        }

        public void setServerId(String serverId) {
            this.serverId = serverId;
        }

        public String getServerName() {
            return serverName;
        }

        public void setServerName(String serverName) {
            this.serverName = serverName;
        }

        public long getUpload() {
            return upload;
        }

        public void setUpload(long upload) {
            this.upload = upload;
        }

        public long getDownload() {
            return download;
        }

        public void setDownload(long download) {
            this.download = download;
        }
    }
}
