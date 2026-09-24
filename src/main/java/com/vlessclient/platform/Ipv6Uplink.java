package com.vlessclient.platform;

/**
 * Whether the host's own network reaches the IPv6 internet, outside any tunnel.
 *
 * <p>The TUN device takes an IPv6 address so that on a dual-stack network IPv6
 * destinations go through the tunnel rather than around it. On a network without
 * IPv6 the address did harm: apps saw a route to the whole IPv6 internet, looked
 * up AAAA records and connected over IPv6, and what the rules sent direct could not
 * leave the machine ({@code dial tcp [2a02:6b8::2:242]:443: connect: no route to
 * host} for ya.ru). The gvisor stack had already completed the app's handshake, so
 * the app saw a reset instead of a failed connect and never fell back to IPv4:
 * Russian sites stopped opening in Chrome, while the terminal, whose resolver asks
 * for A records alone, still reached them.</p>
 */
@FunctionalInterface
public interface Ipv6Uplink {

    /**
     * True when traffic sent direct can reach IPv6 destinations.
     *
     * @return whether a physical interface holds a global IPv6 address
     */
    boolean isPresent();

    /**
     * Returns the check over this host's network interfaces.
     *
     * @return the check over this host's network interfaces
     */
    static Ipv6Uplink current() {
        return new GlobalIpv6AddressProbe();
    }
}
