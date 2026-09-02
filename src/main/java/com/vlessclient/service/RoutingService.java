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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

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

    public RoutingService() {
        this(resolveDataDir());
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
        this.config = config;
        Path file = dataDir.resolve(ROUTING_FILE);
        try {
            // Owner-only and atomic, like the other config files: these rules
            // fingerprint the user's traffic, and a crash mid-write used to be
            // able to truncate them.
            SecureFiles.writePrivately(file, objectMapper.writeValueAsBytes(config));
            log.info("Saved routing config to {}", file);
        } catch (IOException e) {
            log.error("Failed to save routing config to {}", file, e);
        }
    }

    public synchronized void addRule(RoutingRule rule) {
        config.getRules().add(rule);
        saveConfig(config);
    }

    /**
     * Removes the rule with the given id and persists the change. Logs a
     * warning if no matching rule is found.
     *
     * @param ruleId the id of the rule to remove
     */
    public synchronized void removeRule(String ruleId) {
        boolean removed = config.getRules().removeIf(r -> r.getId().equals(ruleId));
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
        if (!Files.exists(file)) {
            log.info("No routing config found at {}, using defaults", file);
            this.config = new RoutingConfig();
            return;
        }
        try {
            this.config = objectMapper.readValue(file.toFile(), RoutingConfig.class);
            log.info("Loaded routing config from {}", file);
            migrateLegacyPreset(file);
        } catch (JacksonException e) {
            log.error("Failed to load routing config from {}", file, e);
            // Aside, not overwritten: the next rule edit would otherwise erase
            // the only copy of a file that a person could likely still repair.
            ConfigStore.quarantineCorrupt(file);
            this.config = new RoutingConfig();
        }
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
