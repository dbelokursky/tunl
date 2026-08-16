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

    /**
     * Installer downloads must come from here. The API response names the URL,
     * so without this bound a tampered response could redirect the download.
     */
    static final String RELEASE_DOWNLOAD_PREFIX =
            "https://github.com/dbelokursky/tunl/releases/download/";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final long CHECK_INTERVAL_HOURS = 24;

    /**
     * SHA-256 published for the pending update's installer, captured alongside
     * its URL so the download is checked against the digest of the same asset.
     */
    private volatile String expectedDigest = "";

    /**
     * The release the last check found, held outside the JavaFX properties so
     * the background download can read it on its own thread. The properties
     * below drive the UI and are only ever touched on the FX thread.
     *
     * @param version the release version
     * @param url     the installer asset URL
     * @param digest  the {@code sha256:<hex>} published for that asset
     */
    record Candidate(String version, String url, String digest) {
    }

    private volatile Candidate candidate;

    /**
     * Whether an installer is waiting in staging, as of the last check. Kept
     * as a field because the Settings row asks on the FX thread, and the
     * answer otherwise costs a file read there.
     */
    private volatile boolean staged;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;
    private final UpdateStaging staging;

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
        this(httpClient, new UpdateStaging());
    }

    UpdateManager(HttpClient httpClient, UpdateStaging staging) {
        this.httpClient = httpClient;
        this.staging = staging;
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
                autoDownloadIfAllowed();
            } catch (Exception e) {
                log.warn("Scheduled update check failed", e);
            }
        }, 0, CHECK_INTERVAL_HOURS, TimeUnit.HOURS);
    }

    /**
     * Downloads the release found by the last check, if the policy allows it
     * right now. Runs on the checker thread, so a slow download delays only
     * the next check — which is a day away.
     */
    void autoDownloadIfAllowed() {
        Candidate pending = candidate;
        if (pending == null) {
            return;
        }
        if (!com.vlessclient.platform.UpdateApplier.current().selfUpdates()) {
            // Nothing here could install it — spending the bytes would only
            // leave an installer the user has to find and run themselves.
            log.info("Update {} available; this installation updates through "
                    + "its package manager", pending.version());
            return;
        }
        staged = staging.pending().isPresent();
        if (!UpdateDownloadPolicy.shouldDownloadNow(staged)) {
            log.info("Update {} available; deferring the download", pending.version());
            return;
        }
        downloadUpdate(pending.url(), pending.digest());
    }

    /**
     * Stops the periodic update check.
     */
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * How a check turned out.
     *
     * <p>The distinction that matters is between the last two and the first
     * two: "nothing newer exists" and "no answer" are not the same fact, and
     * a check that cannot reach GitHub must never be reported as being up to
     * date. Silence looks identical to good news, and the networks this client
     * exists for are exactly the ones where the answer goes missing.</p>
     */
    public enum CheckResult {

        /** A newer release exists; the properties below describe it. */
        UPDATE_AVAILABLE,

        /** GitHub answered, and this build is current. */
        UP_TO_DATE,

        /** GitHub refused: 60 unauthenticated requests per hour, per address. */
        RATE_LIMITED,

        /** No usable answer — offline, blocked, or an unreadable response. */
        UNREACHABLE
    }

    /**
     * Checks the GitHub Releases API for a newer version.
     *
     * @return what the check established, including its own failure
     */
    public CheckResult checkForUpdates() {
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
                return resultForStatus(response.statusCode());
            }

            return processReleaseResponse(response.body());
        } catch (IOException | InterruptedException e) {
            log.warn("Update check failed: {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return CheckResult.UNREACHABLE;
        }
    }

    /**
     * Classifies a non-200 status.
     *
     * <p>403 is the one worth naming: unauthenticated callers get 60 requests
     * an hour per IP address, shared with everything else on that address, and
     * the app sends no token. Told plainly, it is something the user can wait
     * out; folded into a generic failure, it looks like a broken app.</p>
     *
     * @param statusCode the HTTP status the API answered with
     * @return the matching result
     */
    static CheckResult resultForStatus(int statusCode) {
        return statusCode == 403 || statusCode == 429
                ? CheckResult.RATE_LIMITED
                : CheckResult.UNREACHABLE;
    }

    /**
     * Parses the GitHub release JSON and updates properties if a newer version
     * exists. Package-private for testing.
     *
     * @param json the Releases API response body
     * @return what the response established
     */
    CheckResult processReleaseResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String tagName = root.path("tag_name").asText("");
            String version = stripVersionPrefix(tagName);

            InstallerAsset asset = findInstallerAsset(
                    root.path("assets"), installerExtension(), currentArchToken());
            String installerUrl = asset.url();

            if (isNewerVersion(version, AppVersion.VERSION)) {
                log.info("Update available: {} -> {}", AppVersion.VERSION, version);
                expectedDigest = asset.digest();
                candidate = new Candidate(version, installerUrl, asset.digest());
                Platform.runLater(() -> {
                    latestVersion.set(version);
                    downloadUrl.set(installerUrl);
                    updateAvailable.set(true);
                });
                return CheckResult.UPDATE_AVAILABLE;
            }
            log.info("Already on latest version ({})", AppVersion.VERSION);
            return CheckResult.UP_TO_DATE;
        } catch (Exception e) {
            // A response we cannot read is an answer we did not get. Reporting
            // it as up to date would be the very thing this class stopped
            // doing everywhere else.
            log.warn("Failed to parse release response: {}", e.getMessage());
            return CheckResult.UNREACHABLE;
        }
    }

    /**
     * Downloads the installer (DMG/MSI/DEB) for the pending update into the
     * staging directory, verifying it and recording it as the update to apply
     * at the next start.
     *
     * @return the saved file path, or {@code null} if the download failed
     */
    public Path downloadUpdate(String url) {
        return downloadUpdate(url, expectedDigest);
    }

    /**
     * Downloads and verifies the installer.
     *
     * <p>The file is not merely offered to the user any more: it is what the
     * next start will hand to the OS installer without asking again. Two
     * checks make that safe to rely on:</p>
     *
     * <ul>
     *   <li>The URL must sit under the official releases prefix, so a tampered
     *       API response cannot point the download somewhere else.</li>
     *   <li>The bytes must hash to the {@code sha256:} digest the Releases API
     *       published for that same asset. No digest, no install — a release
     *       without one is not offered rather than trusted.</li>
     * </ul>
     *
     * <p>A failed check deletes the partial file: a rejected installer must
     * never be left sitting in Downloads where it could still be opened.</p>
     *
     * @param url            the asset URL to fetch
     * @param expectedDigest {@code sha256:<hex>} for that asset
     * @return the saved file path, or {@code null} if download or verification failed
     */
    Path downloadUpdate(String url, String expectedDigest) {
        if (url == null || url.isBlank()) {
            log.warn("No download URL provided");
            return null;
        }
        if (!url.startsWith(RELEASE_DOWNLOAD_PREFIX)) {
            log.error("Refusing update download outside {}: {}", RELEASE_DOWNLOAD_PREFIX, url);
            return null;
        }
        if (expectedDigest == null || !expectedDigest.startsWith("sha256:")) {
            log.error("Refusing update download with no sha256 digest: {}", url);
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
            Path target = staging.dir().resolve(fileName);

            String actualDigest;
            try (InputStream in = response.body();
                 java.security.DigestInputStream digesting = new java.security.DigestInputStream(
                         in, java.security.MessageDigest.getInstance("SHA-256"))) {
                Files.copy(digesting, target, StandardCopyOption.REPLACE_EXISTING);
                actualDigest = "sha256:" + java.util.HexFormat.of()
                        .formatHex(digesting.getMessageDigest().digest());
            }

            if (!actualDigest.equalsIgnoreCase(expectedDigest)) {
                // Don't leave a rejected installer where the user could open it.
                Files.deleteIfExists(target);
                log.error("Update rejected: sha256 mismatch for {} (expected {}, got {})",
                        fileName, expectedDigest, actualDigest);
                return null;
            }

            if (ReleaseSignature.enforced() && !signatureIsValid(url, actualDigest)) {
                // Same rule as a digest mismatch: an installer that fails a
                // check must not be left where it could still be run.
                Files.deleteIfExists(target);
                return null;
            }

            try {
                staging.stage(new com.vlessclient.platform.PendingUpdate(
                        versionFromReleaseUrl(url), target, expectedDigest));
            } catch (IOException | IllegalArgumentException e) {
                // An installer nothing records is an installer nothing will
                // ever apply; don't leave it on disk pretending otherwise.
                Files.deleteIfExists(target);
                log.error("Failed to stage the downloaded update: {}", e.getMessage());
                return null;
            }

            staged = true;
            log.info("Update downloaded, verified and staged: {}", target);
            return target;
        } catch (IOException | InterruptedException
                 | java.security.NoSuchAlgorithmException e) {
            log.error("Failed to download update: {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /**
     * Fetches the detached signature published next to an asset and checks it
     * against the digest of the bytes just downloaded.
     *
     * <p>The signature lives at the asset's own URL plus {@code .sig}, so it
     * is covered by the same release-prefix pin as the installer and cannot be
     * pointed elsewhere by a tampered API response. A missing signature is a
     * failure, not a reason to skip the check.</p>
     *
     * @param url    the installer asset URL
     * @param digest the {@code sha256:<hex>} of the downloaded bytes
     * @return true when a valid signature was found
     */
    private boolean signatureIsValid(String url, String digest) {
        String signatureUrl = url + ReleaseSignature.SIGNATURE_SUFFIX;
        try {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(signatureUrl))
                            .timeout(HTTP_TIMEOUT)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Update rejected: no signature at {} (status {})",
                        signatureUrl, response.statusCode());
                return false;
            }
            if (!ReleaseSignature.verifyDigest(digest, response.body())) {
                log.error("Update rejected: signature does not match {}", url);
                return false;
            }
            return true;
        } catch (IOException | InterruptedException e) {
            log.error("Update rejected: cannot fetch signature: {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * Whether a verified installer is already waiting to be applied at the
     * next start — the state the background download leaves behind.
     *
     * @return true when an update is staged
     */
    public boolean hasStagedUpdate() {
        return staged;
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

    /**
     * A release asset plus the SHA-256 the API publishes for it. The two travel
     * together so the bytes that get downloaded are always checked against the
     * digest of the <em>same</em> asset the URL came from.
     *
     * @param url    the browser download URL
     * @param digest the {@code sha256:<hex>} digest, or empty when absent
     */
    record InstallerAsset(String url, String digest) {
        static final InstallerAsset NONE = new InstallerAsset("", "");

        boolean isEmpty() {
            return url.isEmpty();
        }
    }

    static String findInstallerAssetUrl(JsonNode assets) {
        return findInstallerAsset(assets, installerExtension(), currentArchToken()).url();
    }

    static String findInstallerAssetUrl(JsonNode assets, String extension, String arch) {
        return findInstallerAsset(assets, extension, arch).url();
    }

    /**
     * Picks the release asset for this platform: prefers a name carrying the
     * current architecture token (a release can ship e.g. both an amd64 and
     * an arm64 deb), falling back to the first extension match for releases
     * that predate multi-arch assets.
     */
    static InstallerAsset findInstallerAsset(JsonNode assets, String extension, String arch) {
        if (assets == null || !assets.isArray()) {
            return InstallerAsset.NONE;
        }
        InstallerAsset fallback = InstallerAsset.NONE;
        for (JsonNode asset : assets) {
            String name = asset.path("name").asText("");
            if (!name.endsWith(extension)) {
                continue;
            }
            InstallerAsset candidate = new InstallerAsset(
                    asset.path("browser_download_url").asText(""),
                    asset.path("digest").asText(""));
            if (name.contains(arch)) {
                return candidate;
            }
            if (fallback.isEmpty()) {
                fallback = candidate;
            }
        }
        return fallback;
    }

    /** The architecture token release assets carry: {@code arm64} or {@code amd64}. */
    static String currentArchToken() {
        String osArch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        return osArch.contains("aarch64") || osArch.contains("arm64") ? "arm64" : "amd64";
    }

    /**
     * Reads the release version out of an asset URL, whose path always carries
     * the tag right after the download prefix
     * ({@code .../releases/download/v1.6.0/tunl_1.6.0_arm64.dmg}). Taken from
     * the URL rather than from the check that produced it, so the version
     * recorded for a file always describes the file actually fetched.
     *
     * @param url an asset URL under {@link #RELEASE_DOWNLOAD_PREFIX}
     * @return the version without its {@code v} prefix, or empty if absent
     */
    static String versionFromReleaseUrl(String url) {
        if (url == null || !url.startsWith(RELEASE_DOWNLOAD_PREFIX)) {
            return "";
        }
        String rest = url.substring(RELEASE_DOWNLOAD_PREFIX.length());
        int slash = rest.indexOf('/');
        return slash <= 0 ? "" : stripVersionPrefix(rest.substring(0, slash));
    }

    private static String extractFileName(String url) {
        String path = URI.create(url).getPath();
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return path.substring(lastSlash + 1);
        }
        return "tunl-update" + installerExtension();
    }
}
