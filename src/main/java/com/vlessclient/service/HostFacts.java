package com.vlessclient.service;

import com.vlessclient.platform.Ipv6Uplink;
import com.vlessclient.platform.SystemProxySupport;

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
 */
public final class HostFacts {

    private final Ipv6Uplink ipv6Uplink;
    private final SystemProxySupport systemProxySupport;

    /** Null until asked. Guarded by this. */
    private Boolean ipv6;
    /** Null until asked. Guarded by this. */
    private Boolean systemProxy;

    HostFacts(Ipv6Uplink ipv6Uplink, SystemProxySupport systemProxySupport) {
        this.ipv6Uplink = ipv6Uplink;
        this.systemProxySupport = systemProxySupport;
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
