package com.vlessclient.model;

import com.vlessclient.platform.Platform;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
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
        String raw = value.trim();
        int slash = raw.indexOf('/');
        if (slash <= 0) {
            return false;
        }
        try {
            InetAddress address = InetAddress.ofLiteral(raw.substring(0, slash));
            int prefix = Integer.parseInt(raw.substring(slash + 1));
            return address instanceof Inet4Address && prefix >= 8 && prefix <= 30;
        } catch (IllegalArgumentException notAnAddressOrPrefix) {
            return false;
        }
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
