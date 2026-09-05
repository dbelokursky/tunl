package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TransportType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP Upgrade links used to fail at the transport dispatch with "Unknown
 * transport type", and the sing-box-flavoured {@code insecure=1} was read
 * only for Hysteria2 while VLESS and Trojan links spelled it the same way.
 */
class HttpUpgradeTransportTest {

    private final ShareLinkParser parser = new ShareLinkParser();

    @Test
    void anHttpUpgradeLinkParsesWithItsPathAndHost() {
        ServerConfig config = parser.parse("vless://uuid@host.example:443?security=tls"
                + "&type=httpupgrade&path=%2Fup&host=cdn.example&insecure=1#Up");

        assertThat(config.getTransport().getType()).isEqualTo(TransportType.HTTPUPGRADE);
        assertThat(config.getTransport().getPath()).isEqualTo("/up");
        assertThat(config.getTransport().getHost()).isEqualTo("cdn.example");
        assertThat(config.getTls().isAllowInsecure()).isTrue();
    }

    @Test
    void theGeneratedOutboundCarriesTheHttpUpgradeTransport() throws Exception {
        ServerConfig config = parser.parse("trojan://pw@host.example:443?security=tls"
                + "&type=httpupgrade&path=%2Fup&host=cdn.example#Up");

        String json = new SingBoxConfigGenerator().generate(config, new AppSettings());
        JsonNode root = JsonMapper.builder().build().readTree(json);

        JsonNode transport = null;
        for (JsonNode outbound : root.get("outbounds")) {
            if (outbound.has("transport")) {
                transport = outbound.get("transport");
            }
        }
        assertThat(transport).isNotNull();
        assertThat(transport.get("type").asString()).isEqualTo("httpupgrade");
        assertThat(transport.get("path").asString()).isEqualTo("/up");
        assertThat(transport.get("host").asString()).isEqualTo("cdn.example");
    }

    @Test
    void trojanReadsTheShortInsecureSpellingToo() {
        ServerConfig config = parser.parse("trojan://pw@host.example:443?security=tls&insecure=1#T");

        assertThat(config.getTls().isAllowInsecure()).isTrue();
    }
}
