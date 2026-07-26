package com.vlessclient.platform;

import java.nio.file.Path;

/**
 * Per-OS locations for the app's data, cache, logs, and the bundled/downloaded
 * sing-box binary.
 *
 * <ul>
 *   <li>macOS: {@code ~/Library/Application Support/VlessClient}</li>
 *   <li>Windows: {@code %APPDATA%\VlessClient}</li>
 *   <li>Linux: {@code $XDG_DATA_HOME/vless-client} ({@code ~/.local/share/…})</li>
 * </ul>
 */
public interface PlatformPaths {

    /** Root data directory — settings, servers, and the core binary live here. */
    Path dataDir();

    /** Where the resolved/cached sing-box binary is kept. */
    default Path coreBinDir() {
        return dataDir().resolve("bin");
    }

    /** Rotating log files. */
    default Path logsDir() {
        return dataDir().resolve("logs");
    }

    /** User Downloads folder (an app-update installer lands here). */
    Path downloadsDir();

    /**
     * System property that redirects {@link #dataDir()} away from the real
     * per-user location. Mirrors {@code vless.singbox.installDir}, and exists
     * for the same reason: without it every service built on
     * {@code PlatformPaths} — ConfigStore, RoutingService, SubscriptionService,
     * the log dir — reads and writes the developer's actual configuration
     * during a test run, so the suite asserts against a different world
     * locally than it does on a clean CI machine, and is one stray save away
     * from clobbering real servers.
     */
    String DATA_DIR_PROPERTY = "vless.data.dir";

    /**
     * Whether {@link #DATA_DIR_PROPERTY} is redirecting the data dir.
     *
     * <p>Legacy-path migrations must check this and do nothing when it is true.
     * The migrations {@code Files.move} a pre-port file from the old
     * mac-shaped location into the current data dir — with the dir redirected,
     * that "old location" is the developer's real profile, so a test run would
     * move their live config out of it and into {@code target/}, where the next
     * {@code mvn clean} deletes it.</p>
     */
    static boolean isDataDirOverridden() {
        String override = System.getProperty(DATA_DIR_PROPERTY);
        return override != null && !override.isBlank();
    }

    /** The paths implementation for the current OS. */
    static PlatformPaths current() {
        String override = System.getProperty(DATA_DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return new OverriddenPlatformPaths(Path.of(override), osPaths());
        }
        return osPaths();
    }

    private static PlatformPaths osPaths() {
        return switch (Platform.current()) {
            case WINDOWS -> new WindowsPlatformPaths();
            case LINUX -> new LinuxPlatformPaths();
            // OTHER keeps the mac fallback: unix-like defaults beat crashing.
            case MAC, OTHER -> new MacPlatformPaths();
        };
    }

    /**
     * Redirects the data dir while leaving Downloads on the real OS location —
     * that one is the user's own folder, not app state, and nothing writes to
     * it during tests.
     */
    record OverriddenPlatformPaths(Path dataDir, PlatformPaths delegate) implements PlatformPaths {
        @Override
        public Path downloadsDir() {
            return delegate.downloadsDir();
        }
    }
}
