package com.vlessclient.platform;

import java.net.InetAddress;
import java.util.Optional;

/**
 * The interface the system sends a packet for an address through, when that
 * is not the interface of its default route: a peer behind a second network
 * card, on a Docker or virtual machine bridge, or across another VPN's route.
 *
 * <p>The core's {@code auto_detect_interface} binds a socket to the
 * interface whose network holds its destination, or else to the default
 * interface. A WireGuard endpoint's socket is opened before its peer is
 * known, so it was bound to the default interface, and a peer reached
 * through any other interface never answered the handshake, while the server
 * showed Connected. Asked as a connected UDP socket asks it: the route is
 * looked up, and nothing is sent.</p>
 */
@FunctionalInterface
public interface InterfaceRoute {

    /** Every address is reached through the default route's interface. */
    InterfaceRoute DEFAULT_ONLY = destination -> Optional.empty();

    /**
     * How the system reaches an address off its default route.
     *
     * @param interfaceName the interface, by the name the system gives it
     *                      (Java's own on Windows)
     * @param source        the local address the system sends from
     */
    record Route(String interfaceName, InetAddress source) {
    }

    /**
     * The route the system takes to {@code destination} when it leaves
     * through another interface than the default route for the address's
     * family, or when there is no default route.
     *
     * @param destination the address, looked up as it is
     * @return the route, or empty when the default route's interface takes
     *     it or no route does
     */
    Optional<Route> offDefault(InetAddress destination);

    /**
     * Returns the lookup over this host's routes.
     *
     * @return the lookup over this host's routes
     */
    static InterfaceRoute current() {
        return SystemInterfaceRoute::offDefault;
    }
}
