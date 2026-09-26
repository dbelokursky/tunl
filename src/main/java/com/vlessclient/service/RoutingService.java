package com.vlessclient.service;

import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.RoutingRule;
import com.vlessclient.platform.PlatformPaths;
import com.vlessclient.platform.SecureFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Loads and persists the routing configuration (bypass countries, bypass
 * list, and custom rules) as JSON in the application data directory,
 * migrating pre-composition preset files on first load.
 *
 * <p>Storage goes through {@link PlatformPaths} and {@link SecureFiles} like
 * every other config file: the routing rules reveal what the user chooses to
 * tunnel, so the file is owner-only and written atomically. Both were missed
 * when the Windows/Linux ports landed — this service kept writing a macOS path
 * on every OS at 0644 with a non-atomic write.</p>
 */
public class RoutingService {

    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    private static final String ROUTING_FILE = "routing.json";

    private final Path dataDir;
    private final ObjectMapper objectMapper;
    private RoutingConfig config;
    private final PersistenceState persistence;

    public RoutingService() {
        this(resolveDataDir());
    }

    /** Creates routing persistence with the application's shared write-failure state. */
    public RoutingService(PersistenceState persistence) {
        this(resolveDataDir(), persistence);
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

    /** The pre-port location: routing.json used to be written mac-style on every OS. */
    private static Path legacyMacDataDir() {
        return Path.of(System.getProperty("user.home"),
                "Library", "Application Support", "VlessClient");
    }

    /**
     * Moves a pre-port {@code routing.json} to the platform data dir, once.
     * Mirrors {@code SubscriptionService.migrateLegacyDataDir}: a file already
     * at the platform path wins, and a failed move is survivable — the user
     * starts from defaults rather than losing the app.
     *
     * @return {@code platformDir}, always — the caller's data dir either way
     */
    static Path migrateLegacyDataDir(Path platformDir, Path legacyDir) {
        if (platformDir.equals(legacyDir)) {
            return platformDir;
        }
        Path platformFile = platformDir.resolve(ROUTING_FILE);
        Path legacyFile = legacyDir.resolve(ROUTING_FILE);
        if (Files.exists(platformFile) || !Files.exists(legacyFile)) {
            return platformDir;
        }
        try {
            SecureFiles.createPrivateDir(platformDir);
            Files.move(legacyFile, platformFile);
            log.info("Migrated routing config from legacy path {} to {}",
                    legacyFile, platformFile);
        } catch (IOException e) {
            log.warn("Could not migrate legacy routing file from {}; "
                    + "continuing with {}", legacyFile, platformDir, e);
        }
        return platformDir;
    }

    RoutingService(Path dataDir) {
        this(dataDir, new PersistenceState());
    }

    RoutingService(Path dataDir, PersistenceState persistence) {
        this.persistence = persistence;
        this.dataDir = dataDir;
        this.objectMapper = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
        ensureDataDir();
        loadConfig();
    }

    public synchronized RoutingConfig getConfig() {
        return config;
    }

    /**
     * Replaces the current routing configuration and writes it to disk.
     *
     * @param config the configuration to store
     */
    public synchronized void saveConfig(RoutingConfig config) {
        // Saving stamps the format this build writes, as for the other files;
        // fields a newer build wrote ride along (KeepsUnknownFields).
        config.setConfigVersion(RoutingConfig.CURRENT_CONFIG_VERSION);
        this.config = config;
        if (persistence.isHeld(ROUTING_FILE)) {
            log.warn("Not saving {}: it could not be opened at startup", ROUTING_FILE);
            return;
        }
        Path file = dataDir.resolve(ROUTING_FILE);
        try {
            // Owner-only and atomic, like the other config files: these rules
            // fingerprint the user's traffic, and a crash mid-write used to be
            // able to truncate them.
            SecureFiles.writePrivately(file, objectMapper.writeValueAsBytes(config));
            persistence.saved(ROUTING_FILE);
            log.info("Saved routing config to {}", file);
        } catch (IOException | JacksonException e) {
            log.error("Failed to save routing config to {}", file, e);
            persistence.failed(ROUTING_FILE, this::retryConfig);
        }
    }

    private synchronized void retryConfig() {
        saveConfig(config);
    }

    /**
     * Adds a rule and persists the change, unless the core would refuse its
     * value: sing-box refuses a whole configuration over one such rule, so it
     * stopped every server from connecting until it was found and deleted.
     *
     * @param rule the rule to add
     * @throws IllegalArgumentException with the reason, worded in the language
     *     of the UI, when the core would refuse the rule's value
     */
    public synchronized void addRule(RoutingRule rule) {
        RoutingRuleCheck.problem(rule.getType(), rule.getValue()).ifPresent(reason -> {
            throw new IllegalArgumentException(reason);
        });
        // A new list, not the live one changed: a config being generated
        // iterates it without this lock, and a rule added from the UI or MCP
        // during a connect threw ConcurrentModificationException there.
        List<RoutingRule> rules = new ArrayList<>(config.getRules());
        rules.add(rule);
        config.setRules(rules);
        saveConfig(config);
    }

    /**
     * Removes the rule with the given id and persists the change. Logs a
     * warning if no matching rule is found.
     *
     * @param ruleId the id of the rule to remove
     */
    public synchronized void removeRule(String ruleId) {
        List<RoutingRule> rules = new ArrayList<>(config.getRules());
        boolean removed = rules.removeIf(r -> r.getId().equals(ruleId));
        config.setRules(rules);
        if (removed) {
            saveConfig(config);
        } else {
            log.warn("Rule not found for removal: {}", ruleId);
        }
    }

    /**
     * Reorders the rules to match the given sequence of ids and persists the
     * change. Ids with no matching rule are ignored.
     *
     * @param ruleIds the rule ids in their desired order
     */
    public synchronized void reorderRules(List<String> ruleIds) {
        List<RoutingRule> reordered = new ArrayList<>();
        for (String id : ruleIds) {
            config.getRules().stream()
                    .filter(r -> r.getId().equals(id))
                    .findFirst()
                    .ifPresent(reordered::add);
        }
        config.setRules(reordered);
        saveConfig(config);
    }

    private void ensureDataDir() {
        try {
            SecureFiles.createPrivateDir(dataDir);
        } catch (IOException e) {
            log.error("Failed to create data directory: {}", dataDir, e);
        }
    }

    private void loadConfig() {
        Path file = dataDir.resolve(ROUTING_FILE);
        this.config = new RoutingConfig();
        StoredJson.Read read = StoredJson.read(objectMapper, file);
        if (!(read instanceof StoredJson.Parsed parsed)) {
            // The defaults for this run. A damaged file goes aside, not under
            // the next rule edit, which would erase the only copy of a file a
            // person could likely still repair.
            ConfigStore.startWithout(read, file, ROUTING_FILE, persistence);
            return;
        }
        try {
            this.config = readKeepingKnownRules(parsed.root());
            if (config.getConfigVersion() > RoutingConfig.CURRENT_CONFIG_VERSION) {
                log.warn("routing.json has config_version {} (this build understands {}); "
                        + "reading best-effort", config.getConfigVersion(),
                        RoutingConfig.CURRENT_CONFIG_VERSION);
            }
            // Future incompatible versions dispatch their migrations here.
            log.info("Loaded routing config from {}", file);
            migrateLegacyPreset(file);
        } catch (JacksonException e) {
            log.error("Failed to load routing config from {}", file, e);
            ConfigStore.setAsideDamaged(file, ROUTING_FILE, persistence);
            this.config = new RoutingConfig();
        }
    }

    /**
     * Reads the file, keeping the rules this build understands.
     *
     * <p>A rule whose type or action comes from a newer build used to fail the
     * whole read: the file went to quarantine and routing fell back to
     * "everything through the VPN" without a word, which is the opposite of
     * what a user with a bypass had configured. Such a rule is dropped instead
     * of guessed at -- defaulting its action would move traffic somewhere the
     * user never asked for -- and reported for the banner.</p>
     */
    private RoutingConfig readKeepingKnownRules(JsonNode tree) {
        if (!(tree instanceof ObjectNode root)) {
            return objectMapper.treeToValue(tree, RoutingConfig.class);
        }
        JsonNode rules = root.remove("rules");
        RoutingConfig loaded = objectMapper.treeToValue(root, RoutingConfig.class);
        if (rules == null || !rules.isArray()) {
            return loaded;
        }
        List<RoutingRule> kept = new ArrayList<>();
        int unreadable = 0;
        for (JsonNode rule : rules) {
            try {
                kept.add(objectMapper.treeToValue(rule, RoutingRule.class));
            } catch (JacksonException e) {
                unreadable++;
                log.warn("Skipping a routing rule this build cannot read: {}", e.getMessage());
            }
        }
        loaded.setRules(kept);
        if (unreadable > 0) {
            persistence.couldNotRead(ROUTING_FILE, unreadable);
        }
        return loaded;
    }

    /**
     * One-time migration from the preset era to section composition. The
     * presets made the sections mutually exclusive, so a file can carry data
     * the old build never applied (the default bypass country under
     * route_all, experimental custom rules outside the custom preset).
     * Migration is conservative: whatever was in effect stays in effect,
     * whatever was dormant is dropped — silently enabling a dormant bypass
     * or rule set would change where the user's traffic goes. The original
     * file is kept as routing.json.bak before the rewrite.
     */
    private void migrateLegacyPreset(Path file) {
        String preset = config.getLegacyPreset();
        if (preset == null) {
            return;
        }
        try {
            Files.copy(file, file.resolveSibling(ROUTING_FILE + ".bak"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Could not back up {} before preset migration", file, e);
        }
        if ("bypass_domestic".equals(preset)) {
            // Pre-multi files could omit the country entirely; the old
            // generator treated that as "ru", so the migration must too.
            if (config.getBypassCountries().isEmpty()) {
                config.setBypassCountries(List.of("ru"));
            }
        } else {
            config.setBypassCountries(List.of());
        }
        if (!"custom".equals(preset)) {
            config.setRules(new ArrayList<>());
        }
        config.clearLegacyPreset();
        saveConfig(config);
        log.info("Migrated routing config from preset '{}' to section composition", preset);
    }
}
