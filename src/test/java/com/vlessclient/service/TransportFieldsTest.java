package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TransportType;
import com.vlessclient.testing.TestServers;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * sing-box gives each transport its own fields and refuses the whole
 * configuration over one it does not know. The generator wrote path, host and
 * service name whatever the type, so a WebSocket link with a Host header (the
 * usual CDN setup) or a gRPC link with an authority produced an outbound the
 * core would not build. Every stored server is a member of the proxy group,
 * so that one server stopped all of them from connecting.
 *
 * <p>The real core's verdict on these shapes is asserted in
 * {@code SingBoxRealBinarySmokeTest}; this pins the field sets so a change
 * shows up without the binary.</p>
 */
class TransportFieldsTest {

    private final SingBoxConfigGenerator generator = new SingBoxConfigGenerator();
    private final ShareLinkParser parser = new ShareLinkParser();
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void webSocketSendsTheHostAsAHeaderRatherThanAField() throws Exception {
        JsonNode transport = transportOf(serverWith(TransportType.WEBSOCKET));

        assertThat(transport.propertyNames()).containsExactlyInAnyOrder("type", "path", "headers");
        assertThat(transport.get("path").asString()).isEqualTo("/path");
        assertThat(transport.get("headers").get("Host").asString()).isEqualTo("cdn.example.com");
    }

    @Test
    void aHostHeaderSetExplicitlyIsKeptOverTheHostField() throws Exception {
        ServerConfig server = serverWith(TransportType.WEBSOCKET);
        server.getTransport().setHeaders(Map.of("host", "header.example.com"));

        JsonNode headers = transportOf(server).get("headers");

        assertThat(headers.propertyNames()).containsExactly("host");
        assertThat(headers.get("host").asString()).isEqualTo("header.example.com");
    }

    @Test
    void grpcCarriesOnlyTheServiceName() throws Exception {
        JsonNode transport = transportOf(serverWith(TransportType.GRPC));

        assertThat(transport.propertyNames()).containsExactlyInAnyOrder("type", "service_name");
        assertThat(transport.get("service_name").asString()).isEqualTo("svc");
    }

    @Test
    void grpcTakesTheServiceNameFromThePathAnEarlierVmessImportLeftItIn() throws Exception {
        ServerConfig server = serverWith(TransportType.GRPC);
        server.getTransport().setServiceName(null);
        server.getTransport().setPath("imported-svc");

        JsonNode transport = transportOf(server);

        assertThat(transport.propertyNames()).containsExactlyInAnyOrder("type", "service_name");
        assertThat(transport.get("service_name").asString()).isEqualTo("imported-svc");
    }

    @Test
    void http2AndHttpUpgradeKeepTheirHostAndPath() throws Exception {
        for (TransportType type : List.of(TransportType.HTTP2, TransportType.HTTPUPGRADE)) {
            JsonNode transport = transportOf(serverWith(type));

            assertThat(transport.propertyNames()).as(type.name())
                    .containsExactlyInAnyOrder("type", "path", "host");
            assertThat(transport.get("host").asString()).as(type.name())
                    .isEqualTo("cdn.example.com");
        }
    }

    @Test
    void quicTakesNoFields() throws Exception {
        assertThat(transportOf(serverWith(TransportType.QUIC)).propertyNames())
                .containsExactly("type");
    }

    @Test
    void aVmessGrpcLinkStoresItsPathAsTheServiceName() {
        String json = """
                {"v":"2","ps":"g","add":"grpc.example.com","port":"443","id":"id",\
                "net":"grpc","type":"gun","host":"grpc.example.com","path":"svc","tls":"tls"}""";

        ServerConfig config = parser.parse("vmess://" + Base64.getEncoder()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8)));

        assertThat(config.getTransport().getServiceName()).isEqualTo("svc");
        assertThat(config.getTransport().getPath()).isNull();
    }

    @Test
    void aVmessGrpcServerExportsItsServiceNameWhereVmessLinksKeepIt() throws Exception {
        ServerConfig server = serverWith(TransportType.GRPC);
        server.setProtocol(Protocol.VMESS);

        String link = new ShareLinkExporter().exportVmess(server);
        JsonNode json = mapper.readTree(
                Base64.getDecoder().decode(link.substring("vmess://".length())));

        assertThat(json.get("path").asString()).isEqualTo("svc");
        assertThat(parser.parse(link).getTransport().getServiceName()).isEqualTo("svc");
    }

    private ServerConfig serverWith(TransportType type) {
        return TestServers.server()
                .name("transport")
                .protocol(Protocol.VLESS)
                .address("203.0.113.1")
                .port(443)
                .uuid("b1c2d3e4-f5a6-7890-abcd-ef1234567890")
                .tls("example.com")
                .transport(type, "/path", "cdn.example.com")
                .serviceName("svc")
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
