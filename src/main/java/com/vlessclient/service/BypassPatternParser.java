package com.vlessclient.service;

import com.vlessclient.app.I18n;
import java.net.IDN;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses user-entered bypass list patterns into a typed form that can be
 * emitted as a sing-box route rule.
 *
 * <p>Supported pattern shapes (case-insensitive):</p>
 * <table>
 *   <caption>Pattern shapes</caption>
 *   <tr><td>{@code example.com}</td>              <td>DOMAIN (exact)</td></tr>
 *   <tr><td>{@code *.example.com}</td>            <td>DOMAIN_SUFFIX {@code example.com}</td></tr>
 *   <tr><td>{@code .example.com}</td>             <td>DOMAIN_SUFFIX {@code example.com}</td></tr>
 *   <tr><td>{@code *google*}</td>                 <td>DOMAIN_KEYWORD {@code google}</td></tr>
 *   <tr><td>{@code *vk.com*}</td>                 <td>DOMAIN_KEYWORD {@code vk.com}</td></tr>
 *   <tr><td>{@code google*}</td>                  <td>DOMAIN_KEYWORD {@code google}</td></tr>
 *   <tr><td>{@code mail.*.com}</td> <td>DOMAIN_REGEX {@code ^mail\..*\.com$}</td></tr>
 *   <tr><td>{@code *.рф}</td>                     <td>DOMAIN_SUFFIX {@code xn--p1ai}</td></tr>
 *   <tr><td>{@code 192.168.0.0/16}</td>           <td>IP_CIDR (as-is)</td></tr>
 *   <tr><td>{@code 203.0.113.42}</td>             <td>IP_CIDR {@code .../32}</td></tr>
 *   <tr><td>{@code https://foo.com/bar}</td>      <td>DOMAIN {@code foo.com}</td></tr>
 * </table>
 *
 * <p>Names reach the core the way connections carry them: lower-case, and
 * internationalized names in punycode. A pattern typed in capitals, one with
 * a dot inside its stars ({@code *vk.com*} became the keyword {@code vkcom})
 * and any {@code .рф} name used to match nothing, silently.</p>
 *
 * <p>Blank lines and lines starting with {@code #} are comments and return
 * {@code null}.</p>
 */
public final class BypassPatternParser {

    private static final Pattern IPV4 = Pattern.compile(
            "^(\\d{1,3}\\.){3}\\d{1,3}$");
    private static final Pattern IPV4_CIDR = Pattern.compile(
            "^(\\d{1,3}\\.){3}\\d{1,3}/\\d{1,2}$");
    private static final Pattern IPV6_CIDR = Pattern.compile(
            "^[0-9a-fA-F:]+/\\d{1,3}$");
    private static final Pattern IPV6 = Pattern.compile(
            "^[0-9a-fA-F:]+:[0-9a-fA-F:]+$");

    private BypassPatternParser() {
    }

    /**
     * Parses one line of user input.
     *
     * @param raw the input line (may contain leading/trailing whitespace)
     * @return a parsed pattern, or {@code null} if the line is blank, a
     *         comment, or can't be recognized
     */
    public static Parsed parse(String raw) {
        String line = hostExpression(raw);
        if (line == null) {
            return null;
        }

        // An address, with its network or alone. Checked as the core reads
        // it: an octet over 255, a prefix over /32 or a short IPv6 string
        // used to reach the core as ip_cidr, and it refused the whole
        // configuration, so one mistyped line stopped every server from
        // connecting. Such a line matches nothing now; see problem().
        String address = addressOf(line);
        if (address != null) {
            return RoutingRuleCheck.isCidr(address) ? new Parsed(Kind.IP_CIDR, address) : null;
        }

        // Domain with wildcards
        boolean leadingStar = line.startsWith("*.") || line.startsWith(".");
        boolean trailingStar = line.endsWith(".*") || line.endsWith("*");
        // A star left once the ones at either end are gone: the first star's
        // place alone missed "*.a.*.b".
        String inner = line.startsWith("*") ? line.substring(1) : line;
        inner = inner.endsWith("*") ? inner.substring(0, inner.length() - 1) : inner;
        boolean hasMiddleStar = inner.contains("*");

        if (leadingStar && !trailingStar && !hasMiddleStar) {
            // *.example.com / .example.com → domain_suffix example.com
            String body = line.startsWith("*.")
                    ? line.substring(2)
                    : line.substring(1);
            // "." and "*." name no suffix: the core refuses an empty one
            // ("empty item is not allowed"), and with it the configuration.
            return body.isEmpty() ? null : new Parsed(Kind.DOMAIN_SUFFIX, ascii(body));
        }

        String bare = stripDots(line.replace("*", ""));
        if (bare.isEmpty()) {
            return null;
        }
        if (hasMiddleStar) {
            // A star between names stands for anything, dots included; a
            // keyword cannot say that ("mail.*.com" became "mailcom").
            return new Parsed(Kind.DOMAIN_REGEX, globToRegex(line));
        }
        if (line.contains("*")) {
            // Stars at the ends only: the rest is found anywhere in the name,
            // dots inside it kept, the ones beside a star dropped.
            return new Parsed(Kind.DOMAIN_KEYWORD, ascii(bare));
        }

        // Plain string — treat as exact domain
        return new Parsed(Kind.DOMAIN, ascii(bare));
    }

    /**
     * Why the core would refuse a line written as an address, worded in the
     * language of the UI; empty for any other line. Such a line is left out of
     * the configuration, and the Routing screen names it instead of saving.
     *
     * @param raw the input line
     * @return the reason, or empty
     */
    public static Optional<String> problem(String raw) {
        String line = hostExpression(raw);
        String address = line == null ? null : addressOf(line);
        return address == null || RoutingRuleCheck.isCidr(address)
                ? Optional.empty()
                : Optional.of(I18n.get("routing.rule.problem.cidr"));
    }

    /**
     * The host part of a line: lower-case, without a URL's scheme, path and
     * port. Null for a blank line or a comment.
     */
    private static String hostExpression(String raw) {
        if (raw == null) {
            return null;
        }
        String line = raw.trim().toLowerCase(Locale.ROOT);
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }
        // Strip URL scheme and path only when input clearly is a URL; otherwise
        // we'd chop off the CIDR suffix from 192.168.0.0/16.
        line = line.contains("://") ? stripUrl(line) : stripTrailingPort(line);
        return line.isEmpty() ? null : line;
    }

    /**
     * The network a line shaped like an address stands for, a bare address as
     * a /32 or /128 one; null for a line of any other shape. Whether the core
     * can read it is asked separately.
     */
    private static String addressOf(String line) {
        if (IPV4_CIDR.matcher(line).matches() || IPV6_CIDR.matcher(line).matches()) {
            return line;
        }
        if (IPV4.matcher(line).matches()) {
            return line + "/32";
        }
        if (IPV6.matcher(line).matches()) {
            return line + "/128";
        }
        return null;
    }

    /** Trims the dots a star leaves at either end: {@code *.google.*} → {@code google}. */
    private static String stripDots(String s) {
        int from = 0;
        int to = s.length();
        while (from < to && s.charAt(from) == '.') {
            from++;
        }
        while (to > from && s.charAt(to - 1) == '.') {
            to--;
        }
        return s.substring(from, to);
    }

    /**
     * A name typed into a custom rule as the core compares it: lower case,
     * internationalized labels in punycode, a leading dot kept. Shared with
     * the bypass list so that the two cannot drift apart again.
     *
     * @param name a domain, suffix or keyword as stored
     * @return the name to write into the configuration
     */
    static String coreName(String name) {
        String lower = name.strip().toLowerCase(Locale.ROOT);
        // IDN refuses the empty label before a leading dot.
        return lower.startsWith(".") ? "." + ascii(lower.substring(1)) : ascii(lower);
    }

    /**
     * The name as the core compares it: punycode for an internationalized
     * one, which is all SNI and DNS carry. A name IDN cannot convert is kept
     * as typed.
     */
    private static String ascii(String name) {
        try {
            return IDN.toASCII(name, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException notConvertible) {
            return name;
        }
    }

    /**
     * An anchored regex for a glob: each run between stars converted to
     * punycode where it is a whole name and quoted, each star any text.
     */
    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        StringBuilder literal = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*') {
                regex.append(quote(literal.toString()));
                literal.setLength(0);
                regex.append(".*");
            } else {
                literal.append(c);
            }
        }
        regex.append(quote(literal.toString())).append('$');
        return regex.toString();
    }

    /**
     * A run of name characters as regex: labels in punycode, dots and any
     * other punctuation escaped. A converted label holds letters, digits and
     * hyphens alone, none of which a regex reads specially.
     */
    private static String quote(String run) {
        StringBuilder out = new StringBuilder();
        String[] labels = run.split("\\.", -1);
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) {
                out.append("\\.");
            }
            String label = labels[i].isEmpty() ? "" : ascii(labels[i]);
            for (char c : label.toCharArray()) {
                if (!Character.isLetterOrDigit(c) && c != '-') {
                    out.append('\\');
                }
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Strips a trailing {@code :port} from a non-URL host expression. Leaves
     * IPv6 ({@code ::}) and CIDR ({@code /}) forms untouched.
     */
    private static String stripTrailingPort(String s) {
        int firstColon = s.indexOf(':');
        int lastColon = s.lastIndexOf(':');
        if (firstColon < 0 || firstColon != lastColon) {
            return s; // no colon, or multiple colons (likely IPv6)
        }
        String port = s.substring(lastColon + 1);
        if (!port.matches("\\d+")) {
            return s;
        }
        return s.substring(0, lastColon);
    }

    private static String stripUrl(String s) {
        int schemeIdx = s.indexOf("://");
        if (schemeIdx >= 0) {
            s = s.substring(schemeIdx + 3);
        }
        int slashIdx = s.indexOf('/');
        if (slashIdx >= 0) {
            s = s.substring(0, slashIdx);
        }
        int atIdx = s.indexOf('@');
        if (atIdx >= 0) {
            s = s.substring(atIdx + 1);
        }
        int colonIdx = s.lastIndexOf(':');
        // Only strip trailing :port if there's NO colon elsewhere (not IPv6)
        if (colonIdx > 0 && s.indexOf(':') == colonIdx) {
            String maybePort = s.substring(colonIdx + 1);
            if (maybePort.matches("\\d+")) {
                s = s.substring(0, colonIdx);
            }
        }
        return s.trim();
    }

    /** The sing-box route-rule kind a pattern maps to. */
    public enum Kind {
        DOMAIN,
        DOMAIN_SUFFIX,
        DOMAIN_KEYWORD,
        DOMAIN_REGEX,
        IP_CIDR
    }

    /** Parsed representation of a single bypass-list entry. */
    public record Parsed(Kind kind, String value) {
        public Parsed {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(value);
        }
    }
}
