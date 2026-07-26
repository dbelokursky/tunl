package com.vlessclient.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.ServerSelection;
import com.vlessclient.service.outbound.OutboundTags;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Automatic server selection. Membership is derived at generate time rather
 * than stored, which is what keeps a subscription refresh from leaving a stale
 * member behind — so these assert on what the group ends up containing, not on
 * any persisted list.
 */
class SingBoxConfigGeneratorSelectionTest {

    private SingBoxConfigGenerator generator;
    private ObjectMapper mapper;
    private AppSettings settings;

    @BeforeEach
    void setUp() {
        generator = new SingBoxConfigGenerator();
        mapper = new ObjectMapper();
        settings = new AppSettings();
    }

    private JsonNode group(List<ServerConfig> candidates, ServerConfig active) throws Exception {
        JsonNode root = mapper.readTree(
                generator.generate(candidates, active, settings, null));
        for (JsonNode outbound : root.get("outbounds")) {
            if (OutboundTags.PROXY.equals(outbound.path("tag").asText())) {
                return outbound;
            }
        }
        throw new AssertionError("no proxy group emitted");
    }

    @Test
    void singleModePinsTheActiveServerOnly() throws Exception {
        List<ServerConfig> servers = List.of(server("A"), server("B"), server("C"));
        settings.setServerSelection(ServerSelection.SINGLE);

        JsonNode group = group(servers, servers.get(1));

        assertThat(group.get("type").asText()).isEqualTo("selector");
        assertThat(group.get("outbounds")).hasSize(1);
        assertThat(group.get("outbounds").get(0).asText())
                .isEqualTo(OutboundTags.server(servers.get(1)));
        // No probing when the user picked the server.
        assertThat(group.has("url")).isFalse();
    }

    @Test
    void autoModeGroupsEveryCandidateAndProbesThem() throws Exception {
        List<ServerConfig> servers = List.of(server("A"), server("B"), server("C"));
        settings.setServerSelection(ServerSelection.AUTO_BEST);

        JsonNode group = group(servers, servers.get(0));

        // urltest, not the Clash-only "fallback": the real core rejects that
        // type outright, which is why there is no separate fallback mode.
        assertThat(group.get("type").asText()).isEqualTo("urltest");
        assertThat(group.get("outbounds")).hasSize(3);
        // Without url+interval the core would never re-measure and the mode
        // would silently behave like a plain selector.
        assertThat(group.get("url").asText()).contains("generate_204");
        assertThat(group.get("interval").asText()).isNotBlank();
    }

    @Test
    void autoModeEmitsAnOutboundForEveryMember() throws Exception {
        List<ServerConfig> servers = List.of(server("A"), server("B"));
        settings.setServerSelection(ServerSelection.AUTO_BEST);

        JsonNode root = mapper.readTree(
                generator.generate(servers, servers.get(0), settings, null));

        // Each member needs its own outbound, or the group references a tag
        // that resolves to nothing.
        for (ServerConfig server : servers) {
            boolean found = false;
            for (JsonNode outbound : root.get("outbounds")) {
                found |= OutboundTags.server(server).equals(outbound.path("tag").asText());
            }
            assertThat(found).as("outbound for %s", server.getName()).isTrue();
        }
    }

    @Test
    void wireguardMembersBecomeEndpointsAndStayInTheGroup() throws Exception {
        ServerConfig vless = server("A");
        ServerConfig wireguard = server("W");
        wireguard.setProtocol(Protocol.WIREGUARD);
        wireguard.setFlow("10.0.0.2/32");
        settings.setServerSelection(ServerSelection.AUTO_BEST);

        JsonNode root = mapper.readTree(
                generator.generate(List.of(vless, wireguard), vless, settings, null));

        assertThat(root.get("endpoints")).hasSize(1);
        assertThat(root.get("endpoints").get(0).get("tag").asText())
                .isEqualTo(OutboundTags.server(wireguard));
        // Referenced by the group all the same — a selector may point at an
        // endpoint, confirmed against the real core.
        JsonNode group = group(List.of(vless, wireguard), vless);
        assertThat(group.get("outbounds")).hasSize(2);
    }

    @Test
    void duplicateCandidatesCollapseAndTheActiveServerIsAlwaysIncluded() throws Exception {
        ServerConfig a = server("A");
        ServerConfig active = server("Z");
        settings.setServerSelection(ServerSelection.AUTO_BEST);

        // Same server twice, and an active one missing from the list.
        JsonNode group = group(List.of(a, a), active);

        assertThat(group.get("outbounds")).hasSize(2);
        assertThat(group.get("outbounds").toString())
                .contains(OutboundTags.server(active));
    }

    /**
     * Switching to an automatic mode with nothing to choose between must still
     * produce a usable config: an empty group is rejected by the core, and
     * refusing to connect over a mode toggle would be worse than ignoring it.
     */
    @Test
    void autoModeWithNoCandidatesFallsBackToTheActiveServer() throws Exception {
        ServerConfig active = server("A");
        settings.setServerSelection(ServerSelection.AUTO_BEST);

        JsonNode group = group(List.of(), active);

        assertThat(group.get("outbounds")).hasSize(1);
        assertThat(group.get("outbounds").get(0).asText())
                .isEqualTo(OutboundTags.server(active));
    }

    @Test
    void unknownPersistedModeReadsAsSingleRatherThanFailing() {
        // A settings file from a newer build must not stop the app starting.
        assertThat(ServerSelection.fromValue("something_new")).isEqualTo(ServerSelection.SINGLE);
        assertThat(ServerSelection.fromValue(null)).isEqualTo(ServerSelection.SINGLE);
        assertThat(ServerSelection.fromValue("AUTO_BEST")).isEqualTo(ServerSelection.AUTO_BEST);
    }

    private ServerConfig server(String name) {
        ServerConfig server = new ServerConfig();
        server.setName(name);
        server.setProtocol(Protocol.VLESS);
        server.setAddress(name.toLowerCase() + ".example.com");
        server.setPort(443);
        server.setUuid("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        return server;
    }
}
