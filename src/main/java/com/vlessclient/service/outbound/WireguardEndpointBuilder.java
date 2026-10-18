package com.vlessclient.service.outbound;

import com.vlessclient.model.ServerConfig;
import com.vlessclient.platform.InterfaceRoute;
import com.vlessclient.platform.Platform;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the sing-box WireGuard endpoint. Unlike the other protocols,
 * WireGuard is not an outbound: sing-box 1.13 removed the legacy
 * {@code wireguard} outbound (deprecated since 1.11) in favor of a top-level
 * {@code endpoints} entry, so this builder produces that entry instead.
 */
public final class WireguardEndpointBuilder {

    private static final Logger log = LoggerFactory.getLogger(WireguardEndpointBuilder.class);

    private final ObjectMapper mapper;
    private final Platform platform;

    public WireguardEndpointBuilder(ObjectMapper mapper) {
        this(mapper, Platform.current());
    }

    /** Test seam: the platform whose way of binding to an interface the endpoint is given. */
    WireguardEndpointBuilder(ObjectMapper mapper, Platform platform) {
        this.mapper = mapper;
        this.platform = platform;
    }

    /**
     * Builds a WireGuard endpoint whose peer is taken to be reached through
     * the default route's interface, unless it is on this machine.
     *
     * @param server the server to build the endpoint from
     * @param tag    the endpoint's tag
     * @return the endpoint as a JSON object node
     */
    public ObjectNode build(ServerConfig server, String tag) {
        return build(server, tag, InterfaceRoute.DEFAULT_ONLY);
    }

    /**
     * Builds a WireGuard endpoint (sing-box 1.11+ schema; the legacy
     * {@code wireguard} outbound was removed in 1.13). Field sources keep the
     * ServerConfig mapping the legacy outbound used: uuid → private_key,
     * encryption → peer public_key, flow → interface addresses,
     * tls.serverName → reserved bytes.
     *
     * @param server the server to build the endpoint from
     * @param tag    the endpoint's tag
     * @param routes the routes off the default interface, as the run's host
     *               facts keep them
     * @return the endpoint as a JSON object node
     */
    public ObjectNode build(ServerConfig server, String tag, InterfaceRoute routes) {
        ObjectNode endpoint = mapper.createObjectNode();
        endpoint.put("type", "wireguard");
        endpoint.put("tag", tag);

        if (server.getFlow() != null && !server.getFlow().isBlank()) {
            // Each address the interface has, comma-separated as a .conf lists
            // them. The peer carries IPv4 and IPv6 alike (allowed_ips below),
            // and a family without an address of its own carried nothing.
            // Each once: the core refuses an address listed twice.
            ArrayNode address = mapper.createArrayNode();
            for (String part : CoreSettings.interfaceAddresses(server.getFlow())) {
                address.add(CoreSettings.interfaceAddress(part));
            }
            if (!address.isEmpty()) {
                endpoint.set("address", address);
            }
        }

        endpoint.put("private_key", server.getUuid());

        // A peer on this machine, a local relay or obfuscator as udp2raw and
        // wg-obfuscator setups have it, is reached from the loopback. The
        // route's auto_detect_interface bound the endpoint's socket to the
        // default interface before it knew the peer, and a handshake sent
        // from there to 127.0.0.1 never arrived. The core leaves an endpoint
        // with a bind address of its own out of that bind, and this one alone.
        String peerAddress = server.getAddress() == null ? "" : server.getAddress().strip();
        boolean localhost = "localhost".equalsIgnoreCase(peerAddress);
        if (localhost || isLoopback(peerAddress, Inet4Address.class)) {
            endpoint.put("inet4_bind_address", "127.0.0.1");
        }
        if (localhost || isLoopback(peerAddress, Inet6Address.class)) {
            endpoint.put("inet6_bind_address", "::1");
        }
        // The same bind, for a peer the system reaches through another
        // interface than the default route's: a second network card, a
        // Docker or virtual machine bridge, another VPN's route. Its handshake
        // left through the default interface too, and never arrived.
        if (!localhost) {
            routedLiteral(peerAddress).flatMap(routes::offDefault)
                    .ifPresent(route -> bindTo(endpoint, route));
        }

        ObjectNode peer = mapper.createObjectNode();
        peer.put("address", server.getAddress());
        peer.put("port", server.getPort());

        if (server.getEncryption() != null && !server.getEncryption().isEmpty()
                && !"none".equals(server.getEncryption())) {
            peer.put("public_key", server.getEncryption());
        }

        // The legacy single-peer outbound implicitly routed everything through
        // the peer; the endpoint schema makes allowed_ips explicit.
        ArrayNode allowedIps = mapper.createArrayNode();
        allowedIps.add("0.0.0.0/0");
        allowedIps.add("::/0");
        peer.set("allowed_ips", allowedIps);

        ArrayNode reserved = parseReservedBytes(server);
        if (reserved != null) {
            peer.set("reserved", reserved);
        }

        ArrayNode peers = mapper.createArrayNode();
        peers.add(peer);
        endpoint.set("peers", peers);

        return endpoint;
    }

    /** Whether {@code address} is a loopback literal of the family; nothing is looked up. */
    private static boolean isLoopback(String address, Class<? extends InetAddress> family) {
        if (address.isEmpty() || address.indexOf(':') < 0 && address.indexOf('.') < 0) {
            return false;
        }
        try {
            InetAddress literal = InetAddress.ofLiteral(address);
            return family.isInstance(literal) && literal.isLoopbackAddress();
        } catch (IllegalArgumentException notLiteral) {
            return false;
        }
    }

    /**
     * {@code address} as a literal the system routes like any other: not a
     * name, which would have to be looked up, nor a loopback, link-local,
     * multicast or unspecified address.
     */
    private static Optional<InetAddress> routedLiteral(String address) {
        if (address.isEmpty() || address.indexOf(':') < 0 && address.indexOf('.') < 0) {
            return Optional.empty();
        }
        try {
            InetAddress literal = InetAddress.ofLiteral(address);
            boolean routed = !literal.isLoopbackAddress() && !literal.isLinkLocalAddress()
                    && !literal.isMulticastAddress() && !literal.isAnyLocalAddress();
            return routed ? Optional.of(literal) : Optional.empty();
        } catch (IllegalArgumentException notLiteral) {
            return Optional.empty();
        }
    }

    /**
     * Binds the endpoint to the interface its peer is reached through, which
     * the core leaves out of the bind to the default interface. On Linux and
     * macOS by the interface's name: an address alone did not keep the
     * socket out of the TUN device on Linux, whose routing rules come before
     * the main table, and the handshake went into the tunnel itself. On
     * Windows by the local address the system sends from: Java names the
     * interfaces there itself, not as the core does, and a socket bound to
     * an address leaves through the interface that holds it. The core binds
     * to an interface on these three systems only.
     */
    private void bindTo(ObjectNode endpoint, InterfaceRoute.Route route) {
        switch (platform) {
            case LINUX, MAC -> endpoint.put("bind_interface", route.interfaceName());
            case WINDOWS -> endpoint.put(route.source() instanceof Inet6Address
                    ? "inet6_bind_address" : "inet4_bind_address",
                    route.source().getHostAddress());
            default -> {
                // "bind_interface is only supported on Linux, macOS and Windows"
            }
        }
    }

    /**
     * Parses the comma-separated reserved-bytes list (stored in
     * tls.serverName). sing-box fatally rejects a reserved array that is not
     * exactly 3 values, and JSON-decodes each value as uint8 — so anything
     * other than exactly three ints in 0..255 must be dropped entirely,
     * or the config won't start at all.
     */
    private ArrayNode parseReservedBytes(ServerConfig server) {
        if (server.getTls() == null || server.getTls().getServerName() == null
                || server.getTls().getServerName().isBlank()) {
            return null;
        }
        ArrayNode reserved = mapper.createArrayNode();
        for (String part : server.getTls().getServerName().split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                int value = Integer.parseInt(trimmed);
                if (value < 0 || value > 255) {
                    log.warn("Skipping out-of-range reserved byte value '{}' "
                            + "for WireGuard server {}", trimmed, server.getAddress());
                    continue;
                }
                reserved.add(value);
            } catch (NumberFormatException e) {
                log.warn("Skipping invalid reserved byte value '{}' for WireGuard server {}",
                        trimmed, server.getAddress());
            }
        }
        if (reserved.size() == 0) {
            return null;
        }
        if (reserved.size() != 3) {
            log.warn("Ignoring WireGuard reserved bytes for server {}: "
                    + "need exactly 3 values, got {}", server.getAddress(), reserved.size());
            return null;
        }
        return reserved;
    }
}
