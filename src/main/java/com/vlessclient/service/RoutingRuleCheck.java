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

    /** The POSIX classes Go has, as in [[:alpha:]] or [[:^digit:]]. */
    private static final Set<String> RE2_POSIX_CLASSES = Set.of(
            "alnum", "alpha", "ascii", "blank", "cntrl", "digit", "graph", "lower", "print",
            "punct", "space", "upper", "word", "xdigit");

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

    /**
     * What {@link #re2Unsupported} names for a "[" inside a class that opens
     * no POSIX class; no other construct it names is a lone "[".
     */
    private static final String NESTED_CLASS = "[";

    private RoutingRuleCheck() {
    }

    /**
     * Why the core would refuse this value for a rule of this type, or could
     * not read the rule from it, worded in the language of the UI; empty when
     * it accepts it. A stored rule with a problem is left out of the
     * configuration, so that the core still starts.
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
     * Why a new rule with this value is not added: its {@link #problem}, or a
     * regular expression no name can match as the core compares names. The
     * core takes the latter, and a stored one stays in the configuration,
     * where it does no harm; only the Routing dialog, RoutingService and MCP
     * refuse it.
     *
     * @param type  the rule's type
     * @param value the rule's value, as it will be stored
     * @return the reason, in the language of the UI, or empty
     */
    public static Optional<String> refusal(RoutingRule.RuleType type, String value) {
        Optional<String> problem = problem(type, value);
        if (problem.isPresent() || type != RoutingRule.RuleType.DOMAIN_REGEX) {
            return problem;
        }
        Letters letters = new Letters(value);
        return letters.neverMatches() ? letters.first() : Optional.empty();
    }

    /**
     * What may keep a rule the core takes from doing what it says, worded in
     * the language of the UI: a letter of a regular expression the core never
     * sees. Shown under the value in the Routing dialog, which still adds the
     * rule, and under a stored rule in the list; empty for a value with a
     * {@link #problem}, which says more.
     *
     * @param type  the rule's type
     * @param value the rule's value, as it is stored
     * @return the hint, or empty
     */
    public static Optional<String> hint(RoutingRule.RuleType type, String value) {
        if (type != RoutingRule.RuleType.DOMAIN_REGEX || problem(type, value).isPresent()) {
            return Optional.empty();
        }
        return new Letters(value).first();
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
        // Lower case is the name's own to do: Java's makes a capital sigma a
        // final one at a word's end, which browsers do not send.
        String name = withoutWildcards(type, value.strip());
        return BypassPatternParser.coreName(name == null ? value : name);
    }

    /**
     * Why the core would not match this name as {@code type} says: a star it
     * cannot read, or nothing left once the stars and the root's dot are
     * gone. The core refuses an empty domain or suffix, and an empty keyword
     * is in every name.
     */
    private static Optional<String> nameProblem(RoutingRule.RuleType type, String value) {
        String name = withoutWildcards(type, value.strip());
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
        // "ru-blocked" names the runetfreedom list the blocked-only mode
        // downloads, and the generator fetches it from there: such a rule was
        // refused here, and one stored by 1.25 left out of the configuration,
        // although the core loaded it.
        String kind = type == RoutingRule.RuleType.GEOSITE ? "geosite-" : "geoip-";
        if (SingBoxConfigGenerator.isBlockedInRussiaList(kind + name)) {
            return Optional.empty();
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
        if (construct == null) {
            return Optional.empty();
        }
        return Optional.of(NESTED_CLASS.equals(construct)
                ? I18n.get("routing.rule.problem.regex.nested")
                : I18n.get("routing.rule.problem.regex.go", construct));
    }

    /**
     * The letters a regular expression asks for that the core never sees: it
     * matches the name in lower case and in punycode, as SNI, DNS and a
     * browser's CONNECT carry it, so "^Mail\." or "\.рф$" passed the check,
     * the core took it, and the rule matched nothing. What a class, an
     * escape, a group's name or its flags hold is not a letter of the name,
     * and (?i) lets upper case through from where it stands to the end of its
     * group, alternatives after it included.
     *
     * <p>The expression is read as Go's parser reads it, which
     * {@link #problem} has found Java's to agree with, for whether every way
     * through it reads such a letter. An alternative without one, or a part
     * that may be left out, still matches: "\.(ru|рф)$" matches every .ru
     * name and "^m\.(VK|vk)\.com$" m.vk.com. Such a rule was refused and,
     * stored by 1.25, left out of the configuration, as one that could never
     * match.</p>
     */
    private static final class Letters {

        private final String regex;
        private int at;
        /** Whether (?i) is in effect where the reading stands. */
        private boolean anyCase;
        /** The first letter the core never sees, or -1. */
        private int first = -1;
        private final boolean never;

        Letters(String regex) {
            this.regex = regex;
            this.never = alternatives();
        }

        /** Whether no name as the core compares it can match: each way through reads one. */
        boolean neverMatches() {
            return never;
        }

        /** The first letter the core never sees, in a sentence of the UI's language. */
        Optional<String> first() {
            if (first < 0) {
                return Optional.empty();
            }
            String letter = Character.toString(first);
            return Optional.of(first > 0x7f
                    ? I18n.get("routing.rule.problem.regex.ascii", letter)
                    : I18n.get("routing.rule.problem.regex.case", letter));
        }

        /**
         * Reads alternatives up to the end, or to the ")" that closes their
         * group: whether each of them reads such a letter.
         */
        private boolean alternatives() {
            boolean every = true;
            // The alternative so far without its last atom, and that atom,
            // which a repeat after it may make optional.
            boolean before = false;
            boolean last = false;
            while (at < regex.length() && regex.charAt(at) != ')') {
                if (regex.charAt(at) == '|') {
                    every &= before || last;
                    before = false;
                    last = false;
                    at++;
                    continue;
                }
                int min = repeat();
                if (min >= 0) {
                    last &= min > 0;
                    continue;
                }
                if (regex.startsWith("\\Q", at)) {
                    // Literal text up to \E, or to the end: each character an
                    // atom of its own, so a repeat after it takes the last.
                    int end = regex.indexOf("\\E", at + 2);
                    int stop = end < 0 ? regex.length() : end;
                    for (int i = at + 2; i < stop; i += Character.charCount(regex.codePointAt(i))) {
                        before |= last;
                        last = unseen(regex.codePointAt(i));
                    }
                    at = end < 0 ? regex.length() : end + 2;
                    continue;
                }
                if (flagsAlone()) {
                    // (?i) and the like are no atom: as in Go, a repeat after
                    // them takes the atom before.
                    continue;
                }
                before |= last;
                last = atom();
            }
            return every && (before || last);
        }

        /**
         * The least count of the repeat that starts here, read past it with a
         * lazy "?" after it; -1, and nothing read, where none starts.
         */
        private int repeat() {
            char c = regex.charAt(at);
            int min;
            if (c == '*' || c == '?') {
                min = 0;
                at++;
            } else if (c == '+') {
                min = 1;
                at++;
            } else {
                Matcher count = c == '{' ? REPEAT.matcher(regex).region(at, regex.length()) : null;
                if (count == null || !count.lookingAt()) {
                    return -1;
                }
                min = repeatCount(count.group(1));
                at = count.end();
            }
            if (at < regex.length() && regex.charAt(at) == '?') {
                at++;
            }
            return min;
        }

        /** Reads past flags that stand alone, as in (?i) or (?-i), and applies them. */
        private boolean flagsAlone() {
            if (!regex.startsWith("(?", at)) {
                return false;
            }
            int end = at + 2;
            while (end < regex.length() && "imsU-".indexOf(regex.charAt(end)) >= 0) {
                end++;
            }
            if (end >= regex.length() || regex.charAt(end) != ')') {
                return false;
            }
            applyFlags(regex.substring(at + 2, end));
            at = end + 1;
            return true;
        }

        /** The flags before a "-" turn on, the ones after it off; only i matters here. */
        private void applyFlags(String flags) {
            int minus = flags.indexOf('-');
            if (flags.substring(0, minus < 0 ? flags.length() : minus).indexOf('i') >= 0) {
                anyCase = true;
            }
            if (minus >= 0 && flags.indexOf('i', minus) >= 0) {
                anyCase = false;
            }
        }

        /** Reads one atom: whether it is a letter the core never sees, or a group reading one. */
        private boolean atom() {
            char c = regex.charAt(at);
            if (c == '(') {
                return group();
            }
            if (c == '[') {
                at = classEnd(regex, at);
                return false;
            }
            if (c == '\\') {
                at = escapeEnd(at);
                return false;
            }
            int letter = regex.codePointAt(at);
            at += Character.charCount(letter);
            return unseen(letter);
        }

        /** Reads a group, its opening and closing included; flags set in it end with it. */
        private boolean group() {
            final boolean outside = anyCase;
            at++;
            if (at < regex.length() && regex.charAt(at) == '?') {
                // (?:, (?i:, (?P<name> or (?<name>: what opens the content.
                int end = at + 1;
                while (end < regex.length() && ":>".indexOf(regex.charAt(end)) < 0) {
                    end++;
                }
                if (end < regex.length() && regex.charAt(end) == ':') {
                    applyFlags(regex.substring(at + 1, end));
                }
                at = end + 1;
            }
            boolean reads = alternatives();
            if (at < regex.length()) {
                at++;
            }
            anyCase = outside;
            return reads;
        }

        /** The index past an escape: a class, an assertion or a character, none a letter here. */
        private int escapeEnd(int backslash) {
            int i = backslash + 1;
            if (i >= regex.length()) {
                return regex.length();
            }
            char c = regex.charAt(i);
            if ("xpP".indexOf(c) >= 0 && i + 1 < regex.length() && regex.charAt(i + 1) == '{') {
                int close = regex.indexOf('}', i + 2);
                return close < 0 ? regex.length() : close + 1;
            }
            if (c == 'x' || c == 'p' || c == 'P') {
                // \x41 carries two digits, \pL one letter.
                return Math.min(regex.length(), i + (c == 'x' ? 3 : 2));
            }
            if (c >= '0' && c <= '7') {
                // Octal, as Go reads it: up to three digits.
                int end = i + 1;
                while (end < Math.min(regex.length(), i + 3)
                        && regex.charAt(end) >= '0' && regex.charAt(end) <= '7') {
                    end++;
                }
                return end;
            }
            return i + Character.charCount(regex.codePointAt(i));
        }

        /**
         * Whether a literal is one the core never sees in a name: a capital
         * where (?i) is not in effect, or anything outside ASCII, unless (?i)
         * folds it to an ASCII letter, as it folds the Kelvin sign to k.
         */
        private boolean unseen(int c) {
            boolean unseen = c > 0x7f
                    ? !anyCase || !isAsciiLetter(Character.toLowerCase(c))
                            && !isAsciiLetter(Character.toUpperCase(c))
                    : !anyCase && c >= 'A' && c <= 'Z';
            if (unseen && first < 0) {
                first = c;
            }
            return unseen;
        }

        private static boolean isAsciiLetter(int c) {
            return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
        }
    }

    /**
     * The index past the character class that opens at {@code start}, as Go
     * reads one: a {@code ]} first belongs to it, and so does the {@code ]}
     * of a POSIX class such as {@code [:alpha:]} inside it.
     */
    private static int classEnd(String regex, int start) {
        int length = regex.length();
        int i = start + 1;
        if (i < length && regex.charAt(i) == '^') {
            i++;
        }
        if (i < length && regex.charAt(i) == ']') {
            i++;
        }
        while (i < length) {
            char c = regex.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (regex.startsWith("[:", i) && regex.indexOf(":]", i + 2) >= 0) {
                i = regex.indexOf(":]", i + 2) + 2;
                continue;
            }
            if (c == ']') {
                return i + 1;
            }
            i++;
        }
        return length;
    }

    /**
     * The first construct Go's regexp refuses although Java's takes it:
     * lookaround, an atomic group, a possessive quantifier, a back-reference,
     * an escape Go does not have ({@code \Z}, {@code \G}, {@code \h} and the
     * like, or any character outside ASCII), a property name only Java knows
     * ({@code \p{Lower}}, {@code \p{IsLatin}}), an inline flag only Java has
     * ({@code (?x)}, {@code (?u)}), a repeat of a repeat ({@code a{2}{3}}),
     * or repeat counts over Go's limit of 1000, alone or multiplied along
     * nested repeats ({@code (a{100}){100}}). A repeat with nothing before it
     * to repeat ({@code {2}} at the start, or after {@code |}, {@code (} or
     * {@code (?:}), a POSIX class Go does not have ({@code [[:alnum_:]]}) and
     * {@code \Q} inside a class, which Java reads as a literal brace, a
     * nested class and a quote. Each of those passed here and then stopped
     * every connect. A class inside a class ({@link #NESTED_CLASS}), which Go
     * does not have. Null when there is none.
     */
    private static String re2Unsupported(String regex) {
        boolean inClass = false;
        // In a class, as Go reads it: whether a character stands before, so
        // that a "-" after it opens a range, and whether what comes next ends
        // one.
        boolean classChar = false;
        boolean rangeEnd = false;
        // Something before a repeat for it to repeat: not the start of the
        // expression or of a group, nor a "|".
        boolean canRepeat = false;
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
                if (inClass) {
                    // A character's escape, but not a class's such as \d.
                    classChar = !rangeEnd && "dDsSwWpP".indexOf(next) < 0;
                    rangeEnd = false;
                }
                if (next == 'Q') {
                    if (inClass) {
                        return "\\Q";
                    }
                    // Literal text up to \E, or to the end; \Q\E holds nothing.
                    int end = regex.indexOf("\\E", i + 2);
                    canRepeat |= (end < 0 ? length : end) > i + 2;
                    i = end < 0 ? length : end + 1;
                    continue;
                }
                canRepeat = true;
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
                boolean endsRange = rangeEnd;
                rangeEnd = false;
                // A POSIX class, as in [[:alpha:]]: Go reads "[:" up to the
                // next ":]" as one where an item starts, and refuses a name it
                // does not have. As the end of a range "[" is a character.
                int posixEnd = !endsRange && regex.startsWith("[:", i)
                        ? regex.indexOf(":]", i + 2) : -1;
                if (posixEnd >= 0) {
                    String name = regex.substring(i + 2, posixEnd);
                    if (!RE2_POSIX_CLASSES.contains(
                            name.startsWith("^") ? name.substring(1) : name)) {
                        return regex.substring(i, posixEnd + 2);
                    }
                    classChar = false;
                    i = posixEnd + 1;
                    continue;
                }
                if (c == '[') {
                    // Any other "[" is a character to Go, where Java opens a
                    // class of its own and reads what follows it as that
                    // class's. "[a-[b]]" was the range a-[ to Go, and the "+*"
                    // of "[a[b]+*]", characters to Java, a repeat of a repeat:
                    // the core refused either, and with it the configuration.
                    return NESTED_CLASS;
                }
                if (c == ']') {
                    inClass = false;
                    continue;
                }
                // A "-" after a character, and before anything but the "]"
                // that ends the class, opens a range.
                rangeEnd = c == '-' && classChar && i + 1 < length
                        && regex.charAt(i + 1) != ']';
                classChar = !endsRange && !rangeEnd;
                continue;
            }
            if (c == '[') {
                inClass = true;
                canRepeat = true;
                settle(groups, atomWeight);
                atomWeight = 1;
                afterRepeat = false;
                classChar = false;
                rangeEnd = false;
                // A ] straight after [ or [^ belongs to the class.
                if (i + 1 < length && regex.charAt(i + 1) == '^') {
                    i++;
                }
                if (i + 1 < length && regex.charAt(i + 1) == ']') {
                    i++;
                    classChar = true;
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
            if (regex.startsWith("(?", i)) {
                // Flags, or a group's name, up to what ends them. Flags alone,
                // as in (?i), hold nothing to repeat; (?:, (?i: and (?P<n>
                // open a group as ( does.
                int end = i + 2;
                while (end < length && ":)>".indexOf(regex.charAt(end)) < 0) {
                    end++;
                }
                afterRepeat = false;
                if (end < length && regex.charAt(end) != ')') {
                    settle(groups, atomWeight);
                    groups.push(new int[] {1});
                    atomWeight = 1;
                    canRepeat = false;
                }
                i = end;
                continue;
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
                if (!canRepeat || afterRepeat || min > RE2_MAX_REPEAT || max > RE2_MAX_REPEAT
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
                canRepeat = false;
            } else if (c == ')' && groups.size() > 1) {
                atomWeight = groups.pop()[0];
                canRepeat = true;
            } else {
                atomWeight = 1;
                canRepeat = c != '|';
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
        if (!bare.contains(":") || !bare.matches("[0-9A-Fa-f:.]+")
                || zone == address.length() - 1) {
            return false;
        }
        // Java's literal parser also takes a group of five digits with a
        // leading zero, a dotted quad with one, and an empty zone, all of which
        // netip refuses ("each group must have 4 or less digits"): such a rule
        // or bypass line passed here, and the core refused the configuration.
        int lastColon = bare.lastIndexOf(':');
        String quad = bare.substring(lastColon + 1);
        boolean endsInQuad = quad.contains(".");
        if (endsInQuad && !isIpv4Address(quad)) {
            return false;
        }
        for (String group : bare.substring(0, endsInQuad ? lastColon : bare.length()).split(":")) {
            if (group.length() > 4) {
                return false;
            }
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
