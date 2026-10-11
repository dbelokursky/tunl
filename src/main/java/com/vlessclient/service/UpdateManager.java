package com.vlessclient.service;

import com.vlessclient.app.AppVersion;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.platform.PendingUpdate;
import com.vlessclient.platform.UpdateApplier;
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
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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

    /**
     * Appended to an installer's name while it downloads. It gets its real
     * name only once its digest and signature check out, so a transfer that
     * fails partway leaves nothing that looks like an installer.
     */
    static final String PART_SUFFIX = ".part";

    /**
     * How often the timer checks. Six hours rather than a day: this app runs
     * for weeks at a stretch, so a daily timer means a release found at
     * launch and then nothing until the same hour tomorrow. Four checks a day
     * costs four requests out of the sixty an unauthenticated caller gets per
     * hour, per address — and that budget is shared with everything else on
     * the address, which is why this is not hourly.
     */
    static final long CHECK_INTERVAL_HOURS = 6;

    /**
     * The floor between checks triggered by an event rather than the timer.
     * A tunnel that flaps would otherwise check on every reconnect.
     */
    static final long EVENT_CHECK_THROTTLE_MS = 15L * 60 * 1000;

    /**
     * How soon the next event may check again after one that could not reach
     * GitHub. The claim is taken before the check runs, and a check fired
     * while the tunnel was still coming up failed and held it for the whole
     * quarter hour: for a user whose network blocks GitHub, the next chance
     * the tunnel gave went unused.
     */
    static final long FAILED_EVENT_CHECK_RETRY_MS = 60L * 1000;

    /** When an event last claimed a check; 0 = never. */
    private volatile long lastEventCheckMs;

    /**
     * When a check last got an answer from GitHub, of either kind; 0 = never.
     * The tunnel coming up checks because the timer's check may not have got
     * through, and when it did, a second one is a wasted request: every launch
     * with auto-connect made two a second apart, against an hourly allowance
     * GitHub shares with the whole address.
     */
    private volatile long lastAnsweredMs;

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
     * Whether {@link #candidate} is a version that failed to install here
     * twice and was given up on. Kept beside it, set on the checker thread,
     * so the UI can ask {@link #hold()} without reading a file.
     */
    private volatile boolean candidateAbandoned;

    /**
     * Whether an installer is waiting in staging, as of the last check. Kept
     * as a field because the Settings row asks on the FX thread, and the
     * answer otherwise costs a file read there.
     */
    private volatile boolean staged;

    /**
     * The same fact, as something the UI can subscribe to.
     *
     * <p>Two of them because the two readers sit on different threads: the
     * download policy asks from the checker thread, where touching an FX
     * property is not allowed, while the dashboard banner needs a change it
     * can react to. With only the volatile, nothing ever told the banner the
     * download had finished — it announced "downloading" and stayed there for
     * the rest of the run, because the properties it does watch were already
     * at their final values by then.</p>
     */
    private final ReadOnlyBooleanWrapper stagedObservable = new ReadOnlyBooleanWrapper(false);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;
    private final UpdateStaging staging;
    private final Supplier<UpdateApplier> applier;

    private final ReadOnlyBooleanWrapper updateAvailable = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyStringWrapper latestVersion = new ReadOnlyStringWrapper("");

    /**
     * True while an installer is being fetched. The download starts on its own
     * now — no button asks for it — so this is the only thing that can tell the
     * user why an available update has not turned into a staged one yet.
     */
    private final ReadOnlyBooleanWrapper downloading = new ReadOnlyBooleanWrapper(false);

    /**
     * The authority behind {@link #downloading} — that one is a JavaFX property
     * and only ever touched on the FX thread, which is no use to the three
     * background threads that have to agree on who is fetching.
     */
    private final java.util.concurrent.atomic.AtomicBoolean downloadInFlight =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Creates an update manager with a default HTTP client that follows
     * redirects and uses the standard connect timeout.
     */
    public UpdateManager() {
        this(AppHttpClients.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    UpdateManager(HttpClient httpClient) {
        this(httpClient, new UpdateStaging());
    }

    UpdateManager(HttpClient httpClient, UpdateStaging staging) {
        this(httpClient, staging, UpdateApplier::current);
    }

    /** Test seam: also what decides whether this installation installs updates itself. */
    UpdateManager(HttpClient httpClient, UpdateStaging staging,
                  Supplier<UpdateApplier> applier) {
        this.httpClient = httpClient;
        this.staging = staging;
        this.applier = applier;
        this.objectMapper = JsonMapper.builder().build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                DaemonThreads.factory("update-checker"));
    }

    /**
     * Starts the periodic update check: once immediately, then on the timer.
     * A tunnel coming up checks too — see {@link #checkAfterEvent()}.
     */
    public void startPeriodicCheck() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (checkForUpdates() != CheckResult.UNREACHABLE) {
                    answered(System.currentTimeMillis());
                }
                autoDownloadIfAllowed();
            } catch (Exception e) {
                log.warn("Scheduled update check failed", e);
            }
        }, 0, CHECK_INTERVAL_HOURS, TimeUnit.HOURS);
    }

    /**
     * Checks each time {@code engine} brings a tunnel up.
     *
     * <p>For a user whose network throttles or blocks GitHub — the reason
     * this app exists — that is the moment a check can succeed at all, and no
     * timer can know it. {@link #checkAfterEvent()} throttles the trigger, so
     * a flapping tunnel does not turn into a flapping check.</p>
     *
     * @param engine the engine whose tunnel to follow
     */
    public void checkWhenConnected(SingBoxEngine engine) {
        engine.connectionStateProperty().addListener((o, was, is) -> {
            if (is == ConnectionState.CONNECTED) {
                checkAfterEvent();
            }
        });
    }

    /**
     * Checks because something happened, not because the timer said so.
     *
     * <p>Wired to the tunnel coming up, which is the moment worth reacting
     * to: for a user whose network throttles or blocks GitHub, that is when
     * the check can succeed at all, and the timer has no way of knowing it.
     * Throttled, because a reconnecting tunnel fires this far faster than a
     * check is worth making.</p>
     */
    public void checkAfterEvent() {
        long claimedAt = System.currentTimeMillis();
        if (!claimEventCheck(claimedAt)) {
            return;
        }
        // Onto the checker thread: callers are the FX thread or whatever
        // thread the engine changed state on, and neither should wait on HTTP.
        scheduler.execute(() -> {
            try {
                // Runs after whatever the checker was doing: at launch, the
                // timer's first check, which may just have got its answer.
                if (answeredRecently(System.currentTimeMillis())) {
                    log.debug("Skipping the event's update check: GitHub answered moments ago");
                    return;
                }
                CheckResult result = checkForUpdates();
                if (result == CheckResult.UNREACHABLE) {
                    eventCheckFailed(claimedAt);
                } else {
                    answered(System.currentTimeMillis());
                }
                autoDownloadIfAllowed();
            } catch (Exception e) {
                log.warn("Event-triggered update check failed", e);
            }
        });
    }

    /**
     * Whether an event-triggered check may run now, recording it if so.
     *
     * @param nowMs the current time
     * @return true when the caller may check
     */
    synchronized boolean claimEventCheck(long nowMs) {
        if (lastEventCheckMs != 0 && nowMs - lastEventCheckMs < EVENT_CHECK_THROTTLE_MS) {
            return false;
        }
        if (answeredRecently(nowMs)) {
            return false;
        }
        lastEventCheckMs = nowMs;
        return true;
    }

    /**
     * Records that a check got an answer from GitHub.
     *
     * @param nowMs when it did
     */
    synchronized void answered(long nowMs) {
        lastAnsweredMs = nowMs;
    }

    /**
     * Whether a check got an answer within the event throttle, which makes an
     * event's check redundant.
     *
     * @param nowMs the current time
     * @return true when GitHub answered less than the throttle ago
     */
    boolean answeredRecently(long nowMs) {
        long answered = lastAnsweredMs;
        return answered != 0 && nowMs - answered < EVENT_CHECK_THROTTLE_MS;
    }

    /**
     * Shortens the claim of an event check that could not reach GitHub to
     * {@link #FAILED_EVENT_CHECK_RETRY_MS}, unless a later event has claimed
     * a check since.
     *
     * @param claimedAtMs when the failed check was claimed
     */
    synchronized void eventCheckFailed(long claimedAtMs) {
        if (lastEventCheckMs == claimedAtMs) {
            lastEventCheckMs = claimedAtMs - EVENT_CHECK_THROTTLE_MS + FAILED_EVENT_CHECK_RETRY_MS;
        }
    }

    /**
     * Downloads the release found by the last check, if the policy allows it
     * right now. Runs on the checker thread, so a slow download delays only
     * the next check.
     *
     * <p>Public because every check must be able to follow through, not only
     * the two the scheduler owns. Settings' "Check for updates" calls it on its
     * own thread after checking: without that, a manual check could report an
     * available update and then do nothing about it until the next six-hourly
     * tick — and since the download is no longer offered as a button, nothing
     * else would have started it.</p>
     */
    public void autoDownloadIfAllowed() {
        Candidate pending = candidate;
        if (pending == null) {
            return;
        }
        if (pending.version().equals(staging.abandonedVersion())) {
            // Fetching it again only restarted the loop the attempt bound was
            // there to end: staged, handed off, failed, given up on, fetched
            // again within seconds, and the app quit at two launches in three.
            log.info("Update {} available; it failed to install here twice, so it is "
                    + "left for the user to install", pending.version());
            return;
        }
        UpdateApplier installer = applier.get();
        if (!installer.selfUpdates()) {
            // Nothing here could install it — spending the bytes would only
            // leave an installer the user has to find and run themselves.
            log.info("Update {} available; this installation does not install it "
                    + "itself ({})", pending.version(), installer.hold());
            return;
        }
        Optional<PendingUpdate> waiting = staging.pending();
        setStaged(waiting.isPresent());
        if (!UpdateDownloadPolicy.shouldDownloadNow(
                waiting.map(PendingUpdate::version), pending.version())) {
            log.info("Update {} available; deferring the download", pending.version());
            return;
        }
        if (waiting.isPresent()) {
            // An older installer waiting must not hold back a newer release:
            // the newer one is often the hotfix for the older.
            log.info("Replacing staged update {} with {}",
                    waiting.get().version(), pending.version());
            staging.clear();
            setStaged(false);
        }
        downloadUpdate(pending.url(), pending.digest());
    }

    /**
     * What keeps this installation from installing the update it found, for
     * the UI to say.
     *
     * @return {@link UpdateApplier.Hold#INSTALL_MANUALLY} for a release that
     *         failed to install here twice, otherwise the platform's answer
     */
    public UpdateApplier.Hold hold() {
        return candidateAbandoned ? UpdateApplier.Hold.INSTALL_MANUALLY : applier.get().hold();
    }

    /**
     * Stops the periodic update check.
     */
    public void shutdown() {
        scheduler.shutdownNow();
        if (httpClient != null) {
            // The client keeps a selector thread; the UI test suite rebuilds
            // the service graph many times per JVM.
            httpClient.shutdownNow();
        }
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
            String tagName = root.path("tag_name").asString("");
            String version = stripVersionPrefix(tagName);

            InstallerAsset asset = findInstallerAsset(
                    root.path("assets"), installerExtension(), currentArchToken());
            String installerUrl = asset.url();

            if (isNewerVersion(version, AppVersion.VERSION)) {
                log.info("Update available: {} -> {}", AppVersion.VERSION, version);
                candidateAbandoned = version.equals(staging.abandonedVersion());
                candidate = new Candidate(version, installerUrl, asset.digest());
                // FxExecutor rather than a bare runLater: with no toolkit (a
                // service test) runLater throws, and the catch below turned a
                // perfectly good answer into "unreachable".
                FxExecutor.run(() -> {
                    latestVersion.set(version);
                    updateAvailable.set(true);
                });
                return CheckResult.UPDATE_AVAILABLE;
            }
            log.info("Already on latest version ({})", AppVersion.VERSION);
            forgetWithdrawn(version);
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
     * Drops what an earlier check found once GitHub no longer reports it.
     *
     * <p>Nothing else ever cleared it: a release found once, then withdrawn
     * or demoted to a prerelease, stayed the download candidate for the rest
     * of a run that can last weeks. It was fetched every six hours and on
     * every connect, offered in the banner, and, once staged, installed at the
     * next restart: the build its maintainer had taken back. An installer
     * already staged for a version newer than the latest release goes too.</p>
     *
     * @param latestReleased the version GitHub now reports as latest
     */
    private void forgetWithdrawn(String latestReleased) {
        Candidate previous = candidate;
        candidate = null;
        candidateAbandoned = false;
        if (previous != null) {
            log.info("Update {} is no longer the latest release ({}); forgetting it",
                    previous.version(), latestReleased);
            FxExecutor.run(() -> {
                updateAvailable.set(false);
                latestVersion.set("");
            });
        }
        Optional<PendingUpdate> waiting = staging.pending();
        if (waiting.isPresent() && isNewerVersion(waiting.get().version(), latestReleased)) {
            log.info("Staged update {} is newer than the latest release {}; discarding it",
                    waiting.get().version(), latestReleased);
            staging.clear();
            setStaged(false);
        }
    }

    /**
     * Downloads and verifies the installer (DMG/MSI/DEB) into the staging
     * directory, recording it as the update to apply at the next start.
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
     * <p>The bytes land in a {@code .part} file that takes the installer's
     * name only once both checks pass, and any failure deletes it: a rejected
     * or half-downloaded installer must never be left where it could still be
     * opened, and a transfer that ran out of disk must give the space back.</p>
     *
     * <p>One at a time. Three callers can reach this — the six-hourly timer,
     * the tunnel coming up, and Settings' own check — on three different
     * threads, and the "is one already staged?" guard upstream cannot separate
     * them: two threads both see nothing staged and both proceed. They would
     * then write the same installer to the same path, and
     * {@code Files.copy(REPLACE_EXISTING)} interleaving two streams into one
     * file produces bytes that match neither, so the digest check rejects the
     * result and both downloads are wasted.</p>
     *
     * @param url            the asset URL to fetch
     * @param expectedDigest {@code sha256:<hex>} for that asset
     * @return the saved file path, or {@code null} if download or verification
     *         failed, or another download was already running
     */
    Path downloadUpdate(String url, String expectedDigest) {
        if (!downloadInFlight.compareAndSet(false, true)) {
            log.info("An installer is already downloading; not starting a second fetch");
            return null;
        }
        // Wrapped rather than flagged inline: the body returns null from a
        // dozen places, and a flag left true by any one of them would leave the
        // UI saying "Downloading…" for the rest of the run.
        setDownloading(true);
        try {
            return fetchAndStage(url, expectedDigest);
        } finally {
            setDownloading(false);
            downloadInFlight.set(false);
        }
    }

    /** The download itself; separated so the gate around it can be tested. */
    Path fetchAndStage(String url, String expectedDigest) {
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

        Path part = null;
        try {
            if (staging.pending().isEmpty()) {
                // Nothing is waiting, so whatever the folder holds is left over:
                // an installer whose marker was lost, or a .part from a run that
                // died mid-download. Each one is up to a hundred megabytes.
                staging.clear();
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/octet-stream")
                    // No timeout here: on this JDK it bounds the whole transfer,
                    // and a slow link lost every installer at the ten-minute
                    // mark. LongDownload bounds the wait and each stall instead.
                    .GET()
                    .build();

            HttpResponse<InputStream> response = LongDownload.send(httpClient, request);

            if (response.statusCode() != 200) {
                response.body().close();
                log.error("Download failed with status {}", response.statusCode());
                return null;
            }

            String fileName = extractFileName(url);
            part = staging.dir().resolve(fileName + PART_SUFFIX);

            String actualDigest;
            try (InputStream in = LongDownload.guard(response.body());
                 java.security.DigestInputStream digesting = new java.security.DigestInputStream(
                         in, java.security.MessageDigest.getInstance("SHA-256"))) {
                Files.copy(digesting, part, StandardCopyOption.REPLACE_EXISTING);
                actualDigest = "sha256:" + java.util.HexFormat.of()
                        .formatHex(digesting.getMessageDigest().digest());
            }

            if (!actualDigest.equalsIgnoreCase(expectedDigest)) {
                // The .part goes on the way out: a rejected installer must not
                // be left where the user could open it.
                log.error("Update rejected: sha256 mismatch for {} (expected {}, got {})",
                        fileName, expectedDigest, actualDigest);
                return null;
            }

            String signature = "";
            if (ReleaseSignature.enforced()) {
                signature = fetchValidSignature(url, versionFromReleaseUrl(url),
                        extractFileName(url), actualDigest);
                if (signature == null) {
                    // Same rule as a digest mismatch: an installer that fails a
                    // check must not be left where it could still be run.
                    return null;
                }
            }

            Path target = staging.dir().resolve(fileName);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            try {
                staging.stage(new PendingUpdate(
                        versionFromReleaseUrl(url), target, expectedDigest), signature);
            } catch (IOException | IllegalArgumentException e) {
                // An installer nothing records is an installer nothing will
                // ever apply; don't leave it on disk pretending otherwise.
                Files.deleteIfExists(target);
                log.error("Failed to stage the downloaded update: {}", e.getMessage());
                return null;
            }

            setStaged(true);
            log.info("Update downloaded, verified and staged: {}", target);
            return target;
        } catch (IOException | InterruptedException
                 | java.security.NoSuchAlgorithmException e) {
            // With the exception, not just its message. A user reported
            // "Failed to download update: closed" — one word, from somewhere
            // inside java.net.http, for a download that succeeded on the same
            // release from the same machine minutes later. The class and the
            // frame it came from are the whole difference between diagnosing
            // that and guessing at it, and this is a failure only a user's
            // machine tends to produce.
            log.error("Failed to download update from {}", url, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        } finally {
            if (part != null) {
                deleteQuietly(part);
            }
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", file, e.getMessage());
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
    private String fetchValidSignature(String url, String version, String assetName,
                                       String digest) {
        String signatureUrl = url + ReleaseSignature.MANIFEST_SIGNATURE_SUFFIX;
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
                return null;
            }
            if (!ReleaseSignature.verifyRelease(version, assetName, digest,
                    response.body())) {
                log.error("Update rejected: signature does not match {}", url);
                return null;
            }
            // Returned rather than discarded: it is stored with the staged
            // installer so the check can be repeated before the file is run,
            // against something the staging directory cannot forge.
            return response.body();
        } catch (IOException | InterruptedException e) {
            log.error("Update rejected: cannot fetch signature: {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
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

    /**
     * Fires when an installer becomes (or stops being) ready to apply, so the
     * dashboard banner can offer the restart the moment the download lands
     * rather than at some later event that may never come.
     *
     * @return the observable form of {@link #hasStagedUpdate()}
     */
    public ReadOnlyBooleanProperty stagedProperty() {
        return stagedObservable.getReadOnlyProperty();
    }

    /**
     * Records the staged state in both forms at once. Package-private so a
     * test can drive the transition the UI depends on.
     *
     * @param value whether a verified installer is now waiting
     */
    void setStaged(boolean value) {
        staged = value;
        // Marshals to the FX thread, and runs inline when there is no toolkit
        // — which is what lets this be exercised in a plain unit test.
        FxExecutor.run(() -> stagedObservable.set(value));
    }

    private void setDownloading(boolean value) {
        FxExecutor.run(() -> downloading.set(value));
    }

    // -- Properties --

    public ReadOnlyBooleanProperty updateAvailableProperty() {
        return updateAvailable.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty latestVersionProperty() {
        return latestVersion.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty downloadingProperty() {
        return downloading.getReadOnlyProperty();
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

    /** The architecture tokens release asset names carry. */
    private static final java.util.List<String> ARCH_TOKENS = java.util.List.of("arm64", "amd64");

    /**
     * The architecture an asset whose name carries no token is built for.
     * The release workflow builds the one DMG on an Apple Silicon runner and
     * the MSI on x64, and debs from before multi-arch releases were amd64.
     */
    static String untaggedArch(String extension) {
        return ".dmg".equals(extension) ? "arm64" : "amd64";
    }

    /**
     * Picks the release asset for this platform: a name carrying the current
     * architecture token (a release can ship both an amd64 and an arm64 deb),
     * or else one carrying no token, but only where such assets are built for
     * this architecture.
     *
     * <p>The fallback used to be the first asset with the right extension,
     * whatever it was built for. An Intel Mac then took the Apple Silicon DMG,
     * whose signature is valid, and the relay replaced a working app with one
     * the machine cannot run. No asset for this machine is the safe answer.</p>
     */
    static InstallerAsset findInstallerAsset(JsonNode assets, String extension, String arch) {
        if (assets == null || !assets.isArray()) {
            return InstallerAsset.NONE;
        }
        InstallerAsset fallback = InstallerAsset.NONE;
        boolean untaggedFits = arch.equals(untaggedArch(extension));
        for (JsonNode asset : assets) {
            String name = asset.path("name").asString("");
            if (!name.endsWith(extension)) {
                continue;
            }
            InstallerAsset candidate = new InstallerAsset(
                    asset.path("browser_download_url").asString(""),
                    asset.path("digest").asString(""));
            if (name.contains(arch)) {
                return candidate;
            }
            boolean untagged = ARCH_TOKENS.stream().noneMatch(name::contains);
            if (untagged && untaggedFits && fallback.isEmpty()) {
                fallback = candidate;
            }
        }
        return fallback;
    }

    /**
     * The architecture token release assets carry: {@code arm64} or
     * {@code amd64}. An architecture no release is built for throws, which
     * the check reports as no answer — it used to download the amd64 build.
     */
    static String currentArchToken() {
        return com.vlessclient.platform.CpuArch.releaseToken();
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
