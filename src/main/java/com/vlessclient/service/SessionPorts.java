package com.vlessclient.service;

import com.vlessclient.platform.SecureFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.OptionalInt;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The HTTP port a run pointed the system's proxy at, kept for the next start.
 *
 * <p>In system-proxy mode the core points the system at its HTTP port. A run
 * that dies without restoring it (a crash, a kill, a power cut) leaves the
 * system behind a dead proxy, and the next start clears a proxy that points
 * at the recorded port. The record is forgotten when the core is stopped,
 * which restores the proxy, so a start with nothing recorded has nothing to
 * clear. It used to look every time: on a Mac that is a networksetup process
 * per network service and proxy type, a second or more before the window
 * appeared.</p>
 */
public final class SessionPorts {

    private static final Logger log = LoggerFactory.getLogger(SessionPorts.class);

    /** The file in the data directory. */
    static final String FILE_NAME = "session-http-port";

    /**
     * Written by the first start of a build that records every session. A run
     * of an older build recorded its port only when it had moved, so a start
     * without this file cannot tell a clean exit from a dead one.
     */
    static final String RECORDS_EVERY_SESSION = "session-records";

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
     * The ports whose system proxy this start should clear: the one the last
     * run recorded, and on the first start of this build in system-proxy mode
     * the chosen port as well, which is all an older build's dead run can
     * have left without a record.
     *
     * @param dataDir         the app's data directory
     * @param systemProxyMode whether the app is in system-proxy mode
     * @param chosenHttpPort  the HTTP port the user chose
     * @return the ports, empty when the last run stopped its core
     */
    public static Set<Integer> stalePorts(Path dataDir, boolean systemProxyMode,
                                          int chosenHttpPort) {
        Set<Integer> ports = new LinkedHashSet<>();
        recorded(dataDir).ifPresent(ports::add);
        Path marker = dataDir.resolve(RECORDS_EVERY_SESSION);
        if (!Files.exists(marker)) {
            if (systemProxyMode) {
                ports.add(chosenHttpPort);
            }
            try {
                SecureFiles.writePrivately(marker, new byte[0]);
            } catch (IOException e) {
                log.warn("Could not mark the session records as complete: {}", e.getMessage());
            }
        }
        return ports;
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
