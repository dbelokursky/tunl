package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The download path against a loopback stand-in for db-ip.com. A country
 * badge is decoration, so every failure must leave the app exactly as it
 * was: no database, no staging file, no disk filled by a hostile archive.
 */
class GeoIpDatabaseDownloadTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Serves {@code body} to the first request and 404 to every later one. */
    private String serveOnce(int status, byte[] body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            boolean first = requests.incrementAndGet() == 1;
            byte[] bytes = first ? body : new byte[0];
            int code = first ? status : 404;
            exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort()
                + "/dbip-country-lite-%s.mmdb.gz";
    }

    private static byte[] gzipOf(byte[] plain) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(plain);
        }
        return out.toByteArray();
    }

    private static byte[] gzipOfZeros(long size) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            byte[] chunk = new byte[1 << 20];
            long left = size;
            while (left > 0) {
                int n = (int) Math.min(chunk.length, left);
                gzip.write(chunk, 0, n);
                left -= n;
            }
        }
        return out.toByteArray();
    }

    private GeoIpDatabase database(String template) {
        return new GeoIpDatabase(dir.resolve("geoip").resolve("dbip-country-lite.mmdb"),
                HttpClient.newHttpClient(), template);
    }

    private List<String> leftovers() throws IOException {
        Path geo = dir.resolve("geoip");
        if (!Files.isDirectory(geo)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(geo)) {
            return files.map(path -> path.getFileName().toString()).toList();
        }
    }

    @Test
    void aDecompressionBombIsAbandonedBeforeItFillsTheDisk() throws IOException {
        // 65 MiB of zeros gzip to about 65 KB. The cap has to bite while
        // decompressing; a check after the copy ran once the disk was full.
        byte[] bomb = gzipOfZeros(65L * 1024 * 1024);
        assertThat(bomb.length).isLessThan(1024 * 1024);

        GeoIpDatabase database = database(serveOnce(200, bomb));

        assertThat(database.ensureDownloaded()).isFalse();
        assertThat(database.isAvailable()).isFalse();
        assertThat(leftovers()).as("no staging file left behind").isEmpty();
    }

    @Test
    void aBodyThatIsNotADatabaseIsNotPromotedAndLeavesNothingBehind() throws IOException {
        GeoIpDatabase database = database(serveOnce(200,
                gzipOf("not a database".getBytes(StandardCharsets.UTF_8))));

        assertThat(database.ensureDownloaded()).isFalse();
        assertThat(database.isAvailable()).isFalse();
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void anAbsentFileIsTriedForEarlierMonthsThenGivenUp() throws IOException {
        GeoIpDatabase database = database(serveOnce(404, new byte[0]));

        assertThat(database.ensureDownloaded()).isFalse();
        assertThat(database.isAvailable()).isFalse();
        assertThat(requests.get()).as("the current month and two before it").isEqualTo(3);
    }
}
