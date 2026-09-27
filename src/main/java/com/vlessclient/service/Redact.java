package com.vlessclient.service;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips credentials out of strings on their way into a log file or an
 * on-disk error field.
 *
 * <p>Two kinds of URL in this app carry secrets in the URL itself, which is
 * why they get sealed rather than stored plainly:</p>
 *
 * <ul>
 *   <li>subscription URLs, whose path or query embeds the account token —
 *       {@code https://provider.example/sub/9f3c…}</li>
 *   <li>share links, whose userinfo is the credential —
 *       {@code vless://&lt;uuid&gt;@host:443?…}</li>
 * </ul>
 *
 * <p>Sealing them in the config file accomplishes nothing if the same value
 * reaches {@code tunl.log} or {@code mcp-audit.log} in plaintext, and log
 * files are what users attach to bug reports. These helpers keep the part
 * that makes a log useful — scheme, host, port — and drop the rest.
 * {@link #publicIpsIn} goes further, for text that may name the user's
 * server: it drops the address itself.</p>
 *
 * <p>Every method is total: none throws or returns null for a non-null
 * argument. Over-redacting is the correct failure mode, so anything that
 * cannot be parsed collapses to {@code scheme://<redacted>}.</p>
 */
public final class Redact {

    /** Stand-in for a value that was removed entirely. */
    public static final String REDACTED = "<redacted>";

    /**
     * A URL embedded in free text. Deliberately greedy up to whitespace or a
     * quote: trailing punctuation swept into the match is redacted too, which
     * is the safe direction to err in.
     */
    private static final Pattern URL_IN_TEXT =
            Pattern.compile("[a-zA-Z][a-zA-Z0-9+.\\-]*://[^\\s\"'<>\\\\]+");

    /**
     * What we are willing to print as a host. Deliberately narrow, because
     * {@link URI} is not: {@code vmess://eyJ2IjoiMiJ9…} parses cleanly with the
     * whole base64 payload as the "host", so trusting {@code getHost()} would
     * echo the credential verbatim. A real host here has a dot (or is a
     * bracketed IPv6 literal, or is localhost); a base64 blob has neither, and
     * cannot have a dot at all since it is not in the base64 alphabet.
     */
    private static final Pattern PLAUSIBLE_HOST =
            Pattern.compile("(?:[A-Za-z0-9\\-]+(?:\\.[A-Za-z0-9\\-]+)+|localhost"
                    + "|\\[[0-9A-Fa-f:.]+])");

    /**
     * Four dotted numbers in free text that are not part of a longer dotted
     * run, such as a version. Settled by parsing: an octet may exceed 255.
     */
    private static final Pattern IPV4_IN_TEXT =
            Pattern.compile("(?<![\\w.])\\d{1,3}(?:\\.\\d{1,3}){3}(?!\\w|\\.\\d)");

    /**
     * Hex groups and colons in free text, which may be an IPv6 address. Loose
     * on purpose, since a time of day matches too; parsing settles it. One
     * followed by a dotted number is left to {@link #IPV4_IN_TEXT}, which takes
     * the IPv4 address at its end.
     */
    private static final Pattern IPV6_IN_TEXT = Pattern.compile(
            "(?<![\\w:.])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?![\\w:]|\\.\\d)");

    private Redact() {
    }

    /**
     * Reduces a single URL to {@code scheme://host[:port]} plus an ellipsis
     * when anything followed. Userinfo, path, query and fragment are dropped:
     * every one of them is a place this app's URLs keep secrets.
     *
     * @param url the URL to redact; may be null or blank
     * @return the redacted form, or the input unchanged when it is null,
     *         blank, or not a URL at all
     */
    public static String url(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        int schemeEnd = url.indexOf("://");
        if (schemeEnd <= 0) {
            // Not a hierarchical URL — nothing we can safely keep.
            return REDACTED;
        }
        String scheme = url.substring(0, schemeEnd);

        String host = null;
        int port = -1;
        try {
            URI uri = new URI(url);
            host = uri.getHost();
            port = uri.getPort();
            if (host == null && uri.getAuthority() != null) {
                // Authorities the URI parser won't split (raw IPv6, odd
                // userinfo) still let us drop everything before the '@'.
                String authority = uri.getAuthority();
                int at = authority.lastIndexOf('@');
                host = at >= 0 ? authority.substring(at + 1) : authority;
            }
        } catch (Exception e) {
            // Share links are frequently not valid URIs (vmess:// carries a
            // bare base64 payload). Fall through to the scheme-only form.
            host = null;
        }

        if (host == null || !PLAUSIBLE_HOST.matcher(host).matches()) {
            return scheme + "://" + REDACTED;
        }
        StringBuilder out = new StringBuilder(scheme).append("://").append(host);
        if (port != -1) {
            out.append(':').append(port);
        }
        // Signal that something was cut, so a redacted URL is never mistaken
        // for the whole thing.
        out.append("/…");
        return out.toString();
    }

    /**
     * Redacts every URL found inside an arbitrary string — an exception
     * message, a stack-trace line, a JSON blob. Use this wherever the text
     * originates outside your control and might quote a URL back at you.
     *
     * @param text the text to scrub; may be null
     * @return the text with every embedded URL replaced by its redacted form
     */
    public static String urlsIn(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = URL_IN_TEXT.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        do {
            out.append(text, last, matcher.start()).append(url(matcher.group()));
            last = matcher.end();
        } while (matcher.find());
        out.append(text, last, text.length());
        return out.toString();
    }

    /**
     * Redacts every IP address in the text that can name a machine on the
     * internet, and keeps loopback, private, link-local, unspecified and
     * multicast ones, which say how this machine is set up and name no one.
     * The core quotes the address it dialed ({@code dial tcp 203.0.113.7:443}),
     * and for a server known by name that is an address no configuration
     * holds.
     *
     * @param text the text to scrub; may be null
     * @return the text with every public IP address replaced
     */
    public static String publicIpsIn(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return publicIpsIn(IPV6_IN_TEXT, publicIpsIn(IPV4_IN_TEXT, text));
    }

    private static String publicIpsIn(Pattern candidates, String text) {
        return candidates.matcher(text).replaceAll(match -> Matcher.quoteReplacement(
                isPublicIp(match.group()) ? REDACTED : match.group()));
    }

    /** Whether {@code literal} is an IP address that can name a machine on the internet. */
    private static boolean isPublicIp(String literal) {
        InetAddress address;
        try {
            address = InetAddress.ofLiteral(literal);
        } catch (IllegalArgumentException notAnAddress) {
            // A time of day, a MAC address: shaped like one, but not one.
            return false;
        }
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        // fc00::/7 is IPv6's private range; isSiteLocalAddress knows only the
        // fec0::/10 it replaced.
        return !(address instanceof Inet6Address) || (address.getAddress()[0] & 0xFE) != 0xFC;
    }
}
