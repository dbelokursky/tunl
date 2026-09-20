package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.ServerSelection;
import com.vlessclient.service.outbound.OutboundTags;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The "Fastest" mode probes a bounded group, and less often the larger it is.
 *
 * <p>The automatic group took every server, and the core probed each one
 * every three minutes, whether the tunnel carried anything or not: with 500
 * servers, about 100 MB an hour of probes (item 2.4 of the 2026-09-19
 * review). The user chose on 2026-09-19 to keep the thirty best by their
 * last measurement and to probe less often as the group grows.</p>
 */
class SingBoxConfigGeneratorFastestGroupTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private static List<ServerConfig> servers(int count) {
        return IntStream.range(0, count).mapToObj(i -> {
            ServerConfig server = new ServerConfig();
            server.setId("s" + i);
            server.setName("Server " + i);
            server.setProtocol(Protocol.VLESS);
            server.setAddress("s" + i + ".example");
            server.setPort(443);
            server.setUuid("11111111-2222-3333-4444-555555555555");
            return server;
        }).toList();
    }

    private static AppSettings fastest() {
        AppSettings settings = new AppSettings();
        settings.setServerSelection(ServerSelection.AUTO_BEST);
        return settings;
    }

    private JsonNode group(SingBoxConfigGenerator generator, List<ServerConfig> candidates,
                           ServerConfig active, AppSettings settings) {
        JsonNode root = mapper.readTree(generator.generate(candidates, active, settings, null));
        for (JsonNode outbound : root.get("outbounds")) {
            if (OutboundTags.PROXY.equals(outbound.path("tag").asString())) {
                return outbound;
            }
        }
        throw new AssertionError("no proxy group emitted");
    }

    private static List<String> members(JsonNode group) {
        List<String> tags = new ArrayList<>();
        group.get("outbounds").forEach(tag -> tags.add(tag.asString()));
        return tags;
    }

    private static List<String> tagsOf(List<ServerConfig> servers) {
        return servers.stream().map(OutboundTags::server).toList();
    }

    @Test
    void theGroupTakesTheFastestByTheirLastMeasurementAndKeepsThePick() {
        List<ServerConfig> all = servers(100);
        // s60 to s99 answered, the later the faster; the rest were not measured.
        SingBoxConfigGenerator generator = new SingBoxConfigGenerator(id -> {
            int index = Integer.parseInt(id.substring(1));
            return index >= 60 ? OptionalLong.of(1000 - index) : OptionalLong.empty();
        });

        List<String> members = members(group(generator, all, all.get(0), fastest()));

        List<ServerConfig> expected = new ArrayList<>();
        for (int i = 99; i > 70; i--) {
            expected.add(all.get(i));
        }
        expected.add(all.get(0));
        assertThat(members).as("the 29 fastest, then the picked server")
                .containsExactlyElementsOf(tagsOf(expected));
    }

    @Test
    void unmeasuredServersComeBeforeUnreachableOnesAndKeepTheirOrder() {
        List<ServerConfig> all = servers(40);
        Map<String, OptionalLong> measured = Map.of(
                "s0", OptionalLong.of(Long.MAX_VALUE), "s1", OptionalLong.of(Long.MAX_VALUE),
                "s39", OptionalLong.of(80));
        SingBoxConfigGenerator generator = new SingBoxConfigGenerator(
                id -> measured.getOrDefault(id, OptionalLong.empty()));

        List<String> members = members(group(generator, all, all.get(39), fastest()));

        assertThat(members).hasSize(SingBoxConfigGenerator.MAX_AUTOMATIC_MEMBERS);
        assertThat(members.getFirst()).isEqualTo(OutboundTags.server(all.get(39)));
        assertThat(members.subList(1, members.size()))
                .as("the unmeasured, in the list's order; the unreachable s0 and s1 left out")
                .containsExactlyElementsOf(tagsOf(all.subList(2, 31)));
    }

    @Test
    void theGroupIsProbedLessOftenTheLargerItIs() {
        SingBoxConfigGenerator generator = new SingBoxConfigGenerator();
        List<ServerConfig> thirty = servers(30);
        List<ServerConfig> twelve = servers(12);
        List<ServerConfig> five = servers(5);

        assertThat(group(generator, thirty, thirty.get(0), fastest()).get("interval").asString())
                .isEqualTo("10m");
        assertThat(group(generator, twelve, twelve.get(0), fastest()).get("interval").asString())
                .isEqualTo("4m");
        assertThat(group(generator, five, five.get(0), fastest()).get("interval").asString())
                .as("never more often than every three minutes, as before").isEqualTo("3m");
    }

    @Test
    void theGroupRestsWhileNothingGoesThroughIt() {
        SingBoxConfigGenerator generator = new SingBoxConfigGenerator();
        List<ServerConfig> all = servers(3);

        assertThat(group(generator, all, all.get(0), fastest()).get("idle_timeout").asString())
                .isEqualTo("10m");
    }

    /** A manual pick can go to any server, so the selector keeps them all. */
    @Test
    void aSelectorKeepsEveryServer() {
        SingBoxConfigGenerator generator = new SingBoxConfigGenerator();
        List<ServerConfig> all = servers(100);
        AppSettings settings = new AppSettings();
        settings.setServerSelection(ServerSelection.SINGLE);

        assertThat(members(group(generator, all, all.get(0), settings))).hasSize(100);
    }
}
