package com.vlessclient.service.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.platform.InterfaceRoute;
import com.vlessclient.platform.Platform;
import com.vlessclient.testing.TestServers;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A WireGuard peer on this machine, a local relay or obfuscator as udp2raw
 * and wg-obfuscator setups have it. The route's auto_detect_interface binds
 * the endpoint's socket to the default interface before it knows the peer,
 * and a packet to 127.0.0.1 sent from there never arrives: the handshake was
 * retried forever. An endpoint with a bind address of its own is left out of
 * that bind, so a loopback peer gets the loopback address. The real core's
 * handshake is asserted in {@code SingBoxRealBinarySmokeTest}.
 */
class WireguardEndpointBuilderTest {

    private final WireguardEndpointBuilder builder =
            new WireguardEndpointBuilder(JsonMapper.builder().build());

    @Test
    void aPeerOnTheIpv4LoopbackIsReachedFromIt() {
        for (String peer : new String[] {"127.0.0.1", "127.0.0.53"}) {
            ObjectNode endpoint = builder.build(wireguard(peer), "srv-wg");

            assertThat(endpoint.path("inet4_bind_address").asString()).as(peer)
                    .isEqualTo("127.0.0.1");
            assertThat(endpoint.has("inet6_bind_address")).as(peer).isFalse();
        }
    }

    @Test
    void aPeerOnTheIpv6LoopbackIsReachedFromIt() {
        ObjectNode endpoint = builder.build(wireguard("::1"), "srv-wg");

        assertThat(endpoint.path("inet6_bind_address").asString()).isEqualTo("::1");
        assertThat(endpoint.has("inet4_bind_address")).isFalse();
    }

    @Test
    void aPeerNamedLocalhostIsReachedFromEitherLoopback() {
        ObjectNode endpoint = builder.build(wireguard("LocalHost"), "srv-wg");

        assertThat(endpoint.path("inet4_bind_address").asString()).isEqualTo("127.0.0.1");
        assertThat(endpoint.path("inet6_bind_address").asString()).isEqualTo("::1");
    }

    @Test
    void aPeerElsewhereKeepsTheDefaultInterface() {
        for (String peer : new String[] {"203.0.113.5", "wg.example.com", "10.0.0.1", "::2"}) {
            ObjectNode endpoint = builder.build(wireguard(peer), "srv-wg");

            assertThat(endpoint.has("inet4_bind_address")).as(peer).isFalse();
            assertThat(endpoint.has("inet6_bind_address")).as(peer).isFalse();
        }
    }

    /**
     * A peer the system reaches through another interface than the default
     * route's: a second network card, a Docker or virtual machine bridge,
     * another VPN's route. The endpoint's socket was bound to the default
     * interface all the same, and the handshake never arrived. On Linux and
     * macOS it is bound to that interface by name; an address alone left the
     * socket to the TUN device's rules on Linux, which took the handshake
     * into the tunnel. On Windows, where Java's interface names are not the
     * core's, by the local address the system sends from.
     */
    @Test
    void aPeerReachedThroughAnotherInterfaceIsBoundToIt() {
        InterfaceRoute veth = destination -> Optional.of(new InterfaceRoute.Route("veth0",
                InetAddress.ofLiteral(destination instanceof Inet6Address ? "fd00::1"
                        : "198.18.0.1")));
        for (Platform platform : List.of(Platform.LINUX, Platform.MAC)) {
            ObjectNode endpoint = new WireguardEndpointBuilder(JsonMapper.builder().build(),
                    platform).build(wireguard("198.18.0.2"), "srv-wg", veth);

            assertThat(endpoint.path("bind_interface").asString()).as(platform.name())
                    .isEqualTo("veth0");
            assertThat(endpoint.has("inet4_bind_address")).as(platform.name()).isFalse();
            assertThat(endpoint.has("inet6_bind_address")).as(platform.name()).isFalse();
        }
        WireguardEndpointBuilder windows =
                new WireguardEndpointBuilder(JsonMapper.builder().build(), Platform.WINDOWS);
        ObjectNode v4 = windows.build(wireguard("198.18.0.2"), "srv-wg", veth);
        ObjectNode v6 = windows.build(wireguard("fd00::2"), "srv-wg", veth);

        assertThat(v4.path("inet4_bind_address").asString()).isEqualTo("198.18.0.1");
        assertThat(v4.has("bind_interface")).isFalse();
        assertThat(v6.path("inet6_bind_address").asString()).isEqualTo("fd00:0:0:0:0:0:0:1");
        assertThat(v6.has("inet4_bind_address")).isFalse();
        assertThat(new WireguardEndpointBuilder(JsonMapper.builder().build(), Platform.OTHER)
                .build(wireguard("198.18.0.2"), "srv-wg", veth).has("bind_interface"))
                .as("the core binds to an interface on Linux, macOS and Windows only").isFalse();
    }

    /**
     * The default route's interface, which the core binds to by itself, is
     * left to it; so is a name, which would have to be looked up, and an
     * address the system routes nowhere else: loopback, link-local, multicast.
     */
    @Test
    void onlyAnAddressRoutedOffTheDefaultInterfaceIsBound() {
        List<InetAddress> asked = new ArrayList<>();
        InterfaceRoute viaDefault = destination -> {
            asked.add(destination);
            return Optional.empty();
        };
        WireguardEndpointBuilder linux =
                new WireguardEndpointBuilder(JsonMapper.builder().build(), Platform.LINUX);

        ObjectNode endpoint = linux.build(wireguard("203.0.113.5"), "srv-wg", viaDefault);
        assertThat(endpoint.has("bind_interface")).isFalse();
        assertThat(endpoint.has("inet4_bind_address")).isFalse();
        assertThat(asked).containsExactly(InetAddress.ofLiteral("203.0.113.5"));

        asked.clear();
        for (String peer : new String[] {"wg.example.com", "localhost", "127.0.0.1", "::1",
            "fe80::1", "224.0.0.1", "0.0.0.0", ""}) {
            linux.build(wireguard(peer), "srv-wg", viaDefault);
        }
        assertThat(asked).as("looked up").isEmpty();
    }

    private static ServerConfig wireguard(String peer) {
        return TestServers.server()
                .id("wg")
                .name("wg")
                .protocol(Protocol.WIREGUARD)
                .address(peer)
                .port(51820)
                .uuid("yAsvzGasgRVNEgeLKWg180ByMB7LlKAPpNON6mlz33g=")
                .encryption("Jlis5v0ROUPH5A57OPn/oLF4Y+qntinc92Zfh/BOkhQ=")
                .flow("10.13.0.2/32")
                .build();
    }
}
