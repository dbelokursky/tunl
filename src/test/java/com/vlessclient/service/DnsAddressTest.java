package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DnsAddressTest {

    /** Every way the fields took an address that worked: they still do. */
    static final List<String> ONE_ADDRESS = List.of(
            "https://1.1.1.1/dns-query", "https://dns.google/dns-query", "tls://dns.quad9.net",
            "quic://dns.adguard-dns.com", "h3://dns.google/dns-query", "dns.google",
            "8.8.8.8:53", "dns.google:53", "[2001:4860:4860::8888]:53", "2001:4860:4860::8888",
            "https://[2606:4700:4700::1111]/dns-query", "udp://[2001:4860:4860::8888]:53",
            "77.88.8.8", "HTTPS://1.1.1.1/dns-query", "https://Dns.Google/dns-query",
            "https://dns.nextdns.io/ab12cd", "https://d.adguard-dns.com/dns-query/SECRET-TOKEN",
            "localhost:5353", "127.0.0.1:5353", "tcp://1.1.1.1:53", "dns.google.",
            "xn--d1acufc.xn--p1ai", "1.1.1.1:", " tls://9.9.9.9:853 ", "dns_01.corp.example",
            "https://1.1.1.1:443/dns-query?ct=application/dns-message");

    /**
     * What reached the core as a server's name, or made it refuse the whole
     * configuration: a list, a scheme it has no server for, a port outside
     * 1 to 65535, a name no host has.
     */
    static final List<String> NOT_ONE_ADDRESS = List.of(
            "", "  ", "1.1.1.1, 8.8.8.8", "1.1.1.1,8.8.8.8", "1.1.1.1 8.8.8.8",
            "1.1.1.1; 8.8.8.8", "1.1.1.1;8.8.8.8", "https://1.1.1.1/dns-query,https://8.8.8.8/x",
            "udp:// 1.1.1.1", "\"1.1.1.1\"", "'1.1.1.1'", "https://", "udp://", "local",
            "fakeip", "system", "dhcp://auto", "http://1.1.1.1/dns-query", "local://x",
            "https://днс.example/dns-query", "днс.example", "udp://1.1.1.1:99999",
            "1.1.1.1:99999", "8.8.8.8:70000", "1.1.1.1:0", "1.1.1.1:+53", "1.1.1.1:5x",
            "01.1.1.1", "1.1.1.256", "1.1.1", "dns..google", "-dns.google", "dns.google-",
            "[2001:4860:4860::8888", "[2001:4860:4860::8888]x", "[dns.google]:53",
            "2001:4860:4860::8888::1", "a.b.c:53:54");

    @Test
    void oneAddressIsReadAsTheCoreTakesIt() {
        for (String address : ONE_ADDRESS) {
            assertThat(DnsAddress.parse(address)).as(address).isPresent();
        }
        assertThat(DnsAddress.parse("8.8.8.8:53"))
                .contains(new DnsAddress("udp", "8.8.8.8", 53, ""));
        assertThat(DnsAddress.parse("HTTPS://1.1.1.1/dns-query"))
                .contains(new DnsAddress("https", "1.1.1.1", 0, "/dns-query"));
        assertThat(DnsAddress.parse("https://[2606:4700:4700::1111]:8443/dns-query"))
                .contains(new DnsAddress("https", "2606:4700:4700::1111", 8443, "/dns-query"));
        assertThat(DnsAddress.parse("https://1.1.1.1/dns-query?ct=x#y"))
                .contains(new DnsAddress("https", "1.1.1.1", 0, "/dns-query"));
        // A path is the core's only for DNS over HTTPS.
        assertThat(DnsAddress.parse("quic://dns.adguard-dns.com/dns-query"))
                .contains(new DnsAddress("quic", "dns.adguard-dns.com", 0, ""));
        assertThat(DnsAddress.parse("tls://user@9.9.9.9:853"))
                .contains(new DnsAddress("tls", "9.9.9.9", 853, ""));
    }

    @Test
    void anythingButOneAddressIsNone() {
        for (String text : NOT_ONE_ADDRESS) {
            assertThat(DnsAddress.parse(text)).as(text).isEmpty();
        }
        assertThat(DnsAddress.parse(null)).isEmpty();
    }

    /** Blank stands for the field's default; only Direct DNS takes "system". */
    @Test
    void eachFieldTakesItsOwnWords() {
        for (String value : new String[] {null, "", " "}) {
            assertThat(DnsAddress.isProxySetting(value)).as("proxy %s", value).isTrue();
            assertThat(DnsAddress.isDirectSetting(value)).as("direct %s", value).isTrue();
        }
        assertThat(DnsAddress.isProxySetting("system")).isFalse();
        assertThat(DnsAddress.isDirectSetting("system")).isTrue();
        assertThat(DnsAddress.isDirectSetting(" System ")).isTrue();
        assertThat(DnsAddress.isProxySetting("1.1.1.1, 8.8.8.8")).isFalse();
        assertThat(DnsAddress.isDirectSetting("1.1.1.1, 8.8.8.8")).isFalse();
        assertThat(DnsAddress.isProxySetting("tls://9.9.9.9")).isTrue();
        assertThat(DnsAddress.isDirectSetting("tls://9.9.9.9")).isTrue();
    }
}
