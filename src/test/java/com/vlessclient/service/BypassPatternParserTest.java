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

    /** A star between names stands for anything, dots included, which only a regex says. */
    @Test
    void aStarInTheMiddleBecomesARegex() {
        assertThat(BypassPatternParser.parse("mail.*.com")).isEqualTo(
                new BypassPatternParser.Parsed(
                        BypassPatternParser.Kind.DOMAIN_REGEX, "^mail\\..*\\.com$"));
        assertThat(BypassPatternParser.parse("*.кремль.*.рф").value())
                .isEqualTo("^.*\\.xn--e1ajeds9e\\..*\\.xn--p1ai$");
    }
}
