package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TransportType;
import com.vlessclient.testing.TestServers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Share links list an HTTP/2 transport's hosts comma-separated, as v2rayN's
 * VMess links and Xray's VLESS ones write them. The core was given the list
 * as one host, sent "Host: a.example,b.example" on every request, and a
 * server holding [a.example, b.example] answered 400: it never carried
 * traffic. The hosts are now sent one by one whenever a configuration is
 * generated, so a server stored with such a list connects too.
 */
class Http2HostListTest {

    private static final String UUID = "b1c2d3e4-f5a6-7890-abcd-ef1234567890";

    private final SingBoxConfigGenerator generator = new SingBoxConfigGenerator();

    @Test
    void aVlessLinksHostListIsSentHostByHost() throws Exception {
        ServerConfig server = new ShareLinkParser().parseForImport("vless://" + UUID
                + "@203.0.113.1:443?security=tls&sni=a.example&type=http&path=%2Fh2"
                + "&host=a.example%2Cb.example#h2");

        assertThat(hostsOf(server)).containsExactly("a.example", "b.example");
    }

    @Test
    void aVmessLinksHostListIsSentHostByHost() throws Exception {
        String json = "{\"v\":\"2\",\"ps\":\"h2\",\"add\":\"203.0.113.1\",\"port\":\"443\","
                + "\"id\":\"" + UUID + "\",\"net\":\"h2\",\"path\":\"/h2\","
                + "\"host\":\"a.example, b.example\",\"tls\":\"tls\",\"sni\":\"a.example\"}";
        ServerConfig server = new ShareLinkParser().parseForImport("vmess://" + Base64
                .getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));

        assertThat(hostsOf(server)).containsExactly("a.example", "b.example");
    }

    @Test
    void emptyItemsAreLeftOutAndOneHostStaysAHost() throws Exception {
        assertThat(hostsOf(server("a.example,,b.example,"))).containsExactly("a.example", "b.example");
        assertThat(transportOf(server("cdn.example")).get("host").isString()).isTrue();
        assertThat(transportOf(server(" , ")).has("host")).isFalse();
    }

    @Test
    void anHttpUpgradeHostIsLeftAsItIs() throws Exception {
        // One host there, in the core and in Xray alike.
        ServerConfig server = server("a.example,b.example");
        server.getTransport().setType(TransportType.HTTPUPGRADE);

        assertThat(transportOf(server).get("host").asString()).isEqualTo("a.example,b.example");
    }

    private static ServerConfig server(String host) {
        return TestServers.server()
                .name("h2")
                .protocol(Protocol.VLESS)
                .address("203.0.113.1")
                .port(443)
                .uuid(UUID)
                .tls("a.example")
                .transport(TransportType.HTTP2, "/h2", host)
                .build();
    }

    private List<String> hostsOf(ServerConfig server) throws Exception {
        JsonNode host = transportOf(server).get("host");
        assertThat(host.isArray()).as("host %s", host).isTrue();
        List<String> hosts = new ArrayList<>();
        host.forEach(item -> hosts.add(item.asString()));
        return hosts;
    }

    private JsonNode transportOf(ServerConfig server) throws Exception {
        JsonNode root = JsonMapper.builder().build()
                .readTree(generator.generate(server, new AppSettings()));
        for (JsonNode outbound : root.get("outbounds")) {
            if (outbound.has("transport")) {
                return outbound.get("transport");
            }
        }
        throw new AssertionError("no outbound carries a transport");
    }
}
