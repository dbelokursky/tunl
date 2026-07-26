package com.vlessclient.service;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses a standard WireGuard {@code .conf} file into a {@link ServerConfig}.
 *
 * <p>WireGuard has no share-link format — every other protocol here arrives as
 * a {@code scheme://} URI handled by {@link ShareLinkParser}, but what a
 * WireGuard user actually holds is the INI-style config their provider gave
 * them. So this is deliberately a separate parser rather than another case in
 * that switch: the input is not a URI and forcing it into one would invent a
 * format nobody else speaks.</p>
 *
 * <p>The field mapping follows what {@code WireguardEndpointBuilder} already
 * reads off {@link ServerConfig}:</p>
 *
 * <pre>
 * [Interface] PrivateKey → uuid          [Peer] PublicKey  → encryption
 * [Interface] Address    → flow          [Peer] Endpoint   → address + port
 * </pre>
 *
 * <p>Unsupported-but-common keys are reported rather than dropped: a config
 * carrying {@code PresharedKey} would connect without it and fail in a way
 * that looks like a server problem, so the caller is told instead.</p>
 */
public class WireguardConfigParser {

    private static final int DEFAULT_PORT = 51820;

    /** Keys the generator cannot express yet; silently ignoring them misleads. */
    private static final List<String> UNSUPPORTED = List.of("presharedkey");

    /** Thrown when the config cannot produce a working server. */
    public static class InvalidConfigException extends IllegalArgumentException {
        public InvalidConfigException(String message) {
            super(message);
        }
    }

    /**
     * Parses WireGuard {@code .conf} text.
     *
     * @param text the file's contents
     * @return a WireGuard {@link ServerConfig}
     * @throws InvalidConfigException if a required field is missing or malformed
     */
    public ServerConfig parse(String text) {
        if (text == null || text.isBlank()) {
            throw new InvalidConfigException("The configuration is empty");
        }

        Map<String, Map<String, String>> sections = parseSections(text);
        Map<String, String> iface = sections.getOrDefault("interface", Map.of());
        Map<String, String> peer = sections.getOrDefault("peer", Map.of());

        if (iface.isEmpty()) {
            throw new InvalidConfigException("No [Interface] section");
        }
        if (peer.isEmpty()) {
            throw new InvalidConfigException("No [Peer] section");
        }

        List<String> unsupported = new ArrayList<>();
        for (String key : UNSUPPORTED) {
            if (iface.containsKey(key) || peer.containsKey(key)) {
                unsupported.add(key);
            }
        }
        if (!unsupported.isEmpty()) {
            throw new InvalidConfigException(
                    "Not supported yet: " + String.join(", ", unsupported));
        }

        String privateKey = require(iface, "privatekey", "[Interface] PrivateKey");
        String publicKey = require(peer, "publickey", "[Peer] PublicKey");

        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.WIREGUARD);
        server.setUuid(privateKey);
        server.setEncryption(publicKey);
        applyEndpoint(server, require(peer, "endpoint", "[Peer] Endpoint"));

        String address = iface.get("address");
        if (address != null && !address.isBlank()) {
            // Providers list several; the builder takes one local address.
            server.setFlow(address.split(",")[0].trim());
        }
        server.setName(defaultName(server));
        return server;
    }

    /**
     * Splits {@code host:port}, tolerating a bracketed IPv6 literal. The port
     * is optional — WireGuard's own default applies when it is absent.
     */
    private void applyEndpoint(ServerConfig server, String endpoint) {
        String value = endpoint.trim();
        String host;
        int port = DEFAULT_PORT;

        int portSeparator = value.lastIndexOf(':');
        boolean bracketed = value.startsWith("[");
        if (bracketed) {
            int close = value.indexOf(']');
            if (close < 0) {
                throw new InvalidConfigException("Malformed [Peer] Endpoint: " + endpoint);
            }
            host = value.substring(1, close);
            portSeparator = value.indexOf(':', close);
            if (portSeparator > 0) {
                port = parsePort(value.substring(portSeparator + 1), endpoint);
            }
        } else if (portSeparator > 0 && value.indexOf(':') == portSeparator) {
            host = value.substring(0, portSeparator);
            port = parsePort(value.substring(portSeparator + 1), endpoint);
        } else {
            // No port, or a bare IPv6 literal with no brackets and no port.
            host = value;
        }

        if (host.isBlank()) {
            throw new InvalidConfigException("Malformed [Peer] Endpoint: " + endpoint);
        }
        server.setAddress(host);
        server.setPort(port);
    }

    private int parsePort(String text, String endpoint) {
        try {
            int port = Integer.parseInt(text.trim());
            if (port < 1 || port > 65535) {
                throw new InvalidConfigException(
                        "Port out of range in [Peer] Endpoint: " + endpoint);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new InvalidConfigException("Malformed port in [Peer] Endpoint: " + endpoint);
        }
    }

    private static String require(Map<String, String> section, String key, String label) {
        String value = section.get(key);
        if (value == null || value.isBlank()) {
            throw new InvalidConfigException("Missing " + label);
        }
        return value.trim();
    }

    private static String defaultName(ServerConfig server) {
        return "WireGuard " + server.getAddress();
    }

    /**
     * Reads the INI structure: {@code [Section]} headers, {@code key = value}
     * pairs, {@code #} and {@code ;} comments. Keys are lower-cased because
     * real-world configs vary the casing freely.
     */
    private Map<String, Map<String, String>> parseSections(String text) {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        Map<String, String> current = null;

        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                String name = line.substring(1, line.length() - 1)
                        .trim().toLowerCase(Locale.ROOT);
                // Multiple [Peer] blocks: the builder emits one peer, so the
                // first wins rather than the last silently replacing it.
                current = sections.computeIfAbsent(name, k -> new LinkedHashMap<>());
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0 || current == null) {
                continue;
            }
            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(eq + 1).trim();
            current.putIfAbsent(key, value);
        }
        return sections;
    }
}
