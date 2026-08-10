package com.vlessclient.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists servers and application settings as JSON in the platform data
 * directory and exposes the server list as an observable collection.
 */
public class ConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);

    private static final String SERVERS_FILE = "servers.json";
    private static final String SETTINGS_FILE = "settings.json";

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

    /**
     * The last value sealed under each secret key, and the tag it produced.
     *
     * <p>Credentials are plaintext in memory, so {@code SecretSealer.isSealed}
     * never short-circuits and {@link #serializableServers()} used to re-seal
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
     * <p>Guarded by this object's monitor: only reached from the synchronized
     * save path and the synchronized mutators.</p>
     */
    private final Map<String, SealedSecret> sealCache = new HashMap<>();

    /** A sealed value and the plaintext that produced it. */
    private record SealedSecret(String plaintext, String tag) {
    }

    public ConfigStore() {
        this(PlatformPaths.current().dataDir(), SecretSealers.forCurrentPlatform());
    }

    /**
     * Creates a store backed by an explicit directory (used for DI and tests).
     *
     * <p>Test seam: sealing disabled so test suites never write into the real
     * OS keychain. Production always goes through the no-arg constructor.</p>
     *
     * @param dataDir the directory backing this store
     */
    public ConfigStore(Path dataDir) {
        this(dataDir, SecretSealers.disabled());
    }

    ConfigStore(Path dataDir, SecretSealer sealer) {
        this.dataDir = dataDir;
        this.sealer = sealer;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.servers = FXCollections.observableArrayList();
        this.settings = new AppSettings();
        ensureDataDir();
        loadServers();
        loadSettings();
        // Fresh per-run token for the local clash_api control endpoint. Set on
        // the in-memory settings only (the field is @JsonIgnore), so it never
        // persists and rotates each run. Shared by the config generator and
        // TrafficMonitor, which both read this settings instance.
        settings.setClashApiSecret(newClashApiSecret());
    }

    private static String newClashApiSecret() {
        byte[] bytes = new byte[24];
        new java.security.SecureRandom().nextBytes(bytes);
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
     * after upserts.</p>
     *
     * @param upserts servers to add or replace, matched by id
     * @param removalIds ids to remove; unknown ids are ignored
     */
    public void applyServerBatch(List<ServerConfig> upserts, List<String> removalIds) {
        Set<String> removed = mutateList(() -> {
            for (ServerConfig incoming : upserts) {
                int existing = indexOfServer(incoming.getId());
                if (existing >= 0) {
                    servers.set(existing, incoming);
                } else {
                    if (servers.stream().noneMatch(ServerConfig::isActive)) {
                        incoming.setActive(true);
                    }
                    servers.add(incoming);
                }
            }
            Set<String> gone = new HashSet<>();
            for (String id : removalIds) {
                if (servers.removeIf(s -> s.getId().equals(id))) {
                    gone.add(id);
                    evictSealCache(id);
                }
            }
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
     * {@code saveServers()} still serializes a consistent snapshot under the
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
        } catch (IOException e) {
            log.error("Failed to duplicate server: {}", serverId, e);
            return;
        }
        copy.setId(UUID.randomUUID().toString());
        copy.setName(copy.getName() + " (copy)");
        copy.setActive(false);
        mutateList(() -> servers.add(copy));
        saveServers();
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
        } catch (IOException e) {
            log.error("Failed to save settings to {}", file, e);
        }
    }

    /** Package-private so tests can count how often a change hits the disk. */
    synchronized void saveServers() {
        Path file = dataDir.resolve(SERVERS_FILE);
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("config_version", SERVERS_CONFIG_VERSION);
            envelope.set("servers", objectMapper.valueToTree(serializableServers()));
            SecureFiles.writePrivately(file, objectMapper.writeValueAsBytes(envelope));
        } catch (IOException e) {
            log.error("Failed to save servers to {}", file, e);
        }
    }

    /**
     * The on-disk view of the server list. In memory credentials stay
     * plaintext; when secure storage is enabled and a backend is available,
     * the persisted copies carry sealed values instead. A failed seal keeps
     * the plaintext — a readable config always wins over a lost credential.
     */
    private List<ServerConfig> serializableServers() {
        boolean sealing = settings.isStoreSecretsSecurely() && sealer.isAvailable();
        if (!sealing) {
            return new ArrayList<>(servers);
        }
        List<ServerConfig> out = new ArrayList<>(servers.size());
        for (ServerConfig live : servers) {
            // uuid carries the credential for every protocol; flow is only a
            // secret for Hysteria2 (obfs password) — for VLESS it holds the
            // public flow-control mode and must stay readable.
            String sealedUuid = sealValue(live, live.getUuid(), "uuid");
            String sealedFlow = live.getProtocol() == Protocol.HYSTERIA2
                    ? sealValue(live, live.getFlow(), "flow")
                    : null;
            if (sealedUuid == null && sealedFlow == null) {
                out.add(live);
                continue;
            }
            try {
                ServerConfig copy = objectMapper.readValue(
                        objectMapper.writeValueAsString(live), ServerConfig.class);
                if (sealedUuid != null) {
                    copy.setUuid(sealedUuid);
                }
                if (sealedFlow != null) {
                    copy.setFlow(sealedFlow);
                }
                out.add(copy);
            } catch (IOException e) {
                log.warn("Could not copy server '{}' for sealing; keeping plaintext",
                        live.getName(), e);
                out.add(live);
            }
        }
        return out;
    }

    /** Seals one field's value; null when there is nothing to seal or it failed. */
    private String sealValue(ServerConfig live, String value, String field) {
        if (value == null || value.isBlank() || SecretSealer.isSealed(value)) {
            return null;
        }
        String key = secretKey(live.getId(), field);
        SealedSecret cached = sealCache.get(key);
        if (cached != null && cached.plaintext().equals(value)) {
            return cached.tag();
        }
        String sealed = sealer.seal(key, value);
        if (sealed == null) {
            log.warn("Could not seal {} for server '{}'; keeping plaintext",
                    field, live.getName());
            return null;
        }
        sealCache.put(key, new SealedSecret(value, sealed));
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
            List<ServerConfig> loaded = objectMapper.convertValue(
                    items, new TypeReference<List<ServerConfig>>() {});
            loaded.forEach(this::unsealInPlace);
            servers.addAll(loaded);
            log.info("Loaded {} servers from {}", servers.size(), file);
        } catch (IOException e) {
            log.error("Failed to load servers from {}", file, e);
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
        sealer.unseal(secretKey(server.getId(), field), stored).ifPresentOrElse(
                setter,
                () -> log.error(
                        "Could not unseal {} for server '{}' ({}); "
                                + "re-enter it or restore the secret backend entry",
                        field, server.getName(), server.getId()));
    }

    private void loadSettings() {
        Path file = dataDir.resolve(SETTINGS_FILE);
        if (!Files.exists(file)) {
            log.info("No settings file found at {}, using defaults", file);
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
            // Future incompatible versions dispatch their migrations here;
            // saving always stamps the version this build writes.
            settings.setConfigVersion(AppSettings.CURRENT_CONFIG_VERSION);
            log.info("Loaded settings from {}", file);
        } catch (IOException e) {
            log.error("Failed to load settings from {}", file, e);
        }
    }
}
