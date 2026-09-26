package com.vlessclient.service;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.RouteMode;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.RoutingRule;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.ServerSelection;
import com.vlessclient.testing.TestServers;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every tag the generated configuration refers to is one it defines.
 *
 * <p>A reference to a tag nothing carries stops the core at startup, or
 * routes nowhere, whichever server is picked. The tags used to be spelled
 * out as literals across the generator, twenty-odd of them, while
 * {@code OutboundTags} held the same words; this pins the result, whatever
 * spells it.</p>
 */
class SingBoxConfigTagReferencesTest {

    private final SingBoxConfigGenerator generator = new SingBoxConfigGenerator();
    private final JsonMapper mapper = JsonMapper.builder().build();

    static Stream<Arguments> configurations() {
        ServerConfig vless = TestServers.vless("Tokyo").id("a").build();
        ServerConfig trojan = TestServers.vless("Paris").id("t").protocol(Protocol.TROJAN)
                .tls("paris.example").build();
        ServerConfig wireguard = TestServers.server().id("w").name("WARP")
                .protocol(Protocol.WIREGUARD).address("warp.example").port(2408)
                .uuid("aGVsbG8=").encryption("cGVlcg==").flow("10.0.0.2/32").build();

        AppSettings tun = new AppSettings();
        tun.setProxyMode(ProxyMode.TUN);
        tun.setDirectDns("https://dns.example/dns-query");
        tun.setServerSelection(ServerSelection.AUTO_BEST);
        RoutingConfig blocked = new RoutingConfig();
        blocked.setMode(RouteMode.BLOCKED_IN_RUSSIA);
        blocked.setBypassCountries(List.of("ru"));
        blocked.setBypassList(List.of("example.org", "10.1.0.0/16"));
        blocked.setRules(List.of(
                new RoutingRule(RoutingRule.RuleType.DOMAIN, "proxy.example",
                        RoutingRule.RuleAction.PROXY),
                new RoutingRule(RoutingRule.RuleType.DOMAIN_SUFFIX, "direct.example",
                        RoutingRule.RuleAction.DIRECT),
                new RoutingRule(RoutingRule.RuleType.IP_CIDR, "192.0.2.0/24",
                        RoutingRule.RuleAction.BLOCK)));

        RoutingConfig bypass = new RoutingConfig();
        bypass.setBypassCountries(List.of("ru", "by"));
        bypass.setRules(List.of(new RoutingRule(RoutingRule.RuleType.GEOSITE, "youtube",
                RoutingRule.RuleAction.DIRECT)));

        return Stream.of(
                Arguments.of("system proxy, everything through the tunnel",
                        List.of(vless), vless, new AppSettings(), new RoutingConfig()),
                Arguments.of("TUN, blocked lists only, rules, a DNS server by name, a group",
                        List.of(vless, wireguard, trojan), vless, tun, blocked),
                Arguments.of("system proxy, countries and a site bypassed",
                        List.of(vless, trojan), trojan, new AppSettings(), bypass));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configurations")
    void everyReferencedTagIsDefined(String name, List<ServerConfig> servers,
                                     ServerConfig active, AppSettings settings,
                                     RoutingConfig routing) {
        JsonNode root = mapper.readTree(generator.generate(servers, active, settings, routing));

        Set<String> outbounds = new HashSet<>();
        root.path("outbounds").forEach(node -> outbounds.add(node.path("tag").asString()));
        root.path("endpoints").forEach(node -> outbounds.add(node.path("tag").asString()));
        Set<String> dnsServers = new HashSet<>();
        root.path("dns").path("servers").forEach(node -> dnsServers.add(node.path("tag").asString()));

        List<String> outboundRefs = new ArrayList<>();
        add(outboundRefs, root.path("route").path("final"));
        root.path("route").path("rules").forEach(rule -> add(outboundRefs, rule.path("outbound")));
        root.path("dns").path("servers").forEach(server -> add(outboundRefs, server.path("detour")));
        root.path("route").path("rule_set").forEach(set -> {
            add(outboundRefs, set.path("http_client").path("detour"));
            add(outboundRefs, set.path("download_detour"));
        });
        root.path("outbounds").forEach(outbound -> {
            outbound.path("outbounds").forEach(member -> add(outboundRefs, member));
            add(outboundRefs, outbound.path("default"));
            add(outboundRefs, outbound.path("detour"));
        });

        List<String> dnsRefs = new ArrayList<>();
        add(dnsRefs, root.path("dns").path("final"));
        root.path("dns").path("rules").forEach(rule -> add(dnsRefs, rule.path("server")));
        root.path("dns").path("servers").forEach(server -> addResolver(dnsRefs,
                server.path("domain_resolver")));
        addResolver(dnsRefs, root.path("route").path("default_domain_resolver"));
        root.path("route").path("rules").forEach(rule -> add(dnsRefs, rule.path("server")));

        assertThat(outboundRefs).as("outbound references").isNotEmpty();
        assertThat(outbounds).as("defined outbounds").containsAll(outboundRefs);
        if (root.has("dns")) {
            // A system proxy with no routing has none: the apps resolve.
            assertThat(dnsRefs).as("DNS references").isNotEmpty();
        }
        assertThat(dnsServers).as("defined DNS servers").containsAll(dnsRefs);
    }

    private static void add(List<String> refs, JsonNode node) {
        if (node.isString()) {
            refs.add(node.asString());
        }
    }

    /** A resolver is a server's tag, or an object that names one. */
    private static void addResolver(List<String> refs, JsonNode node) {
        add(refs, node.isObject() ? node.path("server") : node);
    }
}
