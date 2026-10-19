package com.vlessclient.platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.Optional;

/**
 * {@link InterfaceRoute} asked of the host's routing table. Connecting a UDP
 * socket looks its route up, policy rules included, and sends nothing; the
 * local address it is then given names the interface.
 */
final class SystemInterfaceRoute {

    /**
     * Addresses kept for documentation, which no network routes on its own,
     * so the default route takes them. Not 192.0.2.0/24: some test networks
     * number their own default interface there.
     */
    private static final InetAddress DEFAULT_IPV4 = InetAddress.ofLiteral("203.0.113.1");
    private static final InetAddress DEFAULT_IPV6 = InetAddress.ofLiteral("2001:db8::1");

    /** Any port will do: a UDP socket connected to it sends nothing. */
    private static final int ANY_PORT = 9;

    private SystemInterfaceRoute() {
    }

    static Optional<InterfaceRoute.Route> offDefault(InetAddress destination) {
        Optional<InterfaceRoute.Route> route = routeTo(destination);
        if (route.isEmpty()) {
            return route;
        }
        Optional<InterfaceRoute.Route> viaDefault =
                routeTo(destination instanceof Inet6Address ? DEFAULT_IPV6 : DEFAULT_IPV4);
        boolean sameInterface = viaDefault.isPresent()
                && viaDefault.get().interfaceName().equals(route.get().interfaceName());
        return sameInterface ? Optional.empty() : route;
    }

    /** The interface and local address the system sends to {@code destination} from. */
    static Optional<InterfaceRoute.Route> routeTo(InetAddress destination) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(new InetSocketAddress(destination, ANY_PORT));
            InetAddress source = socket.getLocalAddress();
            if (source == null || source.isAnyLocalAddress()) {
                return Optional.empty();
            }
            NetworkInterface nif = NetworkInterface.getByInetAddress(source);
            return nif == null ? Optional.empty()
                    : Optional.of(new InterfaceRoute.Route(nif.getName(), source));
        } catch (IOException | UncheckedIOException e) {
            // No route there, or no stack for the family, as a host
            // without IPv6 answers: nothing to bind to.
            return Optional.empty();
        }
    }
}
