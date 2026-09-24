package com.vlessclient.platform;

import java.net.Inet6Address;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class GlobalIpv6AddressProbeTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "2a02:6b8::2:242",      // ya.ru, which Chrome could not reach direct
        "2001:4860:4860::8888", // Google Public DNS
        "2606:4700:4700::1111", // Cloudflare
    })
    void publicAddressesCount(String literal) {
        assertThat(GlobalIpv6AddressProbe.isGlobalUnicast(v6(literal))).isTrue();
    }

    /**
     * The Wi-Fi Russian sites stopped opening on had a unique local address and
     * link-local ones, and the tunnel's own address is a ULA as well: none of
     * them leaves the local network, so none makes it an IPv6 network.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "fd94:f522:9369:49cd:456:bbc0:cffa:699b", // ULA from the home router
        "fdfe:dcba:9876::1",                      // the TUN device's own address
        "fe80::44e:997f:acf8:9586",               // link-local
        "::1",                                    // loopback
        "::",                                     // unspecified
        "ff02::1",                                // multicast
    })
    void localAddressesDoNotCount(String literal) {
        assertThat(GlobalIpv6AddressProbe.isGlobalUnicast(v6(literal))).isFalse();
    }

    /**
     * Teredo and 6to4 addresses sit inside the global range but belong to
     * tunnels of their own, not to the network the direct outbound dials
     * through. Google's 2001:4860:: above shares Teredo's first group and
     * still counts.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "2001:0:4136:e378:8000:63bf:3fff:fdd2", // Teredo
        "2002:c000:204::1",                     // 6to4
    })
    void tunnelAddressesDoNotCount(String literal) {
        assertThat(GlobalIpv6AddressProbe.isGlobalUnicast(v6(literal))).isFalse();
    }

    @Test
    void scanningThisHostAnswers() {
        assertThatCode(() -> Ipv6Uplink.current().isPresent()).doesNotThrowAnyException();
    }

    private static Inet6Address v6(String literal) {
        return (Inet6Address) InetAddress.ofLiteral(literal);
    }
}
