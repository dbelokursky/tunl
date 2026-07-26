package com.vlessclient.service;

import com.vlessclient.model.ServerConfig;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a server to its country, cached, off the UI thread.
 *
 * <p>Sits between {@link GeoIpDatabase} and the views for two reasons. A list
 * cell is re-rendered on every scroll tick, and a hostname lookup can block on
 * DNS — doing that inline would stutter the list. And the answer for a given
 * address does not change while the app runs, so it is worth remembering.</p>
 *
 * <p>Resolution is asynchronous by design: {@link #countryOf} answers
 * immediately from cache or returns empty, and the caller is notified later if
 * a lookup was needed. A cell that never gets an answer simply shows no
 * flag.</p>
 */
public class CountryResolver {

    private static final Logger log = LoggerFactory.getLogger(CountryResolver.class);

    /** Marks an address we looked up and found nothing for, so we stop retrying. */
    private static final String UNKNOWN = "";

    private final GeoIpDatabase database;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public CountryResolver(GeoIpDatabase database) {
        this.database = database;
    }

    /**
     * The cached country for a server, if it is already known.
     *
     * @return ISO 3166-1 alpha-2 code, or empty when unknown or not yet resolved
     */
    public Optional<String> countryOf(ServerConfig server) {
        if (server == null || server.getAddress() == null) {
            return Optional.empty();
        }
        String cached = cache.get(server.getAddress());
        return cached == null || cached.equals(UNKNOWN)
                ? Optional.empty()
                : Optional.of(cached);
    }

    /**
     * Resolves in the background and hands the result to {@code onResolved},
     * which runs on a background thread — callers touching the UI must marshal
     * it themselves.
     *
     * <p>A cached answer is delivered synchronously so an already-known flag
     * appears without a frame of delay.</p>
     */
    public void resolveAsync(ServerConfig server, java.util.function.Consumer<String> onResolved) {
        if (server == null || server.getAddress() == null || server.getAddress().isBlank()) {
            return;
        }
        String address = server.getAddress();
        String cached = cache.get(address);
        if (cached != null) {
            if (!cached.equals(UNKNOWN)) {
                onResolved.accept(cached);
            }
            return;
        }
        Thread.startVirtualThread(() -> {
            String code = database.lookup(address).orElse(UNKNOWN);
            cache.put(address, code);
            if (!code.equals(UNKNOWN)) {
                onResolved.accept(code);
            }
        });
    }

    /**
     * Downloads the database in the background if it is missing, so the first
     * launch after this feature ships fills in flags without blocking startup.
     */
    public void warmUp() {
        if (database.isAvailable()) {
            return;
        }
        Thread.startVirtualThread(() -> {
            if (database.ensureDownloaded()) {
                log.info("Geo-IP database ready; server countries will resolve");
            }
        });
    }
}
