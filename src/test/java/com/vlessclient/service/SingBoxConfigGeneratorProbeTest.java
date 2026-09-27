package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.RouteMode;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.RoutingRule;
import com.vlessclient.model.RoutingRule.RuleAction;
import com.vlessclient.model.RoutingRule.RuleType;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.outbound.OutboundTags;
import com.vlessclient.testing.TestServers;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The health checks test the tunnel, whatever the routing sends direct.
 *
 * <p>They went in through http-in, and the route treated them like any other
 * traffic. In the blocked-only mode {@code route.final} is direct and only the
 * runetfreedom lists go through the tunnel; on 2026-09-27
 * {@code sing-box rule-set match} found x.com in geosite-ru-blocked and
 * www.google.com in neither list. So the Google check went direct: with the
 * server dead it still answered while X failed, the verdict read degraded
 * rather than broken, and recovery never restarted the tunnel. In either mode
 * the same happened to a target that the user's bypass list or a direct rule
 * covered. The checks now have an inbound of their own, and the first route
 * rule sends it into the tunnel.</p>
 */
class SingBoxConfigGeneratorProbeTest {

    private final SingBoxConfigGenerator generator = new SingBoxConfigGenerator();
    private final ObjectMapper mapper = JsonMapper.builder().build();

    /** What the first rule has to be: the checks' inbound, into the proxy group. */
    private final JsonNode probeRule = mapper.readTree("""
            {"inbound": ["probe-in"], "action": "route", "outbound": "proxy"}
            """);

    private static ServerConfig server() {
        return TestServers.server()
                .id("s1")
                .name("Server")
                .protocol(Protocol.VLESS)
                .address("server.example")
                .port(443)
                .uuid("11111111-2222-3333-4444-555555555555")
                .build();
    }

    private static RoutingConfig routing(RouteMode mode) {
        RoutingConfig routing = new RoutingConfig();
        routing.setMode(mode);
        return routing;
    }

    private JsonNode generate(ProxyMode proxyMode, RoutingConfig routing) {
        AppSettings settings = new AppSettings();
        settings.setProxyMode(proxyMode);
        return mapper.readTree(generator.generate(server(), settings, routing));
    }

    /** The entry of {@code section} tagged {@code tag}, or null. */
    private static JsonNode tagged(JsonNode config, String section, String tag) {
        for (JsonNode entry : config.path(section)) {
            if (tag.equals(entry.path("tag").asString())) {
                return entry;
            }
        }
        return null;
    }

    /**
     * The route rules below the one that sends the health checks into the
     * tunnel, which is first in every configuration: the rules the routing
     * settings and the proxy mode make, which other tests are about.
     *
     * @param rules a generated {@code route.rules}
     * @return the rules after the checks' own, in order
     */
    static List<JsonNode> belowTheChecks(JsonNode rules) {
        assertThat(rules.path(0).path("inbound").path(0).asString())
                .as("the health checks' rule, first")
                .isEqualTo(SingBoxConfigGenerator.PROBE_INBOUND);
        List<JsonNode> below = new ArrayList<>();
        for (int i = 1; i < rules.size(); i++) {
            below.add(rules.get(i));
        }
        return below;
    }

    private static int indexOf(JsonNode rules, Predicate<JsonNode> rule) {
        for (int i = 0; i < rules.size(); i++) {
            if (rule.test(rules.get(i))) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void inTheBlockedOnlyModeTheChecksStillGoThroughTheTunnel() {
        JsonNode config = generate(ProxyMode.SYSTEM_PROXY, routing(RouteMode.BLOCKED_IN_RUSSIA));
        JsonNode route = config.path("route");

        assertThat(route.path("final").asString())
                .as("where everything the lists do not name goes")
                .isEqualTo("direct");
        JsonNode probe = tagged(config, "inbounds", "probe-in");
        assertThat(probe).as("the checks' own inbound").isNotNull();
        assertThat(probe.path("type").asString())
                .as("SOCKS, whose reply waits for the tunnel's")
                .isEqualTo("socks");
        assertThat(probe.path("listen").asString()).isEqualTo("127.0.0.1");
        assertThat(route.path("rules").path(0))
                .as("the first rule, ahead of the lists and route.final")
                .isEqualTo(probeRule);
        JsonNode group = tagged(config, "outbounds", "proxy");
        assertThat(group).as("what the rule sends the checks to").isNotNull();
        assertThat(group.path("outbounds").valueStream().map(JsonNode::asString))
                .as("the proxy group, fronting the server")
                .containsExactly(OutboundTags.server(server()));
    }

    /**
     * The default targets' own hosts, sent direct by every kind of rule a user
     * can write, in both route modes and both proxy modes: the checks' rule
     * stays first, and the user's rules stay in the config below it.
     */
    @Test
    void theUsersDirectRulesDoNotSendTheChecksDirect() {
        for (RouteMode mode : RouteMode.values()) {
            RoutingConfig routing = routing(mode);
            routing.setBypassList(List.of("google.com", "x.com", "142.250.0.0/15"));
            routing.setRules(List.of(
                    new RoutingRule(RuleType.DOMAIN_SUFFIX, "google.com", RuleAction.DIRECT)));
            routing.setBypassCountries(List.of("ru"));
            for (ProxyMode proxyMode : ProxyMode.values()) {
                JsonNode rules = generate(proxyMode, routing).path("route").path("rules");

                assertThat(rules.path(0)).as("%s, %s", mode, proxyMode).isEqualTo(probeRule);
                assertThat(indexOf(rules, rule -> rule.path("domain").toString()
                        .contains("x.com")))
                        .as("%s, %s: the bypass list, below it", mode, proxyMode)
                        .isPositive();
            }
        }
    }

    /**
     * In TUN mode the generator puts its own rules first, sniffing, the DNS
     * hijack and the LAN bypass, and the checks' rule goes above them. The
     * checks reach the core on the loopback address, which auto_route leaves
     * out of the TUN device, and the proxy's own connection leaves by the
     * physical interface: nothing loops back into the tunnel, and nothing
     * goes direct.
     */
    @Test
    void inTunModeTheChecksNeitherLoopNorGoDirect() {
        JsonNode config = generate(ProxyMode.TUN, routing(RouteMode.BLOCKED_IN_RUSSIA));
        JsonNode route = config.path("route");
        JsonNode rules = route.path("rules");

        assertThat(tagged(config, "inbounds", "tun-in").path("auto_route").asBoolean())
                .isTrue();
        assertThat(rules.path(0)).isEqualTo(probeRule);
        assertThat(rules.path(1).path("action").asString())
                .as("sniffing, which the checks' rule no longer waits for")
                .isEqualTo("sniff");
        assertThat(indexOf(rules, rule -> "hijack-dns".equals(rule.path("action").asString())))
                .isPositive();
        assertThat(indexOf(rules, rule -> rule.path("ip_is_private").asBoolean()))
                .as("the LAN bypass")
                .isPositive();
        assertThat(tagged(config, "inbounds", "probe-in").path("listen").asString())
                .isEqualTo("127.0.0.1");
        assertThat(route.path("auto_detect_interface").asBoolean())
                .as("the proxy dials its server outside the TUN device")
                .isTrue();
    }

    @Test
    void withoutRoutingRulesTheChecksRuleIsStillFirst() {
        for (ProxyMode proxyMode : ProxyMode.values()) {
            AppSettings settings = new AppSettings();
            settings.setProxyMode(proxyMode);
            JsonNode route = mapper.readTree(generator.generate(server(), settings))
                    .path("route");

            assertThat(route.path("rules").path(0)).as("%s", proxyMode).isEqualTo(probeRule);
        }
    }

    /**
     * The inbound listens where the connect put it. A configuration made
     * outside a connect, for the diagnostics bundle or a test, leaves the
     * port to the core rather than naming one that another program may hold.
     */
    @Test
    void theChecksInboundListensWhereTheConnectPutIt() {
        AppSettings settings = new AppSettings();
        JsonNode before = mapper.readTree(generator.generate(server(), settings));
        assertThat(tagged(before, "inbounds", "probe-in").path("listen_port").asInt()).isZero();

        settings.listenOn(settings.getSocksPort(), settings.getHttpPort(),
                settings.getClashApiPort(), 47123);
        JsonNode during = mapper.readTree(generator.generate(server(), settings));

        assertThat(tagged(during, "inbounds", "probe-in").path("listen_port").asInt())
                .isEqualTo(47123);
        assertThat(tagged(during, "inbounds", "http-in").path("listen_port").asInt())
                .as("the HTTP port stays the chosen one")
                .isEqualTo(settings.getHttpPort());
    }
}
