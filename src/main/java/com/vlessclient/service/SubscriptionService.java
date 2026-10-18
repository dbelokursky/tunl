package com.vlessclient.service;

import com.vlessclient.app.AppVersion;
import com.vlessclient.app.I18n;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.Subscription;
import com.vlessclient.platform.PlatformPaths;
import com.vlessclient.platform.SecretSealer;
import com.vlessclient.platform.SecretSealers;
import com.vlessclient.platform.SecureFiles;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Manages subscriptions: fetching remote server lists, parsing them, and keeping the
 * backing {@link ConfigStore} in sync, with optional periodic auto-refresh.
 */
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    /** The subscription list's file in the data directory, as save failures name it. */
    public static final String SUBSCRIPTIONS_FILE = "subscriptions.json";

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

    /**
     * The subscriptions.json entries this build could not read, as they were
     * read, written back by every save. Guarded by this object's monitor.
     */
    private final List<JsonNode> unreadableSubscriptions = new ArrayList<>();

    /**
     * The envelope's top-level fields other than the version and the list,
     * as read, so a newer build's fields survive this build's saves. Guarded
     * by this object's monitor.
     */
    private ObjectNode envelopeExtras;
    private final ObservableList<Subscription> subscriptions;
    private final ConfigStore configStore;
    private final ShareLinkParser shareLinkParser;
    private final HttpClient httpClient;
    private final SecretSealer sealer;

    /**
     * The last URL sealed under each subscription id, and the tag it produced.
     * Every save used to seal every URL again, one secret-tool process each on
     * macOS, and a refresh saves the list. Loading fills it with the tags the
     * file already holds. Guarded by this object's monitor.
     */
    private final Map<String, SealedUrl> urlSealCache = new HashMap<>();

    /** A sealed URL and the plaintext that produced it. */
    private record SealedUrl(String plaintext, String tag) {
    }

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
     * Set once the app starts quitting ({@link #beginShutdown()}): no fetch is
     * sent after it, a refresh cycle stops before the next subscription, and
     * a refresh the quit cut short records nothing.
     */
    private volatile boolean closing;

    /**
     * The headers of the response the current thread's last
     * {@link #fetchContent} read. A side channel on purpose: fetchContent's
     * String contract is the seam every test double overrides, and a double
     * that never sets this reads as "the provider sent no quota headers".
     */
    private final ThreadLocal<HttpHeaders> responseHeaders = new ThreadLocal<>();

    /**
     * Creates a subscription service using the default data directory and HTTP client.
     *
     * @param configStore the store that holds the parsed servers
     * @param shareLinkParser the parser used to decode share links from subscription content
     */
    public SubscriptionService(ConfigStore configStore, ShareLinkParser shareLinkParser) {
        this(configStore, shareLinkParser, SecretSealers.forCurrentPlatform());
    }

    /**
     * Creates a subscription service with an explicit sealer for the stored
     * URLs; the headless UI test graph passes {@link SecretSealers#disabled()}
     * so a test never reaches the OS keychain.
     *
     * @param configStore     the store that holds the parsed servers
     * @param shareLinkParser the parser used to decode share links
     * @param sealer          how subscription URLs are protected at rest
     */
    public SubscriptionService(ConfigStore configStore, ShareLinkParser shareLinkParser,
                               SecretSealer sealer) {
        this(configStore, shareLinkParser,
                resolveDataDir(),
                AppHttpClients.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                sealer);
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
        adoptStampedServers();
    }

    public ObservableList<Subscription> getSubscriptions() {
        return subscriptions;
    }

    /**
     * Adds a new subscription and immediately refreshes it.
     *
     * @param name the display name for the subscription
     * @param url the URL to fetch subscription content from, or another
     *            client's link that wraps it ({@link SubscriptionLinks})
     * @throws IllegalArgumentException for a link Happ encrypted for itself
     */
    public void addSubscription(String name, String url) {
        Subscription sub = new Subscription();
        sub.setName(name);
        sub.setUrl(fetchableUrl(url));
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
        List<String> members = membersOf(sub).stream().map(ServerConfig::getId).toList();
        configStore.applyServerBatch(List.of(), members);
        FxExecutor.run(() -> {
            synchronized (this) {
                subscriptions.remove(sub);
            }
        });
        synchronized (this) {
            urlSealCache.remove(sub.getId());
        }
        saveSubscriptions();
        Thread.startVirtualThread(() -> sealer.delete(urlSecretKey(sub.getId())));
        log.info("Removed subscription '{}' and {} servers", sub.getName(), members.size());
    }

    /**
     * The last refresh's failure as the app words it: one the app worded
     * itself is rendered from its key now, in the current language; a sentence
     * from an older file is shown as it stands, because that is all such a
     * file holds.
     *
     * @param sub a subscription whose last refresh failed
     * @return the failure's text
     */
    public static String failureText(Subscription sub) {
        String key = sub.getLastErrorKey();
        if (key != null && !key.isBlank()) {
            return I18n.get(key, sub.getLastErrorArgs().toArray());
        }
        return sub.getLastError();
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
        // The link this refresh reads. An edit can point the subscription at
        // another one while the fetch waits, and what the old link answers
        // then is no longer the subscription's (see stillFetchedFrom).
        String fetchedUrl = urlOf(sub);

        String content;
        ParsedContent parsed;
        HttpHeaders headers;
        try {
            responseHeaders.remove();
            content = fetchContent(fetchedUrl);
            headers = responseHeaders.get();
            parsed = parseContent(content);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (closing || Thread.currentThread().isInterrupted()) {
                // Stopped, not failed: an interrupt or the closed client is
                // how quitting stops a refresh. Caught as a failure, the quit
                // went into the file as the provider's ("closed",
                // "java.lang.InterruptedException"), the rows showed it at
                // the next launch, and the swallowed interrupt let the cycle
                // go on to the next subscription.
                log.info("Refresh of subscription '{}' stopped before it finished",
                        sub.getName());
                return;
            }
            // Scrub before both sinks: the throw sites we control are already
            // redacted, but a message from the JDK (URI parse failures quote
            // the whole URI) or from a future call site is not ours to trust.
            String reason = Redact.urlsIn(e.getMessage() != null ? e.getMessage() : e.toString());
            log.error("Failed to fetch subscription '{}': {}", sub.getName(), reason);
            // Record it: a failed refresh used to be invisible in the UI, so a
            // subscription with a dead URL or an expired token silently went
            // stale while still looking healthy. In the app's own words where
            // it knows the failure, the technical reason where it does not.
            // A panel that refuses this device can say so with a failure
            // status too (3x-ui: 404), and its headers say more than the code.
            Declined deviceRefused = deviceRefusal(responseHeaders.get());
            Declined worded = deviceRefused != null
                    ? deviceRefused : wordedFailure(e, fetchedUrl);
            recordIfCurrent(sub, fetchedUrl, () -> {
                if (worded != null) {
                    sub.recordFailure(worded.key(), worded.args());
                } else {
                    sub.recordFailure(reason);
                }
            });
            return;
        }

        // An answer that is not the provider's list, though it came with a
        // 200: a panel declining this request, or a list of messages. Taken
        // for the list, it deleted every server of this subscription, or put
        // the messages in their place, and reported success.
        Declined declined = declined(headers, content, parsed.servers());
        if (declined != null) {
            log.warn("Subscription '{}' answered without its servers ({}); keeping the {} "
                    + "stored", sub.getName(), declined.key(), sub.getServerIds().size());
            recordIfCurrent(sub, fetchedUrl,
                    () -> sub.recordFailure(declined.key(), declined.args()));
            return;
        }

        // A body we could not make sense of is a failure, not an empty
        // subscription. diffAndApply removes everything the fetch did not
        // return, so without this a captive portal, an HTML "token expired"
        // page, or a provider changing format deleted every server of this
        // subscription — and then reported success, because lastError was
        // cleared and lastRefreshedAt bumped a few lines below. It runs
        // hourly in the background, so the user need not be watching.
        List<ServerConfig> fetchedServers = parsed.servers().stream()
                .filter(server -> !isMessageEntry(server))
                .toList();
        if (fetchedServers.isEmpty() && content != null && !content.isBlank()) {
            log.warn("Subscription '{}' returned {} bytes with no usable "
                    + "server links; keeping the {} server(s) already stored",
                    sub.getName(), content.length(), sub.getServerIds().size());
            recordIfCurrent(sub, fetchedUrl, () -> {
                if (parsed.unsupportedSchemes().isEmpty()) {
                    sub.recordFailure("subscriptions.error.no.links", List.of());
                } else {
                    // The list was read fine; it just holds nothing this client
                    // can connect to. Say so rather than hinting at expiry.
                    sub.recordFailure("subscriptions.error.unsupported",
                            List.of(parsed.unsupportedSummary()));
                }
            });
            return;
        }

        // Re-validate before applying: the fetch above can take up to 30s, and
        // the user may have deleted this subscription meanwhile. Without the
        // re-check diffAndApply would resolve none of the (now-gone) ids, treat
        // every fetched server as new, and re-insert the whole list as
        // untracked orphans that no future refresh or delete can reach.
        // Or the user pointed it at another link meanwhile, and the refresh
        // that edit started has already applied the new link's list: the old
        // link's late answer put the old servers, quota and interval back
        // over it, for a whole interval.
        // removeSubscription takes the same lock, so this check and the apply
        // are atomic against a concurrent delete; it also serializes two
        // refreshes of one subscription so they cannot mint duplicate servers.
        synchronized (refreshApplyLock) {
            if (!stillFetchedFrom(subscriptionId, fetchedUrl)) {
                log.info("Subscription '{}' was removed or given another link during "
                        + "refresh; discarding the fetched result", sub.getName());
                return;
            }
            if (closing) {
                // The next launch refreshes it; applied now, the batch waits
                // on an FX thread that is going away.
                log.info("Quitting; discarding the fetched result of subscription '{}'",
                        sub.getName());
                return;
            }
            // Before the servers take the name as their prefix.
            nameIfUnnamed(sub, headers);
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
                sub.recordFailure("subscriptions.error.partial",
                        List.of(String.valueOf(parsed.skipped())));
            } else {
                sub.clearLastError();
            }
            // Said on the row: a mixed list lost them with only the line below.
            sub.setLeftOut(parsed.unsupportedSchemes().size(), parsed.unsupportedSummary());
            if (!parsed.unsupportedSchemes().isEmpty()) {
                // Not an error and not a reason to keep withdrawn servers: the
                // provider also hands out protocols this client lacks.
                log.info("Subscription '{}': {} link(s) left out, protocol not "
                        + "supported ({})", sub.getName(),
                        parsed.unsupportedSchemes().size(), parsed.unsupportedSummary());
            }
            if (headers != null) {
                applyUserInfo(sub, headers);
            }
            sub.setAnnounce(announce(headers));
            sub.setUpdateIntervalHours(updateIntervalHours(headers));
            sub.setLastRefreshedAt(System.currentTimeMillis());
            saveSubscriptions();
            log.info("Refreshed subscription '{}': {} servers",
                    sub.getName(), sub.getServerIds().size());
        }
    }

    /** The subscription's link, read under the monitor its edit writes it under. */
    private String urlOf(Subscription sub) {
        synchronized (this) {
            return sub.getUrl();
        }
    }

    /**
     * Whether the subscription is still registered and still has the link a
     * refresh fetched. A scan under the monitor rather than a stream, so it
     * cannot throw a ConcurrentModificationException against an FX-thread
     * change of the list.
     */
    private boolean stillFetchedFrom(String subscriptionId, String fetchedUrl) {
        synchronized (this) {
            for (Subscription s : subscriptions) {
                if (s.getId().equals(subscriptionId)) {
                    return java.util.Objects.equals(s.getUrl(), fetchedUrl);
                }
            }
            return false;
        }
    }

    /**
     * Records a refresh's failure and saves it, unless the subscription was
     * deleted or given another link while the refresh ran, or the app began
     * quitting. The failure of a link the subscription no longer has said
     * nothing about the one it has, and stayed on its row until the next
     * refresh, over a new link that had just worked.
     */
    private void recordIfCurrent(Subscription sub, String fetchedUrl, Runnable record) {
        synchronized (refreshApplyLock) {
            if (closing || !stillFetchedFrom(sub.getId(), fetchedUrl)) {
                log.info("Not recording the failed refresh of subscription '{}': it was "
                        + "removed or given another link meanwhile", sub.getName());
                return;
            }
            record.run();
            saveSubscriptions();
        }
    }

    /**
     * A refresh that got an answer but not the provider's list: the key and
     * arguments of the failure to record.
     */
    record Declined(String key, List<String> args) {
    }

    /** A provider's answer other than 200, with its status for the worded failure. */
    static final class HttpStatusException extends IOException {

        private final int status;

        HttpStatusException(int status, String message) {
            super(message);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    /** The tunnel is up but carries nothing, so the refresh was not sent outside it. */
    static final class TunnelNotCarryingException extends IOException {

        TunnelNotCarryingException(String message) {
            super(message);
        }
    }

    /**
     * A failed fetch in the app's own words, when it is one of the usual
     * failures, or null to keep the technical reason. The reason used to be
     * the exception's message as it came, in English in every language, and
     * for a provider whose name does not resolve the JDK gives no message at
     * all, so the row read "java.net.ConnectException".
     *
     * @param failure what the fetch threw
     * @param url     the subscription's URL, for the host of a name that did
     *                not resolve; nothing else of it is kept
     * @return the key and arguments to record, or null
     */
    static Declined wordedFailure(Throwable failure, String url) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpStatusException status) {
                return httpFailure(status.status());
            }
            if (cause instanceof TunnelNotCarryingException) {
                return new Declined("subscriptions.error.tunnel", List.of());
            }
            if (cause instanceof UnresolvedAddressException
                    || cause instanceof UnknownHostException) {
                return new Declined("subscriptions.error.dns", List.of(hostOf(url)));
            }
            if (cause instanceof HttpTimeoutException) {
                return new Declined("subscriptions.error.timeout", List.of());
            }
            if (cause instanceof SSLException) {
                return new Declined("subscriptions.error.tls", List.of());
            }
        }
        // Refused or cut, once no cause above says more: the JDK wraps a name
        // that does not resolve in a ConnectException too.
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException) {
                return new Declined("subscriptions.error.connect", List.of());
            }
        }
        return null;
    }

    private static Declined httpFailure(int status) {
        String code = String.valueOf(status);
        if (status == 401 || status == 403) {
            return new Declined("subscriptions.error.http.denied", List.of(code));
        }
        if (status == 404 || status == 410) {
            return new Declined("subscriptions.error.http.gone", List.of(code));
        }
        if (status == 429 || status >= 500) {
            return new Declined("subscriptions.error.http.later", List.of(code));
        }
        return new Declined("subscriptions.error.http", List.of(code));
    }

    /** The host of a subscription URL: the path and the query carry the token. */
    private static String hostOf(String url) {
        if (url == null) {
            return "?";
        }
        try {
            String host = URI.create(url.strip()).getHost();
            return host != null ? host : "?";
        } catch (IllegalArgumentException e) {
            return "?";
        }
    }

    /** Longest provider message kept for the subscription's status line. */
    private static final int MAX_PROVIDER_MESSAGE = 300;

    /**
     * Why an answer is not the provider's server list, or null when it is.
     *
     * <p>A panel that declines a request does not always say so with its
     * status. With its device limit on, Remnawave answers HTTP 200: with an
     * empty body, or with entries that only carry a message, and says why in
     * {@code x-hwid-*} headers; an expired or disabled account gets message
     * entries too. A message entry is a link to {@code 0.0.0.0}, which is
     * not a server anyone can reach.</p>
     *
     * <p>An empty answer is declined as well, even with no header to explain
     * it. A provider really can shut down, but the user can delete a
     * subscription that has ended, and a refresh runs hourly with nobody
     * watching.</p>
     */
    static Declined declined(HttpHeaders headers, String content, List<ServerConfig> fetched) {
        Declined device = deviceRefusal(headers);
        if (device != null) {
            return device;
        }
        if (!fetched.isEmpty() && fetched.stream().allMatch(SubscriptionService::isMessageEntry)) {
            String messages = fetched.stream()
                    .map(ServerConfig::getName)
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .collect(java.util.stream.Collectors.joining("; "));
            return new Declined("subscriptions.error.message", List.of(bounded(messages)));
        }
        if (content == null || content.isBlank()) {
            return new Declined("subscriptions.error.empty", List.of());
        }
        return null;
    }

    /**
     * A panel refusing this device, by its {@code x-hwid-*} headers, or null.
     *
     * <p>Remnawave says it with HTTP 200 and 3x-ui (3.7 and later) with 404, so
     * the headers are read whatever the status; on a 404 alone the row said
     * the provider had no subscription at this link.</p>
     *
     * @param headers the answer's headers, or null when there was no answer
     * @return the refusal to record, or null
     */
    static Declined deviceRefusal(HttpHeaders headers) {
        if (headerIsTrue(headers, "x-hwid-max-devices-reached")) {
            String announce = announce(headers);
            return announce.isEmpty()
                    ? new Declined("subscriptions.error.device.limit.plain", List.of())
                    : new Declined("subscriptions.error.device.limit", List.of(announce));
        }
        if (headerIsTrue(headers, "x-hwid-not-supported")) {
            return new Declined("subscriptions.error.device.id", List.of());
        }
        return null;
    }

    /**
     * Whether an entry is a message rather than a server: a link to the
     * unspecified address, which Remnawave builds to carry a line of text
     * ("App not supported", "Subscription expired") where clients show names.
     */
    static boolean isMessageEntry(ServerConfig server) {
        String address = server.getAddress() == null ? "" : server.getAddress().strip();
        return address.equals("0.0.0.0") || address.equals("::")
                || address.equals("[::]") || address.equals("0:0:0:0:0:0:0:0");
    }

    private static boolean headerIsTrue(HttpHeaders headers, String name) {
        return headers != null && headers.firstValue(name)
                .map(value -> value.strip().equalsIgnoreCase("true"))
                .orElse(false);
    }

    /**
     * The provider's {@code announce} header, which panels send base64
     * encoded behind a {@code base64:} prefix, as one clean line; empty when
     * there is none.
     */
    static String announce(HttpHeaders headers) {
        return providerText(headers, "announce");
    }

    /**
     * A header of provider text, such as {@code announce} or
     * {@code profile-title}, which panels send plain or base64 encoded
     * behind a {@code base64:} prefix: as one clean line, empty when there is
     * none.
     */
    static String providerText(HttpHeaders headers, String name) {
        if (headers == null) {
            return "";
        }
        String value = headers.firstValue(name).orElse("").strip();
        if (value.regionMatches(true, 0, "base64:", 0, "base64:".length())) {
            try {
                value = Base64Lenient.decodeUtf8(value.substring("base64:".length()).strip());
            } catch (IllegalArgumentException e) {
                return "";
            }
        }
        return bounded(value);
    }

    /**
     * Names a subscription that was added without a name: after the
     * provider's {@code profile-title}, or after the host of its URL when the
     * provider sends none. A name the user gave stays as it is.
     */
    static void nameIfUnnamed(Subscription sub, HttpHeaders headers) {
        if (sub.getName() != null && !sub.getName().isBlank()) {
            return;
        }
        String title = providerText(headers, "profile-title");
        if (!title.isEmpty()) {
            sub.setName(title);
            return;
        }
        // hostOf answers "?" for a URL it cannot read. That is a placeholder
        // for an error line, not a name: leave the subscription unnamed and
        // let the row show what it shows for one.
        String host = hostOf(sub.getUrl());
        if (!"?".equals(host)) {
            sub.setName(host);
        }
    }

    /** One line of provider text, safe to store and show, and not too long. */
    private static String bounded(String text) {
        String cleaned = text.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ").strip();
        return cleaned.length() > MAX_PROVIDER_MESSAGE
                ? cleaned.substring(0, MAX_PROVIDER_MESSAGE - 1) + "…"
                : cleaned;
    }

    /**
     * Reads the quota a provider attaches to the response, in the
     * {@code subscription-userinfo} header Clash-style clients popularized:
     * {@code upload=…; download=…; total=…; expire=…}, bytes and Unix seconds.
     * A header that is absent changes nothing, so a provider that stops
     * sending it leaves the last known figures in place.
     *
     * @param sub     the subscription to annotate
     * @param headers the response headers
     */
    static void applyUserInfo(Subscription sub, HttpHeaders headers) {
        headers.firstValue("subscription-userinfo").ifPresent(value -> {
            for (String part : value.split(";")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length != 2) {
                    continue;
                }
                long number;
                try {
                    number = Long.parseLong(pair[1].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                switch (pair[0].trim().toLowerCase(java.util.Locale.ROOT)) {
                    case "upload" -> sub.setUploadBytes(number);
                    case "download" -> sub.setDownloadBytes(number);
                    case "total" -> sub.setTotalBytes(number);
                    case "expire" -> {
                        // 0 says the plan does not expire; a value no date can
                        // show is noise, and the row would throw rendering it.
                        if (number >= 0 && number <= Subscription.LAST_EXPIRY) {
                            sub.setExpiresAt(number);
                        }
                    }
                    default -> { }
                }
            }
        });
    }

    /**
     * Renames a subscription or points it at a new URL, then refreshes it so
     * the server names carry the new prefix and a new URL's list replaces the
     * old one. Changing a URL used to mean deleting the subscription (and its
     * servers) and adding it again.
     *
     * @param subscriptionId the subscription to change
     * @param name           the new display name
     * @param url            the new URL; sealed on save like the original
     */
    public void updateSubscription(String subscriptionId, String name, String url) {
        Subscription sub = findById(subscriptionId);
        if (sub == null) {
            log.warn("Subscription not found for update: {}", subscriptionId);
            return;
        }
        String fetchable = fetchableUrl(url);
        FxExecutor.run(() -> {
            synchronized (this) {
                sub.setName(name);
                sub.setUrl(fetchable);
            }
        });
        saveSubscriptions();
        refreshSubscription(subscriptionId);
    }

    /**
     * The URL to store for what the user gave: the subscription URL another
     * client's link wraps, or the text as it is. Saved as it was, a wrapped
     * link failed every refresh on its scheme.
     *
     * @throws IllegalArgumentException for a link Happ encrypted for itself,
     *                                  which holds no URL anyone else can read
     */
    private static String fetchableUrl(String url) {
        if (SubscriptionLinks.encryptedForHapp(url)) {
            throw new IllegalArgumentException(I18n.get("subscriptions.link.happ.encrypted"));
        }
        return SubscriptionLinks.subscriptionUrl(url).orElse(url);
    }

    /**
     * Refreshes every registered subscription.
     */
    public void refreshAll() {
        for (Subscription sub : new ArrayList<>(subscriptions)) {
            refreshSubscription(sub.getId());
        }
    }

    /** How soon after start the auto-refresh first looks for due subscriptions. */
    private static final long FIRST_CHECK_SECONDS = 60;

    /** How often it looks again. */
    private static final long DUE_CHECK_SECONDS = 5 * 60;

    /** The refresh interval when the provider names none: hourly, as before. */
    static final Duration DEFAULT_INTERVAL = Duration.ofHours(1);

    /** The longest interval a provider may ask for: a week. */
    private static final int MAX_INTERVAL_HOURS = 7 * 24;

    /** When each subscription was last tried in this run, successful or not. */
    private final Map<String, Long> lastAttemptAt = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Starts the background auto-refresh. No-op if already running.
     *
     * <p>It used to refresh every subscription hourly, the first time an hour
     * after launch: a list last refreshed yesterday stayed stale for the first
     * hour of a session, however often the provider changed it. Now a minute
     * after start, and every five minutes after, it refreshes the ones due
     * ({@link #refreshDue()}).</p>
     */
    public void startAutoRefresh() {
        startAutoRefresh(FIRST_CHECK_SECONDS, DUE_CHECK_SECONDS, TimeUnit.SECONDS);
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
            scheduler = Executors.newSingleThreadScheduledExecutor(
                    DaemonThreads.factory("subscription-auto-refresh"));
            scheduler.scheduleAtFixedRate(this::guardedRefreshDue, initialDelay, period, unit);
            log.info("Started subscription auto-refresh");
        }
    }

    /**
     * Refreshes the subscriptions due a refresh: the ones whose list is older
     * than their provider's interval ({@code profile-update-interval}), or an
     * hour, and that were not tried within the last hour, or within the
     * interval if it is shorter, so a failing one is not tried at every check.
     *
     * <p>The interval is how often a provider wants a list it has served read
     * again, not a wait after a fetch that never reached it. Counted from the
     * last attempt as well, a failure pushed the next try a whole interval
     * away: a list a 12 h provider could not be reached for, at a start
     * before the network was up, stayed stale and the row said "could not
     * connect" for twelve more hours.</p>
     */
    void refreshDue() {
        refreshDue(System.currentTimeMillis());
    }

    /** {@link #refreshDue()} as of {@code now}; a test seam, since the schedule spans hours. */
    void refreshDue(long now) {
        for (Subscription sub : new ArrayList<>(subscriptions)) {
            if (closing || Thread.currentThread().isInterrupted()) {
                // A quit stops the cycle here: the next fetch would go out
                // as the tunnel's routing is lifted, or fail on the closed
                // client and be recorded as the provider's failure.
                log.info("Subscription refresh cycle stopped by the quit");
                return;
            }
            Duration interval = intervalOf(sub);
            long sinceRefreshed = now - sub.getLastRefreshedAt();
            long sinceTried = now - lastAttemptAt.getOrDefault(sub.getId(), 0L);
            long retryAfter = Math.min(interval.toMillis(), DEFAULT_INTERVAL.toMillis());
            if (sinceRefreshed >= interval.toMillis() && sinceTried >= retryAfter) {
                lastAttemptAt.put(sub.getId(), now);
                refreshSubscription(sub.getId());
                if ("subscriptions.error.tunnel".equals(sub.getLastErrorKey())) {
                    // Declined, not tried: the tunnel the user wants is not up
                    // yet, as at the first check after an auto-connect. The
                    // next check tries again rather than an interval later.
                    lastAttemptAt.remove(sub.getId());
                }
            }
        }
    }

    /**
     * How often a subscription is refreshed.
     *
     * @param sub the subscription
     * @return its provider's interval, at most a week, or an hour
     */
    static Duration intervalOf(Subscription sub) {
        int hours = sub.getUpdateIntervalHours();
        return hours > 0 ? Duration.ofHours(Math.min(hours, MAX_INTERVAL_HOURS)) : DEFAULT_INTERVAL;
    }

    /**
     * The provider's {@code profile-update-interval}, in hours; 0 when absent
     * or unreadable.
     */
    static int updateIntervalHours(HttpHeaders headers) {
        if (headers == null) {
            return 0;
        }
        try {
            return headers.firstValue("profile-update-interval")
                    .map(value -> Integer.parseInt(value.strip()))
                    .filter(hours -> hours > 0)
                    .orElse(0);
        } catch (NumberFormatException e) {
            return 0;
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
     * restarts. Guarding here keeps the next check firing.</p>
     */
    private void guardedRefreshDue() {
        try {
            refreshDue();
        } catch (Exception e) {
            log.error("Scheduled subscription refresh failed", e);
        }
    }

    /**
     * Stops the auto-refresh for good and releases the HTTP client; for the
     * service graph going away, where {@link #stopAutoRefresh()} alone left
     * the client's selector thread behind.
     */
    public void shutdown() {
        beginShutdown();
        stopAutoRefresh();
        if (httpClient != null) {
            httpClient.shutdownNow();
        }
    }

    /**
     * Sends no fetch from now on, without waiting for one under way: what a
     * quit does first, before it lifts the routing that keeps a fetch inside
     * the tunnel. The refreshes were stopped only after the core, seconds
     * later, and a cycle that went on meanwhile sent the next subscription's
     * link, account token and all, straight to the provider from the user's
     * real address. A refresh it cuts short records nothing.
     */
    public void beginShutdown() {
        closing = true;
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
        if (closing) {
            // The quit lifts the tunnel's routing next (beginShutdown).
            throw new IOException("Quitting; the subscription was not fetched.");
        }
        if (AppHttpClients.isTunnelWantedButNotCarrying()) {
            // The selector falls back to a direct connection while the tunnel
            // carries nothing, so the updater keeps working on hostile
            // networks. A subscription URL carries the account token, and
            // sending it directly also exposes the user's real address at the
            // exact moment they believe they are tunneled. Fail the refresh
            // instead; the next one runs on schedule.
            throw new TunnelNotCarryingException("The tunnel is wanted but not carrying "
                    + "traffic, so the subscription was not fetched outside it.");
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
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Tunl/" + AppVersion.VERSION);
        // A panel that limits devices per plan serves a client only when it
        // names its device; without these Remnawave answers with an empty
        // list or a list of messages. The id is random per install, not
        // taken from the hardware.
        request.header("x-hwid", configStore.deviceId())
                .header("x-device-os", deviceOs())
                .header("x-ver-os", System.getProperty("os.version", ""));
        HttpResponse<InputStream> response =
                httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        responseHeaders.set(response.headers());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                // Redacted: this message is both logged and persisted into
                // subscriptions.json as lastError, right next to the url field
                // that serializableSubscriptions() deliberately seals. An expired
                // token answering 401 is the ordinary case, so the plain URL here
                // leaked the token into two files on the most common failure.
                throw new HttpStatusException(response.statusCode(), "HTTP "
                        + response.statusCode() + " for URL: " + Redact.url(url));
            }
            return readBounded(body, url);
        }
    }

    /** The operating system as panels list devices: macOS, Windows or Linux. */
    private static String deviceOs() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("mac")) {
            return "macOS";
        }
        return os.contains("win") ? "Windows" : "Linux";
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
        // Without the byte order mark of a list saved with one, which trim()
        // keeps: the base64 check failed on it, the blob was read as one
        // unreadable line, and the subscription never imported a server.
        String trimmed = ShareLinkParser.withoutByteOrderMark(content).trim();

        // Try base64 decode first
        if (looksLikeBase64(trimmed)) {
            try {
                // And of a list base64-encoded from such a file, whose mark
                // the decoding brings back: before a first line that is a
                // comment, it made that line an unreadable server, and every
                // refresh partial.
                String decoded = ShareLinkParser.withoutByteOrderMark(decodeBase64(trimmed));
                ParsedContent parsed = parseLines(decoded);
                // A list of links this client cannot run is still the list:
                // read again as plain text, the blob is one unreadable line,
                // and the row blamed an expired subscription or a portal.
                if (!parsed.servers().isEmpty() || !parsed.unsupportedSchemes().isEmpty()) {
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

    private static String decodeBase64(String encoded) {
        return Base64Lenient.decodeUtf8(encoded);
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

    /**
     * The schemes of addresses on the web. In a list they are a portal's
     * sign-in link or a footer pointing at the provider's site; no server
     * this client could run is given in one.
     */
    private static final java.util.Set<String> WEB_SCHEMES =
            java.util.Set.of("http", "https", "ftp", "file");

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
                ServerConfig server = shareLinkParser.parseForImport(trimmedLine);
                servers.add(server);
            } catch (ShareLinkParser.NotLinkException e) {
                // A line of a web page, or prose quoting an address: like a
                // comment, it stands for no server. Counted as a protocol this
                // client lacks, a captive portal's login page was reported as
                // a list of unsupported links instead of as no list at all.
                log.debug("Passing over a line that is not a share link");
            } catch (ShareLinkParser.UnsupportedSchemeException e) {
                if (WEB_SCHEMES.contains(e.scheme())) {
                    // A web address on a line of its own: a portal's sign-in
                    // link, or the provider's support page under its list.
                    // Left out as a protocol this client lacks, a portal's
                    // page read as the provider's links asking for "https".
                    log.debug("Passing over a web address in the list");
                    continue;
                }
                unsupported.add(e.scheme());
                log.debug("Leaving out a {} link: protocol not supported", e.scheme());
            } catch (ShareLinkParser.UnsupportedFeatureException e) {
                // Well-formed, but the core would refuse it: like a protocol this
                // client lacks, and unlike a line the parser could not read.
                unsupported.add(e.feature());
                log.debug("Leaving out a link the core would refuse: {}", e.feature());
            } catch (Exception e) {
                skipped++;
                // Scrubbed: the line is a share link and a parser message may
                // quote it, and debug is what a user turns on for a bug report.
                log.debug("Skipping unparseable line: {}", Redact.urlsIn(e.getMessage()));
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
        SubscriptionReconciler.Batch batch = SubscriptionReconciler.reconcile(
                membersOf(sub), strays(), fetchedServers, allowRemovals);
        // Stamped before the servers are saved: their membership is then on
        // disk with them even if the list saved after them never is.
        batch.upserts().forEach(server -> server.setSubscriptionId(sub.getId()));
        // The provider withdrew what it no longer lists, which nobody asked a
        // running tunnel to follow: a pick made in place of a withdrawn one
        // waits for the user's reconnect where its restart would prompt.
        configStore.applyServerBatch(batch.upserts(), batch.removals(),
                ConfigStore.Removal.UNASKED);
        sub.setServerIds(batch.serverIds());
    }

    /**
     * The stored servers of {@code sub}: the ones its list names and the ones
     * stamped with its id, which the list misses when a quit came between a
     * refresh saving the servers and saving the list.
     */
    private List<ServerConfig> membersOf(Subscription sub) {
        Map<String, ServerConfig> members = new LinkedHashMap<>();
        for (String id : sub.getServerIds()) {
            configStore.getServerById(id).ifPresent(server -> members.put(server.getId(), server));
        }
        for (ServerConfig server : configStore.getServersOfSubscription(sub.getId())) {
            members.putIfAbsent(server.getId(), server);
        }
        return List.copyOf(members.values());
    }

    /**
     * The stored servers stamped with a subscription that no longer exists:
     * restored from a backup, which keeps the stamps but not the
     * subscriptions, or left by a subscription list that could not be read.
     * A refresh may take one back when the provider sends the same server.
     *
     * <p>Not one that a subscription's list still names, whatever its stamp:
     * that one is the subscription's. A backup imported over servers a
     * subscription added again had taken back puts their old stamps back,
     * and a subscription added while the subscription list could not be
     * opened, which is then never saved, leaves the servers it took back
     * stamped with an id the next launch does not know. Read as a stray as
     * well, such a server was matched a second time, after the refresh had
     * matched it as the subscription's own: a second fetched server took its
     * id and its pick, and the server itself was gone. A server another
     * subscription lists would have been shared, and taken back and forth by
     * the two.</p>
     */
    private List<ServerConfig> strays() {
        java.util.Set<String> registered = new java.util.HashSet<>();
        java.util.Set<String> listed = new java.util.HashSet<>();
        synchronized (this) {
            for (Subscription s : subscriptions) {
                registered.add(s.getId());
                listed.addAll(s.getServerIds());
            }
        }
        return configStore.getSubscriptionServers().stream()
                .filter(server -> !registered.contains(server.getSubscriptionId())
                        && !listed.contains(server.getId()))
                .toList();
    }

    /**
     * Puts back into each subscription's list the servers stamped with its id
     * that the list misses, so its count and its members agree from the start.
     */
    private void adoptStampedServers() {
        for (Subscription sub : subscriptions) {
            List<String> ids = new ArrayList<>(sub.getServerIds());
            for (ServerConfig server : configStore.getServersOfSubscription(sub.getId())) {
                if (!ids.contains(server.getId())) {
                    ids.add(server.getId());
                }
            }
            if (ids.size() != sub.getServerIds().size()) {
                log.info("Subscription '{}': {} saved server(s) were missing from its list",
                        sub.getName(), ids.size() - sub.getServerIds().size());
                sub.setServerIds(ids);
            }
        }
    }

    private Subscription findById(String id) {
        // A plain loop under the monitor, like stillFetchedFrom: streaming the
        // FX-owned list from the scheduler thread could throw a
        // ConcurrentModificationException against an add on the FX thread.
        synchronized (this) {
            for (Subscription sub : subscriptions) {
                if (sub.getId().equals(id)) {
                    return sub;
                }
            }
        }
        return null;
    }

    /**
     * Writes subscriptions.json again, with the URLs sealed or not as the
     * settings now say, as {@link ConfigStore#rewriteServers} does for the
     * servers. Sealing runs keychain processes: call it off the FX thread.
     */
    public void rewriteSubscriptions() {
        saveSubscriptions();
    }

    synchronized void saveSubscriptions() {
        if (configStore.getPersistenceState().isHeld(SUBSCRIPTIONS_FILE)) {
            log.warn("Not saving {}: it could not be opened at startup", SUBSCRIPTIONS_FILE);
            return;
        }
        Path file = dataDir.resolve(SUBSCRIPTIONS_FILE);
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("config_version", SUBSCRIPTIONS_CONFIG_VERSION);
            ArrayNode list = objectMapper.valueToTree(serializableSubscriptions());
            // An entry this build cannot read goes back as it came: it comes
            // from a newer build, and its sealed URL names a keychain entry.
            unreadableSubscriptions.forEach(entry -> list.add(entry.deepCopy()));
            envelope.set("subscriptions", list);
            if (envelopeExtras != null) {
                envelope.setAll(envelopeExtras.deepCopy());
            }
            SecureFiles.writePrivately(file, objectMapper.writeValueAsBytes(envelope));
            configStore.getPersistenceState().saved(SUBSCRIPTIONS_FILE);
            ConfigStore.dropLegacyBackupOnceMigrated(file, objectMapper, "subscriptions");
        } catch (IOException | JacksonException e) {
            // JacksonException is unchecked in Jackson 3: a serialization that
            // failed escaped past the unsaved banner.
            log.error("Failed to save subscriptions to {}", file, e);
            configStore.getPersistenceState().failed(SUBSCRIPTIONS_FILE, this::saveSubscriptions);
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
            SealedUrl cached = urlSealCache.get(live.getId());
            String sealed = cached != null && cached.plaintext().equals(url)
                    ? cached.tag()
                    : sealer.seal(urlSecretKey(live.getId()), url);
            if (sealed == null) {
                log.warn("Could not seal URL for subscription '{}'; keeping plaintext",
                        live.getName());
                out.add(live);
                continue;
            }
            urlSealCache.put(live.getId(), new SealedUrl(url, sealed));
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
        if (Files.exists(file)) {
            SecureFiles.restrictExisting(file);
        }
        PersistenceState persistence = configStore.getPersistenceState();
        StoredJson.Read read = StoredJson.read(objectMapper, file);
        if (!(read instanceof StoredJson.Parsed parsed)) {
            // A damaged file goes aside, not under the next save: the sealed
            // URLs in it are the keys the keychain entries are filed under.
            ConfigStore.startWithout(read, file, SUBSCRIPTIONS_FILE, persistence);
            return;
        }
        JsonNode root = parsed.root();
        try {
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
                ConfigStore.keepWithoutList(file, SUBSCRIPTIONS_FILE, root,
                        SUBSCRIPTIONS_CONFIG_VERSION, persistence);
                return;
            }
            ObjectNode extras = ConfigStore.envelopeExtras(root, "subscriptions");
            // Per entry, as servers.json is read: one subscription this build
            // cannot read used to fail the whole list and set every other
            // subscription aside with it.
            List<Subscription> loaded = new ArrayList<>();
            int unreadable = 0;
            for (JsonNode item : items) {
                try {
                    loaded.add(objectMapper.treeToValue(item, Subscription.class));
                } catch (JacksonException e) {
                    unreadable++;
                    log.warn("Skipping a subscription entry this build cannot read: {}",
                            e.getMessage());
                    synchronized (this) {
                        unreadableSubscriptions.add(item);
                    }
                }
            }
            Unsealing.each(loaded, this::unsealInPlace);
            subscriptions.addAll(loaded);
            synchronized (this) {
                envelopeExtras = extras;
            }
            if (unreadable > 0) {
                persistence.couldNotRead(SUBSCRIPTIONS_FILE, unreadable);
                persistence.keepsUnreadableOnSave(SUBSCRIPTIONS_FILE);
            }
            log.info("Loaded {} subscriptions from {}", subscriptions.size(), file);
        } catch (JacksonException e) {
            log.error("Failed to load subscriptions from {}", file, e);
            ConfigStore.setAsideDamaged(file, SUBSCRIPTIONS_FILE, persistence);
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
                url -> {
                    subscription.setUrl(url);
                    // The file already holds this URL sealed; see urlSealCache.
                    synchronized (this) {
                        urlSealCache.put(subscription.getId(), new SealedUrl(url, stored));
                    }
                },
                () -> log.error(
                        "Could not unseal the URL for subscription '{}' ({}); "
                                + "re-add it or restore the secret backend entry",
                        subscription.getName(), subscription.getId()));
    }
}
