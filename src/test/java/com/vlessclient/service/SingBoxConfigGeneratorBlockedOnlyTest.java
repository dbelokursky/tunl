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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Only what is blocked in Russia goes through the tunnel, when the user
 * chooses so.
 *
 * <p>Everything went through the VPN, and the only way around it was a list
 * of countries or domains to send direct. Item 4.2 of the 2026-09-19 review;
 * the user chose the runetfreedom lists on 2026-09-19: geosite-ru-blocked and
 * geoip-ru-blocked, rebuilt every six hours.</p>
 */
class SingBoxConfigGeneratorBlockedOnlyTest {

    private static final String LISTS =
            "https://raw.githubusercontent.com/runetfreedom/russia-v2ray-rules-dat/release/sing-box/";

    private final SingBoxConfigGenerator generator = new SingBoxConfigGenerator();
    private final ObjectMapper mapper = JsonMapper.builder().build();

    private static ServerConfig server() {
        ServerConfig server = new ServerConfig();
        server.setId("s1");
        server.setName("Server");
        server.setProtocol(Protocol.VLESS);
        server.setAddress("server.example");
        server.setPort(443);
        server.setUuid("11111111-2222-3333-4444-555555555555");
        return server;
    }

    private static RoutingConfig blockedOnly() {
        RoutingConfig routing = new RoutingConfig();
        routing.setMode(RouteMode.BLOCKED_IN_RUSSIA);
        return routing;
    }

    private JsonNode generate(ProxyMode proxyMode, RoutingConfig routing) {
        AppSettings settings = new AppSettings();
        settings.setProxyMode(proxyMode);
        return mapper.readTree(generator.generate(server(), settings, routing));
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }

    private static int indexOfRuleUsing(JsonNode rules, String ruleSetTag) {
        List<JsonNode> all = list(rules);
        for (int i = 0; i < all.size(); i++) {
            for (JsonNode tag : all.get(i).path("rule_set")) {
                if (ruleSetTag.equals(tag.asString())) {
                    return i;
                }
            }
        }
        return -1;
    }

    @Test
    void onlyTheBlockedListsGoThroughTheTunnel() {
        JsonNode route = generate(ProxyMode.SYSTEM_PROXY, blockedOnly()).get("route");

        assertThat(route.get("final").asString()).as("everything else").isEqualTo("direct");
        JsonNode rule = route.get("rules").get(indexOfRuleUsing(route.get("rules"),
                "geosite-ru-blocked"));
        assertThat(list(rule.get("rule_set"))).extracting(JsonNode::asString)
                .containsExactly("geosite-ru-blocked", "geoip-ru-blocked");
        assertThat(rule.get("outbound").asString()).isEqualTo("proxy");
    }

    /**
     * Through the tunnel, like the other lists: GitHub's raw host is what a
     * blocking network is likeliest to cut, and the proxy is up by the time
     * the core fetches rule sets.
     */
    @Test
    void theListsComeFromRunetfreedomThroughTheTunnelEverySixHours() {
        JsonNode ruleSets = generate(ProxyMode.SYSTEM_PROXY, blockedOnly())
                .get("route").get("rule_set");

        assertThat(list(ruleSets)).extracting(set -> set.get("tag").asString() + " "
                        + set.get("url").asString() + " "
                        + set.path("http_client").path("detour").asString() + " "
                        + set.path("update_interval").asString())
                .containsExactlyInAnyOrder(
                        "geosite-ru-blocked " + LISTS
                                + "rule-set-geosite/geosite-ru-blocked.srs proxy 6h",
                        "geoip-ru-blocked " + LISTS
                                + "rule-set-geoip/geoip-ru-blocked.srs proxy 6h");
    }

    @Test
    void theUsersOwnRulesComeFirst() {
        RoutingConfig routing = blockedOnly();
        routing.setRules(List.of(
                new RoutingRule(RuleType.DOMAIN_SUFFIX, "example.org", RuleAction.PROXY)));
        JsonNode rules = generate(ProxyMode.SYSTEM_PROXY, routing).get("route").get("rules");

        int users = -1;
        List<JsonNode> all = list(rules);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).path("domain_suffix").toString().contains("example.org")) {
                users = i;
            }
        }
        assertThat(users).as("the user's rule").isNotNegative()
                .isLessThan(indexOfRuleUsing(rules, "geosite-ru-blocked"));
    }

    /**
     * The provider's resolver answers a blocked name with the address of a
     * stub page, so those names resolve through the tunnel; everything else
     * resolves directly, as its traffic goes.
     */
    @Test
    void blockedNamesResolveThroughTheTunnelAndTheRestDirectly() {
        JsonNode dns = generate(ProxyMode.TUN, blockedOnly()).get("dns");

        assertThat(dns.get("final").asString()).isEqualTo("direct-dns");
        JsonNode rule = dns.get("rules").get(indexOfRuleUsing(dns.get("rules"),
                "geosite-ru-blocked"));
        assertThat(rule.get("server").asString()).isEqualTo("proxy-dns");
    }

    @Test
    void everythingGoesThroughTheTunnelByDefault() {
        JsonNode root = generate(ProxyMode.TUN, new RoutingConfig());

        assertThat(root.get("route").get("final").asString()).isEqualTo("proxy");
        assertThat(root.get("dns").get("final").asString()).isEqualTo("proxy-dns");
        assertThat(root.get("route").toString()).doesNotContain("ru-blocked");
    }
}
