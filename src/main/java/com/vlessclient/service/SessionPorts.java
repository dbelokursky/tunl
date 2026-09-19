package com.vlessclient.service;

import com.vlessclient.platform.SecureFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The HTTP port a run listened on when it was not the chosen one, kept for
 * the next start.
 *
 * <p>In system-proxy mode the core points the system at its HTTP port. A run
 * that dies without restoring it (a crash, a kill, a power cut) leaves the
 * system behind a dead proxy, and the next start clears a proxy that points
 * at the chosen port. A run that had moved to another port, because another
 * program held the chosen one, left a proxy the next start did not
 * recognise.</p>
 */
public final class SessionPorts {

    private static final Logger log = LoggerFactory.getLogger(SessionPorts.class);

    /** The file in the data directory. */
    static final String FILE_NAME = "session-http-port";

    private SessionPorts() {
    }

    /**
     * Records the HTTP port of the run now starting.
     *
     * @param dataDir the app's data directory
     * @param port    the port the run listens on
     */
    public static void record(Path dataDir, int port) {
        try {
            SecureFiles.writePrivately(dataDir.resolve(FILE_NAME),
                    String.valueOf(port).getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            log.warn("Could not record this run's HTTP port: {}", e.getMessage());
        }
    }

    /**
     * Forgets the recorded port: the run ended the way the core restores the
     * system's proxy itself, or listened on the chosen port.
     *
     * @param dataDir the app's data directory
     */
    public static void forget(Path dataDir) {
        try {
            Files.deleteIfExists(dataDir.resolve(FILE_NAME));
        } catch (IOException e) {
            log.warn("Could not forget the recorded HTTP port: {}", e.getMessage());
        }
    }

    /**
     * The port an earlier run recorded, if it did.
     *
     * @param dataDir the app's data directory
     * @return the port, or empty when none was recorded or the record is unreadable
     */
    public static OptionalInt recorded(Path dataDir) {
        Path file = dataDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return OptionalInt.empty();
        }
        try {
            int port = Integer.parseInt(Files.readString(file, StandardCharsets.US_ASCII).strip());
            return port >= 1 && port <= 65535 ? OptionalInt.of(port) : OptionalInt.empty();
        } catch (IOException | NumberFormatException e) {
            log.warn("Ignoring an unreadable record of a run's HTTP port: {}", e.getMessage());
            return OptionalInt.empty();
        }
    }
}
