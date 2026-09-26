package com.vlessclient.service;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.RoutingRule;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.testing.TestServers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Universal local-bypass ordering (top of the rule list):
 * {@code [ user-bypass?, local-domain (localhost/*.local), private-ip, preset/custom… ]}.
 * The two local-bypass rules are prepended unconditionally so local services
 * stay reachable in every preset and mode.
 */
class SingBoxConfigGeneratorRoutingTest {

    private SingBoxConfigGenerator generator;
    private ObjectMapper mapper;
    private AppSettings defaultSettings;

    @BeforeEach
    void setUp() {
        generator = new SingBoxConfigGenerator();
        mapper = JsonMapper.builder().build();
        defaultSettings = new AppSettings();
    }

    private ServerConfig createVlessServer() {
        return TestServers.server()
                .name("Test Server")
                .protocol(Protocol.VLESS)
                .address("1.2.3.4")
                .port(443)
                .uuid("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
                .build();
    }

    private JsonNode parse(String json) throws Exception {
        return mapper.readTree(json);
    }

    /** True for the {localhost + *.local → direct} rule. */
    private static boolean isLocalDomainRule(JsonNode rule) {
        JsonNode suffix = rule.get("domain_suffix");
        if (suffix == null || !suffix.isArray()) {
            return false;
        }
        for (JsonNode s : suffix) {
            if (".local".equals(s.asString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The route rules without the lookup that system-proxy mode inserts ahead
     * of the first IP rule: the resolve action and the LAN rule right after it.
     * The preset and custom-rule tests are about the rules around it; the
     * systemProxy_ tests below pin the lookup itself.
     */
    private static List<JsonNode> withoutLookup(JsonNode rules) {
        List<JsonNode> kept = new java.util.ArrayList<>();
        boolean afterLookup = false;
        for (JsonNode rule : rules) {
            if ("resolve".equals(rule.path("action").asString())) {
                afterLookup = true;
                continue;
            }
            if (afterLookup && rule.path("ip_is_private").asBoolean()) {
                afterLookup = false;
                continue;
            }
            afterLookup = false;
            kept.add(rule);
        }
        return kept;
    }

    /**
     * A star between names and an internationalized name reach the core the
     * way it compares them, in the route rule and in the rule that resolves
     * those names through the direct DNS.
     */
    @Test
    void bypassList_regexAndPunycodeReachTheRouteAndTheDns() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setBypassList(List.of("mail.*.com", "*.рф"));
        AppSettings tun = new AppSettings();
        tun.setProxyMode(com.vlessclient.model.ProxyMode.TUN);

        JsonNode root = parse(generator.generate(createVlessServer(), tun, routingConfig));

        JsonNode bypass = null;
        for (JsonNode rule : root.get("route").get("rules")) {
            if (rule.has("domain_regex")) {
                bypass = rule;
            }
        }
        assertThat(bypass).as("a route rule with the regex").isNotNull();
        assertThat(bypass.get("domain_regex").get(0).asString()).isEqualTo("^mail\\..*\\.com$");
        assertThat(bypass.get("domain_suffix").get(0).asString()).isEqualTo("xn--p1ai");
        assertThat(bypass.get("outbound").asString()).isEqualTo("direct");
        assertThat(root.get("dns").get("rules")).anySatisfy(rule -> {
            assertThat(rule.path("server").asString()).isEqualTo("direct-dns");
            assertThat(rule.get("domain_regex").get(0).asString()).isEqualTo("^mail\\..*\\.com$");
        });
    }

    @Test
    void bypassList_mergedIntoDirectRule() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setBypassList(List.of(
                "example.com",           // DOMAIN
                "*.github.com",          // DOMAIN_SUFFIX → github.com
                ".corp.local",           // DOMAIN_SUFFIX → corp.local
                "*google*",              // DOMAIN_KEYWORD → google
                "192.168.0.0/16",        // IP_CIDR as-is
                "203.0.113.42",          // IP_CIDR → .../32
                "# comment",             // skipped
                "",                      // skipped
                "https://api.openai.com/v1" // DOMAIN → api.openai.com
        ));

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode rules = parse(json).get("route").get("rules");

        assertThat(rules).isNotNull();
        // [ bypass-list, local-domain, private-ip ]
        assertThat(rules.size()).isEqualTo(3);
        assertThat(isLocalDomainRule(rules.get(1))).isTrue();
        assertThat(rules.get(2).get("ip_is_private").asBoolean()).isTrue();
        assertThat(rules.get(2).get("outbound").asString()).isEqualTo("direct");

        JsonNode bypass = rules.get(0);
        assertThat(bypass.get("action").asString()).isEqualTo("route");
        assertThat(bypass.get("outbound").asString()).isEqualTo("direct");

        JsonNode domains = bypass.get("domain");
        assertThat(domains.size()).isEqualTo(2);
        assertThat(domains.get(0).asString()).isEqualTo("example.com");
        assertThat(domains.get(1).asString()).isEqualTo("api.openai.com");

        JsonNode suffixes = bypass.get("domain_suffix");
        assertThat(suffixes.size()).isEqualTo(2);
        assertThat(suffixes.get(0).asString()).isEqualTo("github.com");
        assertThat(suffixes.get(1).asString()).isEqualTo("corp.local");

        JsonNode keywords = bypass.get("domain_keyword");
        assertThat(keywords.size()).isEqualTo(1);
        assertThat(keywords.get(0).asString()).isEqualTo("google");

        JsonNode cidrs = bypass.get("ip_cidr");
        assertThat(cidrs.size()).isEqualTo(2);
        assertThat(cidrs.get(0).asString()).isEqualTo("192.168.0.0/16");
        assertThat(cidrs.get(1).asString()).isEqualTo("203.0.113.42/32");
    }

    @Test
    void bypassList_emptyProducesNoBypassRule() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();
        // bypassList is empty by default

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode rules = parse(json).get("route").get("rules");

        // Only the two unconditional local-bypass rules; no user domain/cidr rule.
        assertThat(rules.size()).isEqualTo(2);
        assertThat(isLocalDomainRule(rules.get(0))).isTrue();
        assertThat(rules.get(1).get("ip_is_private").asBoolean()).isTrue();
    }

    @Test
    void routeAll_generatesMinimalRouteSection() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode root = parse(json);

        JsonNode route = root.get("route");
        assertThat(route).isNotNull();
        assertThat(route.get("final").asString()).isEqualTo("proxy");
        assertThat(route.get("auto_detect_interface").asBoolean()).isTrue();
        List<JsonNode> rules = withoutLookup(route.get("rules"));
        assertThat(rules).isNotNull();
        // route_all still emits the unconditional local-bypass rules — local
        // services must stay reachable even in "everything via proxy".
        assertThat(rules.size()).isEqualTo(2);
        assertThat(isLocalDomainRule(rules.get(0))).isTrue();
        assertThat(rules.get(1).get("ip_is_private").asBoolean()).isTrue();
        assertThat(rules.get(1).get("outbound").asString()).isEqualTo("direct");
    }

    @Test
    void localHostnames_goDirect() throws Exception {
        // localhost + *.local by NAME must bypass the tunnel: the private-IP
        // rule only catches them once resolved to an IP, which never happens
        // in system-proxy mode (the app hands the hostname to the proxy).
        RoutingConfig routingConfig = new RoutingConfig();

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode rules = parse(json).get("route").get("rules");

        JsonNode local = rules.get(0);
        assertThat(isLocalDomainRule(local)).isTrue();
        assertThat(local.get("domain").get(0).asString()).isEqualTo("localhost");
        assertThat(local.get("domain_suffix").get(0).asString()).isEqualTo(".local");
        assertThat(local.get("action").asString()).isEqualTo("route");
        assertThat(local.get("outbound").asString()).isEqualTo("direct");
    }

    @Test
    void countryBypass_emitsRussianRuleSets() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setBypassCountries(List.of("ru"));

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode root = parse(json);

        JsonNode route = root.get("route");
        assertThat(route).isNotNull();

        // [ local-domain, private-ip, geosite-category-ru, geoip-ru ] — all direct.
        List<JsonNode> rules = withoutLookup(route.get("rules"));
        assertThat(rules.size()).isEqualTo(4);

        assertThat(isLocalDomainRule(rules.get(0))).isTrue();
        assertThat(rules.get(1).get("ip_is_private").asBoolean()).isTrue();
        assertThat(rules.get(1).get("outbound").asString()).isEqualTo("direct");

        assertThat(rules.get(2).get("rule_set").get(0).asString())
                .isEqualTo("geosite-category-ru");
        assertThat(rules.get(2).get("outbound").asString()).isEqualTo("direct");

        assertThat(rules.get(3).get("rule_set").get(0).asString()).isEqualTo("geoip-ru");
        assertThat(rules.get(3).get("outbound").asString()).isEqualTo("direct");

        JsonNode ruleSet = route.get("rule_set");
        assertThat(ruleSet).isNotNull();
        assertThat(ruleSet.size()).isEqualTo(2);
        assertThat(ruleSet.get(0).get("tag").asString()).isEqualTo("geosite-category-ru");
        assertThat(ruleSet.get(0).get("type").asString()).isEqualTo("remote");
        assertThat(ruleSet.get(0).get("format").asString()).isEqualTo("binary");
        assertThat(ruleSet.get(0).get("url").asString())
                .isEqualTo("https://raw.githubusercontent.com/SagerNet/sing-geosite/"
                        + "rule-set/geosite-category-ru.srs");
        // Through the tunnel: GitHub raw is often blocked when dialed
        // directly on the networks this client is for. Set on http_client:
        // download_detour is deprecated, and a newer core stops at rule-set
        // start over it while `check` still passes.
        for (JsonNode entry : ruleSet) {
            assertThat(entry.path("http_client").path("detour").asString()).isEqualTo("proxy");
            assertThat(entry.has("download_detour")).isFalse();
        }
        assertThat(ruleSet.get(1).get("tag").asString()).isEqualTo("geoip-ru");
        assertThat(ruleSet.get(1).get("url").asString())
                .isEqualTo("https://raw.githubusercontent.com/SagerNet/sing-geoip/"
                        + "rule-set/geoip-ru.srs");

        assertThat(route.get("final").asString()).isEqualTo("proxy");
    }

    @Test
    void bypassDomestic_chinaUsesPlainGeositeTag() throws Exception {
        // CN is the one country with a bare 'geosite-cn.srs' aggregate.
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setBypassCountries(List.of("cn"));

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode route = parse(json).get("route");

        // rules[0]=local-domain, rules[1]=private-ip; preset rules start at 2.
        assertThat(route.get("rules").get(2).get("rule_set").get(0).asString())
                .isEqualTo("geosite-cn");
        assertThat(route.get("rule_set").get(0).get("url").asString())
                .endsWith("/geosite-cn.srs");
    }

    @Test
    void bypassDomestic_skipsGeositeWhenCountryHasNoAggregate() throws Exception {
        // Germany (and most others) has no sing-geosite aggregate; the
        // generator must drop the geosite rule rather than emit a 404 URL.
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setBypassCountries(List.of("DE"));

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode route = parse(json).get("route");

        List<JsonNode> rules = withoutLookup(route.get("rules"));
        // [ local-domain, private-ip, geoip-de ]
        assertThat(rules.size()).isEqualTo(3);
        assertThat(isLocalDomainRule(rules.get(0))).isTrue();
        assertThat(rules.get(1).get("ip_is_private").asBoolean()).isTrue();
        assertThat(rules.get(2).get("rule_set").get(0).asString()).isEqualTo("geoip-de");

        JsonNode ruleSet = route.get("rule_set");
        assertThat(ruleSet.size()).isEqualTo(1);
        assertThat(ruleSet.get(0).get("tag").asString()).isEqualTo("geoip-de");
        assertThat(ruleSet.get(0).get("url").asString()).endsWith("/geoip-de.srs");
    }

    @Test
    void bypassDomestic_multipleCountriesShareTwoRules() throws Exception {
        // Multi-select stays flat: one geosite rule and one geoip rule OR the
        // selected countries' rule sets together. kz has no sing-geosite
        // aggregate and contributes only its geoip set.
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setBypassCountries(List.of("ru", "kz", "cn"));

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode route = parse(json).get("route");

        // [ local-domain, private-ip, geosite (ru+cn), geoip (ru+kz+cn) ]
        List<JsonNode> rules = withoutLookup(route.get("rules"));
        assertThat(rules.size()).isEqualTo(4);

        JsonNode geositeRule = rules.get(2);
        assertThat(geositeRule.get("rule_set").get(0).asString())
                .isEqualTo("geosite-category-ru");
        assertThat(geositeRule.get("rule_set").get(1).asString()).isEqualTo("geosite-cn");
        assertThat(geositeRule.get("outbound").asString()).isEqualTo("direct");

        JsonNode geoipRule = rules.get(3);
        assertThat(geoipRule.get("rule_set").get(0).asString()).isEqualTo("geoip-ru");
        assertThat(geoipRule.get("rule_set").get(1).asString()).isEqualTo("geoip-kz");
        assertThat(geoipRule.get("rule_set").get(2).asString()).isEqualTo("geoip-cn");
        assertThat(geoipRule.get("outbound").asString()).isEqualTo("direct");

        // route.rule_set declares each tag exactly once, in insertion order.
        JsonNode ruleSet = route.get("rule_set");
        assertThat(ruleSet.size()).isEqualTo(5);
        assertThat(ruleSet.get(0).get("tag").asString()).isEqualTo("geosite-category-ru");
        assertThat(ruleSet.get(1).get("tag").asString()).isEqualTo("geoip-ru");
        assertThat(ruleSet.get(2).get("tag").asString()).isEqualTo("geoip-kz");
        assertThat(ruleSet.get(3).get("tag").asString()).isEqualTo("geosite-cn");
        assertThat(ruleSet.get(4).get("tag").asString()).isEqualTo("geoip-cn");
    }

    /**
     * A stored rule the core would refuse stopped every server from
     * connecting: sing-box refuses the whole configuration over it. It is
     * left out, and the rules around it stay.
     */
    @Test
    void aStoredRuleTheCoreWouldRefuseIsLeftOutAndTheRestStay() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setRules(List.of(
                new RoutingRule(RoutingRule.RuleType.DOMAIN_REGEX, "(",
                        RoutingRule.RuleAction.PROXY),
                new RoutingRule(RoutingRule.RuleType.DOMAIN_SUFFIX, "example.com",
                        RoutingRule.RuleAction.DIRECT),
                new RoutingRule(RoutingRule.RuleType.IP_CIDR, "999.1.1.1/8",
                        RoutingRule.RuleAction.DIRECT)));

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);

        assertThat(json).doesNotContain("\"(\"").doesNotContain("999.1.1.1/8");
        List<JsonNode> rules = withoutLookup(parse(json).get("route").get("rules"));
        assertThat(rules).anySatisfy(rule -> assertThat(rule.path("domain_suffix").toString())
                .contains("example.com"));
        assertThat(rules).noneSatisfy(rule -> assertThat(rule.has("domain_regex")).isTrue());
    }

    /** The repository names its lists in lower case: "Google" was a 404, and no core. */
    @Test
    void aListNameIsWrittenInLowerCase() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setRules(List.of(
                new RoutingRule(RoutingRule.RuleType.GEOSITE, "Google",
                        RoutingRule.RuleAction.PROXY)));

        JsonNode root = parse(generator.generate(createVlessServer(), defaultSettings,
                routingConfig));

        assertThat(root.get("route").get("rule_set").toString())
                .contains("\"geosite-google\"").contains("geosite-google.srs")
                .doesNotContain("Google");
    }

    @Test
    void customRules_generateCorrectSingBoxRouteEntries() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setRules(List.of(
                new RoutingRule(RoutingRule.RuleType.DOMAIN_SUFFIX, ".google.com",
                        RoutingRule.RuleAction.PROXY),
                new RoutingRule(RoutingRule.RuleType.IP_CIDR, "10.0.0.0/8",
                        RoutingRule.RuleAction.DIRECT),
                new RoutingRule(RoutingRule.RuleType.GEOSITE, "cn",
                        RoutingRule.RuleAction.DIRECT),
                new RoutingRule(RoutingRule.RuleType.GEOIP, "cn",
                        RoutingRule.RuleAction.DIRECT),
                new RoutingRule(RoutingRule.RuleType.DOMAIN, "example.com",
                        RoutingRule.RuleAction.BLOCK),
                new RoutingRule(RoutingRule.RuleType.DOMAIN_KEYWORD, "ads",
                        RoutingRule.RuleAction.BLOCK),
                new RoutingRule(RoutingRule.RuleType.DOMAIN_REGEX, ".*\\.ads\\..*",
                        RoutingRule.RuleAction.BLOCK)
        ));

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode root = parse(json);

        List<JsonNode> rules = withoutLookup(root.get("route").get("rules"));
        // 7 user-defined rules + local-domain + private-ip prepended.
        assertThat(rules.size()).isEqualTo(9);

        // Local-bypass block (always prepended)
        assertThat(isLocalDomainRule(rules.get(0))).isTrue();
        assertThat(rules.get(1).get("ip_is_private").asBoolean()).isTrue();
        assertThat(rules.get(1).get("outbound").asString()).isEqualTo("direct");

        // domain_suffix rule
        assertThat(rules.get(2).get("domain_suffix").get(0).asString()).isEqualTo(".google.com");
        assertThat(rules.get(2).get("outbound").asString()).isEqualTo("proxy");

        // ip_cidr rule
        assertThat(rules.get(3).get("ip_cidr").get(0).asString()).isEqualTo("10.0.0.0/8");
        assertThat(rules.get(3).get("outbound").asString()).isEqualTo("direct");

        // geosite rule — migrated to rule_set reference
        assertThat(rules.get(4).get("rule_set").get(0).asString()).isEqualTo("geosite-cn");
        assertThat(rules.get(4).get("outbound").asString()).isEqualTo("direct");

        // geoip rule — migrated to rule_set reference
        assertThat(rules.get(5).get("rule_set").get(0).asString()).isEqualTo("geoip-cn");
        assertThat(rules.get(5).get("outbound").asString()).isEqualTo("direct");

        JsonNode ruleSet = root.get("route").get("rule_set");
        assertThat(ruleSet).isNotNull();
        assertThat(ruleSet.size()).isEqualTo(2);
        assertThat(ruleSet.get(0).get("tag").asString()).isEqualTo("geosite-cn");
        assertThat(ruleSet.get(1).get("tag").asString()).isEqualTo("geoip-cn");

        // Block rules use the reject action: there is no "block" outbound in
        // the config, and a rule naming one only dropped connections as the
        // side effect of an unresolvable tag.
        assertThat(rules.get(6).get("domain").get(0).asString()).isEqualTo("example.com");
        assertThat(rules.get(6).get("action").asString()).isEqualTo("reject");
        assertThat(rules.get(6).has("outbound")).isFalse();

        // domain_keyword rule
        assertThat(rules.get(7).get("domain_keyword").get(0).asString()).isEqualTo("ads");
        assertThat(rules.get(7).get("action").asString()).isEqualTo("reject");

        // domain_regex rule
        assertThat(rules.get(8).get("domain_regex").get(0).asString()).isEqualTo(".*\\.ads\\..*");
        assertThat(rules.get(8).get("action").asString()).isEqualTo("reject");
    }

    /**
     * Every outbound a rule names must be declared. A dangling tag does not
     * stop the core from starting: the matching connection is just dropped,
     * silently — which is exactly how {@code outbound: "block"} went unnoticed
     * for as long as it did, since no outbound of that tag was ever emitted.
     */
    @Test
    void everyRuleOutboundResolvesToADeclaredOutbound() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setBypassCountries(List.of("ru"));
        routingConfig.setBypassList(List.of("*.example.org"));
        routingConfig.setRules(List.of(
                new RoutingRule(RoutingRule.RuleType.DOMAIN, "blocked.example",
                        RoutingRule.RuleAction.BLOCK),
                new RoutingRule(RoutingRule.RuleType.DOMAIN_SUFFIX, ".direct.example",
                        RoutingRule.RuleAction.DIRECT),
                new RoutingRule(RoutingRule.RuleType.GEOIP, "cn",
                        RoutingRule.RuleAction.PROXY)));

        JsonNode root = parse(generator.generate(createVlessServer(), defaultSettings,
                routingConfig));

        java.util.Set<String> declared = new java.util.HashSet<>();
        root.get("outbounds").forEach(outbound -> declared.add(outbound.get("tag").asString()));
        if (root.has("endpoints")) {
            root.get("endpoints").forEach(endpoint -> declared.add(endpoint.get("tag").asString()));
        }
        assertThat(declared).doesNotContain("block");

        for (JsonNode rule : root.get("route").get("rules")) {
            JsonNode outbound = rule.get("outbound");
            if (outbound != null) {
                assertThat(declared)
                        .as("rule %s names an outbound that does not exist", rule)
                        .contains(outbound.asString());
            }
        }
    }

    @Test
    void legacyGeoFields_neverEmitted() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setBypassCountries(List.of("ru"));

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode route = parse(json).get("route");

        assertThat(route.has("geo_asset_path")).isFalse();
        assertThat(route.has("geoip")).isFalse();
        assertThat(route.has("geosite")).isFalse();
    }

    @Test
    void routeAll_emitsNoRuleSetOrLegacyGeoBlocks() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode route = parse(json).get("route");

        assertThat(route.has("geo_asset_path")).isFalse();
        assertThat(route.has("geoip")).isFalse();
        assertThat(route.has("geosite")).isFalse();
        assertThat(route.has("rule_set")).isFalse();
    }

    @Test
    void noRoutingConfig_stillRoutesThroughTheSelector() throws Exception {
        String json = generator.generate(createVlessServer(), defaultSettings);
        JsonNode root = parse(json);

        assertThat(root.path("route").path("final").asString()).isEqualTo("proxy");
    }

    @Test
    void nullRoutingConfig_stillRoutesThroughTheSelector() throws Exception {
        String json = generator.generate(createVlessServer(), defaultSettings, null);
        JsonNode root = parse(json);

        assertThat(root.path("route").path("final").asString()).isEqualTo("proxy");
    }

    @Test
    void routeAll_localTrafficGoesDirect() throws Exception {
        // The unconditional local bypass is what makes "all local traffic
        // goes around the VPN" true for route_all in system-proxy mode.
        RoutingConfig routingConfig = new RoutingConfig();

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode rules = parse(json).get("route").get("rules");

        assertThat(rules.size()).isEqualTo(2);
        assertThat(isLocalDomainRule(rules.get(0))).isTrue();
        JsonNode privateRule = rules.get(1);
        assertThat(privateRule.get("ip_is_private").asBoolean()).isTrue();
        assertThat(privateRule.get("action").asString()).isEqualTo("route");
        assertThat(privateRule.get("outbound").asString()).isEqualTo("direct");
    }

    @Test
    void customPreset_localBypassPrecedesCustomRules() throws Exception {
        // Ordering matters: a custom PROXY rule for 10.0.0.0/8 must not be
        // able to drag LAN traffic through the proxy. The local bypass is
        // prepended so it wins first.
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setRules(List.of(
                new RoutingRule(RoutingRule.RuleType.IP_CIDR, "10.0.0.0/8",
                        RoutingRule.RuleAction.PROXY)
        ));

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        List<JsonNode> rules = withoutLookup(parse(json).get("route").get("rules"));

        assertThat(rules.size()).isEqualTo(3);
        assertThat(isLocalDomainRule(rules.get(0))).isTrue();
        assertThat(rules.get(1).get("ip_is_private").asBoolean()).isTrue();
        assertThat(rules.get(1).get("outbound").asString()).isEqualTo("direct");
        assertThat(rules.get(2).get("ip_cidr").get(0).asString()).isEqualTo("10.0.0.0/8");
        assertThat(rules.get(2).get("outbound").asString()).isEqualTo("proxy");
    }

    @Test
    void bypassListWinsAboveLanRule() throws Exception {
        // The user's bypass list is the most explicit signal — it must come
        // before the local-bypass rules so the ordering is deterministic.
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setBypassList(List.of("internal.example.com"));

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode rules = parse(json).get("route").get("rules");

        // [ user-bypass, local-domain, private-ip ]
        assertThat(rules.size()).isEqualTo(3);
        assertThat(rules.get(0).has("domain")).isTrue();
        assertThat(rules.get(0).get("domain").get(0).asString()).isEqualTo("internal.example.com");
        assertThat(isLocalDomainRule(rules.get(1))).isTrue();
        assertThat(rules.get(2).get("ip_is_private").asBoolean()).isTrue();
    }

    @Test
    void lanBypass_neverDuplicatedInBypassDomestic() throws Exception {
        // Regression guard: bypass_domestic used to add its own ip_is_private
        // rule. After the move to the universal local-bypass block, the preset
        // must NOT double-emit it.
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setBypassCountries(List.of("ru"));

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        // The LAN rule system-proxy mode repeats after its lookup is not the preset's.
        List<JsonNode> rules = withoutLookup(parse(json).get("route").get("rules"));

        int privateCount = 0;
        for (JsonNode rule : rules) {
            JsonNode privateFlag = rule.get("ip_is_private");
            if (privateFlag != null && privateFlag.asBoolean()) {
                privateCount++;
            }
        }
        assertThat(privateCount).isEqualTo(1);
    }

    @Test
    void customPreset_emptyRulesList_stillEmitsLanBypass() throws Exception {
        // Even with no user-defined rules in custom preset, the local-bypass
        // rules keep local services reachable.
        RoutingConfig routingConfig = new RoutingConfig();

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode route = parse(json).get("route");

        List<JsonNode> rules = withoutLookup(route.get("rules"));
        assertThat(rules.size()).isEqualTo(2);
        assertThat(isLocalDomainRule(rules.get(0))).isTrue();
        assertThat(rules.get(1).get("ip_is_private").asBoolean()).isTrue();
        assertThat(rules.get(1).get("outbound").asString()).isEqualTo("direct");
        assertThat(route.get("final").asString()).isEqualTo("proxy");
    }

    // ===== system-proxy mode: names get an address before the IP rules =====

    /**
     * A browser behind the system proxy asks for a name, and the core compares
     * IP rules with the destination address alone, so "bypass DE" never matched
     * www.spiegel.de. The lookup goes right before the first IP rule, and the
     * name rules above it still match without one.
     */
    @Test
    void systemProxy_resolvesNamesRightBeforeTheFirstIpRule() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setRules(List.of(
                new RoutingRule(RoutingRule.RuleType.DOMAIN_SUFFIX, "corp.example.com",
                        RoutingRule.RuleAction.DIRECT),
                new RoutingRule(RoutingRule.RuleType.IP_CIDR, "203.0.113.0/24",
                        RoutingRule.RuleAction.BLOCK)));

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode rules = parse(json).get("route").get("rules");

        // [ local-domain, private-ip, corp.example.com, resolve, private-ip, 203.0.113.0/24 ]
        assertThat(rules.size()).isEqualTo(6);
        assertThat(rules.get(2).get("domain_suffix").get(0).asString())
                .isEqualTo("corp.example.com");
        assertThat(rules.get(3).path("action").asString()).isEqualTo("resolve");
        assertThat(rules.get(3).has("server"))
                .as("no server of its own: the DNS rules pick one, so LAN names stay local")
                .isFalse();
        assertThat(rules.get(4).path("ip_is_private").asBoolean())
                .as("a name that resolves to a LAN address still goes direct")
                .isTrue();
        assertThat(rules.get(4).path("outbound").asString()).isEqualTo("direct");
        assertThat(rules.get(5).path("ip_cidr").path(0).asString()).isEqualTo("203.0.113.0/24");
    }

    /**
     * The lookup needs resolvers: proxy DNS for a name bound for the tunnel, so
     * the local network is not asked; the system for LAN names. The core also
     * demands a default domain resolver once a dns block exists, which keeps
     * the proxy server's own name off the proxy.
     */
    @Test
    void systemProxy_countryBypassBringsItsOwnResolvers() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setBypassCountries(List.of("de"));

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode root = parse(json);

        JsonNode dns = root.path("dns");
        assertThat(dns.path("final").asString()).isEqualTo("proxy-dns");
        assertThat(dns.path("rules").toString())
                .contains("\".lan\"").contains("\".home.arpa\"").contains("\".internal\"");
        assertThat(root.path("route").path("default_domain_resolver").asString())
                .isEqualTo("local-dns");
        JsonNode rules = root.path("route").path("rules");
        assertThat(rules.path(rules.size() - 3).path("action").asString()).isEqualTo("resolve");
        assertThat(rules.path(rules.size() - 1).path("rule_set").path(0).asString())
                .isEqualTo("geoip-de");
    }

    @Test
    void systemProxy_withoutIpRulesLooksNothingUp() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setBypassList(List.of("example.com", "192.168.0.0/16"));
        routingConfig.setRules(List.of(new RoutingRule(RoutingRule.RuleType.DOMAIN,
                "internal.example.com", RoutingRule.RuleAction.DIRECT)));

        String json = generator.generate(createVlessServer(), defaultSettings, routingConfig);
        JsonNode root = parse(json);

        assertThat(root.path("route").path("rules").toString()).doesNotContain("\"resolve\"");
        assertThat(root.has("dns")).isFalse();
        assertThat(root.path("route").has("default_domain_resolver")).isFalse();
    }

    @Test
    void tunMode_ipRulesNeedNoLookup() throws Exception {
        AppSettings settings = new AppSettings();
        settings.setProxyMode(com.vlessclient.model.ProxyMode.TUN);
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setBypassCountries(List.of("de"));

        String json = generator.generate(createVlessServer(), settings, routingConfig);

        assertThat(parse(json).path("route").path("rules").toString())
                .as("TUN connections carry an address already")
                .doesNotContain("\"resolve\"");
    }

    /** IPv4-only answers are for a TUN device without IPv6, not for the system proxy. */
    @Test
    void systemProxy_keepsTheChosenDnsStrategy() throws Exception {
        AppSettings settings = new AppSettings();
        settings.setTunIpv6Enabled(false);
        settings.setDnsStrategy("prefer_ipv6");
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setBypassCountries(List.of("de"));

        String json = generator.generate(createVlessServer(), settings, routingConfig);

        assertThat(parse(json).path("dns").path("strategy").asString()).isEqualTo("prefer_ipv6");
    }
}
