package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.model.RoutingRule.RuleType;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The routing rule values sing-box refuses. Each value here was put to the
 * pinned core's check in a rule of its own ("error parsing regexp: …",
 * "netip.ParsePrefix(…)"); {@code SingBoxRealBinarySmokeTest} asks the core
 * again for the same values.
 */
class RoutingRuleCheckTest {

    static final List<String> REGEXES_THE_CORE_TAKES = List.of(
            "^(.+\\.)?example\\.com$", "example", "^a+b*c?$", "[a-z]{2,5}", "(?i)google",
            "\\d+\\.\\w+", "(?:a|b)", "(?P<name>a)", "(?<name>a)", "\\Qa.b\\E", "[\\]]",
            "[(?=]", "\\++", "a}+", "\\x41", "\\pL", "\\101", "\\0101", "[[:alpha:]]",
            "\\p{Lu}", "\\PL", "[\\p{Nd}]", "a{1000}", "a{1000,}", "(a{10}){100}",
            "((a{10}){10}){10}", "(a{600}){0,1}", "a{2}?", "a*?", "\\_",
            // A code point's braces hold no repeat count, a {0} takes what it
            // repeats out of the count around it, and a count with a leading
            // zero is a literal brace to Go. Each of these was refused.
            "\\x{2014}", "\\x{2000}", "example\\x{2013}news\\.com", "\\x{41}{2}",
            "^xn--\\x{30}{2}", "(?:\\x{41}){1000}", "((a{500}){0}){3}",
            "((a{500}){0}|a{300}){3}", "(?:(a{600}){0}b{2}){3}", "a{2}{01}", "a{01000}",
            // The inline flags Go has.
            "(?im)a", "(?s-m)a", "(?U)a", "(?-i)a", "(?iU-ms:a)");

    static final List<String> REGEXES_THE_CORE_REFUSES = List.of(
            "(", "[a", "a{2,1}", "*a", "(?=a)", "(?!a)", "(?<=a)b", "(?<!a)b", "(?>a)",
            "a++", "a*+", "a?+", "a{2}+", "(a)\\1", "\\Z", "\\G", "\\R", "\\h", "\\e",
            "\\X", "\\k<name>", "\\cA",
            // Property names Java alone has, repeat counts over Go's 1000, a
            // repeat of a repeat, and an escaped character outside ASCII.
            "\\p{Lower}+\\.example", "\\p{Alpha}", "\\p{IsLatin}", "\\p{javaLowerCase}",
            "[\\p{Space}]", "\\P{InGreek}", "^a{1,2000}$", "a{1001}", "a{1001,}",
            "(a{100}){100}", "((a{10}){10}){11}", "(?:a{2}){501}", "(a{2,}){600}",
            "a{2}{3}", "a*{2}", "\\™", "\\é", "((a{500}){0}|a{400}){3}",
            "((a{600}){0,}){2}",
            // Inline flags Java alone has, and a "-" with no flag after it.
            "(?x)a b", "(?u)a", "(?d)a", "(?c)a", "(?ix)a", "(?x:a)", "(?-)a", "(?i-:a)");

    static final List<String> CIDRS_THE_CORE_TAKES = List.of(
            "10.0.0.0/8", "10.0.0.1", "10.0.0.1/32", "0.0.0.0/0", "2001:db8::/32",
            "2001:db8::1", "::/0", "::ffff:10.0.0.1/128", "fe80::1%en0", "fe80::1%en0/64",
            "2001:0db8:0000::/48");

    static final List<String> CIDRS_THE_CORE_REFUSES = List.of(
            "999.1.1.1/8", "010.0.0.0/8", "10.0.0.0/08", "10.0.0.0/+8", "10.0.0/24",
            "10.0.0.0.0/8", "10.0.0.256/32", "10.0.0.0/", "/8", "10.0.0.0/33",
            "2001:db8::/129", "2001:db8:::1/64", "g::1/64", "[2001:db8::]/32", "1.2.3.4/32/5",
            " 10.0.0.0/8", "10.0.0.0/8 ", "example.com");

    @Test
    void aRegularExpressionIsCheckedAsGoReadsIt() {
        for (String regex : REGEXES_THE_CORE_TAKES) {
            assertThat(RoutingRuleCheck.problem(RuleType.DOMAIN_REGEX, regex)).as(regex).isEmpty();
        }
        for (String regex : REGEXES_THE_CORE_REFUSES) {
            assertThat(RoutingRuleCheck.problem(RuleType.DOMAIN_REGEX, regex)).as(regex)
                    .isPresent();
        }
    }

    /**
     * The add-rule dialog names what the core would not take. For {@code \x{2014}}
     * it named "{2014}", a code point read as a repeat count.
     */
    @Test
    void theConstructTheCoreRefusesIsNamed() {
        assertThat(RoutingRuleCheck.problem(RuleType.DOMAIN_REGEX, "(?ix)example\\.com"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("(?ix)"));
        assertThat(RoutingRuleCheck.problem(RuleType.DOMAIN_REGEX, "(?x:a b)"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("(?x:"));
        assertThat(RoutingRuleCheck.problem(RuleType.DOMAIN_REGEX, "\\x{2014}{2}{3}"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("{3}")
                        .doesNotContain("2014"));
    }

    @Test
    void aCidrIsCheckedAsGoReadsIt() {
        for (String cidr : CIDRS_THE_CORE_TAKES) {
            assertThat(RoutingRuleCheck.problem(RuleType.IP_CIDR, cidr)).as(cidr).isEmpty();
        }
        for (String cidr : CIDRS_THE_CORE_REFUSES) {
            assertThat(RoutingRuleCheck.problem(RuleType.IP_CIDR, cidr)).as(cidr).isPresent();
        }
    }

    /** The core takes a space in a domain, but no hostname has one: the rule never matched. */
    @Test
    void aDomainWithASpaceIsRefused() {
        for (RuleType type : List.of(RuleType.DOMAIN, RuleType.DOMAIN_SUFFIX,
                RuleType.DOMAIN_KEYWORD)) {
            assertThat(RoutingRuleCheck.problem(type, "exa mple.com")).as(type.name()).isPresent();
            assertThat(RoutingRuleCheck.problem(type, "example.com")).as(type.name()).isEmpty();
        }
    }

    /** The repository names its lists in lower case; "Google" was a 404, and no core. */
    @Test
    void aListNameIsReadInLowerCaseAndCheckedForWhatAUrlCannotCarry() {
        assertThat(RoutingRuleCheck.ruleSetName(" Google ")).isEqualTo("google");
        for (String name : List.of("google", "Google", "category-ads-all", "geolocation-!cn",
                "apple@cn", "ru")) {
            assertThat(RoutingRuleCheck.problem(RuleType.GEOSITE, name)).as(name).isEmpty();
        }
        for (String name : List.of("google ads", "google/ads", "google?x", "#cn")) {
            assertThat(RoutingRuleCheck.problem(RuleType.GEOSITE, name)).as(name).isPresent();
            assertThat(RoutingRuleCheck.problem(RuleType.GEOIP, name)).as(name).isPresent();
        }
    }

    @Test
    void aBlankValueIsRefusedForEveryType() {
        for (RuleType type : RuleType.values()) {
            assertThat(RoutingRuleCheck.problem(type, "  ")).as(type.name()).isPresent();
        }
    }

    /** The reason is shown under the value in the add-rule dialog. */
    @Test
    void theReasonIsWordedInTheLanguageOfTheUi() {
        Locale before = I18n.getLocale();
        try {
            I18n.setLocale(Locale.of("ru"));
            assertThat(RoutingRuleCheck.problem(RuleType.DOMAIN_REGEX, "("))
                    .hasValueSatisfying(reason -> assertThat(reason)
                            .containsPattern("\\p{IsCyrillic}"));
            assertThat(RoutingRuleCheck.problem(RuleType.DOMAIN_REGEX, "(?=a)"))
                    .hasValueSatisfying(reason -> assertThat(reason)
                            .containsPattern("\\p{IsCyrillic}").contains("(?="));
            assertThat(RoutingRuleCheck.problem(RuleType.IP_CIDR, "999.1.1.1/8"))
                    .hasValueSatisfying(reason -> assertThat(reason)
                            .containsPattern("\\p{IsCyrillic}"));
        } finally {
            I18n.setLocale(before);
        }
    }
}
