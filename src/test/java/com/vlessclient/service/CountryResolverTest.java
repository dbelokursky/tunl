package com.vlessclient.service;

import com.vlessclient.model.ServerConfig;
import com.vlessclient.testing.Await;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Country resolution as the server list uses it: cached, off the UI thread,
 * and silent when it cannot answer.
 *
 * <p>The database itself is stubbed. What matters here is the behaviour around
 * it — a missing database must not surface as an error, and a list cell being
 * re-rendered on every scroll tick must not re-run a DNS lookup.</p>
 */
class CountryResolverTest {

    @TempDir
    Path tempDir;

    /** Counts lookups so the cache can be observed rather than assumed. */
    private static class StubDatabase extends GeoIpDatabase {
        private final String answer;
        final AtomicInteger lookups = new AtomicInteger();

        StubDatabase(Path dir, String answer) {
            super(dir.resolve("absent.mmdb"), HttpClient.newHttpClient());
            this.answer = answer;
        }

        @Override
        public Optional<String> lookup(String address) {
            lookups.incrementAndGet();
            return Optional.ofNullable(answer);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    private static ServerConfig server(String address) {
        ServerConfig server = new ServerConfig();
        server.setAddress(address);
        return server;
    }

    @Test
    void resolvesOnceAndServesTheRestFromCache() throws Exception {
        StubDatabase database = new StubDatabase(tempDir, "NL");
        CountryResolver resolver = new CountryResolver(database);
        ServerConfig server = server("vpn.example.com");

        CountDownLatch first = new CountDownLatch(1);
        resolver.resolveAsync(server, code -> first.countDown());
        assertThat(first.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(resolver.countryOf(server)).contains("NL");

        // A cell re-rendered on scroll must not trigger another DNS lookup.
        for (int i = 0; i < 5; i++) {
            resolver.resolveAsync(server, code -> { });
        }
        assertThat(database.lookups.get()).isEqualTo(1);
    }

    @Test
    void anUnknownAddressIsRememberedAsUnknownAndNotRetried() throws Exception {
        StubDatabase database = new StubDatabase(tempDir, null);
        CountryResolver resolver = new CountryResolver(database);
        ServerConfig server = server("10.0.0.1");

        CountDownLatch done = new CountDownLatch(1);
        resolver.resolveAsync(server, code -> done.countDown());
        // The callback must NOT fire for an unknown country, so wait for the
        // lookup to land instead of the callback.
        Await.until("the lookup to land", () -> database.lookups.get() > 0,
                Duration.ofSeconds(5));

        assertThat(database.lookups.get()).isEqualTo(1);
        assertThat(done.getCount())
                .as("no flag should be offered for an unknown country")
                .isEqualTo(1);
        assertThat(resolver.countryOf(server)).isEmpty();

        resolver.resolveAsync(server, code -> { });
        // A sleep on purpose: a second lookup would run on a virtual thread
        // the resolver never exposes, and an unknown country calls nothing
        // back — there is no event to wait for, only the absence of one.
        Thread.sleep(100);
        assertThat(database.lookups.get())
                .as("a negative answer is cached too")
                .isEqualTo(1);
    }

    @Test
    void serversWithoutAnAddressAreIgnoredRatherThanFailing() {
        StubDatabase database = new StubDatabase(tempDir, "DE");
        CountryResolver resolver = new CountryResolver(database);

        resolver.resolveAsync(null, code -> { });
        resolver.resolveAsync(server(null), code -> { });
        resolver.resolveAsync(server("  "), code -> { });

        assertThat(database.lookups.get()).isZero();
        assertThat(resolver.countryOf(null)).isEmpty();
        assertThat(resolver.countryOf(server(null))).isEmpty();
    }

    /**
     * The whole feature is decoration: with no database downloaded the list
     * must render exactly as before, not error out.
     */
    @Test
    void aMissingDatabaseDegradesToNoCountry() {
        GeoIpDatabase missing =
                new GeoIpDatabase(tempDir.resolve("nothing.mmdb"), HttpClient.newHttpClient());
        CountryResolver resolver = new CountryResolver(missing);

        assertThat(missing.isAvailable()).isFalse();
        assertThat(missing.lookup("8.8.8.8")).isEmpty();
        assertThat(resolver.countryOf(server("8.8.8.8"))).isEmpty();
    }
}
