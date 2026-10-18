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
            "(?im)a", "(?s-m)a", "(?U)a", "(?-i)a", "(?iU-ms:a)",
            // A repeat of what an assertion, a group or a quote holds, and
            // POSIX classes Go has.
            "x(?i){2}", "^{2}", "\\b{2}", "\\Qab\\E{2}", "(?:){2}", "(|a)", "a{2}(?i){3}",
            "^(www\\.)?example\\.com$", "[[:alnum:]]+", "[[:^alpha:]]", "[[:word:]-]",
            "[]a]", "[^]a]");

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
            "(?x)a b", "(?u)a", "(?d)a", "(?c)a", "(?ix)a", "(?x:a)", "(?-)a", "(?i-:a)",
            // A repeat with nothing before it to repeat, a POSIX class Go does
            // not have, and \Q inside a class.
            "{1,3}\\.example\\.org$", "(www|{2})\\.example\\.com$", "(?i){2}example\\.com$",
            "(?:{2})", "(?i:{2})", "a|{2}", "\\Q\\E{2}", "(?i)(?s){2}",
            "^[[:alnum_:]]+\\.example\\.com$", "[[:ALPHA:]]", "[a[:b]c:]]",
            "[\\Q.\\E]example\\.com$",
            // A class inside a class, which Java has and Go reads as a "[":
            // the range a-[, or what Java read inside it read by Go outside.
            "^(?P<sub>[a-[\\-]z]+)\\.example\\.com$", "[a-[b]]", "[a-[:alpha:]]", "[]-[b]]",
            "[a[b]+*]", "[a[b]**]", "[a[b](]", "[a[b])]");

    static final List<String> CIDRS_THE_CORE_TAKES = List.of(
            "10.0.0.0/8", "10.0.0.1", "10.0.0.1/32", "0.0.0.0/0", "2001:db8::/32",
            "2001:db8::1", "::/0", "::ffff:10.0.0.1/128", "fe80::1%en0", "fe80::1%en0/64",
            "2001:0db8:0000::/48", "0002:db8::/32", "FE80::A/10", "1:2:3:4:5:6:1.2.3.4",
            "::1.2.3.4");

    static final List<String> CIDRS_THE_CORE_REFUSES = List.of(
            "999.1.1.1/8", "010.0.0.0/8", "10.0.0.0/08", "10.0.0.0/+8", "10.0.0/24",
            "10.0.0.0.0/8", "10.0.0.256/32", "10.0.0.0/", "/8", "10.0.0.0/33",
            "2001:db8::/129", "2001:db8:::1/64", "g::1/64", "[2001:db8::]/32", "1.2.3.4/32/5",
            " 10.0.0.0/8", "10.0.0.0/8 ", "example.com",
            // A group of five digits, a dotted quad with a leading zero, an
            // empty zone: Java's literal parser takes each, netip does not.
            "02001:db8::/32", "00000::1", "::ffff:010.0.0.1/128", "::01.2.3.4", "fe80::1%");

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
     * The core matches a regex against the name in lower case and in
     * punycode, the way SNI, DNS and a browser's CONNECT carry it: "^Mail\."
     * or "\.рф$" passed here, the core took it, and the rule matched nothing.
     */
    @Test
    void aRegexTheCoreCanNeverMatchIsRefused() {
        for (String regex : List.of("^Mail\\.", "Gosuslugi\\.ru$", "(?-i)Mail", "\\QMail\\E",
                "(?s:Mail)", "(?P<Sub>Mail)\\.ru")) {
            assertThat(RoutingRuleCheck.problem(RuleType.DOMAIN_REGEX, regex)).as(regex)
                    .hasValueSatisfying(reason -> assertThat(reason).contains("(?i)"));
        }
        assertThat(RoutingRuleCheck.problem(RuleType.DOMAIN_REGEX, "^mail\\.Example"))
                .hasValueSatisfying(reason -> assertThat(reason).contains(" E "));
        for (String regex : List.of("\\.рф$", "яндекс", "^\\Qпочта\\E\\.")) {
            assertThat(RoutingRuleCheck.problem(RuleType.DOMAIN_REGEX, regex)).as(regex)
                    .hasValueSatisfying(reason -> assertThat(reason).contains("punycode"));
        }
        // Upper case where it is not a letter of the name, and (?i).
        for (String regex : List.of("(?i)^Mail\\.", "(?i:Mail)\\.ru$", "(?im)^Mail",
                "[A-Za-z]+\\.ru$", "[[:upper:]a-z]+", "^\\D+\\.example$", "\\p{Lu}", "\\PL",
                "\\x4A", "\\x{4A}", "(?P<Sub>mail)\\.ru", "(?<Sub>mail)", "(?U)a+",
                "\\.xn--p1ai$", "[а-яa-z]+\\.ru$", "\\x{0440}\\x{0444}")) {
            assertThat(RoutingRuleCheck.problem(RuleType.DOMAIN_REGEX, regex)).as(regex)
                    .isEmpty();
        }
    }

    /**
     * Java reads a "[" inside a class as a class of its own, and Go as the
     * character: where Go then found the expression broken, the core refused
     * the whole configuration, and where it did not, the rule meant something
     * else to the core than it said. Both are refused, with what to write; a
     * POSIX class and an escaped "[" are Go's own.
     */
    @Test
    void aClassInsideAClassIsRefused() {
        for (String regex : List.of("[a-z[0-9]]+\\.example\\.com$", "[a[b]]", "[A-[b]]",
                "[a-z&&[^b]]")) {
            assertThat(RoutingRuleCheck.problem(RuleType.DOMAIN_REGEX, regex)).as(regex)
                    .hasValueSatisfying(reason -> assertThat(reason).contains("\\["));
        }
        for (String regex : List.of("[[:alpha:]]+\\.ru$", "[[:digit:][:lower:]]", "[a\\[b]",
                "[\\[\\]]", "\\[a[b]")) {
            assertThat(RoutingRuleCheck.problem(RuleType.DOMAIN_REGEX, regex)).as(regex)
                    .isEmpty();
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

    /**
     * The core has no wildcard in a domain, a suffix or a keyword: it compares
     * the text, so "*.example.net" matched nothing while the bypass list on
     * the same page read it as every name under example.net. A star is read
     * the bypass list's way where the rule's type can say the same, a final
     * dot is the root's, and anything else is refused with what to use.
     */
    @Test
    void aWildcardIsReadAsTheBypassListReadsIt() {
        assertThat(RoutingRuleCheck.coreName(RuleType.DOMAIN_SUFFIX, "*.Example.net"))
                .isEqualTo("example.net");
        assertThat(RoutingRuleCheck.coreName(RuleType.DOMAIN_SUFFIX, "*.пример.рф"))
                .isEqualTo("xn--e1afmkfd.xn--p1ai");
        assertThat(RoutingRuleCheck.coreName(RuleType.DOMAIN_SUFFIX, "example.org."))
                .isEqualTo("example.org");
        assertThat(RoutingRuleCheck.coreName(RuleType.DOMAIN_SUFFIX, ".example.org"))
                .as("a leading dot is the core's own: every name under it, not itself")
                .isEqualTo(".example.org");
        assertThat(RoutingRuleCheck.coreName(RuleType.DOMAIN, "example.org."))
                .isEqualTo("example.org");
        assertThat(RoutingRuleCheck.coreName(RuleType.DOMAIN_KEYWORD, "*tracker*"))
                .isEqualTo("tracker");
        assertThat(RoutingRuleCheck.coreName(RuleType.DOMAIN_KEYWORD, "*.vk.com*"))
                .isEqualTo("vk.com");
        assertThat(RoutingRuleCheck.coreName(RuleType.DOMAIN_KEYWORD, "tracker."))
                .as("no star: a keyword's dots are its own").isEqualTo("tracker.");
        for (String value : List.of("*.example.net", "example.org.", ".example.org")) {
            assertThat(RoutingRuleCheck.problem(RuleType.DOMAIN_SUFFIX, value)).as(value)
                    .isEmpty();
        }
        assertThat(RoutingRuleCheck.problem(RuleType.DOMAIN_KEYWORD, "*tracker*")).isEmpty();
        assertThat(RoutingRuleCheck.problem(RuleType.DOMAIN, "example.org.")).isEmpty();

        for (String value : List.of("*.example.net", "mail.*.com", "*example.net", ".example.net",
                ".", "*")) {
            assertThat(RoutingRuleCheck.problem(RuleType.DOMAIN, value)).as("domain %s", value)
                    .isPresent();
        }
        for (String value : List.of("*example.net", "example.*", "*.*.example.net",
                "mail.*.com", "*.", ".", "*")) {
            assertThat(RoutingRuleCheck.problem(RuleType.DOMAIN_SUFFIX, value))
                    .as("suffix %s", value).isPresent();
        }
        // An empty keyword is in every name: the rule would have caught everything.
        for (String value : List.of("foo*bar", "*", "**", "*.*", "*.")) {
            assertThat(RoutingRuleCheck.problem(RuleType.DOMAIN_KEYWORD, value))
                    .as("keyword %s", value).isPresent();
        }
        // Without a star a keyword is the core's own, a dot as well: it is in
        // every name with one, and a rule stored with it worked.
        assertThat(RoutingRuleCheck.problem(RuleType.DOMAIN_KEYWORD, ".")).isEmpty();
        assertThat(RoutingRuleCheck.coreName(RuleType.DOMAIN_KEYWORD, ".")).isEqualTo(".");
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
                "apple@cn", "category-ru")) {
            assertThat(RoutingRuleCheck.problem(RuleType.GEOSITE, name)).as(name).isEmpty();
        }
        for (String name : List.of("google ads", "google/ads", "google?x", "#cn")) {
            assertThat(RoutingRuleCheck.problem(RuleType.GEOSITE, name)).as(name).isPresent();
            assertThat(RoutingRuleCheck.problem(RuleType.GEOIP, name)).as(name).isPresent();
        }
    }

    /**
     * A list the repositories do not publish was a 404 when the core fetched
     * it at start, and the core does not start without its rule-sets: a typo
     * such as "youtub" stopped every server from connecting, with a message
     * that read like a blocked network. sing-geosite has no list "ru" (it is
     * "category-ru"), and sing-geoip one per country with addresses of its own.
     */
    @Test
    void aListNameIsOneTheRepositoryPublishes() {
        for (String name : List.of("youtub", "ru", "category-ruu", "google@ru")) {
            assertThat(RoutingRuleCheck.problem(RuleType.GEOSITE, name)).as(name)
                    .hasValueSatisfying(reason -> assertThat(reason).contains(name));
        }
        // The country bypass's own lists among them.
        for (String name : List.of("youtube", "YouTube", "cn", "netflix", "google@cn",
                "category-ads-all", "category-ru", "category-ir")) {
            assertThat(RoutingRuleCheck.problem(RuleType.GEOSITE, name)).as(name).isEmpty();
        }
        for (String name : List.of("rus", "private", "bv", "category-ru", "zz")) {
            assertThat(RoutingRuleCheck.problem(RuleType.GEOIP, name)).as(name)
                    .hasValueSatisfying(reason -> assertThat(reason).contains(name));
        }
        for (String name : List.of("ru", "RU", "cn", "us", "ir")) {
            assertThat(RoutingRuleCheck.problem(RuleType.GEOIP, name)).as(name).isEmpty();
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
