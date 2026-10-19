package com.vlessclient.service;

import com.vlessclient.platform.InterfaceRoute;
import com.vlessclient.platform.Ipv6Uplink;
import com.vlessclient.platform.SystemProxySupport;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What the host was like for one core: the facts its configuration depends on
 * beyond the user's settings, each asked at most once.
 *
 * <p>The configuration is generated again to tell whether the running core
 * still matches the settings, on the FX thread, whenever the dashboard
 * updates. Asked afresh each time, a fact made that answer depend on the host
 * rather than the settings: a network gaining IPv6 mid-session read as a
 * change the user had made ("restart to apply"), and every update of the card
 * walked the network interfaces, or started {@code gsettings} on Linux, on the
 * FX thread. A run keeps the facts it started from; a fact it never needed is
 * asked the first time it is.</p>
 *
 * <p>The servers' last latencies are kept the same way. The "Fastest" mode
 * picks its members by them, and after "Test all" re-measured the servers the
 * group generated for the comparison came out different from the running
 * one, so the Dashboard asked for a reconnect although nothing was changed.</p>
 */
public final class HostFacts {

    private final Ipv6Uplink ipv6Uplink;
    private final SystemProxySupport systemProxySupport;
    private final SingBoxConfigGenerator.LatencyRanking ranking;
    private final InterfaceRoute interfaceRoute;

    /** Null until asked. Guarded by this. */
    private Boolean ipv6;
    /** Null until asked. Guarded by this. */
    private Boolean systemProxy;
    /** Each server's latency as first asked. Guarded by this. */
    private final Map<String, OptionalLong> latencies = new HashMap<>();
    /** Each peer's route off the default interface, as first asked. Guarded by this. */
    private final Map<InetAddress, Optional<InterfaceRoute.Route>> routes = new HashMap<>();

    HostFacts(Ipv6Uplink ipv6Uplink, SystemProxySupport systemProxySupport) {
        this(ipv6Uplink, systemProxySupport, SingBoxConfigGenerator.LatencyRanking.NONE);
    }

    HostFacts(Ipv6Uplink ipv6Uplink, SystemProxySupport systemProxySupport,
              SingBoxConfigGenerator.LatencyRanking ranking) {
        this(ipv6Uplink, systemProxySupport, ranking, InterfaceRoute.DEFAULT_ONLY);
    }

    HostFacts(Ipv6Uplink ipv6Uplink, SystemProxySupport systemProxySupport,
              SingBoxConfigGenerator.LatencyRanking ranking, InterfaceRoute interfaceRoute) {
        this.ipv6Uplink = ipv6Uplink;
        this.systemProxySupport = systemProxySupport;
        this.ranking = ranking;
        this.interfaceRoute = interfaceRoute;
    }

    /**
     * A server's last latency, as it was when this run first asked.
     *
     * @param serverId the server's id
     * @return what {@link SingBoxConfigGenerator.LatencyRanking#lastLatency}
     *     answered the first time
     */
    public synchronized OptionalLong lastLatency(String serverId) {
        return latencies.computeIfAbsent(serverId, ranking::lastLatency);
    }

    /**
     * Whether the host's own network reaches the IPv6 internet.
     *
     * @return what {@link Ipv6Uplink#isPresent()} answered the first time
     */
    public synchronized boolean ipv6Uplink() {
        if (ipv6 == null) {
            ipv6 = ipv6Uplink.isPresent();
        }
        return ipv6;
    }

    /**
     * Whether sing-box can set the OS proxy on this host.
     *
     * @return what {@link SystemProxySupport#canAutoConfigure()} answered the
     *     first time
     */
    public synchronized boolean systemProxyAutoConfigurable() {
        if (systemProxy == null) {
            systemProxy = systemProxySupport.canAutoConfigure();
        }
        return systemProxy;
    }

    /**
     * The route to a WireGuard peer when the system takes it through another
     * interface than the default route's, as it was when this run first
     * asked: a network gaining or losing an interface mid-session is no
     * change of the settings.
     *
     * @param peer the peer's address
     * @return what {@link InterfaceRoute#offDefault} answered the first time
     */
    public synchronized Optional<InterfaceRoute.Route> routeOffDefault(InetAddress peer) {
        return routes.computeIfAbsent(peer, interfaceRoute::offDefault);
    }
}
