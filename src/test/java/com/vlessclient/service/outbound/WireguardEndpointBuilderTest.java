package com.vlessclient.service.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.testing.TestServers;
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
