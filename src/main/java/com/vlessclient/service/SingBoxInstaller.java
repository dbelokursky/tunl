package com.vlessclient.service;

import com.vlessclient.platform.SecureFiles;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads, extracts, and manages a cached copy of the {@code sing-box} binary,
 * so the user does not have to install it manually.
 *
 * <p>Resolution order for finding an existing binary:</p>
 * <ol>
 *   <li>Bundled inside the macOS .app bundle ({@code Contents/Resources/sing-box})</li>
 *   <li>Cached download at {@code ~/Library/Application Support/VlessClient/bin/sing-box}</li>
 *   <li>Homebrew / MacPorts standard locations</li>
 *   <li>Anything on {@code $PATH}</li>
 * </ol>
 *
 * <p>If none of the above return a working binary, {@link #install(DoubleConsumer)}
 * downloads the pinned release from GitHub, extracts it into the cache directory,
 * sets the executable bit, and returns the path.</p>
 */
public class SingBoxInstaller {

    private static final Logger log = LoggerFactory.getLogger(SingBoxInstaller.class);

    /**
     * Pinned sing-box version, loaded from the singbox.properties classpath
     * resource — the single source of truth shared with pom.xml and
     * scripts/bundle-singbox.sh. Bump with scripts/bump-singbox.sh.
     */
    public static final String PINNED_VERSION;

    /**
     * Records which pinned version populated the cache, so
     * {@link #reconcileCacheWithPin()} can spot a cache left behind by an
     * older app release without spawning the binary on every launch.
     */
    private static final String VERSION_MARKER_NAME = "sing-box.version";

    /** Version probes on the startup path: short, so a wedged binary can't stall launch. */
    private static final int VERSION_PROBE_TIMEOUT_SECONDS = 5;

    /**
     * Post-install verification: longer, because the first exec of a freshly
     * extracted 50 MB binary can be slow (on-access antivirus scanning, cold
     * page cache), and failing there would discard a good download.
     */
    private static final int VERSION_VERIFY_TIMEOUT_SECONDS = 10;

    /**
     * SHA-256 checksums of the host OS's release archives, keyed by
     * architecture (arm64/amd64), from the same properties resource. Verified
     * after the runtime fallback download to protect against corruption or
     * tampering. amd64 is always pinned; arm64 where we ship it
     * (darwin and linux).
     */
    private static final Map<String, String> EXPECTED_SHA256;

    static {
        java.util.Properties props = new java.util.Properties();
        try (InputStream in = SingBoxInstaller.class.getResourceAsStream("/singbox.properties")) {
            if (in == null) {
                throw new IllegalStateException(
                        "singbox.properties is missing from the classpath");
            }
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read singbox.properties", e);
        }
        String version = props.getProperty("singbox.version", "").trim();
        String osKey = com.vlessclient.platform.CorePlatform.current().osKey();
        Map<String, String> checksums = new java.util.HashMap<>();
        for (String arch : new String[]{"arm64", "amd64"}) {
            String sha = props.getProperty("singbox.sha256." + osKey + "-" + arch, "").trim();
            if (sha.length() == 64) {
                checksums.put(arch, sha);
            }
        }
        if (version.isEmpty() || !checksums.containsKey("amd64")) {
            throw new IllegalStateException(
                    "singbox.properties is incomplete: version='" + version
                            + "', pinned archs for " + osKey + ": " + checksums.keySet());
        }
        PINNED_VERSION = version;
        EXPECTED_SHA256 = Map.copyOf(checksums);
    }

    private static final String DOWNLOAD_URL_TEMPLATE =
            "https://github.com/SagerNet/sing-box/releases/download/v%s/sing-box-%s-"
                    + com.vlessclient.platform.CorePlatform.current().osKey()
                    + "-%s."
                    + com.vlessclient.platform.CorePlatform.current().archiveExtension();

    private final com.vlessclient.platform.CorePlatform corePlatform =
            com.vlessclient.platform.CorePlatform.current();
    private final String binaryName = corePlatform.binaryName();

    private final Path installDir;
    private final HttpClient httpClient;
    private final String downloadUrlTemplate;
    private final Map<String, String> expectedSha256;

    public SingBoxInstaller() {
        this(resolveInstallDir(), DOWNLOAD_URL_TEMPLATE, EXPECTED_SHA256);
    }

    /**
     * Returns the install directory to use. Tests can override the default
     * location via the {@code vless.singbox.installDir} system property so
     * they don't touch the user's real ~/Library/Application Support cache.
     */
    private static Path resolveInstallDir() {
        String override = System.getProperty("vless.singbox.installDir");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return defaultInstallDir();
    }

    /** Test constructor: allows overriding install dir, URL template, and checksums. */
    SingBoxInstaller(
            Path installDir, String downloadUrlTemplate, Map<String, String> expectedSha256) {
        this.installDir = installDir;
        this.downloadUrlTemplate = downloadUrlTemplate;
        this.expectedSha256 = Map.copyOf(expectedSha256);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
    }

    /** Test constructor: install dir only, uses production URL + checksums. */
    SingBoxInstaller(Path installDir) {
        this(installDir, DOWNLOAD_URL_TEMPLATE, EXPECTED_SHA256);
    }

    private static Path defaultInstallDir() {
        return com.vlessclient.platform.PlatformPaths.current().coreBinDir();
    }

    /**
     * Returns the path to an already-available {@code sing-box} binary, if any.
     * Does not perform any downloads.
     */
    public Optional<Path> findExisting() {
        // 1. Bundled inside the macOS .app bundle
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            Path appBundle = Path.of(javaHome).getParent();
            if (appBundle != null) {
                Path bundled = appBundle.resolve("Resources").resolve(binaryName);
                if (Files.isExecutable(bundled)) {
                    return Optional.of(bundled.toAbsolutePath());
                }
            }
        }

        // 2. Cached auto-download
        Path cached = installDir.resolve(binaryName);
        if (Files.isExecutable(cached)) {
            return Optional.of(cached.toAbsolutePath());
        }

        // 3. Bundled inside the JAR/classpath (maven build-time bundling).
        //    Extract to the cache directory on first use and return the path.
        //    Tests can skip this by setting vless.singbox.skipBundled=true so
        //    they don't litter the user's Application Support directory.
        if (!Boolean.getBoolean("vless.singbox.skipBundled")) {
            try {
                Optional<Path> extracted = extractBundledResource();
                if (extracted.isPresent()) {
                    return extracted;
                }
            } catch (IOException e) {
                log.warn("Failed to extract bundled sing-box from classpath: {}", e.getMessage());
            }
        }

        // 3. Common system install locations
        String[] commonPaths = {
                "/opt/homebrew/bin/sing-box",
                "/usr/local/bin/sing-box",
                "/opt/local/bin/sing-box"
        };
        for (String p : commonPaths) {
            Path candidate = Path.of(p);
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }

        // 4. $PATH
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                Path candidate = Path.of(dir, binaryName);
                if (Files.isExecutable(candidate)) {
                    return Optional.of(candidate.toAbsolutePath());
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Drops a cached binary that a different app pin put there, so the core
     * this release ships takes over. Call once at startup, before
     * {@link #findExisting()}.
     *
     * <p>Necessary because {@code findExisting()} prefers the cache over the
     * classpath-bundled binary: without this, a cache written by an earlier
     * release would shadow the newer pinned core forever, and bumping the pin
     * would have no effect for existing users.</p>
     *
     * <p>The cache's version comes from the {@value #VERSION_MARKER_NAME}
     * marker. Caches written before the marker existed are probed once by
     * running the binary, and the answer is recorded so later launches stay on
     * the cheap path. A cache whose version cannot be determined is treated as
     * stale — losing a cache costs one re-extraction, keeping a wrong one
     * silently pins the user to the wrong core.</p>
     */
    public void reconcileCacheWithPin() {
        removeCoreUpdaterResidue();
        Path cached = managedBinaryPath();
        Path marker = installDir.resolve(VERSION_MARKER_NAME);
        if (!Files.isRegularFile(cached)) {
            deleteQuietly(marker);
            return;
        }

        String cachedVersion = readMarker(marker);
        if (cachedVersion == null) {
            cachedVersion = probeVersion(cached, VERSION_PROBE_TIMEOUT_SECONDS);
            if (cachedVersion != null) {
                writeMarker(marker, cachedVersion);
            }
        }
        if (PINNED_VERSION.equals(cachedVersion)) {
            return;
        }

        log.info("Cached sing-box {} does not match the app-pinned {}; removing the cache "
                        + "so the bundled binary takes over",
                cachedVersion != null ? cachedVersion : "of unknown version", PINNED_VERSION);
        deleteQuietly(cached);
        deleteQuietly(marker);
    }

    /**
     * Removes leftovers of the in-app core updater that used to live in this
     * directory: its rollback copy (a full binary, tens of megabytes) and its
     * state file. Both are inert now, so this is one-time housekeeping for
     * users upgrading from a release that had the updater.
     */
    private void removeCoreUpdaterResidue() {
        deleteQuietly(installDir.resolve(binaryName + ".previous"));
        deleteQuietly(installDir.resolve("core-update.json"));
    }

    private String readMarker(Path marker) {
        if (!Files.isRegularFile(marker)) {
            return null;
        }
        try {
            String recorded = Files.readString(marker).trim();
            return recorded.isEmpty() ? null : recorded;
        } catch (IOException e) {
            log.debug("Could not read {}: {}", marker, e.getMessage());
            return null;
        }
    }

    private void writeMarker(Path marker, String version) {
        try {
            Files.createDirectories(SecureFiles.parentDirectory(marker));
            Files.writeString(marker, version);
        } catch (IOException e) {
            log.debug("Could not record the cached sing-box version: {}", e.getMessage());
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("Could not delete {}: {}", path, e.getMessage());
        }
    }

    /**
     * The version of the sing-box binary at {@code binary}, as a bare
     * {@code 1.13.14}.
     *
     * <p>For the managed binary the answer comes from the
     * {@value #VERSION_MARKER_NAME} marker, so the common case costs no
     * process spawn. That is safe because {@link #reconcileCacheWithPin()}
     * runs at startup before anything reads this and deletes any cache whose
     * marker disagrees with the pin. Anything else — Homebrew, {@code $PATH},
     * the .app bundle — is asked directly.</p>
     *
     * @return the version, or null when it cannot be determined
     */
    public String detectVersion(Path binary) {
        if (binary == null) {
            return null;
        }
        if (isManagedBinary(binary)) {
            String recorded = readMarker(installDir.resolve(VERSION_MARKER_NAME));
            if (recorded != null) {
                return recorded;
            }
        }
        return probeVersion(binary, VERSION_PROBE_TIMEOUT_SECONDS);
    }

    private boolean isManagedBinary(Path binary) {
        return binary.toAbsolutePath().normalize()
                .equals(managedBinaryPath().toAbsolutePath().normalize());
    }

    /** Runs the binary and parses its version, or null if it did not answer as expected. */
    private String probeVersion(Path binary, int timeoutSeconds) {
        VersionOutput out = runVersionCommand(binary, timeoutSeconds);
        return out != null && out.exitCode() == 0 ? parseVersionLine(out.firstLine()) : null;
    }

    /** What {@code sing-box version} answered: its exit code and first output line. */
    private record VersionOutput(int exitCode, String firstLine) {
    }

    /**
     * Runs {@code <binary> version}. The single place this app shells out for a
     * version, so the two traps live in one method instead of three.
     *
     * <p>Output goes to a file rather than a pipe. Reading the pipe before
     * {@code waitFor} hangs forever on a binary that never closes stdout — and
     * one call site is the startup path, where that would freeze the app before
     * the UI appears. Reading it after {@code waitFor} deadlocks the other way
     * round once the output outgrows the pipe buffer. A file sidesteps both and
     * keeps the timeout guard meaningful.</p>
     *
     * @return the outcome, or null when the process could not be run, timed out,
     *         or the thread was interrupted
     */
    private VersionOutput runVersionCommand(Path binary, int timeoutSeconds) {
        Path out = null;
        try {
            out = Files.createTempFile("singbox-version-", ".out");
            ProcessBuilder pb = new ProcessBuilder(binary.toAbsolutePath().toString(), "version");
            pb.redirectErrorStream(true);
            pb.redirectOutput(out.toFile());
            Process proc = pb.start();
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                log.debug("sing-box at {} did not report its version in time", binary);
                return null;
            }
            return new VersionOutput(proc.exitValue(),
                    Files.readString(out).lines().findFirst().orElse(""));
        } catch (IOException e) {
            log.debug("Could not read the sing-box version at {}: {}", binary, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (out != null) {
                deleteQuietly(out);
            }
        }
    }

    /** Parses {@code "sing-box version 1.13.14"} into {@code "1.13.14"}; null otherwise. */
    private static String parseVersionLine(String firstLine) {
        String prefix = "sing-box version ";
        return firstLine.startsWith(prefix) ? firstLine.substring(prefix.length()).trim() : null;
    }

    /**
     * Downloads the pinned sing-box release for the current CPU architecture,
     * extracts it into the cache directory, and sets the executable bit.
     *
     * @param progress callback invoked with a value in [0.0, 1.0] as bytes are downloaded;
     *                 may be {@code null} if progress reporting is not needed
     * @return path to the installed binary
     * @throws IOException          if any step (network, extraction, chmod) fails
     * @throws InterruptedException if the current thread is interrupted during download
     */
    public Path install(DoubleConsumer progress) throws IOException, InterruptedException {
        Files.createDirectories(installDir);

        String arch = detectArch();
        String url = String.format(Locale.ROOT, downloadUrlTemplate,
                PINNED_VERSION, PINNED_VERSION, arch);
        log.info("Downloading sing-box {} ({}) from {}", PINNED_VERSION, arch, url);

        Path tarball = Files.createTempFile("sing-box-", ".tar.gz");
        try {
            downloadWithProgress(url, tarball, progress);
            verifyChecksum(tarball, arch);
            Path extractDir = Files.createTempDirectory("sing-box-extract-");
            try {
                extractArchive(tarball, extractDir);
                Path sourceBinary = findBinaryInDir(extractDir);
                Path targetBinary = installDir.resolve(binaryName);
                Files.copy(sourceBinary, targetBinary, StandardCopyOption.REPLACE_EXISTING);
                makeExecutable(targetBinary);
                verifyBinary(targetBinary);
                writeMarker(installDir.resolve(VERSION_MARKER_NAME), PINNED_VERSION);
                log.info("sing-box {} installed at {}", PINNED_VERSION, targetBinary);
                return targetBinary.toAbsolutePath();
            } finally {
                deleteRecursive(extractDir);
            }
        } finally {
            Files.deleteIfExists(tarball);
        }
    }

    private void verifyChecksum(Path tarball, String arch) throws IOException {
        String expected = expectedSha256.get(arch);
        if (expected == null) {
            throw new IOException("No SHA-256 checksum registered for arch: " + arch);
        }
        String actual = sha256(tarball);
        if (!expected.equalsIgnoreCase(actual)) {
            throw new IOException("SHA-256 mismatch for sing-box-" + PINNED_VERSION + "-" + arch
                    + ": expected " + expected + ", got " + actual);
        }
        log.info("SHA-256 verified: {}", actual);
    }

    String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    md.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not supported", e);
        }
    }

    /**
     * If a sing-box binary matching the current architecture is bundled inside
     * the classpath at {@code /native/darwin-{arch}/sing-box}, extracts it to
     * the cache directory, sets the executable bit, and returns the path.
     */
    private Optional<Path> extractBundledResource() throws IOException {
        String arch = detectArchOrNull();
        if (arch == null) {
            return Optional.empty();
        }
        String resource = corePlatform.bundledResourcePath(arch);
        try (InputStream in = SingBoxInstaller.class.getResourceAsStream(resource)) {
            if (in == null) {
                return Optional.empty();
            }
            Files.createDirectories(installDir);
            Path target = installDir.resolve(binaryName);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            makeExecutable(target);
            writeMarker(installDir.resolve(VERSION_MARKER_NAME), PINNED_VERSION);
            log.info("Extracted bundled sing-box ({}) from classpath to {}", arch, target);
            return Optional.of(target.toAbsolutePath());
        }
    }

    private String detectArchOrNull() {
        try {
            return detectArch();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    String detectArch() {
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            return "arm64";
        }
        if (osArch.contains("x86_64") || osArch.contains("amd64")) {
            return "amd64";
        }
        throw new IllegalStateException("Unsupported CPU architecture: " + osArch);
    }

    void downloadWithProgress(String url, Path target, DoubleConsumer progress)
            throws IOException, InterruptedException {
        downloadWithProgress(url, target, progress, 0);
    }

    /**
     * Downloads {@code url} to {@code target}, reporting progress and optionally
     * enforcing a maximum size.
     *
     * @param maxBytes abort the download once this many bytes have been
     *                 written; 0 disables the cap
     */
    void downloadWithProgress(String url, Path target, DoubleConsumer progress, long maxBytes)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();

        HttpResponse<InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        int status = response.statusCode();
        if (status != 200) {
            throw new IOException("Failed to download sing-box: HTTP " + status + " for " + url);
        }

        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);

        try (InputStream in = response.body();
                var out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[16 * 1024];
            long downloaded = 0;
            int read;
            AtomicBoolean reportedIndeterminate = new AtomicBoolean(false);
            while ((read = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Download cancelled");
                }
                out.write(buffer, 0, read);
                downloaded += read;
                if (maxBytes > 0 && downloaded > maxBytes) {
                    throw new IOException("Download exceeds the " + maxBytes
                            + "-byte size cap: " + url);
                }
                if (progress != null) {
                    if (contentLength > 0) {
                        progress.accept(Math.min(1.0, (double) downloaded / contentLength));
                    } else if (!reportedIndeterminate.getAndSet(true)) {
                        progress.accept(-1.0);
                    }
                }
            }
        }
    }

    /** Extracts the downloaded core archive (per-OS: tar.gz on macOS, zip on Windows). */
    void extractArchive(Path archive, Path destDir) throws IOException, InterruptedException {
        corePlatform.extract(archive, destDir);
    }

    Path findBinaryInDir(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> hasFileName(p, binaryName))
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                            "sing-box binary not found in extracted archive at " + dir));
        }
    }

    private static boolean hasFileName(Path path, String expected) {
        Path fileName = path.getFileName();
        return fileName != null && fileName.toString().equals(expected);
    }

    void makeExecutable(Path binary) throws IOException {
        File f = binary.toFile();
        if (!f.setExecutable(true, false)) {
            throw new IOException("Failed to set executable bit on " + binary);
        }
    }

    private void verifyBinary(Path binary) throws IOException {
        VersionOutput out = runVersionCommand(binary, VERSION_VERIFY_TIMEOUT_SECONDS);
        if (out == null) {
            throw new IOException("sing-box version check did not complete for " + binary);
        }
        if (out.exitCode() != 0) {
            throw new IOException("sing-box version check failed (exit " + out.exitCode()
                    + "): " + out.firstLine());
        }
        log.info("sing-box verification OK: {}", out.firstLine());
    }

    void deleteRecursive(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.debug("Failed to delete {}", p);
                        }
                    });
        } catch (IOException e) {
            log.debug("Failed to clean up {}", dir);
        }
    }

    public Path getInstallDir() {
        return installDir;
    }

    /**
     * The cached binary this app manages (download target of {@link #install}
     * and the classpath-extraction target). The path is stable across app
     * releases, so the sudoers rule and a live SingBoxEngine, both bound to
     * it, survive a pin bump.
     */
    public Path managedBinaryPath() {
        return installDir.resolve(binaryName);
    }

    /** Brew command the user can run to install sing-box manually. */
    public static String brewInstallCommand() {
        return "brew install sing-box";
    }
}
