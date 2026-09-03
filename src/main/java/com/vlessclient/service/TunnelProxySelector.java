package com.vlessclient.service;

import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.TunnelHealth;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends the application's own HTTP requests through the tunnel while the
 * tunnel is up.
 *
 * <p>Subscription refreshes, the update check and download, the country
 * database and the sing-box release check all go through
 * {@link java.net.http.HttpClient}. Such a client asks
 * {@link ProxySelector#getDefault()} on every request, and the stock
 * implementation answers DIRECT for everything unless the JVM was started
 * with {@code -Djava.net.useSystemProxies=true}, which nothing here does. So in
 * SYSTEM_PROXY mode every one of those requests left the machine past the
 * tunnel the user had just connected through. On the networks this client
 * exists for, where GitHub or the subscription host is what is blocked, that
 * made "connected" and "the update check can succeed" two different moments.
 * TUN mode captured the JVM's traffic like everything else, which is why the
 * gap was invisible there.</p>
 *
 * <p>The decision is made per request, not per client: a client built at
 * startup keeps asking, so it follows every connect and disconnect after it.
 * While the core is {@link ConnectionState#CONNECTED} and the reachability
 * checks have not declared the tunnel {@link TunnelHealth#BROKEN}, public
 * hosts are reached through sing-box's local HTTP inbound; loopback targets
 * (clash_api, the MCP listener, tests) are never proxied; and every other
 * case is answered by the fallback selector, the JVM default, exactly as
 * before. The fallback is consulted first even when the tunnel wins, so the
 * test suite's no-network guard still sees each request.</p>
 */
public class TunnelProxySelector extends ProxySelector {

    private static final Logger log = LoggerFactory.getLogger(TunnelProxySelector.class);
    private static final List<Proxy> DIRECT = List.of(Proxy.NO_PROXY);

    private final Supplier<OptionalInt> tunnelPort;
    private final Supplier<ProxySelector> fallback;

    /**
     * Creates a selector that consults the JVM default when the tunnel is not
     * carrying traffic.
     *
     * @param tunnelPort the local HTTP inbound port to route through, or empty
     *                   whenever requests should not use the tunnel; asked on
     *                   every request
     */
    public TunnelProxySelector(Supplier<OptionalInt> tunnelPort) {
        this(tunnelPort, ProxySelector::getDefault);
    }

    /** Test seam: an explicit fallback instead of the process-wide default. */
    TunnelProxySelector(Supplier<OptionalInt> tunnelPort, Supplier<ProxySelector> fallback) {
        this.tunnelPort = tunnelPort;
        this.fallback = fallback;
    }

    /**
     * Whether requests should go through the tunnel in this state.
     *
     * <p>{@code CONNECTED} alone is not enough: the core can be up while the
     * server refuses everything, and a request sent into that tunnel fails
     * where a direct one might not. {@link TunnelHealth#BROKEN} is the one
     * verdict that says so; {@code CHECKING}, {@code DEGRADED} and
     * {@code UNKNOWN} are inconclusive and get the benefit of the doubt, and
     * {@code UNMONITORED} means nobody is looking.</p>
     */
    static boolean carriesTraffic(ConnectionState state, TunnelHealth health) {
        return state == ConnectionState.CONNECTED && health != TunnelHealth.BROKEN;
    }

    @Override
    public List<Proxy> select(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("uri");
        }
        List<Proxy> base = fallbackSelect(uri);
        if (isLoopback(uri.getHost())) {
            return base;
        }
        OptionalInt port = tunnelPort.get();
        if (port == null || port.isEmpty()) {
            return base;
        }
        InetSocketAddress local =
                new InetSocketAddress(InetAddress.getLoopbackAddress(), port.getAsInt());
        // Host only: a subscription path can carry an account token.
        log.debug("Routing a request to {} through the tunnel at {}", uri.getHost(), local);
        return List.of(new Proxy(Proxy.Type.HTTP, local));
    }

    @Override
    public void connectFailed(URI uri, SocketAddress address, IOException failure) {
        if (uri == null || address == null || failure == null) {
            throw new IllegalArgumentException("uri, address and failure are required");
        }
        if (address instanceof InetSocketAddress socket
                && socket.getAddress() != null && socket.getAddress().isLoopbackAddress()) {
            // Our own inbound refused: the core is going down or the port
            // changed underneath a request. The next request re-reads the
            // state, so there is nothing to remember here.
            log.debug("Local proxy {} refused a request to {}: {}",
                    address, uri.getHost(), failure.toString());
            return;
        }
        ProxySelector selector = fallback.get();
        if (selector != null) {
            selector.connectFailed(uri, address, failure);
        }
    }

    private List<Proxy> fallbackSelect(URI uri) {
        ProxySelector selector = fallback.get();
        if (selector == null) {
            return DIRECT;
        }
        List<Proxy> selected = selector.select(uri);
        return selected == null || selected.isEmpty() ? DIRECT : selected;
    }

    /**
     * Loopback by literal only: a hostname other than {@code localhost} would
     * need a DNS query to classify, and DNS is exactly what the tunnel is
     * supposed to answer for it.
     */
    static boolean isLoopback(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String bare = host.toLowerCase(Locale.ROOT);
        if (bare.startsWith("[") && bare.endsWith("]")) {
            bare = bare.substring(1, bare.length() - 1);
        }
        if (bare.equals("localhost") || bare.endsWith(".localhost")) {
            return true;
        }
        try {
            return InetAddress.ofLiteral(bare).isLoopbackAddress();
        } catch (IllegalArgumentException notAnIpLiteral) {
            return false;
        }
    }
}
