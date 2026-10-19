package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.platform.InterfaceRoute;
import com.vlessclient.platform.Platform;
import com.vlessclient.testing.TestServers;
import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A WireGuard peer the system reaches through another interface than the
 * default route's, a second network card or a Docker or virtual machine
 * bridge: the route's auto_detect_interface bound the endpoint's socket to
 * the default interface, and the handshake never arrived while the server
 * showed Connected, in either mode. The endpoint is bound to the peer's
 * interface from the run's host facts, so the configuration generated again
 * to compare, on the FX thread, binds it the same way and asks nothing.
 */
class SingBoxConfigGeneratorWireguardRouteTest {

    private static final InterfaceRoute.Route VETH =
            new InterfaceRoute.Route("veth0", InetAddress.ofLiteral("198.18.0.1"));

    @Test
    void aPeerOffTheDefaultInterfaceIsBoundToItAndAskedOncePerRun() throws Exception {
        List<InetAddress> asked = new CopyOnWriteArrayList<>();
        SingBoxConfigGenerator generator = SingBoxConfigGenerator.withRoutes(destination -> {
            asked.add(destination);
            return Optional.of(VETH);
        });
        ServerConfig server = wireguard("198.18.0.2");
        for (ProxyMode mode : ProxyMode.values()) {
            asked.clear();
            AppSettings settings = new AppSettings();
            settings.setProxyMode(mode);
            HostFacts run = generator.hostFacts();

            String config = generator.generate(List.of(server), server, settings, null, run);
            String again = generator.generate(List.of(server), server, settings, null, run);

            assertThat(again).as(mode.name()).isEqualTo(config);
            assertThat(asked).as(mode.name())
                    .containsExactly(InetAddress.ofLiteral("198.18.0.2"));
            JsonNode endpoint = JsonMapper.builder().build().readTree(config)
                    .path("endpoints").get(0);
            switch (Platform.current()) {
                case WINDOWS -> assertThat(endpoint.path("inet4_bind_address").asString())
                        .as(mode.name()).isEqualTo("198.18.0.1");
                case LINUX, MAC -> assertThat(endpoint.path("bind_interface").asString())
                        .as(mode.name()).isEqualTo("veth0");
                default -> assertThat(endpoint.has("bind_interface")).isFalse();
            }
        }
    }

    @Test
    void aPeerThroughTheDefaultInterfaceIsLeftToTheCore() throws Exception {
        SingBoxConfigGenerator generator =
                SingBoxConfigGenerator.withRoutes(InterfaceRoute.DEFAULT_ONLY);
        ServerConfig server = wireguard("203.0.113.11");

        JsonNode endpoint = JsonMapper.builder().build()
                .readTree(generator.generate(server, new AppSettings())).path("endpoints").get(0);

        assertThat(endpoint.has("bind_interface")).isFalse();
        assertThat(endpoint.has("inet4_bind_address")).isFalse();
        assertThat(endpoint.has("inet6_bind_address")).isFalse();
    }

    private static ServerConfig wireguard(String peer) {
        return TestServers.server()
                .id("wg")
                .name("wg")
                .protocol(Protocol.WIREGUARD)
                .address(peer)
                .port(41041)
                .uuid("yAsvzGasgRVNEgeLKWg180ByMB7LlKAPpNON6mlz33g=")
                .encryption("Jlis5v0ROUPH5A57OPn/oLF4Y+qntinc92Zfh/BOkhQ=")
                .flow("10.13.0.2/32")
                .build();
    }
}
