package com.vlessclient.service.outbound;

import com.vlessclient.model.ServerConfig;

/**
 * The sing-box tag vocabulary, in one place.
 *
 * <p>Tags are a contract in three directions at once: the generator emits them,
 * routing and DNS resolve them ({@code route.final}, the DNS {@code detour},
 * every rule-set {@code download_detour}), and the clash_api client addresses
 * proxies by them. Deriving them in more than one place is how those three
 * drift apart, so every tag this app emits comes from here.</p>
 *
 * <p>{@link #PROXY} is the fixed entry point. Everything downstream references
 * it and nothing downstream cares what kind of node carries it — today a
 * selector group, historically a single outbound. That indirection is what lets
 * proxy groups exist without touching route, DNS or rule-sets at all.</p>
 */
public final class OutboundTags {

    /**
     * The tag every downstream reference resolves to. Held by the proxy group,
     * which in turn points at one or more {@link #server(String) server tags}.
     */
    public static final String PROXY = "proxy";

    /** Direct (un-proxied) egress. */
    public static final String DIRECT = "direct";

    private static final String SERVER_PREFIX = "srv-";

    private OutboundTags() {
    }

    /**
     * The tag for one server's own outbound (or, for WireGuard, its endpoint).
     *
     * <p>Derived from the server id rather than its name: names are
     * user-editable, duplicated freely by subscriptions, and may contain
     * anything, while the id is stable and unique — and a tag collision would
     * silently route traffic through the wrong server.</p>
     *
     * @param serverId the server's stable id
     * @return the tag to emit for that server
     */
    public static String server(String serverId) {
        return SERVER_PREFIX + serverId;
    }

    /** The server tag for {@code server}. */
    public static String server(ServerConfig server) {
        return server(server.getId());
    }
}
