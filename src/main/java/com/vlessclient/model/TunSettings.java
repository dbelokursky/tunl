package com.vlessclient.model;

import com.vlessclient.platform.Platform;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which TUN settings a TUN start can succeed with, for the Settings page and
 * MCP's {@code set_setting} alike.
 *
 * <p>MCP refused an address the core cannot use and the Settings page stored
 * it, and neither checked the interface name. The core is given both as they
 * are, so a value that fails one TUN start fails every one, until the user
 * finds the field.</p>
 */
public final class TunSettings {

    /** What macOS names a TUN device; the core refuses anything else ("bad tun name"). */
    private static final Pattern MAC_NAME = Pattern.compile("utun\\d+");

    /** An IPv4 address field as Go reads it: 0 to 255, without a leading zero. */
    private static final String OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9][0-9]|[0-9])";

    /** An IPv4 address with a decimal prefix of its own, neither with a sign nor a leading zero. */
    private static final Pattern IPV4_NETWORK =
            Pattern.compile(OCTET + "(?:\\." + OCTET + "){3}/([1-9][0-9]?)");

    /** Linux's limit on an interface name, the terminating zero not counted. */
    private static final int LINUX_NAME_BYTES = 15;

    private TunSettings() {
    }

    /**
     * Whether {@code value} is an IPv4 network for the TUN device, such as
     * {@code 172.19.0.1/30}: an address the core cannot parse fails every TUN
     * start, and a prefix longer than /30 leaves no room for the peer.
     *
     * @param value the address with its prefix, or null
     * @return true when the core can give the device that address
     */
    public static boolean isIpv4Network(String value) {
        if (value == null) {
            return false;
        }
        // As Go's netip reads it. Java's literal parser also took three
        // fields ("172.19.1", a dropped octet) and a field with a leading
        // zero, and Integer.parseInt a prefix of "030" or "+30": stored, each
        // failed every TUN start, as the core refused it.
        Matcher network = IPV4_NETWORK.matcher(value.trim());
        if (!network.matches()) {
            return false;
        }
        int prefix = Integer.parseInt(network.group(1));
        return prefix >= 8 && prefix <= 30;
    }

    /**
     * Whether {@code name} is a TUN interface name the system takes: on macOS
     * {@code utunN}, on Linux at most 15 bytes with no space, slash or colon
     * and not {@code .} or {@code ..}, elsewhere anything not blank.
     *
     * @param name     the name, or null
     * @param platform the system the device is made on
     * @return true when the device can be made with that name
     */
    public static boolean isInterfaceName(String name, Platform platform) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return switch (platform) {
            case MAC -> MAC_NAME.matcher(name).matches();
            case LINUX -> name.getBytes(StandardCharsets.UTF_8).length <= LINUX_NAME_BYTES
                    && !name.equals(".") && !name.equals("..")
                    && name.chars().noneMatch(c -> c == '/' || c == ':'
                            || Character.isWhitespace(c));
            case WINDOWS, OTHER -> true;
        };
    }
}
