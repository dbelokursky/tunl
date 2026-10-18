package com.vlessclient.service;

import com.vlessclient.app.I18n;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.RoutingRule;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.Inet6Address;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What sing-box accepts as the value of a routing rule.
 *
 * <p>The core refuses a whole configuration over one rule it cannot parse, so
 * a regular expression with an unclosed bracket or a CIDR with an octet over
 * 255 stopped every server from connecting until the rule was found and
 * deleted. The rules here follow sing-box 1.14's own answers, and
 * {@code SingBoxRealBinarySmokeTest} checks that they still agree with the
 * pinned core.</p>
 */
public final class RoutingRuleCheck {

    private static final Logger log = LoggerFactory.getLogger(RoutingRuleCheck.class);

    /** An IPv4 field as Go reads it: 0 to 255, without a leading zero. */
    private static final String OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9][0-9]|[0-9])";

    private static final Pattern IPV4 = Pattern.compile(OCTET + "(?:\\." + OCTET + "){3}");

    /** Prefix bits as Go reads them: decimal, without a sign or a leading zero. */
    private static final Pattern BITS = Pattern.compile("0|[1-9][0-9]{0,2}");

    /** The names of the rule-sets the app downloads: lower case, as the repository has them. */
    private static final Pattern RULE_SET_NAME = Pattern.compile("[a-z0-9!@._-]+");

    /** The letters Go's regexp takes after a backslash; the rest is a syntax error there. */
    private static final String RE2_ESCAPE_LETTERS = "afnrtvdDsSwWbBAzpPxQE";

    /**
     * The names after {@code \p} that both take: Unicode's general categories,
     * and the few POSIX names Go 1.25 knows as aliases of them. Java also takes
     * Lower, Alpha, IsLatin, InGreek, javaLowerCase and more, which Go refuses
     * ("invalid character class range"); Go takes script names, which Java
     * refuses before this is asked.
     */
    private static final Set<String> RE2_PROPERTY_NAMES = Set.of(
            "C", "Cc", "Cf", "Cn", "Co", "Cs", "L", "LC", "Ll", "Lm", "Lo", "Lt", "Lu",
            "M", "Mc", "Me", "Mn", "N", "Nd", "Nl", "No", "P", "Pc", "Pd", "Pe", "Pf",
            "Pi", "Po", "Ps", "S", "Sc", "Sk", "Sm", "So", "Z", "Zl", "Zp", "Zs",
            "ASCII", "Cntrl", "Digit", "Punct");

    /** Go's limit on a repeat count, and on the counts of nested repeats multiplied. */
    private static final int RE2_MAX_REPEAT = 1000;

    /**
     * A repeat as Go reads one: {n}, {n,} or {n,m}, each count without a
     * leading zero. Anything else is a literal brace: {01} as well, which
     * Java reads as a count.
     */
    private static final Pattern REPEAT =
            Pattern.compile("\\{(0|[1-9][0-9]*)(,(0|[1-9][0-9]*)?)?}");

    /** The inline flags Go has, as in (?i) or (?s-m:, with a flag after any "-". */
    private static final Pattern RE2_FLAGS = Pattern.compile("[imsU]*(?:-[imsU]+)?");

    private RoutingRuleCheck() {
    }

    /**
     * Why the core would refuse this value for a rule of this type, worded in
     * the language of the UI, or empty when it accepts it.
     *
     * @param type  the rule's type
     * @param value the rule's value, as it will be stored
     * @return the reason, or empty
     */
    public static Optional<String> problem(RoutingRule.RuleType type, String value) {
        if (type == null) {
            return Optional.empty();
        }
        if (value == null || value.isBlank()) {
            return Optional.of(I18n.get("routing.rule.problem.empty"));
        }
        return switch (type) {
            // The core takes a space in a domain, but no hostname has one:
            // such a rule would never match.
            case DOMAIN, DOMAIN_SUFFIX, DOMAIN_KEYWORD ->
                    value.chars().anyMatch(Character::isWhitespace)
                            ? Optional.of(I18n.get("routing.rule.problem.space"))
                            : nameProblem(type, value);
            case DOMAIN_REGEX -> regexProblem(value);
            case IP_CIDR -> isCidr(value)
                    ? Optional.empty()
                    : Optional.of(I18n.get("routing.rule.problem.cidr"));
            case GEOSITE, GEOIP -> listProblem(type, ruleSetName(value));
        };
    }

    /**
     * A domain, suffix or keyword as the core compares it: lower case,
     * internationalized labels in punycode, a star read as the bypass list
     * reads it and the root's final dot dropped. The core has no wildcard and
     * compares the text, so "*.example.org", the bypass list's way to say
     * every name under example.org, matched nothing as a suffix rule.
     *
     * @param type  {@code DOMAIN}, {@code DOMAIN_SUFFIX} or {@code DOMAIN_KEYWORD}
     * @param value the rule's value, one {@link #problem} takes
     * @return the value to write into the configuration
     */
    public static String coreName(RoutingRule.RuleType type, String value) {
        String name = withoutWildcards(type, value.strip().toLowerCase(Locale.ROOT));
        return BypassPatternParser.coreName(name == null ? value : name);
    }

    /**
     * Why the core would not match this name as {@code type} says: a star it
     * cannot read, or nothing left once the stars and the root's dot are
     * gone. The core refuses an empty domain or suffix, and an empty keyword
     * is in every name.
     */
    private static Optional<String> nameProblem(RoutingRule.RuleType type, String value) {
        String name = withoutWildcards(type, value.strip().toLowerCase(Locale.ROOT));
        if (name != null && !name.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(switch (type) {
            case DOMAIN -> I18n.get("routing.rule.problem.name.domain");
            case DOMAIN_SUFFIX -> I18n.get("routing.rule.problem.name.suffix");
            default -> I18n.get("routing.rule.problem.name.keyword");
        });
    }

    /**
     * The name with the stars its type can read dropped, as the bypass list
     * drops them: "*." in front of a suffix (every name under the rest), and
     * stars at either end of a keyword, with the dots beside them. Null where
     * a star is left: in a domain, which matches one name, or in the middle.
     * A domain does not start with a dot either; a suffix that does is the
     * core's own, for the names under it but not itself.
     */
    private static String withoutWildcards(RoutingRule.RuleType type, String name) {
        return switch (type) {
            case DOMAIN -> name.contains("*") || name.startsWith(".")
                    ? null : withoutRootDot(name);
            case DOMAIN_SUFFIX -> {
                String suffix = name.startsWith("*.") ? name.substring(2) : name;
                yield suffix.contains("*") ? null : withoutRootDot(suffix);
            }
            case DOMAIN_KEYWORD -> {
                if (!name.contains("*")) {
                    yield name;
                }
                int from = 0;
                int to = name.length();
                while (from < to && name.charAt(from) == '*') {
                    from++;
                }
                while (to > from && name.charAt(to - 1) == '*') {
                    to--;
                }
                String keyword = name.substring(from, to);
                yield keyword.contains("*") ? null : stripDots(keyword);
            }
            default -> name;
        };
    }

    /** The name without the root's dot at its end, which no name a connection carries has. */
    private static String withoutRootDot(String name) {
        int end = name.length();
        while (end > 0 && name.charAt(end - 1) == '.') {
            end--;
        }
        return name.substring(0, end);
    }

    /** The name without the dots at either end that stood beside a star. */
    private static String stripDots(String name) {
        int from = 0;
        while (from < name.length() && name.charAt(from) == '.') {
            from++;
        }
        return withoutRootDot(name.substring(from));
    }

    /**
     * Why there is no list of this name to download. The core fetches a rule's
     * list when it starts and does not start without it, and a name the
     * repository does not publish, such as the typo "youtub", was a 404: every
     * server failed to connect, with a message that read like a blocked
     * network, until the rule was found and deleted.
     */
    private static Optional<String> listProblem(RoutingRule.RuleType type, String name) {
        if (!RULE_SET_NAME.matcher(name).matches()) {
            return Optional.of(I18n.get("routing.rule.problem.rule.set"));
        }
        if (type == RoutingRule.RuleType.GEOSITE) {
            return GeositeLists.published(name)
                    ? Optional.empty()
                    : Optional.of(I18n.get("routing.rule.problem.geosite", name));
        }
        // sing-geoip has one list for each country with addresses of its own.
        return RoutingConfig.isBypassCountry(name)
                ? Optional.empty()
                : Optional.of(I18n.get("routing.rule.problem.geoip", name));
    }

    /** The lists sing-geosite publishes, as {@code geosite-lists.txt} has them. */
    private static final class GeositeLists {

        private static final Set<String> NAMES = read();

        private GeositeLists() {
        }

        static boolean published(String name) {
            // Without the file every name passes, as before it existed: it
            // must not leave out each Geosite rule the user has.
            return NAMES.isEmpty() || NAMES.contains(name);
        }

        private static Set<String> read() {
            try (InputStream in = RoutingRuleCheck.class.getResourceAsStream(
                    "/geosite-lists.txt")) {
                if (in == null) {
                    log.error("geosite-lists.txt is missing; Geosite names go unchecked");
                    return Set.of();
                }
                try (BufferedReader lines = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    return lines.lines().map(String::strip)
                            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                            .collect(Collectors.toUnmodifiableSet());
                }
            } catch (IOException | UncheckedIOException e) {
                log.error("Could not read geosite-lists.txt; Geosite names go unchecked", e);
                return Set.of();
            }
        }
    }

    /**
     * A geosite or geoip name as the rule-set repository spells it: in lower
     * case. The download of "Google" failed with a 404, and a core that cannot
     * load a rule-set does not start.
     *
     * @param value the name as stored
     * @return the name to build the rule-set's tag and URL from
     */
    public static String ruleSetName(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * A regular expression for Go's regexp. Java compiles it first, which
     * catches what both refuse, such as an unclosed bracket; then the
     * constructs only Java has are looked for.
     */
    private static Optional<String> regexProblem(String regex) {
        try {
            // Go also takes the (?P<name>...) form of a named group; Java does not.
            Pattern.compile(regex.replace("(?P<", "(?<"));
        } catch (PatternSyntaxException e) {
            return Optional.of(I18n.get("routing.rule.problem.regex"));
        }
        String construct = re2Unsupported(regex);
        return construct == null
                ? Optional.empty()
                : Optional.of(I18n.get("routing.rule.problem.regex.go", construct));
    }

    /**
     * The first construct Go's regexp refuses although Java's takes it:
     * lookaround, an atomic group, a possessive quantifier, a back-reference,
     * an escape Go does not have ({@code \Z}, {@code \G}, {@code \h} and the
     * like, or any character outside ASCII), a property name only Java knows
     * ({@code \p{Lower}}, {@code \p{IsLatin}}), an inline flag only Java has
     * ({@code (?x)}, {@code (?u)}), a repeat of a repeat ({@code a{2}{3}}),
     * or repeat counts over Go's limit of 1000, alone or multiplied along
     * nested repeats ({@code (a{100}){100}}). Each of those passed here and
     * then stopped every connect. Null when there is none.
     */
    private static String re2Unsupported(String regex) {
        boolean inClass = false;
        // Per open group, the largest product of nested repeat counts among
        // its finished atoms; the whole expression is the first.
        Deque<int[]> groups = new ArrayDeque<>();
        groups.push(new int[] {1});
        // The atom a repeat would apply to. It counts in its group only once
        // the next one starts, since a {0} after it still takes it out: Go
        // does not look inside a repeat of at most zero, and refused nothing
        // in ((a{500}){0}){3}, which was refused here.
        int atomWeight = 1;
        boolean afterRepeat = false;
        int length = regex.length();
        for (int i = 0; i < length; i++) {
            char c = regex.charAt(i);
            if (c == '\\' && i + 1 < length) {
                settle(groups, atomWeight);
                char next = regex.charAt(i + 1);
                atomWeight = 1;
                afterRepeat = false;
                if (next == 'Q') {
                    // Literal text up to \E, or to the end.
                    int end = regex.indexOf("\\E", i + 2);
                    i = end < 0 ? length : end + 1;
                    continue;
                }
                String escape = regex.substring(i, i + 2);
                if (next > 0x7f) {
                    // Go takes an escaped ASCII punctuation mark alone.
                    return escape;
                }
                if (Character.isDigit(next)) {
                    // Octal: \0, or \1-\7 followed by an octal digit. Else a back-reference.
                    boolean octal = next == '0' || next <= '7' && i + 2 < length
                            && regex.charAt(i + 2) >= '0' && regex.charAt(i + 2) <= '7';
                    if (!octal) {
                        return escape;
                    }
                } else if (next == 'p' || next == 'P') {
                    boolean braced = i + 2 < length && regex.charAt(i + 2) == '{';
                    int end = braced ? regex.indexOf('}', i + 3) : Math.min(i + 2, length - 1);
                    String name = braced
                            ? (end < 0 ? "" : regex.substring(i + 3, end))
                            : regex.substring(end, end + 1);
                    if (!RE2_PROPERTY_NAMES.contains(name)) {
                        return end < 0 ? escape : regex.substring(i, end + 1);
                    }
                    i = end;
                    continue;
                } else if (next == 'x' && i + 2 < length && regex.charAt(i + 2) == '{') {
                    // A code point, as in \x{2014}: its braces hold no repeat
                    // count. Read as one, \x{2014} was over Go's 1000 and
                    // \x{41}{2} a repeat of a repeat, so a stored rule the core
                    // takes was left out. Java compiled it: the brace is closed.
                    int close = regex.indexOf('}', i + 3);
                    i = close < 0 ? length : close;
                    continue;
                } else if (Character.isLetter(next)
                        && RE2_ESCAPE_LETTERS.indexOf(next) < 0) {
                    return escape;
                }
                i++;
                continue;
            }
            if (inClass) {
                inClass = c != ']';
                continue;
            }
            if (c == '[') {
                inClass = true;
                settle(groups, atomWeight);
                atomWeight = 1;
                afterRepeat = false;
                // A ] straight after [ or [^ belongs to the class.
                if (i + 1 < length && regex.charAt(i + 1) == '^') {
                    i++;
                }
                if (i + 1 < length && regex.charAt(i + 1) == ']') {
                    i++;
                }
                continue;
            }
            for (String group : new String[] {"(?=", "(?!", "(?<=", "(?<!", "(?>"}) {
                if (regex.startsWith(group, i)) {
                    return group;
                }
            }
            String flags = c == '(' ? unsupportedFlags(regex, i) : null;
            if (flags != null) {
                return flags;
            }
            if ((c == '*' || c == '+' || c == '?') && i + 1 < length
                    && regex.charAt(i + 1) == '+' && !isGroupFlag(regex, i)) {
                return regex.substring(i, i + 2);
            }
            Matcher repeat = c == '{' ? REPEAT.matcher(regex).region(i, length) : null;
            if (repeat != null && repeat.lookingAt()) {
                String text = repeat.group();
                if (repeat.end() < length && regex.charAt(repeat.end()) == '+') {
                    return text + "+";
                }
                int min = repeatCount(repeat.group(1));
                int max = repeat.group(2) == null ? min
                        : repeat.group(3) == null ? -1 : repeatCount(repeat.group(3));
                // Go counts an open-ended repeat by its minimum.
                int count = max < 0 ? min : max;
                if (afterRepeat || min > RE2_MAX_REPEAT || max > RE2_MAX_REPEAT
                        || count >= 2 && (long) count * atomWeight > RE2_MAX_REPEAT) {
                    return text;
                }
                atomWeight = max == 0 ? 0 : Math.max(1, count) * atomWeight;
                afterRepeat = true;
                i = repeat.end() - 1;
                continue;
            }
            if (c == '*' || c == '+' || c == '?') {
                // Lazy after a repeat, or a repeat of its own: either way the
                // next {n} would repeat a repeat. Any count passes through.
                afterRepeat = true;
                continue;
            }
            afterRepeat = false;
            settle(groups, atomWeight);
            if (c == '(') {
                groups.push(new int[] {1});
                atomWeight = 1;
            } else if (c == ')' && groups.size() > 1) {
                atomWeight = groups.pop()[0];
            } else {
                atomWeight = 1;
            }
        }
        return null;
    }

    /** Counts the atom just finished in its group's largest product. */
    private static void settle(Deque<int[]> groups, int atomWeight) {
        int[] group = groups.peek();
        group[0] = Math.max(group[0], atomWeight);
    }

    /**
     * The inline flags opened at {@code index}, as in (?x) or (?i-:, when Go
     * refuses them; null for any other group, named groups included. Java
     * also has c, d, u and x, and takes a "-" with no flag after it: such a
     * rule passed here, and the core refused the whole configuration over it.
     */
    private static String unsupportedFlags(String regex, int index) {
        int start = index + 2;
        if (!regex.startsWith("(?", index) || start >= regex.length()
                || regex.charAt(start) == '<' || regex.charAt(start) == 'P') {
            return null;
        }
        // Java compiled it, so the flags run to a ) or a :.
        int end = start;
        while (end < regex.length() && regex.charAt(end) != ')' && regex.charAt(end) != ':') {
            end++;
        }
        return RE2_FLAGS.matcher(regex.substring(start, end)).matches()
                ? null
                : regex.substring(index, Math.min(end + 1, regex.length()));
    }

    /** A repeat count's digits as a number; more digits than Go's limit has, over it. */
    private static int repeatCount(String digits) {
        return digits.length() > 4 ? Integer.MAX_VALUE : Integer.parseInt(digits);
    }

    /** Whether the ? at {@code index} opens a group's flags, as in (?:, rather than quantifying. */
    private static boolean isGroupFlag(String regex, int index) {
        return regex.charAt(index) == '?' && index > 0 && regex.charAt(index - 1) == '(';
    }

    /**
     * A CIDR as sing-box parses it: an IPv4 or IPv6 address, with or without
     * a prefix length. Nothing around it: the core refuses a space. The bypass
     * list reads its addresses with this too.
     */
    static boolean isCidr(String value) {
        int slash = value.indexOf('/');
        String address = slash < 0 ? value : value.substring(0, slash);
        String bits = slash < 0 ? null : value.substring(slash + 1);
        int maxBits;
        if (isIpv4Address(address)) {
            maxBits = 32;
        } else if (isIpv6Address(address)) {
            maxBits = 128;
        } else {
            return false;
        }
        if (bits == null) {
            return true;
        }
        return BITS.matcher(bits).matches() && Integer.parseInt(bits) <= maxBits;
    }

    /** An IPv4 address as Go reads one: four fields of 0 to 255, none with a leading zero. */
    static boolean isIpv4Address(String address) {
        return IPV4.matcher(address).matches();
    }

    /** An IPv6 address as Go reads one, with or without a zone. */
    static boolean isIpv6Address(String address) {
        int zone = address.indexOf('%');
        String bare = zone < 0 ? address : address.substring(0, zone);
        if (!bare.contains(":") || !bare.matches("[0-9A-Fa-f:.]+")) {
            return false;
        }
        try {
            // A literal only: nothing is looked up.
            Inet6Address.ofLiteral(bare);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
