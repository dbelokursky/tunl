package com.vlessclient.service;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WireGuard is the one supported protocol with no share-link format, so the
 * import path takes the {@code .conf} file a provider actually hands out.
 * These cover the shapes that show up in the wild plus the failures that must
 * be explained rather than swallowed — a half-parsed config would produce a
 * server that cannot connect and looks like a server-side problem.
 */
class WireguardConfigParserTest {

    private final WireguardConfigParser parser = new WireguardConfigParser();

    private static final String TYPICAL = """
            [Interface]
            PrivateKey = qGvIaLPuXqhbbFJTZ8kMtKz1BZ0i9CTfKk1PavDTVFo=
            Address = 10.66.66.2/32, fd42:42::2/128
            DNS = 1.1.1.1

            [Peer]
            PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = vpn.example.com:51820
            """;

    @Test
    void parsesATypicalProviderConfig() {
        ServerConfig server = parser.parse(TYPICAL);

        assertThat(server.getProtocol()).isEqualTo(Protocol.WIREGUARD);
        // The mapping WireguardEndpointBuilder already expects.
        assertThat(server.getUuid()).isEqualTo("qGvIaLPuXqhbbFJTZ8kMtKz1BZ0i9CTfKk1PavDTVFo=");
        assertThat(server.getEncryption()).isEqualTo("xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=");
        assertThat(server.getAddress()).isEqualTo("vpn.example.com");
        assertThat(server.getPort()).isEqualTo(51820);
        // Several local addresses are common; the endpoint takes one.
        assertThat(server.getFlow()).isEqualTo("10.66.66.2/32");
        assertThat(server.getName()).contains("vpn.example.com");
    }

    @Test
    void acceptsCommentsBlankLinesAndAnyKeyCasing() {
        ServerConfig server = parser.parse("""
                # exported by wg-quick
                [interface]
                privatekey=qGvIaLPuXqhbbFJTZ8kMtKz1BZ0i9CTfKk1PavDTVFo=

                ; peer section
                [PEER]
                PUBLICKEY = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
                endpoint   =   198.51.100.7:1234
                """);

        assertThat(server.getAddress()).isEqualTo("198.51.100.7");
        assertThat(server.getPort()).isEqualTo(1234);
    }

    @Test
    void handlesABracketedIpv6Endpoint() {
        ServerConfig server = parser.parse(TYPICAL.replace(
                "Endpoint = vpn.example.com:51820", "Endpoint = [2001:db8::1]:51820"));

        assertThat(server.getAddress()).isEqualTo("2001:db8::1");
        assertThat(server.getPort()).isEqualTo(51820);
    }

    @Test
    void fallsBackToTheStandardPortWhenTheEndpointOmitsOne() {
        ServerConfig server = parser.parse(TYPICAL.replace(
                "Endpoint = vpn.example.com:51820", "Endpoint = vpn.example.com"));

        assertThat(server.getPort()).isEqualTo(51820);
    }

    @Test
    void namesEachMissingRequiredField() {
        assertThatThrownBy(() -> parser.parse(TYPICAL.replaceAll("(?m)^PrivateKey.*$", "")))
                .isInstanceOf(WireguardConfigParser.InvalidConfigException.class)
                .hasMessageContaining("PrivateKey");

        assertThatThrownBy(() -> parser.parse(TYPICAL.replaceAll("(?m)^PublicKey.*$", "")))
                .hasMessageContaining("PublicKey");

        assertThatThrownBy(() -> parser.parse(TYPICAL.replaceAll("(?m)^Endpoint.*$", "")))
                .hasMessageContaining("Endpoint");
    }

    @Test
    void rejectsInputThatIsNotAWireguardConfig() {
        assertThatThrownBy(() -> parser.parse("vless://uuid@host:443#Server"))
                .isInstanceOf(WireguardConfigParser.InvalidConfigException.class)
                .hasMessageContaining("[Interface]");

        assertThatThrownBy(() -> parser.parse("")).hasMessageContaining("empty");
        assertThatThrownBy(() -> parser.parse(null)).hasMessageContaining("empty");
    }

    /**
     * A PresharedKey the endpoint builder cannot emit must stop the import.
     * Accepting it would produce a server that connects without the key and
     * fails in a way that looks like the provider's fault.
     */
    @Test
    void refusesConfigsUsingFeaturesTheCoreConfigCannotExpressYet() {
        assertThatThrownBy(() -> parser.parse(TYPICAL.replace(
                "AllowedIPs = 0.0.0.0/0, ::/0",
                "PresharedKey = 4kR0F5b0m9nqmTFcnAF3n5Vq0LN2ScK5EsAV3o2xUFo=\nAllowedIPs = 0.0.0.0/0")))
                .isInstanceOf(WireguardConfigParser.InvalidConfigException.class)
                .hasMessageContaining("PresharedKey".toLowerCase());
    }

    @Test
    void malformedEndpointPortIsReportedNotGuessed() {
        assertThatThrownBy(() -> parser.parse(TYPICAL.replace(
                "Endpoint = vpn.example.com:51820", "Endpoint = vpn.example.com:abc")))
                .hasMessageContaining("port");

        assertThatThrownBy(() -> parser.parse(TYPICAL.replace(
                "Endpoint = vpn.example.com:51820", "Endpoint = vpn.example.com:70000")))
                .hasMessageContaining("range");
    }
}
