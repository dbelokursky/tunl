package com.vlessclient.service;

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
 * that makes a log useful — scheme, host, port — and drop the rest.</p>
 *
 * <p>Both methods are total: they never throw and never return null for a
 * non-null argument. Over-redacting is the correct failure mode, so anything
 * that cannot be parsed collapses to {@code scheme://<redacted>}.</p>
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
}
