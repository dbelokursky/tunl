package com.vlessclient.service;

import com.vlessclient.model.AppSettings;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One DNS server as the core takes it: an IP address or a host name, with a
 * scheme the core has a server type for, or none, which reads as udp, and an
 * optional port. The Settings page, MCP's {@code set_setting} and the
 * generator all read an address here.
 *
 * <p>Only the scheme used to be checked, and the generator wrote whatever it
 * could not split as the server's name. Two resolvers, the primary and
 * secondary every OS dialog asks for ({@code 1.1.1.1, 8.8.8.8}), reached the
 * core as one server of that name: it started, and every lookup through it
 * failed while the card said Connected. A port past 65535 made it refuse the
 * whole configuration, so TUN mode never started.</p>
 *
 * @param type   the core's server type: udp, tcp, tls, https, h3 or quic
 * @param server the IP address, without brackets, or the host name
 * @param port   the port, or 0 for the type's own
 * @param path   the DNS-over-HTTPS path, or empty for the core's default
 */
public record DnsAddress(String type, String server, int port, String path) {

    /** The DNS server types the core takes as a scheme. */
    private static final Set<String> TYPES = Set.of("udp", "tcp", "tls", "https", "h3", "quic");

    /**
     * The types that take a path: DNS over HTTPS and over HTTP/3. Given one, a
     * {@code quic://} server made the core refuse the whole configuration
     * ("unknown field path").
     */
    private static final Set<String> TYPES_WITH_PATH = Set.of("https", "h3");

    /**
     * The characters of a URL, but for the ones that separate the items of a
     * list or quote them: a space, a comma, a semicolon, a quote.
     */
    private static final Pattern URL_CHARACTERS =
            Pattern.compile("[A-Za-z0-9._~:/?#\\[\\]@!$&()*+=%-]+");

    /**
     * A host name's label: letters, digits and hyphens inside, at most 63 of
     * them. An underscore too, which some networks' own hosts carry.
     */
    private static final Pattern LABEL =
            Pattern.compile("[A-Za-z0-9_](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?");

    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,5}");

    /** The longest host name DNS carries, without its final dot. */
    private static final int MAX_NAME = 253;

    /**
     * Reads one DNS server, as typed into Settings or sent over MCP: space
     * around it is not part of it.
     *
     * @param text the address
     * @return the server, or empty when the text is not one address the core
     *     can use: blank, a list, a scheme the core has no server for, a port
     *     outside 1 to 65535, or a name no host has
     */
    public static Optional<DnsAddress> parse(String text) {
        String value = text == null ? "" : text.strip();
        if (!URL_CHARACTERS.matcher(value).matches()) {
            return Optional.empty();
        }
        int schemeEnd = value.indexOf("://");
        String type = schemeEnd < 0
                ? "udp" : value.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        String rest = schemeEnd < 0 ? value : value.substring(schemeEnd + 3);
        if (!TYPES.contains(type)) {
            return Optional.empty();
        }
        int authorityEnd = firstOf(rest, "/?#");
        // User information has no place in the core's server; it was always dropped.
        String authority = rest.substring(0, authorityEnd);
        authority = authority.substring(authority.lastIndexOf('@') + 1);

        String server;
        String port = "";
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            String after = close < 0 ? "" : authority.substring(close + 1);
            if (close < 0 || !after.isEmpty() && !after.startsWith(":")) {
                return Optional.empty();
            }
            server = authority.substring(1, close);
            port = after.isEmpty() ? "" : after.substring(1);
            if (!RoutingRuleCheck.isIpv6Address(server)) {
                return Optional.empty();
            }
        } else if (authority.indexOf(':') != authority.lastIndexOf(':')) {
            // An IPv6 address without brackets, which leaves no room for a port.
            server = authority;
            if (!RoutingRuleCheck.isIpv6Address(server)) {
                return Optional.empty();
            }
        } else {
            int colon = authority.indexOf(':');
            server = colon < 0 ? authority : authority.substring(0, colon);
            port = colon < 0 ? "" : authority.substring(colon + 1);
            if (!RoutingRuleCheck.isIpv4Address(server) && !isHostName(server)) {
                return Optional.empty();
            }
        }
        int portNumber = 0;
        if (!port.isEmpty()) {
            if (!DIGITS.matcher(port).matches()) {
                return Optional.empty();
            }
            portNumber = Integer.parseInt(port);
            if (portNumber < 1 || portNumber > 65535) {
                return Optional.empty();
            }
        }
        String path = TYPES_WITH_PATH.contains(type)
                ? rest.substring(authorityEnd, firstOf(rest, "?#")) : "";
        return Optional.of(new DnsAddress(type, server, portNumber, path));
    }

    /**
     * Whether Proxy DNS can hold this: one address, or blank, which stands
     * for the default the field shows.
     *
     * @param value the setting as it would be stored
     * @return true when the core can use it
     */
    public static boolean isProxySetting(String value) {
        return value == null || value.isBlank() || parse(value).isPresent();
    }

    /**
     * Whether Direct DNS can hold this: one address, {@link AppSettings#SYSTEM_DNS}
     * for the system's resolver, or blank, which stands for that too.
     *
     * @param value the setting as it would be stored
     * @return true when the core can use it
     */
    public static boolean isDirectSetting(String value) {
        return isProxySetting(value) || AppSettings.SYSTEM_DNS.equalsIgnoreCase(value.strip());
    }

    /**
     * A host's name: two labels or more, the last of them not a number, or
     * {@code localhost}. A single word such as {@code local} or {@code fakeip},
     * which the core has as server types, names no host anyone resolves.
     */
    private static boolean isHostName(String name) {
        String bare = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        if ("localhost".equalsIgnoreCase(bare)) {
            return true;
        }
        String[] labels = bare.split("\\.", -1);
        if (labels.length < 2 || bare.length() > MAX_NAME
                || labels[labels.length - 1].chars().allMatch(Character::isDigit)) {
            return false;
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

    /** The index of the first of {@code chars} in {@code text}, or its length. */
    private static int firstOf(String text, String chars) {
        for (int i = 0; i < text.length(); i++) {
            if (chars.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return text.length();
    }
}
