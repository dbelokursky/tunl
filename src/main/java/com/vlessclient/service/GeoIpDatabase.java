package com.vlessclient.service;

import com.maxmind.db.Reader;
import com.vlessclient.platform.PlatformPaths;
import com.vlessclient.platform.SecureFiles;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local IP-to-country lookup, backed by a DB-IP Lite database in MMDB format.
 *
 * <p><b>Why local.</b> The obvious way to label a server with a country is to
 * ask an online geo-IP API — and it is the one thing a privacy tool must not
 * do: it would tell a third party which VPN servers the user has configured.
 * The lookup therefore happens entirely on the user's machine, and nothing
 * about their server list ever leaves it.</p>
 *
 * <p><b>Why downloaded, not bundled.</b> The installer already ships a ~50 MB
 * sing-box; adding a country database that goes stale within a month would
 * make it bigger <em>and</em> less accurate over time. It is fetched once on
 * demand into the app data directory instead, reusing the same pattern as the
 * core download.</p>
 *
 * <p><b>Failure is not an error.</b> Every method degrades quietly: no
 * database, no network, an unreadable file — the caller gets an empty result
 * and the UI simply shows no flag. A country badge is decoration; it must
 * never be able to keep someone from connecting.</p>
 *
 * <p>Data licence: DB-IP Lite, CC BY 4.0 — attribution is carried in the
 * About screen.</p>
 */
public class GeoIpDatabase {

    private static final Logger log = LoggerFactory.getLogger(GeoIpDatabase.class);

    /**
     * DB-IP publishes one file per month and keeps recent ones around, so the
     * current month is tried first and older ones serve as fallbacks — near a
     * month boundary the new file may not be published yet.
     */
    private static final String URL_TEMPLATE =
            "https://download.db-ip.com/free/dbip-country-lite-%s.mmdb.gz";
    private static final int MONTHS_TO_TRY = 3;

    private static final String DB_FILE = "dbip-country-lite.mmdb";
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final Path databasePath;
    private final HttpClient httpClient;
    private final String urlTemplate;

    /** Opened lazily and kept: the reader memory-maps the file. */
    private volatile Reader reader;
    private volatile boolean unavailable;

    /** Uses the app data directory and a redirect-following HTTP client. */
    public GeoIpDatabase() {
        this(PlatformPaths.current().dataDir().resolve("geoip").resolve(DB_FILE),
                AppHttpClients.newBuilder()
                        .connectTimeout(TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build());
    }

    /** Test seam: explicit database location and HTTP client. */
    GeoIpDatabase(Path databasePath, HttpClient httpClient) {
        this(databasePath, httpClient, URL_TEMPLATE);
    }

    /** Test seam: also where to download from (a {@code %s} takes the month). */
    GeoIpDatabase(Path databasePath, HttpClient httpClient, String urlTemplate) {
        this.databasePath = databasePath;
        this.httpClient = httpClient;
        this.urlTemplate = urlTemplate;
    }

    /** Whether a database file is present locally. */
    public boolean isAvailable() {
        return Files.isRegularFile(databasePath);
    }

    /**
     * Looks up the ISO 3166-1 alpha-2 country code for an address.
     *
     * @param address a literal IP or a hostname; hostnames are resolved
     *                locally first
     * @return the upper-case country code, or empty when unknown — including
     *         when no database has been downloaded
     */
    public Optional<String> lookup(String address) {
        if (address == null || address.isBlank() || !openReader()) {
            return Optional.empty();
        }
        try {
            // Resolves a hostname through the OS resolver. That is a DNS query
            // the app would make on connect anyway, so it reveals nothing new.
            InetAddress ip = InetAddress.getByName(address.trim());
            // Decoded as a plain Map: the 3.x reader maps records onto types
            // carrying @MaxMindDbConstructor and cannot target JsonNode, which
            // was a 2.x affordance.
            Map<?, ?> record = reader.get(ip, Map.class);
            return countryCode(record);
        } catch (IOException | RuntimeException e) {
            log.debug("Country lookup failed for {}: {}", address, e.toString());
            return Optional.empty();
        }
    }

    /** Digs the ISO code out of the decoded record, tolerating gaps. */
    private static Optional<String> countryCode(Map<?, ?> record) {
        if (record == null || !(record.get("country") instanceof Map<?, ?> country)) {
            return Optional.empty();
        }
        Object code = country.get("iso_code");
        if (!(code instanceof String iso) || iso.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(iso.toUpperCase(Locale.ROOT));
    }

    /**
     * Downloads the database if it is not present yet.
     *
     * @return true if a database is available afterwards
     */
    public boolean ensureDownloaded() {
        if (isAvailable()) {
            return true;
        }
        LocalDate month = LocalDate.now();
        for (int attempt = 0; attempt < MONTHS_TO_TRY; attempt++) {
            String stamp = month.minusMonths(attempt)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM"));
            if (download(String.format(urlTemplate, stamp))) {
                return true;
            }
        }
        log.info("No geo-IP database available; server country badges stay hidden");
        return false;
    }

    private boolean download(String url) {
        Path staging = databasePath.resolveSibling(DB_FILE + ".part");
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                log.debug("Geo-IP database not at {} (HTTP {})", url, response.statusCode());
                return false;
            }

            SecureFiles.createPrivateDir(SecureFiles.parentDirectory(databasePath));
            // The size cap is enforced while decompressing. Checking the byte
            // count after the copy let a gzip bomb fill the disk first: the
            // check only ran once the whole stream had landed.
            try (InputStream in = new BoundedInputStream(
                    new GZIPInputStream(response.body()), MAX_BYTES)) {
                long written = Files.copy(in, staging, StandardCopyOption.REPLACE_EXISTING);
                if (written <= 0) {
                    throw new IOException("empty download");
                }
            }
            // Only becomes the real database once it parses: a truncated
            // file would fail on every lookup afterwards.
            try (Reader probe = new Reader(staging.toFile())) {
                log.info("Downloaded geo-IP database ({})",
                        probe.getMetadata().databaseType());
            }
            Files.move(staging, databasePath, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException e) {
            log.debug("Geo-IP download from {} failed: {}", url, e.toString());
            discard(staging);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            discard(staging);
            return false;
        }
    }

    /** Removes a staging file a failed download left behind. */
    private static void discard(Path staging) {
        try {
            Files.deleteIfExists(staging);
        } catch (IOException e) {
            log.debug("Could not remove {}: {}", staging, e.getMessage());
        }
    }

    /** Fails the read once more than {@code limit} bytes have gone through. */
    private static final class BoundedInputStream extends FilterInputStream {

        private final long limit;
        private long count;

        BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                account(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) {
                account(n);
            }
            return n;
        }

        private void account(long n) throws IOException {
            count += n;
            if (count > limit) {
                throw new IOException("decompressed size exceeds " + limit + " bytes");
            }
        }
    }

    private boolean openReader() {
        if (reader != null) {
            return true;
        }
        if (unavailable || !isAvailable()) {
            return false;
        }
        synchronized (this) {
            if (reader != null) {
                return true;
            }
            try {
                reader = new Reader(databasePath.toFile());
                return true;
            } catch (IOException | RuntimeException e) {
                // Corrupt file: stop retrying on every lookup.
                log.warn("Could not open the geo-IP database: {}", e.toString());
                unavailable = true;
                return false;
            }
        }
    }

    /** Closes the reader; safe to call more than once. */
    public void shutdown() {
        Reader current = reader;
        reader = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException e) {
                log.debug("Error closing the geo-IP database", e);
            }
        }
    }
}
