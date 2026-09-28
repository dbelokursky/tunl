package com.vlessclient.service;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.function.Supplier;

/**
 * An HTTP client built on first use, for a service that downloads something
 * once in a long while or never.
 *
 * <p>A client is not free to hold: building the first one sets up the JDK's
 * TLS context, and every client keeps a selector thread and its buffers until
 * it is shut down. The core installer and the country database had one each
 * from startup, for the whole run, while the bundled core and an existing
 * database meant they almost never sent a request.</p>
 */
final class LazyHttpClient {

    private final Supplier<HttpClient> factory;
    private HttpClient client;
    private boolean shutDown;

    private LazyHttpClient(Supplier<HttpClient> factory, HttpClient client) {
        this.factory = factory;
        this.client = client;
    }

    /**
     * A client that {@code factory} builds the first time it is asked for.
     *
     * @param factory builds the client
     * @return the lazy client
     */
    static LazyHttpClient of(Supplier<HttpClient> factory) {
        return new LazyHttpClient(factory, null);
    }

    /**
     * A client that already exists, a test's usually.
     *
     * @param client the client, or null for none
     * @return the holder
     */
    static LazyHttpClient ofBuilt(HttpClient client) {
        return new LazyHttpClient(() -> client, client);
    }

    /**
     * The client, built now if this is the first request for it.
     *
     * @return the client
     * @throws IOException after {@link #shutdownNow()}, as a request on a
     *         client that was shut down fails
     */
    synchronized HttpClient get() throws IOException {
        if (shutDown) {
            throw new IOException("HTTP client already shut down");
        }
        if (client == null) {
            client = factory.get();
        }
        return client;
    }

    /** Shuts the client down if it was ever built; later requests for it fail. */
    synchronized void shutdownNow() {
        shutDown = true;
        if (client != null) {
            client.shutdownNow();
        }
    }

    /** Whether the client has been built. Test seam. */
    synchronized boolean isBuilt() {
        return client != null;
    }
}
