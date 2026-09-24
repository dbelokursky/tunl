package com.vlessclient.platform;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Ipv6Uplink} read off the host's network interfaces.
 *
 * <p>Tunnels are left out: they are point-to-point devices, this app's own TUN
 * from the previous run as much as another VPN, and what the rules send direct
 * leaves through the physical network, not through them.</p>
 */
final class GlobalIpv6AddressProbe implements Ipv6Uplink {

    private static final Logger log = LoggerFactory.getLogger(GlobalIpv6AddressProbe.class);

    @Override
    public boolean isPresent() {
        List<NetworkInterface> interfaces;
        try {
            interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
        } catch (SocketException e) {
            // Unknown is not absent: without the device's IPv6 address a
            // dual-stack network sends IPv6 around the tunnel, which is worse
            // than the direct routes this check protects.
            log.warn("Could not list the network interfaces; assuming an IPv6 uplink", e);
            return true;
        }
        for (NetworkInterface nif : interfaces) {
            if (isPhysicalAndUp(nif) && hasGlobalAddress(nif)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPhysicalAndUp(NetworkInterface nif) {
        try {
            return nif.isUp() && !nif.isLoopback() && !nif.isPointToPoint();
        } catch (SocketException e) {
            // The interface went away while the list was being read.
            return false;
        }
    }

    private static boolean hasGlobalAddress(NetworkInterface nif) {
        for (InetAddress address : Collections.list(nif.getInetAddresses())) {
            if (address instanceof Inet6Address v6 && isGlobalUnicast(v6)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code address} is on the public IPv6 internet, as opposed to a
     * scope that never leaves the local network.
     *
     * <p>The range is checked rather than the local scopes ruled out: Java's
     * own tests miss a unique local address ({@code fc00::/7}), because
     * {@link Inet6Address#isSiteLocalAddress()} knows only the retired
     * {@code fec0::/10}, and a home router's {@code fd94:…} is exactly what the
     * Wi-Fi without IPv6 had.</p>
     *
     * @param address an address one of the host's interfaces holds
     * @return true when traffic from it can reach the IPv6 internet
     */
    static boolean isGlobalUnicast(Inet6Address address) {
        byte[] bytes = address.getAddress();
        // IANA allocates global unicast from 2000::/3 alone.
        if ((bytes[0] & 0xE0) != 0x20) {
            return false;
        }
        // Teredo (2001::/32) and 6to4 (2002::/16) are tunnels of their own,
        // not the network the direct outbound dials through; Windows long set
        // Teredo up by itself, whatever the network.
        boolean teredo = bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0 && bytes[3] == 0;
        boolean sixToFour = bytes[0] == 0x20 && bytes[1] == 0x02;
        return !teredo && !sixToFour;
    }
}
