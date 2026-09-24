package com.vlessclient.platform;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;

/**
 * Whether the host is on a network at all: a physical interface up with an
 * address beyond its own link.
 *
 * <p>Recovery restarted the tunnel whenever no health-check target answered,
 * and with the Wi-Fi gone (a roam, the lid closed) none can: the restart cut
 * whatever still worked, routed the gap direct and grew the backoff toward
 * five minutes, only to fail again. Without a network there is nothing a
 * restart can fix.</p>
 */
@FunctionalInterface
public interface NetworkPresence {

    /**
     * True when some physical interface can reach beyond its link.
     *
     * @return whether the host is on a network
     */
    boolean isUp();

    /**
     * Returns the check over this host's network interfaces. When they cannot
     * be listed it answers yes, which leaves recovery as it was.
     *
     * @return the check over this host's network interfaces
     */
    static NetworkPresence current() {
        return () -> {
            try {
                for (NetworkInterface nif
                        : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (isPhysicalAndUp(nif) && reachesBeyondItsLink(nif)) {
                        return true;
                    }
                }
                return false;
            } catch (SocketException unknown) {
                return true;
            }
        };
    }

    private static boolean isPhysicalAndUp(NetworkInterface nif) {
        try {
            // Point-to-point: the tunnel's own device and other VPNs, which
            // stay up with the network gone.
            return nif.isUp() && !nif.isLoopback() && !nif.isPointToPoint();
        } catch (SocketException gone) {
            return false;
        }
    }

    private static boolean reachesBeyondItsLink(NetworkInterface nif) {
        for (InetAddress address : Collections.list(nif.getInetAddresses())) {
            if (address instanceof Inet4Address v4 && !v4.isLinkLocalAddress()) {
                return true;
            }
            if (address instanceof Inet6Address v6 && (v6.getAddress()[0] & 0xE0) == 0x20) {
                return true;
            }
        }
        return false;
    }
}
