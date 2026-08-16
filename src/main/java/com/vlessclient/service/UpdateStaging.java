package com.vlessclient.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vlessclient.platform.PendingUpdate;
import com.vlessclient.platform.PlatformPaths;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Stages updates under the per-user data directory. */
    public UpdateStaging() {
        this(PlatformPaths.current().dataDir().resolve(DIR_NAME));
    }

    UpdateStaging(Path dir) {
        this.dir = dir;
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
    public void stage(PendingUpdate update) throws IOException {
        Files.createDirectories(dir);
        ObjectNode marker = objectMapper.createObjectNode();
        marker.put("version", update.version());
        marker.put("installer", update.installer().toAbsolutePath().toString());
        marker.put("digest", update.digest());
        marker.put("attempts", 0);
        Files.writeString(dir.resolve(MARKER_NAME), objectMapper.writeValueAsString(marker));
        log.info("Staged update {} at {}", update.version(), update.installer());
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
        } catch (IOException e) {
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
        } catch (IOException | ClassCastException e) {
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
            Path installer = Path.of(root.path("installer").asText(""));
            if (!Files.isRegularFile(installer)) {
                log.warn("Staged installer {} is gone, clearing the marker", installer);
                clear();
                return Optional.empty();
            }
            return Optional.of(new PendingUpdate(
                    root.path("version").asText(""),
                    installer,
                    root.path("digest").asText("")));
        } catch (IOException | IllegalArgumentException e) {
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
        try (InputStream in = Files.newInputStream(file);
                DigestInputStream digesting = new DigestInputStream(in, digest)) {
            byte[] buffer = new byte[8192];
            while (digesting.read(buffer) != -1) {
                // reading is what updates the digest
            }
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }
}
