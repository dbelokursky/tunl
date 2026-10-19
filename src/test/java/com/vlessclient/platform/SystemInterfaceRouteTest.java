package com.vlessclient.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.NetworkInterface;
import org.junit.jupiter.api.Test;

/**
 * The host's own routes, as a connected UDP socket is given them: nothing is
 * sent. Each answer here holds on every host, whatever its networks.
 */
class SystemInterfaceRouteTest {

    /** No default route leaves through the loopback. */
    @Test
    void theLoopbackIsReachedThroughItsOwnInterface() throws Exception {
        InetAddress loopback = InetAddress.ofLiteral("127.0.0.1");
        String name = NetworkInterface.getByInetAddress(loopback).getName();

        assertThat(SystemInterfaceRoute.offDefault(loopback)).hasValueSatisfying(route -> {
            assertThat(route.interfaceName()).isEqualTo(name);
            assertThat(route.source()).isEqualTo(loopback);
        });
    }

    /**
     * What only the default route takes is left to it, IPv6 too, which a host
     * without IPv6 answers with no route at all.
     */
    @Test
    void anAddressTheDefaultRouteTakesIsLeftToIt() {
        assertThat(SystemInterfaceRoute.offDefault(InetAddress.ofLiteral("203.0.113.1")))
                .isEmpty();
        assertThat(SystemInterfaceRoute.offDefault(InetAddress.ofLiteral("2001:db8::1")))
                .isEmpty();
        assertThat(InterfaceRoute.current().offDefault(InetAddress.ofLiteral("203.0.113.9")))
                .isEmpty();
    }
}
