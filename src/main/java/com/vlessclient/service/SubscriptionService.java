package com.vlessclient.service;

import com.vlessclient.app.AppVersion;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.Subscription;
import com.vlessclient.platform.PlatformPaths;
import com.vlessclient.platform.SecretSealer;
import com.vlessclient.platform.SecretSealers;
import com.vlessclient.platform.SecureFiles;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Manages subscriptions: fetching remote server lists, parsing them, and keeping the
 * backing {@link ConfigStore} in sync, with optional periodic auto-refresh.
 */
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    private static final String SUBSCRIPTIONS_FILE = "subscriptions.json";

    /**
     * Version stream of subscriptions.json: v0 is the pre-envelope bare
     * array, v1 wraps it as {@code {"config_version":1,"subscriptions":[…]}}.
     */
    static final int SUBSCRIPTIONS_CONFIG_VERSION = 1;

    /**
     * How long stopAutoRefresh() lets an in-flight refresh finish before it
     * interrupts. Kept small on purpose: shutdown runs it well inside the
     * Cmd+Q quit watchdog (2s) and the engine is already stopped by then, so a
     * stuck refresh must never be what holds shutdown open — the old 60s could
     * never complete under either quit path anyway.
     */
    private static final int SHUTDOWN_GRACE_SECONDS = 2;

    /**
     * Ceiling on a subscription response. Real lists are a few KB of share
     * links; 4 MiB is far above any legitimate one and far below anything
     * that threatens the heap.
     */
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private final Path dataDir;
    private final ObjectMapper objectMapper;
    private final ObservableList<Subscription> subscriptions;
    private final ConfigStore configStore;
    private final ShareLinkParser shareLinkParser;
    private final HttpClient httpClient;
    private final SecretSealer sealer;
    private final Object lifecycleLock = new Object();
    /**
     * Serializes refreshSubscription's apply stage against removeSubscription
     * (and against another refresh of the same subscription). It is NOT held
     * across the network fetch — only the in-memory diff/apply — so a slow
     * fetch never blocks a delete. The FX thread never takes it, so blocking on
     * the FX executor while holding it cannot deadlock.
     */
    private final Object refreshApplyLock = new Object();
    private ScheduledExecutorService scheduler;

    /**
     * Creates a subscription service using the default data directory and HTTP client.
     *
     * @param configStore the store that holds the parsed servers
     * @param shareLinkParser the parser used to decode share links from subscription content
     */
    public SubscriptionService(ConfigStore configStore, ShareLinkParser shareLinkParser) {
        this(configStore, shareLinkParser,
                resolveDataDir(),
                AppHttpClients.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                SecretSealers.forCurrentPlatform());
    }

    /**
     * The data dir, migrating a pre-port file into it first — except when the
     * dir is redirected for tests, where the "legacy" location is the
     * developer's real profile and migrating would move their live config out
     * of it.
     */
    private static Path resolveDataDir() {
        Path platformDir = PlatformPaths.current().dataDir();
        if (PlatformPaths.isDataDirOverridden()) {
            return platformDir;
        }
        return migrateLegacyDataDir(platformDir, legacyMacDataDir());
    }

    /** The pre-port location: subscriptions.json used to be written mac-style on every OS. */
    private static Path legacyMacDataDir() {
        return Path.of(System.getProperty("user.home"),
                "Library", "Application Support", "VlessClient");
    }

    /**
     * One-time migration for the Windows/Linux ports: earlier builds wrote
     * {@code subscriptions.json} under the macOS-style path on every OS. If
     * the platform-correct location has no file yet but the legacy one does,
     * move it over; an existing platform file always wins. Never throws —
     * on failure the legacy file stays put for manual recovery.
     */
    static Path migrateLegacyDataDir(Path platformDir, Path legacyDir) {
        if (platformDir.equals(legacyDir)) {
            return platformDir;
        }
        Path platformFile = platformDir.resolve(SUBSCRIPTIONS_FILE);
        Path legacyFile = legacyDir.resolve(SUBSCRIPTIONS_FILE);
        if (Files.exists(platformFile) || !Files.exists(legacyFile)) {
            return platformDir;
        }
        try {
            Files.createDirectories(platformDir);
            Files.move(legacyFile, platformFile);
            log.info("Migrated subscriptions from legacy path {} to {}",
                    legacyFile, platformFile);
        } catch (IOException e) {
            log.warn("Could not migrate legacy subscriptions file from {}; "
                    + "continuing with {}", legacyFile, platformDir, e);
        }
        return platformDir;
    }

    /**
     * Test seam: sealing disabled so test suites never write into the real
     * OS keychain. Production always goes through the public constructor.
     */
    SubscriptionService(ConfigStore configStore, ShareLinkParser shareLinkParser,
                         Path dataDir, HttpClient httpClient) {
        this(configStore, shareLinkParser, dataDir, httpClient, SecretSealers.disabled());
    }

    SubscriptionService(ConfigStore configStore, ShareLinkParser shareLinkParser,
                         Path dataDir, HttpClient httpClient, SecretSealer sealer) {
        this.configStore = configStore;
        this.shareLinkParser = shareLinkParser;
        this.dataDir = dataDir;
        this.httpClient = httpClient;
        this.sealer = sealer;
        this.objectMapper = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
        this.subscriptions = FXCollections.observableArrayList();
        loadSubscriptions();
    }

    public ObservableList<Subscription> getSubscriptions() {
        return subscriptions;
    }

    /**
     * Adds a new subscription and immediately refreshes it.
     *
     * @param name the display name for the subscription
     * @param url the URL to fetch subscription content from
     */
    public void addSubscription(String name, String url) {
        Subscription sub = new Subscription();
        sub.setName(name);
        sub.setUrl(url);
        // SubscriptionsViewController adds from a virtual thread, and the list
        // is bound to a TableView. Same rule as ConfigStore: mutate on the FX
        // thread, without holding this monitor while waiting for it.
        FxExecutor.run(() -> {
            synchronized (this) {
                subscriptions.add(sub);
            }
        });
        saveSubscriptions();
        refreshSubscription(sub.getId());
    }

    /**
     * Removes a subscription and all servers it contributed to the config store.
     *
     * @param subscriptionId the id of the subscription to remove
     */
    public void removeSubscription(String subscriptionId) {
        // Same lock refreshSubscription's apply stage takes: a refresh that
        // fetched just before this delete must not re-insert the servers we are
        // about to remove. Reading serverIds inside the lock also means that if
        // a refresh applied first, we delete the ids it just wrote, not a stale
        // set — no orphans either way.
        synchronized (refreshApplyLock) {
            removeSubscriptionLocked(subscriptionId);
        }
    }

    private void removeSubscriptionLocked(String subscriptionId) {
        Subscription sub = findById(subscriptionId);
        if (sub == null) {
            log.warn("Subscription not found for removal: {}", subscriptionId);
            return;
        }
        // One batch rather than one save per server, matching diffAndApply.
        configStore.applyServerBatch(List.of(), List.copyOf(sub.getServerIds()));
        FxExecutor.run(() -> {
            synchronized (this) {
                subscriptions.remove(sub);
            }
        });
        saveSubscriptions();
        Thread.startVirtualThread(() -> sealer.delete(urlSecretKey(sub.getId())));
        log.info("Removed subscription '{}' and {} servers",
                sub.getName(), sub.getServerIds().size());
    }

    /**
     * Fetches and re-parses a single subscription, applying additions, updates, and removals.
     *
     * @param subscriptionId the id of the subscription to refresh
     */
    public void refreshSubscription(String subscriptionId) {
        Subscription sub = findById(subscriptionId);
        if (sub == null) {
            log.warn("Subscription not found for refresh: {}", subscriptionId);
            return;
        }

        String content;
        ParsedContent parsed;
        try {
            content = fetchContent(sub.getUrl());
            parsed = parseContent(content);
        } catch (Exception e) {
            // Scrub before both sinks: the throw sites we control are already
            // redacted, but a message from the JDK (URI parse failures quote
            // the whole URI) or from a future call site is not ours to trust.
            String reason = Redact.urlsIn(e.getMessage() != null ? e.getMessage() : e.toString());
            log.error("Failed to fetch subscription '{}': {}", sub.getName(), reason);
            // Record it: a failed refresh used to be invisible in the UI, so a
            // subscription with a dead URL or an expired token silently went
            // stale while still looking healthy.
            sub.setLastError(reason);
            saveSubscriptions();
            return;
        }

        // A body we could not make sense of is a failure, not an empty
        // subscription. diffAndApply removes everything the fetch did not
        // return, so without this a captive portal, an HTML "token expired"
        // page, or a provider changing format deleted every server of this
        // subscription — and then reported success, because lastError was
        // cleared and lastRefreshedAt bumped a few lines below. It runs
        // hourly in the background, so the user need not be watching.
        //
        // A genuinely empty body is left alone: a provider really can shut all
        // its servers down, and that case has no ambiguity to protect against.
        List<ServerConfig> fetchedServers = parsed.servers();
        if (fetchedServers.isEmpty() && content != null && !content.isBlank()) {
            log.warn("Subscription '{}' returned {} bytes with no usable "
                    + "server links; keeping the {} server(s) already stored",
                    sub.getName(), content.length(), sub.getServerIds().size());
            if (parsed.unsupportedSchemes().isEmpty()) {
                sub.setLastError("The response contained no recognizable server links. "
                        + "The subscription may have expired, or a captive portal "
                        + "may have answered instead of the provider.");
            } else {
                // The list was read fine; it just holds nothing this client
                // can connect to. Say so rather than hinting at expiry.
                sub.setLastError("Every link in the response uses a protocol this "
                        + "app does not support (" + parsed.unsupportedSummary() + ").");
            }
            saveSubscriptions();
            return;
        }

        // Re-validate before applying: the fetch above can take up to 30s, and
        // the user may have deleted this subscription meanwhile. Without the
        // re-check diffAndApply would resolve none of the (now-gone) ids, treat
        // every fetched server as new, and re-insert the whole list as
        // untracked orphans that no future refresh or delete can reach.
        // removeSubscription takes the same lock, so this check and the apply
        // are atomic against a concurrent delete; it also serializes two
        // refreshes of one subscription so they cannot mint duplicate servers.
        synchronized (refreshApplyLock) {
            if (!isRegistered(subscriptionId)) {
                log.info("Subscription '{}' was removed during refresh; "
                        + "discarding the fetched result", sub.getName());
                return;
            }
            // A line the parser could not read looks exactly like a server the
            // provider withdrew, and diffAndApply would delete it. That is the
            // same "silently loses servers and reports success" shape the empty
            // body is already guarded against, only partial - so on any skipped
            // line, keep everything and say so instead of clearing lastError.
            boolean partial = parsed.skipped() > 0;
            applyNamePrefix(fetchedServers, sub.getName());
            long insecure = fetchedServers.stream()
                    .filter(s -> s.getTls() != null && s.getTls().isAllowInsecure())
                    .count();
            if (insecure > 0) {
                // The list is applied as the provider sent it, but silently:
                // a link that turns certificate verification off is one a
                // network attacker on the fetch path could have written.
                log.warn("Subscription '{}': {} server(s) turn certificate verification "
                        + "off (allowInsecure); they are marked in the server list",
                        sub.getName(), insecure);
            }
            diffAndApply(sub, fetchedServers, !partial);

            if (partial) {
                log.warn("Subscription '{}': {} line(s) could not be parsed; "
                        + "keeping every stored server and removing none",
                        sub.getName(), parsed.skipped());
                sub.setLastError(parsed.skipped() + " line(s) in the response could "
                        + "not be read, so no servers were removed. The provider "
                        + "may have changed format, or the response may be "
                        + "truncated.");
            } else {
                sub.setLastError(null);
            }
            if (!parsed.unsupportedSchemes().isEmpty()) {
                // Not an error and not a reason to keep withdrawn servers: the
                // provider also hands out protocols this client lacks.
                log.info("Subscription '{}': {} link(s) left out, protocol not "
                        + "supported ({})", sub.getName(),
                        parsed.unsupportedSchemes().size(), parsed.unsupportedSummary());
            }
            sub.setLastRefreshedAt(System.currentTimeMillis());
            saveSubscriptions();
            log.info("Refreshed subscription '{}': {} servers",
                    sub.getName(), sub.getServerIds().size());
        }
    }

    /**
     * Refreshes every registered subscription.
     */
    public void refreshAll() {
        for (Subscription sub : new ArrayList<>(subscriptions)) {
            refreshSubscription(sub.getId());
        }
    }

    /**
     * Starts hourly background auto-refresh of all subscriptions. No-op if already running.
     */
    public void startAutoRefresh() {
        startAutoRefresh(1, 1, TimeUnit.HOURS);
    }

    /**
     * Schedules auto-refresh with an explicit initial delay and period.
     * Package-private so a test can trigger an immediate cycle instead of
     * waiting an hour.
     */
    void startAutoRefresh(long initialDelay, long period, TimeUnit unit) {
        synchronized (lifecycleLock) {
            if (scheduler != null && !scheduler.isShutdown()) {
                return;
            }
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "subscription-auto-refresh");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(this::guardedRefreshAll, initialDelay, period, unit);
            log.info("Started subscription auto-refresh");
        }
    }

    /**
     * Runs a refresh cycle, swallowing and logging any failure.
     *
     * <p>{@link java.util.concurrent.ScheduledExecutorService#scheduleAtFixedRate
     * scheduleAtFixedRate} cancels all future executions the moment one throws,
     * so a single transient error — a CME from an off-thread list read, an
     * FX-thread timeout inside applyServerBatch — would otherwise stop
     * auto-refresh forever, with no log line and no UI signal, until the app
     * restarts. Guarding here keeps the next hour firing.</p>
     */
    private void guardedRefreshAll() {
        try {
            refreshAll();
        } catch (Exception e) {
            log.error("Scheduled subscription refresh failed", e);
        }
    }

    /**
     * Stops background auto-refresh, waiting for any in-flight refresh to finish. No-op if
     * not running.
     */
    public void stopAutoRefresh() {
        ScheduledExecutorService toShutdown;
        synchronized (lifecycleLock) {
            if (scheduler == null || scheduler.isShutdown()) {
                return;
            }
            toShutdown = scheduler;
            scheduler = null;
        }
        // shutdown() lets an in-flight refresh keep running; we then wait a
        // bounded grace for it so stopAutoRefresh() is synchronous with respect
        // to a quick cycle, but interrupt anything slower (shutdownNow) rather
        // than block shutdown — see SHUTDOWN_GRACE_SECONDS.
        toShutdown.shutdown();
        try {
            if (!toShutdown.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Subscription auto-refresh did not terminate within {}s; "
                        + "forcing shutdown", SHUTDOWN_GRACE_SECONDS);
                toShutdown.shutdownNow();
            }
        } catch (InterruptedException e) {
            toShutdown.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Stopped subscription auto-refresh");
    }

    /**
     * Whether {@code url} is fetched over plaintext {@code http}. Such a
     * subscription is MITM-injectable: a network attacker can rewrite the
     * returned server list to route the user through their own proxy, and any
     * token in the URL travels in the clear. The app warns but does not block
     * — some providers only offer http.
     */
    public static boolean isInsecureHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            String scheme = URI.create(url.trim()).getScheme();
            return scheme != null && scheme.equalsIgnoreCase("http");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    String fetchContent(String url) throws IOException, InterruptedException {
        if (AppHttpClients.isTunnelBrokenWhileConnected()) {
            // The selector falls back to a direct connection in this one
            // state so the updater keeps working on hostile networks. A
            // subscription URL carries the account token, and sending it
            // directly also exposes the user's real address at the exact
            // moment they believe they are tunneled. Fail the refresh instead;
            // the next one runs after the tunnel recovers or is torn down.
            throw new IOException("The tunnel is up but not carrying traffic, so the "
                    + "subscription was not fetched outside it. Reconnect, or disconnect "
                    + "and refresh again.");
        }
        if (isInsecureHttpUrl(url)) {
            // Host only — the path and query can carry an account token.
            String host;
            try {
                host = URI.create(url.trim()).getHost();
            } catch (IllegalArgumentException e) {
                host = "?";
            }
            log.warn("Subscription fetched over plaintext http (MITM/injection "
                    + "risk; prefer https): host={}", host);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Tunl/" + AppVersion.VERSION)
                .build();
        HttpResponse<InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                // Redacted: this message is both logged and persisted into
                // subscriptions.json as lastError, right next to the url field
                // that serializableSubscriptions() deliberately seals. An expired
                // token answering 401 is the ordinary case, so the plain URL here
                // leaked the token into two files on the most common failure.
                throw new IOException("HTTP " + response.statusCode()
                        + " for URL: " + Redact.url(url));
            }
            return readBounded(body, url);
        }
    }

    /**
     * Reads at most {@link #MAX_BODY_BYTES}, then gives up.
     *
     * <p>The body used to be buffered whole by {@code BodyHandlers.ofString()}.
     * Auto-refresh runs this on a timer, unattended, against a URL the user
     * pasted from a provider — so a hostile or simply broken endpoint could
     * grow the heap until the app died, once an hour, with no one watching.</p>
     */
    private static String readBounded(InputStream in, String url) throws IOException {
        byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new IOException("Subscription body exceeds " + MAX_BODY_BYTES
                    + " bytes for URL: " + Redact.url(url));
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    ParsedContent parseContent(String content) {
        if (content == null || content.isBlank()) {
            return new ParsedContent(List.of(), 0);
        }
        String trimmed = content.trim();

        // Try base64 decode first
        if (looksLikeBase64(trimmed)) {
            try {
                String decoded = decodeBase64(trimmed);
                ParsedContent parsed = parseLines(decoded);
                if (!parsed.servers().isEmpty()) {
                    return parsed;
                }
            } catch (Exception e) {
                log.debug("Base64 decode failed, trying other formats: {}", e.getMessage());
            }
        }

        // Try plain text lines (share links)
        ParsedContent parsed = parseLines(trimmed);
        if (!parsed.servers().isEmpty()) {
            return parsed;
        }

        log.warn("Could not parse subscription content (length={})", trimmed.length());
        return parsed;
    }

    private boolean looksLikeBase64(String text) {
        if (text.contains("://")) {
            return false;
        }
        String cleaned = text.replaceAll("\\s+", "");
        return cleaned.matches("^[A-Za-z0-9+/=_-]+$") && cleaned.length() > 20;
    }

    private String decodeBase64(String encoded) {
        String cleaned = encoded.replaceAll("\\s+", "");
        try {
            return new String(Base64.getDecoder().decode(cleaned), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            try {
                return new String(Base64.getUrlDecoder().decode(cleaned), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e2) {
                String padded = cleaned;
                int pad = padded.length() % 4;
                if (pad > 0) {
                    padded = padded + "=".repeat(4 - pad);
                }
                try {
                    return new String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e3) {
                    return new String(Base64.getUrlDecoder().decode(padded),
                            StandardCharsets.UTF_8);
                }
            }
        }
    }

    /**
     * What a fetched body yielded: the servers, how many non-blank lines were
     * rejected as unreadable, and the schemes of the links this client does
     * not implement. The distinction is the whole point — a line the parser
     * cannot read is indistinguishable, to {@code diffAndApply}, from a
     * server the provider withdrew, whereas a {@code tuic://} link is merely a
     * server this client cannot use. Counting the second kind as the first
     * left every mixed-protocol subscription permanently "failed", with
     * removals disabled on every refresh.
     */
    record ParsedContent(List<ServerConfig> servers, int skipped,
                         List<String> unsupportedSchemes) {

        ParsedContent(List<ServerConfig> servers, int skipped) {
            this(servers, skipped, List.of());
        }

        /** The distinct unsupported schemes, comma-separated, for messages. */
        String unsupportedSummary() {
            return String.join(", ", new java.util.TreeSet<>(unsupportedSchemes));
        }
    }

    private ParsedContent parseLines(String text) {
        List<ServerConfig> servers = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        int skipped = 0;
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()
                    || trimmedLine.startsWith("#") || trimmedLine.startsWith("//")) {
                // Blank, or a comment line some providers put in a plain-text
                // list. Neither stands for a server, so neither is "skipped".
                continue;
            }
            try {
                ServerConfig server = shareLinkParser.parse(trimmedLine);
                servers.add(server);
            } catch (ShareLinkParser.UnsupportedSchemeException e) {
                unsupported.add(e.scheme());
                log.debug("Leaving out a {} link: protocol not supported", e.scheme());
            } catch (Exception e) {
                skipped++;
                log.debug("Skipping unparseable line: {}", e.getMessage());
            }
        }
        return new ParsedContent(servers, skipped, List.copyOf(unsupported));
    }

    private void applyNamePrefix(List<ServerConfig> servers, String subscriptionName) {
        for (ServerConfig server : servers) {
            String originalName = server.getName() != null ? server.getName() : "";
            server.setName("[" + subscriptionName + "] " + originalName);
        }
    }

    /**
     * Reconciles the stored servers of one subscription with what the fetch
     * returned, in a single batched save.
     *
     * @param allowRemovals false when the fetch was only partly understood, in
     *                      which case a server missing from the fetched set is
     *                      kept and stays tracked by this subscription
     */
    private void diffAndApply(Subscription sub, List<ServerConfig> fetchedServers,
            boolean allowRemovals) {
        // Build map of existing servers by matching key (address+port+protocol)
        Map<String, ServerConfig> existingByKey = sub.getServerIds().stream()
                .map(configStore::getServerById)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .collect(Collectors.toMap(this::serverKey, Function.identity(),
                        (a, b) -> a));

        Map<String, ServerConfig> fetchedByKey = fetchedServers.stream()
                .collect(Collectors.toMap(this::serverKey, Function.identity(),
                        (a, b) -> a));

        List<String> newServerIds = new ArrayList<>();
        List<ServerConfig> upserts = new ArrayList<>(fetchedByKey.size());
        List<String> removals = new ArrayList<>();

        // Add new or update existing
        for (Map.Entry<String, ServerConfig> entry : fetchedByKey.entrySet()) {
            String key = entry.getKey();
            ServerConfig fetched = entry.getValue();
            ServerConfig existing = existingByKey.get(key);

            if (existing != null) {
                // Update: keep the existing ID, update fields
                fetched.setId(existing.getId());
                fetched.setActive(existing.isActive());
                newServerIds.add(existing.getId());
            } else {
                newServerIds.add(fetched.getId());
            }
            upserts.add(fetched);
        }

        // Remove servers that are no longer in the subscription
        for (Map.Entry<String, ServerConfig> entry : existingByKey.entrySet()) {
            if (!fetchedByKey.containsKey(entry.getKey())) {
                if (allowRemovals) {
                    removals.add(entry.getValue().getId());
                } else {
                    // Keeping the server is only half of it: dropping its id
                    // here would leave it in the store with no subscription
                    // owning it, which no later refresh or delete could reach.
                    newServerIds.add(entry.getValue().getId());
                }
            }
        }

        // One save for the whole refresh. Going through addServer/updateServer/
        // removeServer meant a full save — and a full re-seal of every stored
        // credential — per changed server.
        configStore.applyServerBatch(upserts, removals);

        sub.setServerIds(newServerIds);
    }

    private String serverKey(ServerConfig server) {
        return server.getAddress() + ":" + server.getPort() + ":" + server.getProtocol();
    }

    private Subscription findById(String id) {
        return subscriptions.stream()
                .filter(s -> s.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    /**
     * Whether a subscription with this id is still registered. Uses an explicit
     * synchronized scan rather than the streaming findById so it cannot throw a
     * ConcurrentModificationException against an FX-thread list mutation while
     * refreshSubscription re-checks existence at apply time.
     */
    private boolean isRegistered(String id) {
        synchronized (this) {
            for (Subscription s : subscriptions) {
                if (s.getId().equals(id)) {
                    return true;
                }
            }
            return false;
        }
    }

    synchronized void saveSubscriptions() {
        Path file = dataDir.resolve(SUBSCRIPTIONS_FILE);
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("config_version", SUBSCRIPTIONS_CONFIG_VERSION);
            envelope.set("subscriptions",
                    objectMapper.valueToTree(serializableSubscriptions()));
            SecureFiles.writePrivately(file, objectMapper.writeValueAsBytes(envelope));
        } catch (IOException e) {
            log.error("Failed to save subscriptions to {}", file, e);
        }
    }

    /**
     * The on-disk view of the subscription list. Subscription URLs usually
     * embed the account token, so they get the same treatment as server
     * credentials: plaintext in memory, sealed in the file when secure
     * storage is enabled and a backend is available. A failed seal keeps the
     * plaintext — a readable config always wins over a lost URL.
     */
    private List<Subscription> serializableSubscriptions() {
        boolean sealing = configStore.getSettings().isStoreSecretsSecurely()
                && sealer.isAvailable();
        if (!sealing) {
            return new ArrayList<>(subscriptions);
        }
        List<Subscription> out = new ArrayList<>(subscriptions.size());
        for (Subscription live : subscriptions) {
            String url = live.getUrl();
            if (url == null || url.isBlank() || SecretSealer.isSealed(url)) {
                out.add(live);
                continue;
            }
            String sealed = sealer.seal(urlSecretKey(live.getId()), url);
            if (sealed == null) {
                log.warn("Could not seal URL for subscription '{}'; keeping plaintext",
                        live.getName());
                out.add(live);
                continue;
            }
            try {
                Subscription copy = objectMapper.readValue(
                        objectMapper.writeValueAsString(live), Subscription.class);
                copy.setUrl(sealed);
                out.add(copy);
            } catch (JacksonException e) {
                log.warn("Could not copy subscription '{}' for sealing; keeping plaintext",
                        live.getName(), e);
                out.add(live);
            }
        }
        return out;
    }

    private static String urlSecretKey(String subscriptionId) {
        return subscriptionId + ".url";
    }

    private void loadSubscriptions() {
        Path file = dataDir.resolve(SUBSCRIPTIONS_FILE);
        if (!Files.exists(file)) {
            log.info("No subscriptions file found at {}, starting with empty list", file);
            return;
        }
        SecureFiles.restrictExisting(file);
        try {
            JsonNode root = objectMapper.readTree(file.toFile());
            JsonNode items;
            if (root.isArray()) {
                // v0: pre-envelope bare array — back up once, upgrade on save.
                ConfigStore.backupLegacyOnce(file);
                items = root;
                log.info("subscriptions.json is the legacy (v0) array format; "
                        + "it will be upgraded to v{} on the next save",
                        SUBSCRIPTIONS_CONFIG_VERSION);
            } else {
                int version = root.path("config_version").asInt(0);
                if (version > SUBSCRIPTIONS_CONFIG_VERSION) {
                    log.warn("subscriptions.json has config_version {} (this "
                            + "build understands {}); reading best-effort",
                            version, SUBSCRIPTIONS_CONFIG_VERSION);
                }
                // Future incompatible versions dispatch their migrations here.
                items = root.path("subscriptions");
            }
            if (!items.isArray()) {
                log.error("subscriptions.json has no readable list; leaving it empty");
                return;
            }
            List<Subscription> loaded = objectMapper.convertValue(
                    items, new TypeReference<List<Subscription>>() {});
            loaded.forEach(this::unsealInPlace);
            subscriptions.addAll(loaded);
            log.info("Loaded {} subscriptions from {}", subscriptions.size(), file);
        } catch (JacksonException e) {
            log.error("Failed to load subscriptions from {}", file, e);
            // Same treatment as servers.json and settings.json: the next save
            // would otherwise overwrite the only copy of a file that was very
            // likely still recoverable by hand — and the sealed URLs in it
            // are the keys the keychain entries are filed under.
            ConfigStore.quarantineCorrupt(file);
        }
    }

    /**
     * Restores the in-memory plaintext URL for a sealed subscription. On
     * failure the tag is kept so the entry stays visible; refreshing will
     * fail until the URL is re-entered or the backend entry reappears.
     */
    private void unsealInPlace(Subscription subscription) {
        String stored = subscription.getUrl();
        if (!SecretSealer.isSealed(stored)) {
            return;
        }
        sealer.unseal(urlSecretKey(subscription.getId()), stored).ifPresentOrElse(
                subscription::setUrl,
                () -> log.error(
                        "Could not unseal the URL for subscription '{}' ({}); "
                                + "re-add it or restore the secret backend entry",
                        subscription.getName(), subscription.getId()));
    }
}
