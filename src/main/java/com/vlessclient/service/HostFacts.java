package com.vlessclient.service;

import com.vlessclient.platform.Ipv6Uplink;
import com.vlessclient.platform.SystemProxySupport;
import java.util.HashMap;
import java.util.Map;
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

    /** Null until asked. Guarded by this. */
    private Boolean ipv6;
    /** Null until asked. Guarded by this. */
    private Boolean systemProxy;
    /** Each server's latency as first asked. Guarded by this. */
    private final Map<String, OptionalLong> latencies = new HashMap<>();

    HostFacts(Ipv6Uplink ipv6Uplink, SystemProxySupport systemProxySupport) {
        this(ipv6Uplink, systemProxySupport, SingBoxConfigGenerator.LatencyRanking.NONE);
    }

    HostFacts(Ipv6Uplink ipv6Uplink, SystemProxySupport systemProxySupport,
              SingBoxConfigGenerator.LatencyRanking ranking) {
        this.ipv6Uplink = ipv6Uplink;
        this.systemProxySupport = systemProxySupport;
        this.ranking = ranking;
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
}
