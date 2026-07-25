package com.vlessclient.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vlessclient.app.AppVersion;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks GitHub Releases for new versions and downloads updates.
 */
public class UpdateManager {

    private static final Logger log = LoggerFactory.getLogger(UpdateManager.class);

    static final String RELEASES_URL =
            "https://api.github.com/repos/dbelokursky/tunl/releases/latest";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final long CHECK_INTERVAL_HOURS = 24;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;

    private final ReadOnlyBooleanWrapper updateAvailable = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyStringWrapper latestVersion = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper downloadUrl = new ReadOnlyStringWrapper("");

    /**
     * Creates an update manager with a default HTTP client that follows
     * redirects and uses the standard connect timeout.
     */
    public UpdateManager() {
        this(HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    UpdateManager(HttpClient httpClient) {
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "update-checker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the periodic update check: once immediately, then every 24 hours.
     */
    public void startPeriodicCheck() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkForUpdates();
            } catch (Exception e) {
                log.warn("Scheduled update check failed", e);
            }
        }, 0, CHECK_INTERVAL_HOURS, TimeUnit.HOURS);
    }

    /**
     * Stops the periodic update check.
     */
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * Checks the GitHub Releases API for a newer version.
     */
    public void checkForUpdates() {
        log.info("Checking for updates...");
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(RELEASES_URL))
                    .header("Accept", "application/vnd.github+json")
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("GitHub API returned status {}", response.statusCode());
                return;
            }

            processReleaseResponse(response.body());
        } catch (IOException | InterruptedException e) {
            log.warn("Update check failed: {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Parses the GitHub release JSON and updates properties if a newer version exists.
     * Package-private for testing.
     */
    void processReleaseResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String tagName = root.path("tag_name").asText("");
            String version = stripVersionPrefix(tagName);

            String installerUrl = findInstallerAssetUrl(root.path("assets"));

            if (isNewerVersion(version, AppVersion.VERSION)) {
                log.info("Update available: {} -> {}", AppVersion.VERSION, version);
                Platform.runLater(() -> {
                    latestVersion.set(version);
                    downloadUrl.set(installerUrl);
                    updateAvailable.set(true);
                });
            } else {
                log.info("Already on latest version ({})", AppVersion.VERSION);
            }
        } catch (Exception e) {
            log.warn("Failed to parse release response: {}", e.getMessage());
        }
    }

    /**
     * Downloads the installer (DMG/MSI) from the given URL to the user's
     * Downloads directory.
     *
     * @return the saved file path, or {@code null} if the download failed
     */
    public Path downloadUpdate(String url) {
        if (url == null || url.isBlank()) {
            log.warn("No download URL provided");
            return null;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/octet-stream")
                    .timeout(Duration.ofMinutes(10))
                    .GET()
                    .build();

            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                log.error("Download failed with status {}", response.statusCode());
                return null;
            }

            String fileName = extractFileName(url);
            Path downloadsDir = com.vlessclient.platform.PlatformPaths.current().downloadsDir();
            Path target = downloadsDir.resolve(fileName);

            try (InputStream in = response.body()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }

            log.info("Update downloaded to {}", target);
            return target;
        } catch (IOException | InterruptedException e) {
            log.error("Failed to download update: {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    // -- Properties --

    public ReadOnlyBooleanProperty updateAvailableProperty() {
        return updateAvailable.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty latestVersionProperty() {
        return latestVersion.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty downloadUrlProperty() {
        return downloadUrl.getReadOnlyProperty();
    }

    // -- Version comparison utilities (package-private for testing) --

    /**
     * Strips a leading 'v' or 'V' prefix from a version string.
     */
    static String stripVersionPrefix(String version) {
        if (version != null && !version.isEmpty()
                && (version.charAt(0) == 'v' || version.charAt(0) == 'V')) {
            return version.substring(1);
        }
        return version;
    }

    /**
     * Returns true if {@code candidate} is newer than {@code current}.
     * Compares dot-separated numeric segments (e.g. "0.2.0" > "0.1.0").
     */
    static boolean isNewerVersion(String candidate, String current) {
        if (candidate == null || current == null
                || candidate.isBlank() || current.isBlank()) {
            return false;
        }

        String[] candidateParts = candidate.split("\\.");
        String[] currentParts = current.split("\\.");
        int length = Math.max(candidateParts.length, currentParts.length);

        for (int i = 0; i < length; i++) {
            int c = i < candidateParts.length ? parseSegment(candidateParts[i]) : 0;
            int r = i < currentParts.length ? parseSegment(currentParts[i]) : 0;
            if (c > r) {
                return true;
            }
            if (c < r) {
                return false;
            }
        }
        return false;
    }

    private static int parseSegment(String segment) {
        try {
            return Integer.parseInt(segment);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The installer format this platform consumes (DMG/MSI/DEB). */
    static String installerExtension() {
        return switch (com.vlessclient.platform.Platform.current()) {
            case WINDOWS -> ".msi";
            case LINUX -> ".deb";
            case MAC, OTHER -> ".dmg";
        };
    }

    static String findInstallerAssetUrl(JsonNode assets) {
        return findInstallerAssetUrl(assets, installerExtension(), currentArchToken());
    }

    /**
     * Picks the release asset for this platform: prefers a name carrying the
     * current architecture token (a release can ship e.g. both an amd64 and
     * an arm64 deb), falling back to the first extension match for releases
     * that predate multi-arch assets.
     */
    static String findInstallerAssetUrl(JsonNode assets, String extension, String arch) {
        if (assets == null || !assets.isArray()) {
            return "";
        }
        String fallback = "";
        for (JsonNode asset : assets) {
            String name = asset.path("name").asText("");
            if (!name.endsWith(extension)) {
                continue;
            }
            if (name.contains(arch)) {
                return asset.path("browser_download_url").asText("");
            }
            if (fallback.isEmpty()) {
                fallback = asset.path("browser_download_url").asText("");
            }
        }
        return fallback;
    }

    /** The architecture token release assets carry: {@code arm64} or {@code amd64}. */
    static String currentArchToken() {
        String osArch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        return osArch.contains("aarch64") || osArch.contains("arm64") ? "arm64" : "amd64";
    }

    private static String extractFileName(String url) {
        String path = URI.create(url).getPath();
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return path.substring(lastSlash + 1);
        }
        return "VLESS-Client-update" + installerExtension();
    }
}
