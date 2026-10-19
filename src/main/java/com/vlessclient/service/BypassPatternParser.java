package com.vlessclient.service;

import com.vlessclient.app.I18n;
import java.net.IDN;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    private static final int ZERO_WIDTH_NON_JOINER = 0x200C;
    private static final int ZERO_WIDTH_JOINER = 0x200D;

    /** The scripts whose letters join one another, as Unicode's ArabicShaping.txt lists them. */
    private static final Set<Character.UnicodeScript> JOINING_SCRIPTS = Set.of(
            Character.UnicodeScript.ARABIC, Character.UnicodeScript.SYRIAC,
            Character.UnicodeScript.NKO, Character.UnicodeScript.MONGOLIAN,
            Character.UnicodeScript.MANDAIC, Character.UnicodeScript.MANICHAEAN,
            Character.UnicodeScript.PSALTER_PAHLAVI, Character.UnicodeScript.SOGDIAN,
            Character.UnicodeScript.OLD_UYGHUR, Character.UnicodeScript.CHORASMIAN,
            Character.UnicodeScript.ADLAM, Character.UnicodeScript.HANIFI_ROHINGYA,
            Character.UnicodeScript.PHAGS_PA);

    /**
     * The letters of the Arabic block that do not join the letter after them,
     * joining type R, U or C in ArabicShaping.txt: alef, dal, reh, waw and
     * their kin, hamza and tatweel. Persian typists put a non-joiner after
     * them, where it does nothing, and CONTEXTJ refuses it there. Ranges,
     * first and last.
     */
    private static final int[] ARABIC_NOT_JOINING_FORWARD = {
        0x0621, 0x0625, 0x0627, 0x0627, 0x0629, 0x0629, 0x062F, 0x0632, 0x0640, 0x0640,
        0x0648, 0x0648, 0x0671, 0x0677, 0x0688, 0x0699, 0x06C0, 0x06C0, 0x06C3, 0x06CB,
        0x06CD, 0x06CD, 0x06CF, 0x06CF, 0x06D2, 0x06D3, 0x06D5, 0x06D5, 0x06EE, 0x06EF};

    /** The letters of the Arabic block that do not join the one before them: hamzas, tatweel. */
    private static final Set<Integer> ARABIC_NOT_JOINING_BACKWARD = Set.of(0x0621, 0x0640, 0x0674);

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
        String line = lowerCase(withoutFormatCharacters(raw).trim());
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

    /**
     * The line without the invisible format characters that text copied out
     * of right-to-left writing carries (U+200E and U+200F, U+202A to U+202E,
     * U+2066 to U+2069) and the like: no name has one, and IDN refuses a label
     * that does, so the line kept it and matched nothing, or, in a glob, made
     * a regex the core refuses. The joiners some scripts spell names with
     * stay; the ones no browser keeps go when the name is put into punycode.
     */
    private static String withoutFormatCharacters(String raw) {
        StringBuilder line = new StringBuilder(raw.length());
        raw.codePoints()
                .filter(c -> Character.getType(c) != Character.FORMAT
                        || c == ZERO_WIDTH_NON_JOINER || c == ZERO_WIDTH_JOINER)
                .forEach(line::appendCodePoint);
        return line.toString();
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
        String lower = lowerCase(name.strip());
        // IDN refuses the empty label before a leading dot.
        return lower.startsWith(".") ? "." + ascii(lower.substring(1)) : ascii(lower);
    }

    /**
     * Lower case as UTS #46 has it, which browsers follow: as the locale-free
     * lower case, but a capital sigma is the medial one wherever it stands,
     * where Java's makes it final at the end of a word, and a capital sharp s
     * is "ss".
     */
    private static String lowerCase(String name) {
        return name.replace("Σ", "σ").replace("ẞ", "ss").toLowerCase(Locale.ROOT);
    }

    /**
     * The name as the core compares it: punycode for an internationalized
     * one, which is all SNI and DNS carry, spelled as browsers spell it. A
     * name IDN cannot convert is kept as typed.
     *
     * <p>Java's IDN is IDNA 2003, which maps a German sharp s to "ss", a final
     * sigma to a medial one and drops a joiner; browsers follow UTS #46
     * without transitional processing, which keeps all four. "straße.de"
     * became strasse.de, another registrable domain, while browsers send
     * xn--strae-oqa.de. A label holding one of the four is put in punycode
     * as it is; the rest of what IDN maps, both read alike.</p>
     */
    private static String ascii(String typed) {
        String name = withoutStrayJoiners(typed);
        if (name.codePoints().noneMatch(BypassPatternParser::isDeviation)) {
            return idnaAscii(name);
        }
        StringBuilder out = new StringBuilder();
        // The dots IDN reads as a name's dots.
        String[] labels = name.split("[.。．｡]", -1);
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) {
                out.append('.');
            }
            String label = labels[i];
            out.append(label.codePoints().anyMatch(BypassPatternParser::isDeviation)
                    ? Punycode.label(Normalizer.normalize(label, Normalizer.Form.NFC))
                    : label.isEmpty() ? "" : idnaAscii(label));
        }
        return out.toString();
    }

    private static String idnaAscii(String name) {
        try {
            return IDN.toASCII(name, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException notConvertible) {
            return name;
        }
    }

    /**
     * The characters UTS #46 keeps where IDNA 2003 maps them: the sharp s,
     * the final sigma, and the zero-width non-joiner and joiner.
     */
    private static boolean isDeviation(int c) {
        return c == 0x00df || c == 0x03c2 || c == ZERO_WIDTH_NON_JOINER
                || c == ZERO_WIDTH_JOINER;
    }

    /**
     * The name without the joiners no browser keeps. A host may hold a
     * zero-width joiner or non-joiner only where RFC 5892's CONTEXTJ rules
     * allow it, which UTS #46 CheckJoiners and so the WHATWG URL standard
     * apply: right after a virama, or, the non-joiner, between letters that
     * join. Elsewhere browsers refuse the host. Such a joiner comes along
     * with a name copied out of a messenger, or out of a post hiding it from
     * filters, and was put into punycode with it: rutracker.org with a
     * non-joiner before its dot became xn--rutracker-y69d.org, a name no
     * client sends, so the rule matched nothing, and nothing said why.
     * Dropped, as IDNA 2003 did.
     *
     * <p>Which letters join is read from their script, Arabic, Syriac, N'Ko,
     * Mongolian and the like, since Java has no joining types; in the Arabic
     * block, which Persian, Arabic and Urdu are written in, the letters that
     * join on one side only are known as well. Elsewhere, a non-joiner beside
     * a letter that does not join it is kept although CONTEXTJ refuses it; no
     * registry takes such a name either.</p>
     */
    private static String withoutStrayJoiners(String name) {
        if (name.indexOf(ZERO_WIDTH_NON_JOINER) < 0 && name.indexOf(ZERO_WIDTH_JOINER) < 0) {
            return name;
        }
        int[] points = name.codePoints().toArray();
        StringBuilder kept = new StringBuilder(name.length());
        for (int i = 0; i < points.length; i++) {
            boolean joiner = points[i] == ZERO_WIDTH_NON_JOINER || points[i] == ZERO_WIDTH_JOINER;
            if (!joiner || joinerAllowed(points, i)) {
                kept.appendCodePoint(points[i]);
            }
        }
        return kept.toString();
    }

    /** RFC 5892 appendix A.1 and A.2 for the joiner at {@code at}, as described above. */
    private static boolean joinerAllowed(int[] points, int at) {
        if (at > 0 && isVirama(points[at - 1])) {
            return true;
        }
        if (points[at] == ZERO_WIDTH_JOINER) {
            return false;
        }
        // A joining letter on either side, with marks between it and the
        // non-joiner skipped: they are transparent to joining.
        int before = at - 1;
        while (before >= 0 && isTransparent(points[before])) {
            before--;
        }
        int after = at + 1;
        while (after < points.length && isTransparent(points[after])) {
            after++;
        }
        return before >= 0 && after < points.length
                && isJoiningLetter(points[before]) && !isArabicNotJoiningForward(points[before])
                && isJoiningLetter(points[after])
                && !ARABIC_NOT_JOINING_BACKWARD.contains(points[after]);
    }

    private static boolean isArabicNotJoiningForward(int c) {
        for (int i = 0; i < ARABIC_NOT_JOINING_FORWARD.length; i += 2) {
            if (c >= ARABIC_NOT_JOINING_FORWARD[i] && c <= ARABIC_NOT_JOINING_FORWARD[i + 1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a character is a virama, canonical combining class 9, which
     * Java does not expose: canonical ordering moves a mark of a higher class
     * after one of a lower, so a virama goes after U+3099 (class 8) and
     * before U+05B0 (class 10), and no other class does both.
     */
    private static boolean isVirama(int c) {
        String mark = Character.toString(c);
        String eight = Character.toString(0x3099);
        String ten = Character.toString(0x05b0);
        return Normalizer.normalize("a" + mark + eight, Normalizer.Form.NFD)
                .equals("a" + eight + mark)
                && Normalizer.normalize("a" + ten + mark, Normalizer.Form.NFD)
                .equals("a" + mark + ten);
    }

    private static boolean isTransparent(int c) {
        int type = Character.getType(c);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK;
    }

    private static boolean isJoiningLetter(int c) {
        return Character.isLetter(c) && JOINING_SCRIPTS.contains(Character.UnicodeScript.of(c));
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
     * hyphens alone, none of which a regex reads specially. What IDN could
     * not convert is written as the code point, which Go reads anywhere: a
     * backslash before a character outside ASCII is an escape it refuses, and
     * put before each half of a character outside the BMP, it left the
     * configuration impossible to write.
     */
    private static String quote(String run) {
        StringBuilder out = new StringBuilder();
        String[] labels = run.split("\\.", -1);
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) {
                out.append("\\.");
            }
            String label = labels[i].isEmpty() ? "" : ascii(labels[i]);
            label.codePoints().forEach(c -> {
                if (c > 0x7f) {
                    out.append("\\x{").append(Integer.toHexString(c)).append('}');
                    return;
                }
                if (!Character.isLetterOrDigit(c) && c != '-') {
                    out.append('\\');
                }
                out.appendCodePoint(c);
            });
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
