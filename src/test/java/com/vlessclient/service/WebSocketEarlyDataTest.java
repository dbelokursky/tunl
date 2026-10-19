package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TransportType;
import com.vlessclient.testing.TestServers;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Xray's links ask for WebSocket early data in the path: {@code /ws?ed=2048}.
 * Xray's client takes the parameter out of the path and sends the first bytes
 * in the {@code Sec-WebSocket-Protocol} header, and its server compares the
 * path without it. The core was handed the path as it was, and sent it with
 * the {@code ?} escaped, {@code GET /ws%3Fed=2048}, which such a server
 * answers with 404: the server showed Connected and never carried a byte.
 * HTTPUpgrade links carry the same parameter, which the core's HTTPUpgrade
 * has no early data for.
 *
 * <p>The stored path keeps the parameter, so a server imported before this
 * connects without a new import, and a copied link still carries it for the
 * clients that read it.</p>
 */
class WebSocketEarlyDataTest {

    private static final String UUID = "b1c2d3e4-f5a6-7890-abcd-ef1234567890";

    private final SingBoxConfigGenerator generator = new SingBoxConfigGenerator();
    private final ShareLinkParser parser = new ShareLinkParser();
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void aWebSocketLinksEarlyDataGoesInTheHeaderAndLeavesThePath() throws Exception {
        ServerConfig server = parser.parseForImport("vless://" + UUID + "@203.0.113.1:443"
                + "?security=tls&sni=a.example&type=ws&path=%2Fws%3Fed%3D2048"
                + "&host=cdn.example#ws");

        JsonNode transport = transportOf(server);

        assertThat(transport.get("path").asString()).isEqualTo("/ws");
        assertThat(transport.get("max_early_data").asInt()).isEqualTo(2048);
        assertThat(transport.get("early_data_header_name").asString())
                .isEqualTo("Sec-WebSocket-Protocol");
        assertThat(transport.get("headers").get("Host").asString()).isEqualTo("cdn.example");
    }

    @Test
    void aServerStoredWithEarlyDataInItsPathConnectsWithoutANewImport() throws Exception {
        JsonNode transport = transportOf(server(TransportType.WEBSOCKET, "/ws?ed=2048"));

        assertThat(transport.get("path").asString()).isEqualTo("/ws");
        assertThat(transport.get("max_early_data").asInt()).isEqualTo(2048);
    }

    @Test
    void aVmessLinksEarlyDataIsReadFromItsPathToo() throws Exception {
        String json = "{\"v\":\"2\",\"ps\":\"vm\",\"add\":\"203.0.113.1\",\"port\":\"443\","
                + "\"id\":\"" + UUID + "\",\"net\":\"ws\",\"path\":\"/?ed=2560\","
                + "\"host\":\"cdn.example\",\"tls\":\"tls\",\"sni\":\"a.example\"}";
        ServerConfig server = parser.parseForImport("vmess://" + Base64.getEncoder()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8)));

        JsonNode transport = transportOf(server);

        assertThat(transport.get("path").asString()).isEqualTo("/");
        assertThat(transport.get("max_early_data").asInt()).isEqualTo(2560);
    }

    @Test
    void theOtherParametersOfThePathStayInIt() throws Exception {
        assertThat(transportOf(server(TransportType.WEBSOCKET, "/ws?ed=2048&token=abc"))
                .get("path").asString()).isEqualTo("/ws?token=abc");
        assertThat(transportOf(server(TransportType.WEBSOCKET, "/ws?token=abc&ed=2048"))
                .get("path").asString()).isEqualTo("/ws?token=abc");
    }

    @Test
    void aPathWithoutEarlyDataIsSentAsItIs() throws Exception {
        JsonNode transport = transportOf(server(TransportType.WEBSOCKET, "/ws?token=abc"));

        assertThat(transport.get("path").asString()).isEqualTo("/ws?token=abc");
        assertThat(transport.has("max_early_data")).isFalse();
        assertThat(transport.has("early_data_header_name")).isFalse();
    }

    @Test
    void anEarlyDataSizeThatIsNoNumberLeavesThePathAndAsksForNone() throws Exception {
        // As Xray reads it: the parameter goes, and the size it could not read is 0.
        JsonNode transport = transportOf(server(TransportType.WEBSOCKET, "/ws?ed=abc"));

        assertThat(transport.get("path").asString()).isEqualTo("/ws");
        assertThat(transport.has("max_early_data")).isFalse();
        assertThat(transport.has("early_data_header_name")).isFalse();
    }

    @Test
    void httpUpgradeLeavesTheParameterOutAndSendsNoEarlyData() throws Exception {
        ServerConfig server = parser.parseForImport("vless://" + UUID + "@203.0.113.1:443"
                + "?security=tls&sni=a.example&type=httpupgrade&path=%2Fup%3Fed%3D2048"
                + "&host=cdn.example#up");

        JsonNode transport = transportOf(server);

        assertThat(transport.propertyNames()).containsExactlyInAnyOrder("type", "path", "host");
        assertThat(transport.get("path").asString()).isEqualTo("/up");
    }

    @Test
    void anHttp2PathIsLeftAsItIs() throws Exception {
        // Xray reads early data on WebSocket and HTTPUpgrade only.
        assertThat(transportOf(server(TransportType.HTTP2, "/h2?ed=2048"))
                .get("path").asString()).isEqualTo("/h2?ed=2048");
    }

    @Test
    void aCopiedLinkKeepsTheParameterAndReadsBackAsTheSameServer() {
        String link = "vless://" + UUID + "@203.0.113.1:443?security=tls&sni=a.example"
                + "&type=ws&path=%2Fws%3Fed%3D2048&host=cdn.example#ws";
        ServerConfig server = parser.parseForImport(link);

        String exported = new ShareLinkExporter().export(server);

        assertThat(server.getTransport().getPath()).isEqualTo("/ws?ed=2048");
        assertThat(exported).contains("path=%2Fws%3Fed%3D2048");
        assertThat(parser.parseForImport(exported).getTransport().getPath())
                .isEqualTo("/ws?ed=2048");
    }

    private static ServerConfig server(TransportType type, String path) {
        return TestServers.server()
                .name("early-data")
                .protocol(Protocol.VLESS)
                .address("203.0.113.1")
                .port(443)
                .uuid(UUID)
                .tls("a.example")
                .transport(type, path, "cdn.example")
                .build();
    }

    private JsonNode transportOf(ServerConfig server) throws Exception {
        JsonNode root = mapper.readTree(generator.generate(server, new AppSettings()));
        for (JsonNode outbound : root.get("outbounds")) {
            if (outbound.has("transport")) {
                return outbound.get("transport");
            }
        }
        throw new AssertionError("no outbound carries a transport");
    }
}
