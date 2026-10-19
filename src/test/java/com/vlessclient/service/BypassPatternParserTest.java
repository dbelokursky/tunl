package com.vlessclient.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BypassPatternParserTest {

    @Test
    void parsesExactDomain() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse("example.com");
        assertThat(p).isNotNull();
        assertThat(p.kind()).isEqualTo(BypassPatternParser.Kind.DOMAIN);
        assertThat(p.value()).isEqualTo("example.com");
    }

    @Test
    void parsesLeadingWildcardAsDomainSuffix() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse("*.example.com");
        assertThat(p.kind()).isEqualTo(BypassPatternParser.Kind.DOMAIN_SUFFIX);
        assertThat(p.value()).isEqualTo("example.com");
    }

    @Test
    void parsesLeadingDotAsDomainSuffix() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse(".example.com");
        assertThat(p.kind()).isEqualTo(BypassPatternParser.Kind.DOMAIN_SUFFIX);
        assertThat(p.value()).isEqualTo("example.com");
    }

    @Test
    void parsesSurroundingWildcardsAsKeyword() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse("*google*");
        assertThat(p.kind()).isEqualTo(BypassPatternParser.Kind.DOMAIN_KEYWORD);
        assertThat(p.value()).isEqualTo("google");
    }

    @Test
    void parsesTrailingWildcardAsKeyword() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse("github*");
        assertThat(p.kind()).isEqualTo(BypassPatternParser.Kind.DOMAIN_KEYWORD);
        assertThat(p.value()).isEqualTo("github");
    }

    @Test
    void parsesIpv4Cidr() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse("192.168.0.0/16");
        assertThat(p.kind()).isEqualTo(BypassPatternParser.Kind.IP_CIDR);
        assertThat(p.value()).isEqualTo("192.168.0.0/16");
    }

    @Test
    void parsesBareIpv4AsSlash32() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse("203.0.113.42");
        assertThat(p.kind()).isEqualTo(BypassPatternParser.Kind.IP_CIDR);
        assertThat(p.value()).isEqualTo("203.0.113.42/32");
    }

    @Test
    void parsesIpv6Cidr() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse("fd00::/8");
        assertThat(p.kind()).isEqualTo(BypassPatternParser.Kind.IP_CIDR);
        assertThat(p.value()).isEqualTo("fd00::/8");
    }

    @Test
    void stripsUrlSchemeAndPath() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse("https://api.openai.com/v1/chat");
        assertThat(p.kind()).isEqualTo(BypassPatternParser.Kind.DOMAIN);
        assertThat(p.value()).isEqualTo("api.openai.com");
    }

    @Test
    void stripsPortFromHost() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse("example.com:8080");
        assertThat(p.kind()).isEqualTo(BypassPatternParser.Kind.DOMAIN);
        assertThat(p.value()).isEqualTo("example.com");
    }

    @Test
    void stripsUserInfo() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse("https://user:pass@example.com/secret");
        assertThat(p.kind()).isEqualTo(BypassPatternParser.Kind.DOMAIN);
        assertThat(p.value()).isEqualTo("example.com");
    }

    @Test
    void trimsWhitespace() {
        BypassPatternParser.Parsed p = BypassPatternParser.parse("  example.com  ");
        assertThat(p.value()).isEqualTo("example.com");
    }

    @Test
    void blankLineReturnsNull() {
        assertThat(BypassPatternParser.parse("")).isNull();
        assertThat(BypassPatternParser.parse("   ")).isNull();
        assertThat(BypassPatternParser.parse(null)).isNull();
    }

    @Test
    void commentLineReturnsNull() {
        assertThat(BypassPatternParser.parse("# comment")).isNull();
        assertThat(BypassPatternParser.parse("  # still comment")).isNull();
    }

    @Test
    void pureWildcardReturnsNull() {
        assertThat(BypassPatternParser.parse("*")).isNull();
    }

    /** {@code *vk.com*} became the keyword {@code vkcom}, which no name contains. */
    @Test
    void aKeywordKeepsTheDotsInsideIt() {
        assertThat(BypassPatternParser.parse("*vk.com*")).isEqualTo(
                new BypassPatternParser.Parsed(BypassPatternParser.Kind.DOMAIN_KEYWORD, "vk.com"));
        assertThat(BypassPatternParser.parse("*.google.*")).as("dots beside a star go")
                .isEqualTo(new BypassPatternParser.Parsed(
                        BypassPatternParser.Kind.DOMAIN_KEYWORD, "google"));
    }

    /** The core compares lower-case names; {@code Example.RU} matched nothing. */
    @Test
    void aPatternIsLowerCased() {
        assertThat(BypassPatternParser.parse("Example.RU")).isEqualTo(
                new BypassPatternParser.Parsed(BypassPatternParser.Kind.DOMAIN, "example.ru"));
    }

    /** SNI and DNS carry punycode, so {@code *.рф} as typed matched nothing. */
    @Test
    void anInternationalizedNameBecomesPunycode() {
        assertThat(BypassPatternParser.parse("*.рф")).isEqualTo(new BypassPatternParser.Parsed(
                BypassPatternParser.Kind.DOMAIN_SUFFIX, "xn--p1ai"));
        assertThat(BypassPatternParser.parse("кремль.рф")).isEqualTo(
                new BypassPatternParser.Parsed(
                        BypassPatternParser.Kind.DOMAIN, "xn--e1ajeds9e.xn--p1ai"));
    }

    /**
     * An address the core cannot read reached it as ip_cidr, and it refused
     * the whole configuration: one mistyped line stopped every server from
     * connecting. Such a line matches nothing now, and says why.
     */
    @Test
    void anAddressTheCoreCannotReadIsLeftOutWithTheReason() {
        for (String line : java.util.List.of("192.168.1.256", "10.0.0.0/33", "192.168.01.1",
                "010.0.0.1", "10.0.0.0/08", "cafe::/200", "1:2:3:4:5:6:7:8:9", "dead:beef",
                "300.1.1.1:8080", "https://999.0.0.1/path", "02001:db8::/32", "00000::1")) {
            assertThat(BypassPatternParser.parse(line)).as(line).isNull();
            assertThat(BypassPatternParser.problem(line)).as(line).isPresent();
        }
        for (String line : java.util.List.of("192.168.0.0/16", "203.0.113.42", "fd00::/8",
                "2001:db8::1", "0.0.0.0/0", "::/0", "1.2.3.4:80", "https://10.0.0.1:8080/x",
                "example.com", "*.example.org", "*google*", "# 10.0.0.0/33", "", "  ")) {
            assertThat(BypassPatternParser.problem(line)).as(line).isEmpty();
        }
    }

    /** {@code .} and {@code *.} name no suffix, and the core refused the empty one. */
    @Test
    void aSuffixWithNothingAfterTheDotIsLeftOut() {
        assertThat(BypassPatternParser.parse(".")).isNull();
        assertThat(BypassPatternParser.parse("*.")).isNull();
    }

    /** A star between names stands for anything, dots included, which only a regex says. */
    @Test
    void aStarInTheMiddleBecomesARegex() {
        assertThat(BypassPatternParser.parse("mail.*.com")).isEqualTo(
                new BypassPatternParser.Parsed(
                        BypassPatternParser.Kind.DOMAIN_REGEX, "^mail\\..*\\.com$"));
        assertThat(BypassPatternParser.parse("*.кремль.*.рф").value())
                .isEqualTo("^.*\\.xn--e1ajeds9e\\..*\\.xn--p1ai$");
    }

    /**
     * A name is written as browsers send it, by UTS #46 without transitional
     * processing, as Chrome, Firefox and Safari do. Java's IDN is IDNA 2003,
     * which turns a German sharp s into "ss", a final sigma into a medial one
     * and drops a joiner: "straße.de" became strasse.de, another registrable
     * domain, so a Direct rule for it sent strasse.de's traffic and lookups
     * around the tunnel, while xn--strae-oqa.de, the name browsers send, still
     * went through it. Expected values are what Node's WHATWG URL gives.
     */
    @Test
    void aNameIsSpelledAsBrowsersSendIt() {
        java.util.Map<String, String> sent = new java.util.LinkedHashMap<>();
        sent.put("straße.de", "xn--strae-oqa.de");
        sent.put("Straße.DE", "xn--strae-oqa.de");
        sent.put("www.straße.de", "www.xn--strae-oqa.de");
        sent.put("faß.de", "xn--fa-hia.de");
        sent.put("ελληνικός.gr", "xn--qxaegecap6byf.gr");
        sent.put("σοφίας.gr", "xn--kxae4bobt.gr");
        sent.put("می‌خواهم.ir",
                "xn--mgbn2ecje63gr19l.ir");
        // Unchanged: a capital sigma is a medial one wherever it stands, and a
        // capital sharp s is "ss", as UTS #46 maps them.
        sent.put("ΕΛΛΗΝΙΚΌΣ.GR", "xn--qxaegecap4c9d.gr");
        sent.put("STRAẞE.de", "strasse.de");
        sent.put("Βουλή.gr", "xn--jxah1ap4a.gr");
        sent.put("ÖBB.at", "xn--bb-eka.at");
        sent.put("пример.рф", "xn--e1afmkfd.xn--p1ai");
        sent.put("İstanbul.com", "xn--istanbul-o0e.com");
        sent.forEach((typed, browser) ->
                assertThat(BypassPatternParser.coreName(typed)).as(typed).isEqualTo(browser));

        assertThat(BypassPatternParser.parse("*.maße.de")).isEqualTo(
                new BypassPatternParser.Parsed(
                        BypassPatternParser.Kind.DOMAIN_SUFFIX, "xn--mae-6ka.de"));
    }

    /**
     * A joiner where no browser keeps one, beside a Latin or Cyrillic letter,
     * at a label's edge, or a joining one between Arabic letters, comes along
     * with a name copied out of a messenger. It was put into punycode with
     * the name: rutracker.org with a non-joiner before its dot became
     * xn--rutracker-y69d.org, which no client sends, so the entry or rule
     * matched nothing. It is dropped, as IDNA 2003 and 1.25.1 dropped it. A
     * joiner a script spells with, after a virama or between joining letters,
     * stays. Every expected name is what Node's WHATWG URL sends.
     */
    @Test
    void aJoinerNoBrowserKeepsIsDroppedAndOneAScriptSpellsWithStays() {
        java.util.Map<String, String> sent = new java.util.LinkedHashMap<>();
        sent.put("rutracker‌.org", "rutracker.org");
        sent.put("example.com‍", "example.com");
        sent.put("‌example.com", "example.com");
        sent.put("ab‍c.com", "abc.com");
        sent.put("пример‌.рф",
                "xn--e1afmkfd.xn--p1ai");
        sent.put("ب‍ب.example", "xn--ngba.example");
        // After a virama, in Devanagari, Malayalam and, outside the BMP, Brahmi.
        sent.put("क्‍ष.example", "xn--11b2ezcw70k.example");
        sent.put("क्‌ष.example", "xn--11b2ezcs70k.example");
        sent.put("ന്‍.example", "xn--uwc8ep99d.example");
        sent.put("𑀓𑁆‍𑀓.example",
                "xn--1ug2565gba6r.example");
        // A non-joiner between Persian letters, a mark between included.
        sent.put("می‌خواهم.example",
                "xn--mgbn2ecje63gr19l.example");
        sent.put("بَ‌ب.example", "xn--ngba7iz95i.example");
        sent.put("کتاب‌ها.example",
                "xn--mgbacg1m8wy53l.example");
        // One after a letter that joins nothing after it, as waw or alef, or
        // before one that joins nothing before it, as hamza: it does nothing,
        // and the name is the one without it.
        sent.put("رو‌زنامه.example",
                "xn--mgbtd1fdic.example");
        sent.put("ا‌ب.example", "xn--mgbc.example");
        sent.put("ب‌ء.example", "xn--ggbn.example");
        sent.forEach((typed, browser) ->
                assertThat(BypassPatternParser.coreName(typed)).as(typed).isEqualTo(browser));

        assertThat(BypassPatternParser.parse("*.rutracker‌.net")).isEqualTo(
                new BypassPatternParser.Parsed(
                        BypassPatternParser.Kind.DOMAIN_SUFFIX, "rutracker.net"));
        assertThat(BypassPatternParser.parse("rutracker‌.org").value())
                .isEqualTo("rutracker.org");
        assertThat(BypassPatternParser.parse("mail.*.rutracker‌.org").value())
                .isEqualTo("^mail\\..*\\.rutracker\\.org$");
    }

    /**
     * Text copied out of a right-to-left chat brings invisible directional
     * marks along. IDN refuses a label holding one, the label was kept as
     * typed, and the backslash put before every character but a letter or
     * digit made "\" + U+200F, an escape Go refuses: the core refused the
     * whole configuration, and no server connected over a line the user never
     * wrote as a regex. A character outside the BMP got its backslash between
     * its two halves, and the configuration could not be written at all.
     */
    @Test
    void anInvisibleMarkInAGlobLeavesARegexTheCoreCompiles() {
        assertThat(BypassPatternParser.parse("mail.*.example.com‏"))
                .isEqualTo(new BypassPatternParser.Parsed(
                        BypassPatternParser.Kind.DOMAIN_REGEX, "^mail\\..*\\.example\\.com$"));
        assertThat(BypassPatternParser.parse("‏mail.*.example.com‎").value())
                .isEqualTo("^mail\\..*\\.example\\.com$");
        assertThat(BypassPatternParser.parse("mail.*.‫example‬.com").value())
                .isEqualTo("^mail\\..*\\.example\\.com$");
        assertThat(BypassPatternParser.parse("*.example.*​")).isEqualTo(
                new BypassPatternParser.Parsed(BypassPatternParser.Kind.DOMAIN_KEYWORD, "example"));
        assertThat(BypassPatternParser.parse("example.com‏")).isEqualTo(
                new BypassPatternParser.Parsed(BypassPatternParser.Kind.DOMAIN, "example.com"));

        // What IDN still cannot convert is written as a code point Go reads.
        for (String line : java.util.List.of("mail.*.󰀀.com", "mail.*.☃א.com",
                "mail.*.пример.рф‎", "mail.*.𝅳.com")) {
            String regex = BypassPatternParser.parse(line).value();
            assertThat(regex).as(line).doesNotContain("\\‎").matches("\\p{ASCII}+");
            assertThat(RoutingRuleCheck.problem(
                    com.vlessclient.model.RoutingRule.RuleType.DOMAIN_REGEX, regex))
                    .as("%s -> %s", line, regex).isEmpty();
        }
    }
}
