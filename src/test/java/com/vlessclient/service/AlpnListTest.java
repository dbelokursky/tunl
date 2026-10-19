package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.testing.TestServers;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An ALPN list with an empty item, from a link's alpn=h2%2C%2Chttp%2F1.1 or
 * the form's field, went to the core as it was split: ["h2", "",
 * "http/1.1"]. The core's check takes that, and then every dial failed before
 * a ClientHello went out ("tls: invalid NextProtos value"): the server showed
 * Connected and carried nothing. Empty items are left out whenever a
 * configuration is generated, so a server stored with one connects too.
 */
class AlpnListTest {

    private static final String UUID = "b1c2d3e4-f5a6-7890-abcd-ef1234567890";

    private final SingBoxConfigGenerator generator = new SingBoxConfigGenerator();

    @Test
    void aLinksListWithAnEmptyItemIsSentWithoutIt() throws Exception {
        ServerConfig server = new ShareLinkParser().parseForImport("vless://" + UUID
                + "@203.0.113.1:443?security=tls&sni=a.example&alpn=h2%2C%2Chttp%2F1.1#a");

        assertThat(alpnOf(server)).containsExactly("h2", "http/1.1");
    }

    @Test
    void emptyItemsOfAStoredListAreLeftOut() throws Exception {
        assertThat(alpnOf(server(Protocol.VLESS, ",h2"))).containsExactly("h2");
        assertThat(alpnOf(server(Protocol.VLESS, "h2, ,http/1.1")))
                .containsExactly("h2", "http/1.1");
        assertThat(alpnOf(server(Protocol.HYSTERIA2, ",h3"))).containsExactly("h3");
        assertThat(alpnOf(server(Protocol.VLESS, "h2,"))).containsExactly("h2");
    }

    @Test
    void aListOfOnlyEmptyItemsAsksForNone() throws Exception {
        assertThat(tlsOf(server(Protocol.VLESS, " , ")).has("alpn")).isFalse();
    }

    private static ServerConfig server(Protocol protocol, String alpn) {
        return TestServers.server()
                .name("alpn")
                .protocol(protocol)
                .address("203.0.113.1")
                .port(443)
                .uuid(UUID)
                .tls("a.example")
                .alpn(alpn)
                .build();
    }

    private List<String> alpnOf(ServerConfig server) throws Exception {
        List<String> items = new ArrayList<>();
        tlsOf(server).get("alpn").forEach(item -> items.add(item.asString()));
        return items;
    }

    private JsonNode tlsOf(ServerConfig server) throws Exception {
        JsonNode root = JsonMapper.builder().build()
                .readTree(generator.generate(server, new AppSettings()));
        for (JsonNode outbound : root.get("outbounds")) {
            if (outbound.has("tls")) {
                return outbound.get("tls");
            }
        }
        throw new AssertionError("no outbound carries TLS");
    }
}
