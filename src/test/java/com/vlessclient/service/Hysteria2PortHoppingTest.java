package com.vlessclient.service;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.outbound.CoreSettings;
import com.vlessclient.service.outbound.Hysteria2OutboundBuilder;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hysteria2 port hopping and obfuscation, from the link to the core.
 *
 * <p>The official form lists the ports in the authority, {@code
 * host:443,20000-50000}, which Java's URI reads as no host at all: the line
 * was unreadable, and one such line kept a whole subscription "partial", so
 * it stopped removing the servers the provider withdrew. 3x-ui writes the
 * list as {@code mport}, which was ignored, and its {@code obfs=gecko} was
 * built as salamander (item 1.5.1 of the 2026-09-25 review). The core's
 * spelling of the list, {@code from:to}, is checked by
 * {@code SingBoxRealBinarySmokeTest}.</p>
 */
class Hysteria2PortHoppingTest {

    private final ShareLinkParser parser = new ShareLinkParser();

    @Test
    void theOfficialFormListsThePortsInTheAuthority() {
        ServerConfig server = parser.parse("hysteria2://secret@hy.example:443,20000-50000/"
                + "?sni=hy.example&obfs=salamander&obfs-password=o#Hop");

        assertThat(server.getProtocol()).isEqualTo(Protocol.HYSTERIA2);
        assertThat(server.getAddress()).isEqualTo("hy.example");
        assertThat(server.getPort()).isEqualTo(443);
        assertThat(server.getServerPorts()).isEqualTo("443,20000-50000");
        assertThat(server.getName()).isEqualTo("Hop");
    }

    @Test
    void aRangeAloneStartsAtItsFirstPort() {
        ServerConfig server = parser.parse("hy2://secret@hy.example:20000-50000?sni=hy.example#R");

        assertThat(server.getPort()).isEqualTo(20000);
        assertThat(server.getServerPorts()).isEqualTo("20000-50000");
    }

    @Test
    void anIpv6HostKeepsItsBrackets() {
        ServerConfig server = parser.parse("hysteria2://secret@[2001:db8::7]:443,5000-6000/"
                + "?sni=hy.example#Six");

        assertThat(server.getAddress()).contains("2001:db8::7");
        assertThat(server.getPort()).isEqualTo(443);
        assertThat(server.getServerPorts()).isEqualTo("443,5000-6000");
    }

    @Test
    void threeXuiWritesTheListAsMport() {
        ServerConfig server = parser.parse("hysteria2://secret@hy.example:443"
                + "?mport=20000-50000&sni=hy.example&obfs=gecko&obfs-password=o#M");

        assertThat(server.getPort()).isEqualTo(443);
        assertThat(server.getServerPorts()).isEqualTo("20000-50000");
        assertThat(server.getEncryption()).isEqualTo("gecko");
    }

    @Test
    void anOrdinaryLinkDoesNotHop() {
        ServerConfig server = parser.parse("hysteria2://secret@hy.example:443?sni=hy.example#P");

        assertThat(server.getServerPorts()).isNull();
    }

    @Test
    void aPortListOutOfRangeIsRefusedAtTheDoor() {
        assertThatThrownBy(() -> parser.parse(
                "hysteria2://secret@hy.example:443,70000-80000/?sni=hy.example#X"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("70000-80000");
        assertThatThrownBy(() -> parser.parse(
                "hysteria2://secret@hy.example:443?mport=9-3&sni=hy.example#Y"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCoreGetsEveryItemAsARangeAndTheLinksObfuscation() {
        ServerConfig server = parser.parse("hysteria2://secret@hy.example:443,20000-50000/"
                + "?sni=hy.example&obfs=gecko&obfs-password=o#Hop");

        JsonNode outbound = new Hysteria2OutboundBuilder(JsonMapper.builder().build())
                .build(server, "srv-hop");

        assertThat(outbound.path("server_ports").toString())
                .isEqualTo("[\"443:443\",\"20000:50000\"]");
        assertThat(outbound.path("obfs").path("type").asString()).isEqualTo("gecko");
        assertThat(CoreSettings.refusal(server)).isEmpty();
    }

    @Test
    void anExportedLinkKeepsItsPorts() {
        ServerConfig server = parser.parse("hysteria2://secret@hy.example:443,20000-50000/"
                + "?sni=hy.example#Hop");

        String link = new ShareLinkExporter().export(server);
        ServerConfig again = parser.parse(link);

        assertThat(link).contains("hy.example:443?").contains("mport=443%2C20000-50000");
        assertThat(again.getServerPorts()).isEqualTo("443,20000-50000");
        assertThat(again.getPort()).isEqualTo(443);
    }

    @Test
    void obfuscationTheCoreDoesNotKnowIsRefused() {
        ServerConfig server = parser.parse("hysteria2://secret@hy.example:443"
                + "?sni=hy.example&obfs=foo&obfs-password=o#F");

        assertThat(CoreSettings.refusal(server)).hasValueSatisfying(refusal ->
                assertThat(refusal.feature()).isEqualTo("obfs foo"));
    }
}
