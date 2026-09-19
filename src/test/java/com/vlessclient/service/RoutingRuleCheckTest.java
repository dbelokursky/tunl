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
            "[(?=]", "\\++", "a}+", "\\x41", "\\pL", "\\101", "\\0101", "[[:alpha:]]");

    static final List<String> REGEXES_THE_CORE_REFUSES = List.of(
            "(", "[a", "a{2,1}", "*a", "(?=a)", "(?!a)", "(?<=a)b", "(?<!a)b", "(?>a)",
            "a++", "a*+", "a?+", "a{2}+", "(a)\\1", "\\Z", "\\G", "\\R", "\\h", "\\e",
            "\\X", "\\k<name>", "\\cA");

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
