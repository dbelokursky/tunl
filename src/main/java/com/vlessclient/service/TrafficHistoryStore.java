package com.vlessclient.service;

import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TrafficHistory;
import com.vlessclient.platform.PlatformPaths;
import com.vlessclient.platform.SecureFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Accumulates per-day, per-server traffic totals and persists them as JSON in
 * the application data directory.
 *
 * <p>Fed from {@link TrafficMonitor}'s per-second samples through
 * {@link #attach}, so the totals are an estimate of what this client saw
 * rather than an accounting figure — bytes that pass while the core's traffic
 * stream is reconnecting are never sampled and never counted. A subscription's
 * own {@code subscription-userinfo} quota is the authoritative number for a
 * plan, counts every device on the account, and is a different thing; these
 * two must not be presented as the same figure.</p>
 *
 * <p>Nothing is pruned. A day holds one row per server that carried traffic,
 * so a year is a few tens of kilobytes, and the file is emptied only by
 * {@link #reset()}. That makes it a durable record of when the user ran a
 * tunnel and through which exit, which is why it is written owner-only through
 * {@link SecureFiles} like the rest of the config, and why the UI that shows
 * it also has to offer clearing it.</p>
 */
public class TrafficHistoryStore {

    private static final Logger log = LoggerFactory.getLogger(TrafficHistoryStore.class);

    private static final String HISTORY_FILE = "traffic-history.json";

    /**
     * How long a change may sit in memory before it reaches the disk. A minute
     * bounds what a kill -9 costs (one minute of one server's bytes) without
     * writing the whole file every second the tunnel is up.
     */
    private static final long FLUSH_INTERVAL_MS = 60_000L;

    /** Attribution for bytes that flowed while no server could be named. */
    private static final String UNKNOWN_SERVER_ID = "unknown";

    private final Path dataDir;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    /**
     * Buckets keyed by ISO date. A TreeMap because ISO-8601 sorts
     * chronologically as text, so the file comes out in date order and a
     * month is a key-prefix scan.
     */
    private final Map<String, TrafficHistory.Day> byDate = new TreeMap<>();

    private int version = 1;
    private boolean dirty;
    private boolean attached;
    private long lastFlushAt;

    /**
     * Writes happen here rather than on the caller's thread: samples arrive on
     * the FX thread, and the file grows for as long as the user keeps the app.
     */
    private final ExecutorService io = Executors.newSingleThreadExecutor(
            DaemonThreads.factory("traffic-history-io"));

    /** One day's totals, with the gaps filled in. */
    public record DayTotal(LocalDate date, long upload, long download) {

        /**
         * Sums both directions, which is the figure the day charts plot.
         *
         * @return upload plus download
         */
        public long total() {
            return upload + download;
        }
    }

    /** One server's share of a window, newest known name first. */
    public record ServerTotal(String serverId, String serverName, long upload, long download) {

        /**
         * Sums both directions.
         *
         * @return upload plus download
         */
        public long total() {
            return upload + download;
        }
    }

    public TrafficHistoryStore() {
        this(PlatformPaths.current().dataDir(), Clock.systemDefaultZone());
    }

    /**
     * Creates a store over an explicit directory and clock.
     *
     * @param dataDir the directory holding {@code traffic-history.json}
     * @param clock the clock deciding which day a sample lands in; injected so
     *     a test can cross midnight without waiting for it
     */
    public TrafficHistoryStore(Path dataDir, Clock clock) {
        this.dataDir = dataDir;
        this.clock = clock;
        this.objectMapper = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
        this.lastFlushAt = clock.millis();
        load();
    }

    /**
     * Mirrors the monitor's samples into the history, attributing each to
     * whichever server is carrying traffic at that moment.
     *
     * @param monitor the live traffic monitor
     * @param routedServer supplies the server the tunnel is currently using,
     *     or null when none can be named
     */
    public synchronized void attach(TrafficMonitor monitor,
                                    Supplier<ServerConfig> routedServer) {
        if (attached) {
            // A second listener would double every byte. The dashboard is
            // built once today, but a reloaded view must not silently inflate
            // the record it cannot un-inflate.
            log.warn("TrafficHistoryStore is already attached; ignoring");
            return;
        }
        attached = true;
        // The monitor publishes upload before download on each poll tick, so
        // reading the upload property from inside the download listener pairs
        // the two values that were measured together -- the same pairing
        // TrafficDisplayBinder relies on for the session totals.
        monitor.downloadSpeedProperty().addListener((obs, oldVal, newVal) ->
                record(routedServer.get(), monitor.uploadSpeedProperty().get(),
                        newVal.longValue()));
    }

    /**
     * Adds one sample to today's bucket.
     *
     * @param server the server that carried it, or null if unknown
     * @param uploadBytes bytes sent in this sample
     * @param downloadBytes bytes received in this sample
     */
    public synchronized void record(ServerConfig server, long uploadBytes, long downloadBytes) {
        long up = Math.max(0, uploadBytes);
        long down = Math.max(0, downloadBytes);
        if (up == 0 && down == 0) {
            // An idle tunnel samples once a second forever; recording those
            // would create a row for every day the app merely ran.
            return;
        }
        TrafficHistory.Day day = dayFor(LocalDate.now(clock));
        TrafficHistory.ServerUsage usage = usageFor(day, server);
        usage.setUpload(usage.getUpload() + up);
        usage.setDownload(usage.getDownload() + down);
        dirty = true;

        long now = clock.millis();
        if (now - lastFlushAt >= FLUSH_INTERVAL_MS) {
            lastFlushAt = now;
            io.execute(this::flush);
        }
    }

    /**
     * Test seam: waits until the queued disk work has run.
     *
     * <p>The queue is single-threaded and FIFO, so an empty task completing
     * means every write submitted before it has finished. Without this a test
     * that crosses the flush interval races the write against its own
     * teardown, and the loser is whichever the OS scheduled second.</p>
     *
     * @param millis how long to wait
     * @return false if it did not settle within the timeout
     */
    boolean awaitIdle(long millis) {
        try {
            io.submit(() -> { }).get(millis, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
    }

    /** Writes pending changes to disk; a no-op when nothing changed. */
    public synchronized void flush() {
        if (!dirty) {
            return;
        }
        TrafficHistory history = new TrafficHistory();
        history.setVersion(version);
        history.setDays(new ArrayList<>(byDate.values()));
        Path file = dataDir.resolve(HISTORY_FILE);
        try {
            Files.createDirectories(dataDir);
            // Owner-only and atomic, like every other file in the data dir:
            // this one says when the user ran a tunnel and through which exit.
            SecureFiles.writePrivately(file, objectMapper.writeValueAsBytes(history));
            dirty = false;
        } catch (IOException e) {
            log.error("Failed to save traffic history to {}", file, e);
        }
    }

    /**
     * Forgets everything and removes the file. The only way the record is
     * cleared, since nothing expires on its own.
     */
    public synchronized void reset() {
        byDate.clear();
        dirty = false;
        Path file = dataDir.resolve(HISTORY_FILE);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.error("Failed to delete traffic history at {}", file, e);
        }
    }

    /**
     * The last {@code count} days ending today, oldest first, with days that
     * saw no traffic present as zeroes — a chart needs the gaps to be gaps
     * rather than missing bars.
     *
     * @param count how many days to return; values below 1 return an empty list
     * @return one entry per day
     */
    public synchronized List<DayTotal> lastDays(int count) {
        List<DayTotal> out = new ArrayList<>();
        if (count < 1) {
            return out;
        }
        LocalDate today = LocalDate.now(clock);
        for (int i = count - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            TrafficHistory.Day day = byDate.get(date.format(DateTimeFormatter.ISO_LOCAL_DATE));
            long up = 0;
            long down = 0;
            if (day != null) {
                for (TrafficHistory.ServerUsage usage : day.getServers()) {
                    up += usage.getUpload();
                    down += usage.getDownload();
                }
            }
            out.add(new DayTotal(date, up, down));
        }
        return out;
    }

    /**
     * Everything recorded in one calendar month.
     *
     * @param month the month to total
     * @return upload plus download across that month
     */
    public synchronized long totalForMonth(YearMonth month) {
        String prefix = month.toString() + "-";
        long total = 0;
        for (Map.Entry<String, TrafficHistory.Day> entry : byDate.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                for (TrafficHistory.ServerUsage usage : entry.getValue().getServers()) {
                    total += usage.getUpload() + usage.getDownload();
                }
            }
        }
        return total;
    }

    /**
     * The busiest servers over the last {@code days} days.
     *
     * @param limit how many to return
     * @param days how far back to look, ending today
     * @return servers ordered by total bytes, busiest first
     */
    public synchronized List<ServerTotal> topServers(int limit, int days) {
        Map<String, long[]> totals = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(clock);
        for (int i = days - 1; i >= 0; i--) {
            String key = today.minusDays(i).format(DateTimeFormatter.ISO_LOCAL_DATE);
            TrafficHistory.Day day = byDate.get(key);
            if (day == null) {
                continue;
            }
            for (TrafficHistory.ServerUsage usage : day.getServers()) {
                long[] sum = totals.computeIfAbsent(usage.getServerId(), id -> new long[2]);
                sum[0] += usage.getUpload();
                sum[1] += usage.getDownload();
                // Later days win, so a renamed server shows its current name.
                if (usage.getServerName() != null && !usage.getServerName().isBlank()) {
                    names.put(usage.getServerId(), usage.getServerName());
                }
            }
        }
        List<ServerTotal> out = new ArrayList<>();
        totals.forEach((id, sum) ->
                out.add(new ServerTotal(id, names.get(id), sum[0], sum[1])));
        out.sort(Comparator.comparingLong(ServerTotal::total).reversed());
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private TrafficHistory.Day dayFor(LocalDate date) {
        return byDate.computeIfAbsent(date.format(DateTimeFormatter.ISO_LOCAL_DATE), key -> {
            TrafficHistory.Day day = new TrafficHistory.Day();
            day.setDate(key);
            return day;
        });
    }

    private static TrafficHistory.ServerUsage usageFor(TrafficHistory.Day day,
                                                       ServerConfig server) {
        String id = server != null && server.getId() != null && !server.getId().isBlank()
                ? server.getId() : UNKNOWN_SERVER_ID;
        for (TrafficHistory.ServerUsage usage : day.getServers()) {
            if (id.equals(usage.getServerId())) {
                if (server != null && server.getName() != null) {
                    // Keep the name current: the row is what the UI labels.
                    usage.setServerName(server.getName());
                }
                return usage;
            }
        }
        TrafficHistory.ServerUsage usage = new TrafficHistory.ServerUsage();
        usage.setServerId(id);
        usage.setServerName(server != null ? server.getName() : null);
        day.getServers().add(usage);
        return usage;
    }

    private synchronized void load() {
        Path file = dataDir.resolve(HISTORY_FILE);
        if (!Files.exists(file)) {
            return;
        }
        try {
            TrafficHistory history = objectMapper.readValue(Files.readAllBytes(file),
                    TrafficHistory.class);
            version = history.getVersion();
            for (TrafficHistory.Day day : history.getDays()) {
                if (day.getDate() != null && !day.getDate().isBlank()) {
                    byDate.put(day.getDate(), day);
                }
            }
        } catch (IOException | JacksonException e) {
            // A corrupt history is worth losing, never worth blocking startup:
            // it is a convenience record, not configuration.
            log.error("Could not read traffic history at {}; starting empty", file, e);
            byDate.clear();
        }
    }
}
