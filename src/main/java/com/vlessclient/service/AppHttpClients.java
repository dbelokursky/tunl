package com.vlessclient.service;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.TunnelHealth;
import java.net.http.HttpClient;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Builds the {@link HttpClient}s the application uses to reach the internet,
 * so that all of them follow the tunnel instead of going around it.
 *
 * <p>Every client built here carries the same {@link TunnelProxySelector},
 * which decides per request whether to go through sing-box's local HTTP
 * inbound. That decision cannot be made at construction time: these clients
 * are built while {@code ServiceLocator} assembles the service graph, long
 * before there is an engine, a port or a health verdict, and they outlive
 * every connect and disconnect afterwards.</p>
 *
 * <p>Which clients belong here: the ones that talk to the internet
 * (subscriptions, the updater, the country database, the core installer and
 * the core release check). The ones that talk to {@code 127.0.0.1} — the
 * traffic monitor, the delay probe — do not, and the selector would refuse to
 * proxy them anyway.</p>
 *
 * <p>Until {@link #routeThroughTunnel} is called the clients behave exactly as
 * {@code HttpClient.newBuilder()} did: the selector falls through to the JVM
 * default for every request. That is what unit tests see, and it is why the
 * UI tests' no-network guard still observes each request.</p>
 */
public final class AppHttpClients {

    /** Where the tunnel's local port comes from; empty means "do not proxy". */
    private static final AtomicReference<Supplier<OptionalInt>> PORT =
            new AtomicReference<>(OptionalInt::empty);

    /**
     * One selector for the whole process. Stable across binds, so a client
     * built before {@link #routeThroughTunnel} still picks the tunnel up.
     */
    private static final TunnelProxySelector SELECTOR =
            new TunnelProxySelector(() -> PORT.get().get());

    /**
     * Whether the core is up but the reachability checks have declared the
     * tunnel broken — the one state in which the selector sends the app's own
     * requests direct while the user believes they are tunneled.
     */
    private static final AtomicReference<BooleanSupplier> BROKEN_WHILE_CONNECTED =
            new AtomicReference<>(() -> false);

    private AppHttpClients() {
    }

    /**
     * A builder whose client routes through the tunnel while one is up.
     *
     * @return a fresh builder; callers add their own timeouts and redirect
     *         policy as before
     */
    public static HttpClient.Builder newBuilder() {
        return HttpClient.newBuilder().proxy(SELECTOR);
    }

    /**
     * Points the clients at the tunnel.
     *
     * @param port supplies the local HTTP inbound port while requests should
     *             use the tunnel, and empty otherwise; consulted per request
     */
    public static void routeThroughTunnel(Supplier<OptionalInt> port) {
        PORT.set(port == null ? OptionalInt::empty : port);
    }

    /**
     * Points the clients at whatever tunnel the application currently has.
     *
     * <p>The policy of when a tunnel may carry these requests lives with the
     * selector rather than with the caller, so the answer cannot drift between
     * here and {@link TunnelProxySelector}. Everything is read per request: an
     * engine registered after the binary finishes downloading, a port changed
     * in Settings, and every connect and disconnect are all picked up without
     * rebuilding a client.</p>
     *
     * @param engine   the current engine, or null before one exists
     * @param settings the current settings, for the local HTTP inbound port
     * @param health   the reachability verdict for the running tunnel
     */
    public static void followTunnel(Supplier<SingBoxEngine> engine,
                                    Supplier<AppSettings> settings,
                                    TunnelHealthState health) {
        routeThroughTunnel(() -> {
            SingBoxEngine current = engine.get();
            if (current == null || !TunnelProxySelector.carriesTraffic(
                    current.connectionStateProperty().get(), health.get())) {
                return OptionalInt.empty();
            }
            return OptionalInt.of(settings.get().getHttpPort());
        });
        BROKEN_WHILE_CONNECTED.set(() -> {
            SingBoxEngine current = engine.get();
            return current != null
                    && current.connectionStateProperty().get() == ConnectionState.CONNECTED
                    && health.get() == TunnelHealth.BROKEN;
        });
    }

    /**
     * Sends every subsequent request straight out again, as if no tunnel
     * existed. Called on shutdown so a service graph rebuilt in the same JVM
     * (the UI test suite does this repeatedly) never inherits the previous
     * graph's engine.
     */
    public static void routeDirect() {
        PORT.set(OptionalInt::empty);
        BROKEN_WHILE_CONNECTED.set(() -> false);
    }

    /**
     * True while the core is connected but the tunnel has been declared
     * broken. Requests made now bypass the tunnel; a caller carrying a
     * credential in its URL (a subscription fetch) should decline to send
     * rather than expose it, and the user's real address, directly.
     *
     * @return whether the tunnel is being bypassed while it looks connected
     */
    public static boolean isTunnelBrokenWhileConnected() {
        return BROKEN_WHILE_CONNECTED.get().getAsBoolean();
    }

    /** Test seam: replaces the probe behind {@link #isTunnelBrokenWhileConnected()}. */
    static void setTunnelBrokenProbe(BooleanSupplier probe) {
        BROKEN_WHILE_CONNECTED.set(probe == null ? () -> false : probe);
    }

    /** The selector every client built here shares. Package-private for tests. */
    static TunnelProxySelector selector() {
        return SELECTOR;
    }
}
