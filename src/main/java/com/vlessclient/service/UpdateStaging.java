package com.vlessclient.service;

import com.vlessclient.platform.PendingUpdate;
import com.vlessclient.platform.PlatformPaths;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.BiPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Where a downloaded update waits between the run that fetched it and the run
 * that installs it.
 *
 * <p>Deliberately not the Downloads folder. An installer that is going to be
 * launched without the user picking it needs to live somewhere the app owns
 * and can reason about: a marker file naming the expected version and digest,
 * next to the file it describes, is what makes "is there an update ready?"
 * answerable at startup without trusting a filename.</p>
 */
public final class UpdateStaging {

    private static final Logger log = LoggerFactory.getLogger(UpdateStaging.class);

    private static final String DIR_NAME = "updates";
    private static final String MARKER_NAME = "pending.json";

    private final Path dir;
    private final BiPredicate<String, String> signatureCheck;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    /** Stages updates under the per-user data directory. */
    public UpdateStaging() {
        this(PlatformPaths.current().dataDir().resolve(DIR_NAME));
    }

    UpdateStaging(Path dir) {
        this(dir, ReleaseSignature::verifyDigest);
    }

    /**
     * Test seam: how a staged digest's signature is checked. Tests cannot
     * produce a real one — the private key is the release maintainer's — so
     * they inject the verdict. The case that matters most needs no key and
     * uses the real check: a forged signature must be rejected.
     */
    UpdateStaging(Path dir, BiPredicate<String, String> signatureCheck) {
        this.dir = dir;
        this.signatureCheck = signatureCheck;
    }

    /**
     * Returns the staging directory, creating it if needed.
     *
     * @return the directory downloads should land in
     * @throws IOException if the directory cannot be created
     */
    public Path dir() throws IOException {
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * Records a verified installer as the update to install at the next start.
     *
     * @param update the downloaded and verified update
     * @throws IOException if the marker cannot be written
     */
    public void stage(PendingUpdate update, String signatureBase64) throws IOException {
        Files.createDirectories(dir);
        ObjectNode marker = objectMapper.createObjectNode();
        marker.put("version", update.version());
        marker.put("installer", update.installer().toAbsolutePath().toString());
        marker.put("digest", update.digest());
        // The release signature over that digest. Without it the marker
        // certifies itself: whoever can rewrite the installer can rewrite the
        // digest beside it, and the check before execution passes. The
        // signature cannot be produced without the release private key.
        marker.put("signature", signatureBase64 == null ? "" : signatureBase64);
        marker.put("attempts", 0);
        marker.put("stagedAt", System.currentTimeMillis());
        Files.writeString(dir.resolve(MARKER_NAME), objectMapper.writeValueAsString(marker));
        log.info("Staged update {} at {}", update.version(), update.installer());
    }

    /**
     * When the staged update was recorded, as epoch millis.
     *
     * <p>0 means the question cannot be answered — no marker, or one written
     * before this field existed. Callers must read that as "not stale" rather
     * than "stale forever ago": the alternative would throw away a perfectly
     * good installer the first time a build that knows about this field meets
     * a marker from one that did not.</p>
     *
     * @return the timestamp, or 0 when unknown
     */
    public long stagedAt() {
        Path marker = dir.resolve(MARKER_NAME);
        if (!Files.isRegularFile(marker)) {
            return 0;
        }
        try {
            return objectMapper.readTree(Files.readString(marker)).path("stagedAt").asLong(0);
        } catch (IOException | JacksonException e) {
            return 0;
        }
    }

    /**
     * How many times applying the staged update has been attempted.
     *
     * <p>The count exists to break a restart loop. Handing off to an applier
     * ends with this process exiting and the new build starting — if the swap
     * silently failed, that new build is the old one, and it finds the same
     * marker waiting. Without a bound, the app would relaunch itself forever.</p>
     *
     * @return the number of recorded attempts, 0 when unknown
     */
    public int attempts() {
        Path marker = dir.resolve(MARKER_NAME);
        if (!Files.isRegularFile(marker)) {
            return 0;
        }
        try {
            return objectMapper.readTree(Files.readString(marker)).path("attempts").asInt(0);
        } catch (IOException | JacksonException e) {
            return 0;
        }
    }

    /**
     * Records that applying the staged update is about to be attempted. Must
     * be persisted before the handoff, not after: after, there is no "after".
     */
    public void recordAttempt() {
        Path marker = dir.resolve(MARKER_NAME);
        try {
            ObjectNode root = (ObjectNode) objectMapper.readTree(Files.readString(marker));
            root.put("attempts", root.path("attempts").asInt(0) + 1);
            Files.writeString(marker, objectMapper.writeValueAsString(root));
        } catch (IOException | JacksonException | ClassCastException e) {
            log.warn("Failed to record an apply attempt: {}", e.getMessage());
        }
    }

    /**
     * Returns the staged update, if there is one whose installer is still on
     * disk. A marker pointing at a file that has since been deleted is treated
     * as no update at all, and cleared.
     *
     * @return the staged update, or empty when nothing is waiting
     */
    public Optional<PendingUpdate> pending() {
        Path marker = dir.resolve(MARKER_NAME);
        if (!Files.isRegularFile(marker)) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(marker));
            Path installer = Path.of(root.path("installer").asString(""));
            // The marker names the file this app will execute, so the name has
            // to stay inside the directory this app owns. Without this a
            // rewritten marker can point anywhere on the machine.
            if (!installer.toAbsolutePath().normalize()
                    .startsWith(dir.toAbsolutePath().normalize())) {
                log.error("Staged installer {} is outside {}, refusing it", installer, dir);
                clear();
                return Optional.empty();
            }
            if (!Files.isRegularFile(installer)) {
                log.warn("Staged installer {} is gone, clearing the marker", installer);
                clear();
                return Optional.empty();
            }
            return Optional.of(new PendingUpdate(
                    root.path("version").asString(""),
                    installer,
                    root.path("digest").asString("")));
        } catch (IOException | JacksonException | IllegalArgumentException e) {
            log.warn("Unreadable update marker, clearing it: {}", e.getMessage());
            clear();
            return Optional.empty();
        }
    }

    /**
     * Re-checks the staged installer against the digest recorded with it.
     *
     * <p>The bytes were already verified when they were downloaded. They are
     * verified again here because time has passed since: the file has been
     * sitting in a user-writable directory across at least one restart, and
     * the next thing that happens to it is being executed.</p>
     *
     * @param update the staged update
     * @return true when the file still hashes to the recorded digest
     */
    public boolean verify(PendingUpdate update) {
        // First: is this digest one the release publisher actually signed? The
        // hash comparison below only proves the file matches the marker, and
        // the marker is as writable as the installer next to it.
        if (ReleaseSignature.enforced()
                && !signatureCheck.test(update.digest(), stagedSignature())) {
            log.error("Staged update {} has no valid release signature for its "
                    + "digest; refusing to run it", update.version());
            return false;
        }
        try {
            String actual = sha256(update.installer());
            if (actual.equalsIgnoreCase(update.digest())) {
                return true;
            }
            log.error("Staged installer {} no longer matches its digest "
                    + "(expected {}, got {})", update.installer(), update.digest(), actual);
            return false;
        } catch (IOException | NoSuchAlgorithmException e) {
            log.error("Cannot verify staged installer: {}", e.getMessage());
            return false;
        }
    }

    /** The release signature recorded beside the staged digest, or "". */
    private String stagedSignature() {
        Path marker = dir.resolve(MARKER_NAME);
        if (!Files.isRegularFile(marker)) {
            return "";
        }
        try {
            return objectMapper.readTree(Files.readString(marker))
                    .path("signature").asString("");
        } catch (IOException | JacksonException e) {
            return "";
        }
    }

    /**
     * Removes everything in the staging directory: the installer, the marker
     * and the relay script and log an applier may have left behind. Bounded to
     * files directly inside the directory — nothing here ever creates
     * subdirectories, so a recursive delete would only widen the blast radius.
     */
    public void clear() {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry)) {
                    Files.deleteIfExists(entry);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to clear the staging directory: {}", e.getMessage());
        }
    }

    /**
     * Returns the {@code sha256:<hex>} digest of a file.
     *
     * @param file the file to hash
     * @return the digest, prefixed the way the Releases API publishes it
     * @throws IOException              if the file cannot be read
     * @throws NoSuchAlgorithmException if SHA-256 is unavailable
     */
    static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }
}
