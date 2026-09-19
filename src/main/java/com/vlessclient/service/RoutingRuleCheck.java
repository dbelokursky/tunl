package com.vlessclient.service;

import com.vlessclient.app.I18n;
import com.vlessclient.model.RoutingRule;
import java.net.Inet6Address;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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

    /** An IPv4 field as Go reads it: 0 to 255, without a leading zero. */
    private static final String OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9][0-9]|[0-9])";

    private static final Pattern IPV4 = Pattern.compile(OCTET + "(?:\\." + OCTET + "){3}");

    /** Prefix bits as Go reads them: decimal, without a sign or a leading zero. */
    private static final Pattern BITS = Pattern.compile("0|[1-9][0-9]{0,2}");

    /** The names of the rule-sets the app downloads: lower case, as the repository has them. */
    private static final Pattern RULE_SET_NAME = Pattern.compile("[a-z0-9!@._-]+");

    /** The letters Go's regexp takes after a backslash; the rest is a syntax error there. */
    private static final String RE2_ESCAPE_LETTERS = "afnrtvdDsSwWbBAzpPxQE";

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
                            : Optional.empty();
            case DOMAIN_REGEX -> regexProblem(value);
            case IP_CIDR -> isCidr(value)
                    ? Optional.empty()
                    : Optional.of(I18n.get("routing.rule.problem.cidr"));
            case GEOSITE, GEOIP -> RULE_SET_NAME.matcher(ruleSetName(value)).matches()
                    ? Optional.empty()
                    : Optional.of(I18n.get("routing.rule.problem.rule.set"));
        };
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
     * or an escape Go does not have ({@code \Z}, {@code \G}, {@code \h} and
     * the like). Null when there is none.
     */
    private static String re2Unsupported(String regex) {
        boolean inClass = false;
        int length = regex.length();
        for (int i = 0; i < length; i++) {
            char c = regex.charAt(i);
            if (c == '\\' && i + 1 < length) {
                char next = regex.charAt(i + 1);
                if (next == 'Q') {
                    // Literal text up to \E, or to the end.
                    int end = regex.indexOf("\\E", i + 2);
                    i = end < 0 ? length : end + 1;
                    continue;
                }
                String escape = regex.substring(i, i + 2);
                if (Character.isDigit(next)) {
                    // Octal: \0, or \1-\7 followed by an octal digit. Else a back-reference.
                    boolean octal = next == '0' || next <= '7' && i + 2 < length
                            && regex.charAt(i + 2) >= '0' && regex.charAt(i + 2) <= '7';
                    if (!octal) {
                        return escape;
                    }
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
            if ((c == '*' || c == '+' || c == '?') && i + 1 < length
                    && regex.charAt(i + 1) == '+' && !isGroupFlag(regex, i)) {
                return regex.substring(i, i + 2);
            }
            if (c == '{') {
                int close = regex.indexOf('}', i);
                if (close > 0 && regex.substring(i + 1, close).matches("[0-9]+(?:,[0-9]*)?")
                        && close + 1 < length && regex.charAt(close + 1) == '+') {
                    return regex.substring(i, close + 2);
                }
            }
        }
        return null;
    }

    /** Whether the ? at {@code index} opens a group's flags, as in (?:, rather than quantifying. */
    private static boolean isGroupFlag(String regex, int index) {
        return regex.charAt(index) == '?' && index > 0 && regex.charAt(index - 1) == '(';
    }

    /**
     * A CIDR as sing-box parses it: an IPv4 or IPv6 address, with or without
     * a prefix length. Nothing around it: the core refuses a space.
     */
    private static boolean isCidr(String value) {
        int slash = value.indexOf('/');
        String address = slash < 0 ? value : value.substring(0, slash);
        String bits = slash < 0 ? null : value.substring(slash + 1);
        int maxBits;
        if (IPV4.matcher(address).matches()) {
            maxBits = 32;
        } else if (isIpv6(address)) {
            maxBits = 128;
        } else {
            return false;
        }
        if (bits == null) {
            return true;
        }
        return BITS.matcher(bits).matches() && Integer.parseInt(bits) <= maxBits;
    }

    private static boolean isIpv6(String address) {
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
