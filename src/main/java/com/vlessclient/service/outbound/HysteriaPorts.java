package com.vlessclient.service.outbound;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hysteria2 port hopping, as links write it and as the core reads it.
 *
 * <p>A link lists the ports a server listens on, {@code 443,20000-50000}: in
 * its authority, the official form, or in 3x-ui's {@code mport}. The core
 * wants each item as a range, {@code 443:443} and {@code 20000:50000}, and
 * refuses a bare port with "bad port range".</p>
 */
public final class HysteriaPorts {

    private static final Pattern ITEM = Pattern.compile("(\\d{1,5})(?:-(\\d{1,5}))?");

    private HysteriaPorts() {
    }

    /**
     * Whether {@code spec} lists ports and ranges within 1 to 65535, each
     * range ascending.
     *
     * @param spec the list, as a link writes it
     * @return whether the core can take it
     */
    public static boolean isValid(String spec) {
        return spec != null && ranges(spec) != null;
    }

    /**
     * The first port the list names: where a client that does not hop
     * connects.
     *
     * @param spec a valid list
     * @return its first port
     */
    public static int firstPort(String spec) {
        return ranges(spec).getFirst()[0];
    }

    /**
     * The list in the core's spelling, one {@code from:to} per item.
     *
     * @param spec a valid list
     * @return the items for {@code server_ports}
     */
    public static List<String> forCore(String spec) {
        return ranges(spec).stream().map(range -> range[0] + ":" + range[1]).toList();
    }

    /** The items as {from, to}, or null when any is malformed or out of range. */
    private static List<int[]> ranges(String spec) {
        List<int[]> ranges = new ArrayList<>();
        for (String item : spec.strip().split(",", -1)) {
            Matcher matcher = ITEM.matcher(item.strip());
            if (!matcher.matches()) {
                return null;
            }
            int from = Integer.parseInt(matcher.group(1));
            int to = matcher.group(2) == null ? from : Integer.parseInt(matcher.group(2));
            if (from < 1 || to > 65535 || from > to) {
                return null;
            }
            ranges.add(new int[] {from, to});
        }
        return ranges;
    }
}
