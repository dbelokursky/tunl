package com.vlessclient.service;

import com.vlessclient.app.I18n;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.platform.PlatformPaths;
import com.vlessclient.platform.SecretSealer;
import com.vlessclient.platform.SecretSealers;
import com.vlessclient.platform.SecureFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
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
 * Persists servers and application settings as JSON in the platform data
 * directory and exposes the server list as an observable collection.
 */
public class ConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);

    private static final String SERVERS_FILE = "servers.json";
    private static final String SETTINGS_FILE = "settings.json";
    private static final SecureRandom CLASH_API_RANDOM = new SecureRandom();

    /**
     * Version stream of servers.json: v0 is the pre-envelope bare array,
     * v1 wraps it as {@code {"config_version":1,"servers":[…]}}. Bump on the
     * first incompatible change and dispatch a migration in loadServers().
     */
    static final int SERVERS_CONFIG_VERSION = 1;

    private final Path dataDir;
    private final ObjectMapper objectMapper;
    private final ObservableList<ServerConfig> servers;
    private final SecretSealer sealer;
    private AppSettings settings;
    private final PersistenceState persistence = new PersistenceState();

    /**
     * The last value sealed under each secret key, and the tag it produced.
     *
     * <p>Credentials are plaintext in memory, so {@code SecretSealer.isSealed}
     * never short-circuits and {@link #sealCredentials} used to re-seal
     * every server on every save. Each seal forks the platform secret tool
     * ({@code security} on macOS), so one 60-server save meant 60 processes,
     * and a subscription refresh — which saved once per server — meant 3600.</p>
     *
     * <p>Reusing the tag is safe because the keychain entry it names is still
     * there and still holds this exact plaintext. The one case it misses is an
     * entry deleted out from under a running app, which a save would otherwise
     * have recreated; that already surfaces on load as "Could not unseal …",
     * and the fix there is the same either way.</p>
     *
     * <p>Loading fills it too, with the tags the file already holds: without
     * them the first save after a restart sealed every credential again.</p>
     *
     * <p>Guarded by this object's monitor, which the save holds only to read
     * and fill it: the keychain runs outside.</p>
     */
    private final Map<String, SealedSecret> sealCache = new HashMap<>();

    /**
     * Serializes the writes of servers.json. The FX thread never waits for
     * it: a save asked for there while another runs is left to that one,
     * which writes again before it lets go.
     */
    private final ReentrantLock serversFile = new ReentrantLock();

    /** A change to the server list that no write has taken in yet. */
    private final AtomicBoolean serversUnsaved = new AtomicBoolean();

    /** A sealed value and the plaintext that produced it. */
    private record SealedSecret(String plaintext, String tag) {
    }

    /**
     * Creates a store backed by an explicit directory (used for DI and tests).
     *
     * <p>Test seam: sealing disabled so test suites never write into the real
     * OS keychain. Production takes the constructor below, from
     * {@code ServiceLocator}: the platform's data directory and its sealer.</p>
     *
     * @param dataDir the directory backing this store
     */
    public ConfigStore(Path dataDir) {
        this(dataDir, SecretSealers.disabled());
    }

    /**
     * Creates a store backed by an explicit directory and an explicit sealer.
     *
     * <p>Public so the locator can build the headless UI test graph over the
     * real data-dir resolution with {@link SecretSealers#disabled()}: that
     * graph used the platform sealer, and a UI test that added a server wrote
     * through the developer's login Keychain.</p>
     *
     * @param dataDir the directory backing this store
     * @param sealer  how credentials are protected at rest
     */
    public ConfigStore(Path dataDir, SecretSealer sealer) {
        this.dataDir = dataDir;
        this.sealer = sealer;
        this.objectMapper = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
        this.servers = FXCollections.observableArrayList();
        this.settings = new AppSettings();
        ensureDataDir();
        loadServers();
        loadSettings();
        // A token for the local clash_api control endpoint, on the in-memory
        // settings only (the field is @JsonIgnore), so it never persists.
        // ConnectionService draws another for every core it starts; this one
        // covers whatever reads the settings before the first connect.
        settings.setClashApiSecret(newClashApiSecret());
    }

    /**
     * This install's device id, drawn the first time it is asked for: when a
     * subscription is first fetched, or Settings first shows it. Saved at once,
     * so a restart does not draw another and look like a second device to a
     * provider that limits them.
     *
     * @return the id
     */
    public String deviceId() {
        String id = settings.getDeviceId();
        if (id != null && !id.isBlank()) {
            // Most calls: no need to wait for this store's monitor, which a
            // background import holds while it seals credentials.
            return id;
        }
        synchronized (this) {
            // Through getSettings(), as every other reader goes: the field
            // read directly under this monitor tipped SpotBugs' share of
            // locked reads of it over the line where it reports the rest as
            // inconsistent synchronization.
            AppSettings current = getSettings();
            id = current.getDeviceId();
            if (id == null || id.isBlank()) {
                id = newDeviceId();
                current.setDeviceId(id);
                saveSettings(current);
            }
            return id;
        }
    }

    /**
     * A new random device id, for a new install or a reset in Settings. A
     * UUID, which the {@code x-hwid} pattern panels accept
     * ({@code [a-zA-Z0-9=-]{10,64}}).
     *
     * @return the id
     */
    public static String newDeviceId() {
        return UUID.randomUUID().toString();
    }

    /**
     * A new token for the clash_api control endpoint: 24 random bytes in hex.
     *
     * @return the token
     */
    static String newClashApiSecret() {
        byte[] bytes = new byte[24];
        CLASH_API_RANDOM.nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    public ObservableList<ServerConfig> getServers() {
        return servers;
    }

    /**
     * Adds a server and persists it, activating it when nothing else is active.
     *
     * <p>The auto-activation is what makes a fresh install usable: {@code active}
     * defaults to false and no import path sets it, so without this the first
     * server a user adds leaves them with a full list and no active server —
     * Connect then fails with "no server is selected" and the only cure is a
     * list gesture the UI never mentions.</p>
     */
    public void addServer(ServerConfig server) {
        mutateList(() -> {
            if (servers.stream().noneMatch(ServerConfig::isActive)) {
                server.setActive(true);
            }
            servers.add(server);
            return null;
        });
        saveServers();
    }

    /**
     * Makes the given server the active one and persists the change.
     *
     * <p>The single place that owns the "exactly one active" invariant. Callers
     * used to flip the flags themselves, which diverged: the tray wrote through
     * to disk while the server list only mutated memory, so a choice made in
     * the list was lost on restart. Elements are re-{@code set} rather than
     * mutated in place so the {@code ObservableList} fires a change and
     * listeners (e.g. the Connect button's availability) see the new state.</p>
     *
     * @param serverId id of the server to activate; unknown ids deactivate all
     */
    public void setActiveServer(String serverId) {
        boolean changed = mutateList(() -> {
            boolean any = false;
            for (int i = 0; i < servers.size(); i++) {
                ServerConfig s = servers.get(i);
                boolean shouldBeActive = s.getId().equals(serverId);
                if (s.isActive() != shouldBeActive) {
                    s.setActive(shouldBeActive);
                    servers.set(i, s);
                    any = true;
                }
            }
            return any;
        });
        if (changed) {
            saveServers();
        }
    }

    /**
     * Replaces the stored server that shares this server's id and persists the
     * change. Logs a warning if no matching server is found.
     *
     * @param server the updated server, matched by id
     */
    public void updateServer(ServerConfig server) {
        boolean replaced = mutateList(() -> {
            int at = indexOfServer(server.getId());
            if (at < 0) {
                return false;
            }
            servers.set(at, server);
            return true;
        });
        if (replaced) {
            saveServers();
        } else {
            log.warn("Server not found for update: {}", server.getId());
        }
    }

    /**
     * Removes the server with the given id and persists the change. Logs a
     * warning if no matching server is found.
     *
     * @param serverId the id of the server to remove
     */
    public void removeServer(String serverId) {
        boolean removed = mutateList(() -> servers.removeIf(s -> s.getId().equals(serverId)));
        if (removed) {
            synchronized (this) {
                evictSealCache(serverId);
            }
            saveServers();
            // Off the caller's (usually FX) thread; a leftover entry is inert
            // if this races or fails.
            Thread.startVirtualThread(() -> {
                sealer.delete(secretKey(serverId, "uuid"));
                sealer.delete(secretKey(serverId, "flow"));
            });
        } else {
            log.warn("Server not found for removal: {}", serverId);
        }
    }

    /**
     * Applies a whole set of additions, replacements and removals, then saves
     * once.
     *
     * <p>The per-server methods each call {@code saveServers()}, and a save
     * serializes — and seals — the entire list. Driving a subscription refresh
     * through them cost one full save per changed server: quadratic writes,
     * quadratic seals, and N change events on the observable list for what the
     * user experiences as one operation.</p>
     *
     * <p>Semantics match the per-server methods exactly: an upsert whose id is
     * already present replaces it in place, an upsert with a new id is
     * appended and inherits {@link #addServer}'s rule that the first server to
     * exist when nothing is active becomes active, and removals are applied
     * after upserts. A batch that removes the picked server picks another, so
     * a non-empty list always has one.</p>
     *
     * @param upserts servers to add or replace, matched by id
     * @param removalIds ids to remove; unknown ids are ignored
     */
    public void applyServerBatch(List<ServerConfig> upserts, List<String> removalIds) {
        Set<String> removed = mutateList(() -> {
            List<ServerConfig> added = new ArrayList<>();
            for (ServerConfig incoming : upserts) {
                int existing = indexOfServer(incoming.getId());
                if (existing >= 0) {
                    servers.set(existing, incoming);
                } else {
                    if (servers.stream().noneMatch(ServerConfig::isActive)) {
                        incoming.setActive(true);
                    }
                    servers.add(incoming);
                    added.add(incoming);
                }
            }
            Set<String> gone = new HashSet<>();
            for (String id : removalIds) {
                if (servers.removeIf(s -> s.getId().equals(id))) {
                    gone.add(id);
                    evictSealCache(id);
                }
            }
            keepOnePicked(added, upserts);
            return gone;
        });

        if (upserts.isEmpty() && removed.isEmpty()) {
            return;
        }
        saveServers();
        if (!removed.isEmpty()) {
            // Same treatment as removeServer: off the caller's thread, and a
            // leftover entry is inert if it races or fails.
            Thread.startVirtualThread(() -> removed.forEach(id -> {
                sealer.delete(secretKey(id, "uuid"));
                sealer.delete(secretKey(id, "flow"));
            }));
        }
    }

    /**
     * Picks a server again when a batch removed the picked one. Upserts ran
     * first and activated a newcomer only while nothing was active, so a
     * subscription that moved the picked server to a new address and name
     * (withdrawn and added, as nothing ties the two) was left with no server
     * picked: the running core kept the withdrawn one, and the next recovery
     * stopped it and found nothing to connect to. A server this batch added
     * is the likeliest replacement, then one it updated, then the first.
     */
    private void keepOnePicked(List<ServerConfig> added, List<ServerConfig> upserts) {
        if (servers.isEmpty() || servers.stream().anyMatch(ServerConfig::isActive)) {
            return;
        }
        ServerConfig pick = added.stream().filter(servers::contains).findFirst()
                .or(() -> upserts.stream().filter(servers::contains).findFirst())
                .orElse(servers.getFirst());
        pick.setActive(true);
        servers.set(servers.indexOf(pick), pick);
        log.info("The picked server was withdrawn; picked '{}' instead", pick.getName());
    }

    /**
     * Applies a mutation to the server list on the JavaFX Application Thread.
     *
     * <p>{@link #getServers()} hands out the live {@code ObservableList} that
     * {@code ServersViewController} wraps in a {@code FilteredList}/
     * {@code SortedList}/{@code ListView}. Mutating it off the FX thread means
     * mutating a live scene graph's backing collection: the change events fire
     * on whatever thread mutated, and a listener then touches controls from
     * there. Callers arrive from everywhere — virtual threads in
     * {@code SubscriptionsViewController}, the hourly refresh scheduler, MCP
     * HTTP workers, the AWT thread behind the tray menu.</p>
     *
     * <p><strong>The monitor is taken inside the action, never around it.</strong>
     * Waiting for the FX thread while holding this object's monitor would
     * deadlock the moment the FX thread called any synchronized method here:
     * caller waits for FX, FX waits for the monitor. Everything that needs the
     * monitor and does <em>not</em> need the FX thread — the disk write in
     * {@code saveServers()} and the sealing it drives — therefore stays on the
     * calling thread, which also keeps that work off the FX thread.</p>
     *
     * <p>The cost of the split is that a mutation and its save are two critical
     * sections rather than one, so a concurrent change can land in between.
     * {@code saveServers()} still takes a consistent snapshot under the
     * monitor; it may simply be a newer one than the caller produced.</p>
     */
    private <T> T mutateList(Supplier<T> mutation) {
        return FxExecutor.get(() -> {
            synchronized (this) {
                return mutation.get();
            }
        });
    }

    private int indexOfServer(String id) {
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Adds a copy of the server with the given id, assigning it a new id, a
     * "(copy)" name suffix, and inactive state, then persists the change.
     *
     * @param serverId the id of the server to duplicate
     */
    public void duplicateServer(String serverId) {
        Optional<ServerConfig> original = getServerById(serverId);
        if (original.isEmpty()) {
            log.warn("Server not found for duplication: {}", serverId);
            return;
        }
        ServerConfig copy;
        try {
            String json = objectMapper.writeValueAsString(original.get());
            copy = objectMapper.readValue(json, ServerConfig.class);
        } catch (JacksonException e) {
            log.error("Failed to duplicate server: {}", serverId, e);
            return;
        }
        copy.setId(UUID.randomUUID().toString());
        copy.setName(copy.getName() + I18n.get("servers.copy.suffix"));
        copy.setActive(false);
        // The user's own server from now on: a subscription's refresh must
        // neither match it nor remove it as one the provider withdrew.
        copy.setSubscriptionId(null);
        mutateList(() -> servers.add(copy));
        saveServers();
    }

    /**
     * The servers stamped as coming from a subscription, whether or not its
     * own list of server ids names them.
     *
     * @param subscriptionId the subscription's id
     * @return a snapshot of the stamped servers, in list order
     */
    public synchronized List<ServerConfig> getServersOfSubscription(String subscriptionId) {
        return servers.stream()
                .filter(s -> subscriptionId.equals(s.getSubscriptionId()))
                .toList();
    }

    /**
     * Finds the server with the given id.
     *
     * @param id the server id to look up
     * @return the matching server, or an empty optional if none exists
     */
    public synchronized Optional<ServerConfig> getServerById(String id) {
        // Synchronized because the callers are off the FX thread — MCP workers,
        // the tray, the subscription refresh — and would otherwise stream over
        // the list while the FX thread mutates it.
        return servers.stream()
                .filter(s -> s.getId().equals(id))
                .findFirst();
    }

    /** Shared write-failure state for UI feedback and MCP error reporting. */
    public PersistenceState getPersistenceState() {
        return persistence;
    }

    public AppSettings getSettings() {
        return settings;
    }

    /**
     * Returns the directory backing this store.
     *
     * @return the data directory (holds servers.json, settings.json, ...)
     */
    public Path getDataDir() {
        return dataDir;
    }

    /**
     * Replaces the current settings and writes them to disk.
     *
     * @param settings the settings to store
     */
    public synchronized void saveSettings(AppSettings settings) {
        // The clash_api secret is a runtime-only value (@JsonIgnore, minted at
        // startup and never persisted). Carry it across a settings swap so a
        // caller passing a fresh AppSettings can't silently drop the token and
        // leave the control endpoint open on the next connect.
        if (settings != this.settings && (settings.getClashApiSecret() == null
                || settings.getClashApiSecret().isBlank())) {
            settings.setClashApiSecret(this.settings.getClashApiSecret());
        }
        this.settings = settings;
        Path file = dataDir.resolve(SETTINGS_FILE);
        try {
            SecureFiles.writePrivately(file, objectMapper.writeValueAsBytes(settings));
            persistence.saved(SETTINGS_FILE);
        } catch (IOException e) {
            log.error("Failed to save settings to {}", file, e);
            persistence.failed(SETTINGS_FILE, this::retrySettings);
        }
    }

    private synchronized void retrySettings() {
        saveSettings(settings);
    }

    /**
     * Writes the server list. Package-private so tests can count how often a
     * change hits the disk.
     *
     * <p>Sealing takes a keychain process per new credential, and the whole
     * save used to run inside this object's monitor, which the FX thread
     * takes to read the list, change it and save the settings: a
     * subscription of 300 servers froze the window for seconds. Now only the
     * snapshot is taken under the monitor, and the write under a lock of its
     * own that the FX thread only tries.</p>
     */
    void saveServers() {
        serversUnsaved.set(true);
        boolean onFxThread = Platform.isFxApplicationThread();
        do {
            if (onFxThread) {
                if (!serversFile.tryLock()) {
                    // The write under way takes this change in before it lets go.
                    return;
                }
            } else {
                serversFile.lock();
            }
            try {
                while (serversUnsaved.getAndSet(false)) {
                    writeServers();
                }
            } finally {
                serversFile.unlock();
            }
            // A change that came in while the lock was being let go.
        } while (serversUnsaved.get());
    }

    /**
     * Writes servers.json as the list is now, with its credentials sealed
     * when secure storage is on.
     */
    private void writeServers() {
        ArrayNode snapshot;
        boolean secureStorage;
        synchronized (this) {
            snapshot = objectMapper.valueToTree(servers);
            secureStorage = settings.isStoreSecretsSecurely();
        }
        if (secureStorage && sealer.isAvailable()) {
            sealCredentials(snapshot);
        }
        Path file = dataDir.resolve(SERVERS_FILE);
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("config_version", SERVERS_CONFIG_VERSION);
            envelope.set("servers", snapshot);
            SecureFiles.writePrivately(file, objectMapper.writeValueAsBytes(envelope));
            persistence.saved(SERVERS_FILE);
            dropLegacyBackupOnceMigrated(file, objectMapper, "servers");
        } catch (IOException e) {
            log.error("Failed to save servers to {}", file, e);
            persistence.failed(SERVERS_FILE, this::saveServers);
        }
    }

    /**
     * Replaces the credentials in a snapshot of the list with their sealed
     * tags; in memory they stay plaintext. uuid carries the credential for
     * every protocol; flow is a secret only for Hysteria2 (its obfs
     * password), and for VLESS holds the public flow-control mode, which must
     * stay readable. A failed seal keeps the plaintext: a readable config
     * always wins over a lost credential.
     */
    private void sealCredentials(ArrayNode snapshot) {
        for (JsonNode node : snapshot) {
            if (node instanceof ObjectNode server) {
                String id = server.path("id").asString("");
                String name = server.path("name").asString("");
                sealField(server, id, name, "uuid");
                if (Protocol.HYSTERIA2.getValue().equals(server.path("protocol").asString(""))) {
                    sealField(server, id, name, "flow");
                }
            }
        }
    }

    private void sealField(ObjectNode server, String id, String name, String field) {
        JsonNode value = server.get(field);
        if (value != null && value.isString()) {
            String sealed = sealValue(id, name, value.asString(), field);
            if (sealed != null) {
                server.put(field, sealed);
            }
        }
    }

    /**
     * Seals one field's value; null when there is nothing to seal or it
     * failed. The keychain runs outside this object's monitor.
     */
    private String sealValue(String serverId, String serverName, String value, String field) {
        if (value == null || value.isBlank() || SecretSealer.isSealed(value)) {
            return null;
        }
        String key = secretKey(serverId, field);
        synchronized (this) {
            SealedSecret cached = sealCache.get(key);
            if (cached != null && cached.plaintext().equals(value)) {
                return cached.tag();
            }
        }
        String sealed = sealer.seal(key, value);
        if (sealed == null) {
            log.warn("Could not seal {} for server '{}'; keeping plaintext", field, serverName);
            return null;
        }
        synchronized (this) {
            // Not for a server removed meanwhile, whose keychain entry is
            // being deleted: a later server of the same id would reuse a tag
            // that names nothing.
            if (indexOfServer(serverId) >= 0) {
                sealCache.put(key, new SealedSecret(value, sealed));
            }
        }
        return sealed;
    }

    /** Forgets a removed server's cached seals so its keys cannot be reused. */
    private void evictSealCache(String serverId) {
        sealCache.remove(secretKey(serverId, "uuid"));
        sealCache.remove(secretKey(serverId, "flow"));
    }

    private static String secretKey(String serverId, String field) {
        return serverId + "." + field;
    }

    private void ensureDataDir() {
        try {
            SecureFiles.createPrivateDir(dataDir);
        } catch (IOException e) {
            log.error("Failed to create data directory: {}", dataDir, e);
        }
    }

    private void loadServers() {
        Path file = dataDir.resolve(SERVERS_FILE);
        if (!Files.exists(file)) {
            log.info("No servers file found at {}, starting with empty list", file);
            return;
        }
        SecureFiles.restrictExisting(file);   // tighten a 0644 file from an older build
        try {
            JsonNode root = objectMapper.readTree(file.toFile());
            JsonNode items;
            if (root.isArray()) {
                // v0: the pre-envelope bare array. Keep a one-time backup so
                // downgrading past the envelope change stays possible, then
                // the next save writes v1.
                backupLegacyOnce(file);
                items = root;
                log.info("servers.json is the legacy (v0) array format; "
                        + "it will be upgraded to v{} on the next save", SERVERS_CONFIG_VERSION);
            } else {
                int version = root.path("config_version").asInt(0);
                if (version > SERVERS_CONFIG_VERSION) {
                    // Newer app wrote it; read best-effort rather than drop
                    // the user's servers on a downgrade.
                    log.warn("servers.json has config_version {} (this build "
                            + "understands {}); reading best-effort", version,
                            SERVERS_CONFIG_VERSION);
                }
                // Future incompatible versions dispatch their migrations here.
                items = root.path("servers");
            }
            if (!items.isArray()) {
                log.error("servers.json has no readable server list; leaving list empty");
                return;
            }
            List<ServerConfig> loaded = new ArrayList<>();
            int unreadable = 0;
            for (JsonNode item : items) {
                try {
                    loaded.add(objectMapper.treeToValue(item, ServerConfig.class));
                } catch (JacksonException e) {
                    // Per entry, not per file: a protocol or transport a newer
                    // build wrote used to fail the whole list, quarantine
                    // servers.json and leave a downgraded install with nothing.
                    unreadable++;
                    log.warn("Skipping a server entry this build cannot read: {}",
                            e.getMessage());
                }
            }
            loaded.forEach(this::unsealInPlace);
            servers.addAll(loaded);
            if (unreadable > 0) {
                persistence.couldNotRead(SERVERS_FILE, unreadable);
            }
            log.info("Loaded {} servers from {}", servers.size(), file);
        } catch (JacksonException e) {
            log.error("Failed to load servers from {}", file, e);
            quarantineCorrupt(file);
        }
    }

    /**
     * Moves a file we could not read aside, as {@code <name>.corrupt-<epoch>}.
     *
     * <p>Writes here are atomic ({@link com.vlessclient.platform.SecureFiles}
     * writes a temp file and {@code ATOMIC_MOVE}s it), so a file that will not
     * parse was damaged from outside the app — a full disk, a half-restored
     * backup, a sync client. Falling back to an empty list is the right thing
     * to keep the app usable, but the next save then overwrote the only copy of
     * data that was very likely still recoverable by hand. Moving it aside
     * costs nothing and keeps that possibility open.</p>
     *
     * <p>Package-private: {@link RoutingService} quarantines the same way.</p>
     */
    static void quarantineCorrupt(Path file) {
        Path quarantined = file.resolveSibling(
                file.getFileName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(file, quarantined);
            log.error("Moved unreadable {} aside to {}; starting from defaults",
                    file, quarantined);
        } catch (IOException e) {
            log.error("Could not move unreadable {} aside: {}", file, e.getMessage());
        }
    }

    /**
     * Copies a legacy pre-envelope file to {@code <name>.v0.bak} once.
     * Package-private: {@link SubscriptionService} migrates the same way.
     */
    static void backupLegacyOnce(Path file) {
        Path backup = file.resolveSibling(file.getFileName() + ".v0.bak");
        if (Files.exists(backup)) {
            return;
        }
        try {
            Files.copy(file, backup);
        } catch (IOException e) {
            log.warn("Could not back up legacy {} to {}", file.getFileName(), backup, e);
        }
    }

    /**
     * Removes the {@code .v0.bak} beside {@code file} once the file itself
     * reads back in the current envelope format.
     *
     * <p>The backup covers the window between reading a pre-envelope file and
     * writing the first envelope one. Past that window it is a copy of the
     * user's credentials in the clear -- these files predate sealing -- living
     * in the data directory for good. It goes only when the new file is really
     * there: if the save did not land, the backup is still the only copy, so
     * anything unexpected here keeps it.</p>
     */
    static void dropLegacyBackupOnceMigrated(Path file, ObjectMapper mapper, String listField) {
        Path backup = file.resolveSibling(file.getFileName() + ".v0.bak");
        if (!Files.exists(backup)) {
            return;
        }
        try {
            JsonNode root = mapper.readTree(file.toFile());
            if (!root.isObject() || !root.path(listField).isArray()) {
                return;
            }
            Files.delete(backup);
            log.info("Removed {}: {} is in the current format now",
                    backup.getFileName(), file.getFileName());
        } catch (JacksonException | IOException e) {
            log.warn("Kept {}: could not confirm {} migrated ({})",
                    backup.getFileName(), file.getFileName(), e.getMessage());
        }
    }

    /**
     * Restores the in-memory plaintext for a sealed credential. Untagged
     * (legacy plaintext) values pass through. On failure the tag is kept so
     * the entry stays visible and recovers if the backend entry reappears;
     * connecting will fail until the credential is re-entered.
     */
    private void unsealInPlace(ServerConfig server) {
        unsealField(server, server.getUuid(), "uuid", server::setUuid);
        unsealField(server, server.getFlow(), "flow", server::setFlow);
    }

    private void unsealField(ServerConfig server, String stored, String field,
                             Consumer<String> setter) {
        if (!SecretSealer.isSealed(stored)) {
            return;
        }
        String key = secretKey(server.getId(), field);
        sealer.unseal(key, stored).ifPresentOrElse(
                plaintext -> {
                    setter.accept(plaintext);
                    // The file already holds this value sealed: a save reuses
                    // the tag instead of forking the secret tool for it again.
                    synchronized (this) {
                        sealCache.put(key, new SealedSecret(plaintext, stored));
                    }
                },
                () -> log.error(
                        "Could not unseal {} for server '{}' ({}); "
                                + "re-enter it or restore the secret backend entry",
                        field, server.getName(), server.getId()));
    }

    private void loadSettings() {
        Path file = dataDir.resolve(SETTINGS_FILE);
        if (!Files.exists(file)) {
            log.info("No settings file found at {}, using defaults", file);
            // A new install speaks the system's language when the app has it,
            // as its theme already follows the system; it always spoke English.
            settings.setLanguage(AppSettings.languageFor(java.util.Locale.getDefault()));
            return;
        }
        SecureFiles.restrictExisting(file);
        try {
            this.settings = objectMapper.readValue(file.toFile(), AppSettings.class);
            if (settings.getConfigVersion() > AppSettings.CURRENT_CONFIG_VERSION) {
                log.warn("settings.json has config_version {} (this build "
                        + "understands {}); reading best-effort",
                        settings.getConfigVersion(), AppSettings.CURRENT_CONFIG_VERSION);
            }
            // The exact retired default only: an address the user typed stays.
            if (AppSettings.RETIRED_DIRECT_DNS_DEFAULT.equals(settings.getDirectDns())) {
                settings.setDirectDns(AppSettings.SYSTEM_DNS);
                log.info("Direct DNS moved from the retired default {} to the system resolver",
                        AppSettings.RETIRED_DIRECT_DNS_DEFAULT);
            }
            // Future incompatible versions dispatch their migrations here;
            // saving always stamps the version this build writes.
            settings.setConfigVersion(AppSettings.CURRENT_CONFIG_VERSION);
            log.info("Loaded settings from {}", file);
        } catch (JacksonException e) {
            log.error("Failed to load settings from {}", file, e);
            quarantineCorrupt(file);
        }
    }
}
