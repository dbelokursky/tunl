package com.vlessclient.service;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.RoutingRule;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TransportType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class SingBoxConfigGeneratorTunTest {

    private SingBoxConfigGenerator generator;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        generator = new SingBoxConfigGenerator();
        mapper = JsonMapper.builder().build();
    }

    private ServerConfig createVlessServer() {
        ServerConfig server = new ServerConfig();
        server.setName("Test Server");
        server.setProtocol(Protocol.VLESS);
        server.setAddress("1.2.3.4");
        server.setPort(443);
        server.setUuid("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        server.getTls().setEnabled(false);
        server.getTransport().setType(TransportType.TCP);
        return server;
    }

    private AppSettings tunSettings() {
        AppSettings settings = new AppSettings();
        settings.setProxyMode(ProxyMode.TUN);
        return settings;
    }

    private AppSettings systemProxySettings() {
        AppSettings settings = new AppSettings();
        settings.setProxyMode(ProxyMode.SYSTEM_PROXY);
        return settings;
    }

    private JsonNode parse(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void tunMode_includesTunInbound() throws Exception {
        String json = generator.generate(createVlessServer(), tunSettings());
        JsonNode inbounds = parse(json).get("inbounds");

        boolean hasTun = false;
        for (JsonNode inbound : inbounds) {
            if ("tun".equals(inbound.get("type").asString())) {
                hasTun = true;
                assertThat(inbound.get("tag").asString()).isEqualTo("tun-in");
                // The default interface name is platform-dependent (utunN is
                // a macOS kernel requirement; Windows names the adapter
                // freely), so pin against the settings value, not a literal.
                assertThat(inbound.get("interface_name").asString())
                        .isEqualTo(new AppSettings().getTunInterfaceName());
                JsonNode addr = inbound.get("address");
                assertThat(addr).isNotNull();
                assertThat(addr.isArray()).isTrue();
                assertThat(addr.get(0).asString()).isEqualTo("172.19.0.1/30");
                assertThat(inbound.get("dns_mode").asString()).isEqualTo("hijack");
                // Let sing-box derive 172.19.0.2 from the TUN prefix so it
                // can keep the DNS endpoint and interception rule aligned.
                assertThat(inbound.has("dns_address")).isFalse();
                break;
            }
        }
        assertThat(hasTun).isTrue();
    }

    @Test
    void tunMode_tunInboundHasAutoRouteButNoStrictRoute() throws Exception {
        // strict_route + route_exclude_address used to be set together in
        // v0.1.6, and they conflict: route_exclude_address tells sing-box
        // "let LAN escape the TUN" while strict_route installs a firewall
        // that blocks everything outside the TUN. The combination killed
        // direct-outbound dials (RU sites under bypass_domestic, host-level
        // connects to the proxy IP, latency probes). v0.1.7 keeps the LAN
        // exclude list and drops strict_route.
        String json = generator.generate(createVlessServer(), tunSettings());
        JsonNode inbounds = parse(json).get("inbounds");

        JsonNode tun = null;
        for (JsonNode inbound : inbounds) {
            if ("tun".equals(inbound.get("type").asString())) {
                tun = inbound;
                break;
            }
        }

        assertThat(tun).isNotNull();
        assertThat(tun.get("auto_route").asBoolean()).isTrue();
        // strict_route is deliberately absent — see comment in the
        // generator and the v0.1.7 investigation in the commit log.
        assertThat(tun.has("strict_route")).isFalse();
        // stack=gvisor (not system) — without strict_route's PF rules the
        // system stack silently drops TCP traffic from the TUN on macOS;
        // gvisor's userspace TCP/IP has no such dependency. v0.1.8 fix.
        assertThat(tun.get("stack").asString()).isEqualTo("gvisor");
        // sing-box 1.13 removed sniff/sniff_override_destination from inbounds
        assertThat(tun.has("sniff")).isFalse();
        assertThat(tun.has("sniff_override_destination")).isFalse();
    }

    @Test
    void tunMode_excludesPrivateAndMulticastFromAutoRoute() throws Exception {
        // Without route_exclude_address, auto_route's /1+/2+… coverage of
        // 0.0.0.0/0 swallows 192.168.0.0/16 into the TUN device — Screen
        // Sharing to 192.168.1.140 or macmini.local would hang. The exclude
        // list is the structural fix; the route-rule ip_is_private→direct
        // remains as a safety net.
        String json = generator.generate(createVlessServer(), tunSettings());
        JsonNode inbounds = parse(json).get("inbounds");

        JsonNode tun = null;
        for (JsonNode inbound : inbounds) {
            if ("tun".equals(inbound.get("type").asString())) {
                tun = inbound;
                break;
            }
        }

        assertThat(tun).isNotNull();
        JsonNode exclude = tun.get("route_exclude_address");
        assertThat(exclude).isNotNull();
        assertThat(exclude.isArray()).isTrue();

        java.util.List<String> entries = new java.util.ArrayList<>();
        for (JsonNode e : exclude) {
            entries.add(e.asString());
        }
        // IPv4 private + link-local + multicast (mDNS, SSDP).
        assertThat(entries).contains(
                "10.0.0.0/8",
                "172.16.0.0/12",
                "192.168.0.0/16",
                "169.254.0.0/16",
                "224.0.0.0/4");
        // IPv6 ULA + link-local + multicast.
        assertThat(entries).contains("fc00::/7", "fe80::/10", "ff00::/8");
    }

    @Test
    void systemProxyMode_doesNotEmitRouteExcludeAddress() throws Exception {
        // route_exclude_address is a TUN-inbound option only; it has no
        // analogue in socks/http inbound, and emitting it there would be
        // a config error.
        String json = generator.generate(createVlessServer(), systemProxySettings());
        JsonNode inbounds = parse(json).get("inbounds");

        for (JsonNode inbound : inbounds) {
            assertThat(inbound.has("route_exclude_address")).isFalse();
        }
    }

    @Test
    void tunMode_stillIncludesSocksAndHttpInbounds() throws Exception {
        String json = generator.generate(createVlessServer(), tunSettings());
        JsonNode inbounds = parse(json).get("inbounds");

        assertThat(inbounds.size()).isEqualTo(3);

        boolean hasSocks = false;
        boolean hasHttp = false;
        for (JsonNode inbound : inbounds) {
            String type = inbound.get("type").asString();
            if ("socks".equals(type)) {
                hasSocks = true;
            }
            if ("http".equals(type)) {
                hasHttp = true;
            }
        }
        assertThat(hasSocks).isTrue();
        assertThat(hasHttp).isTrue();
    }

    @Test
    void tunMode_includesDnsSection() throws Exception {
        String json = generator.generate(createVlessServer(), tunSettings());
        JsonNode root = parse(json);

        JsonNode dns = root.get("dns");
        assertThat(dns).isNotNull();

        JsonNode servers = dns.get("servers");
        assertThat(servers).isNotNull();
        // proxy-dns, direct-dns, and the local resolver for localhost/*.local.
        assertThat(servers.size()).isEqualTo(3);

        assertThat(servers.get(0).get("tag").asString()).isEqualTo("proxy-dns");
        assertThat(servers.get(0).get("type").asString()).isEqualTo("https");
        assertThat(servers.get(0).get("server").asString()).isEqualTo("1.1.1.1");
        assertThat(servers.get(0).get("path").asString()).isEqualTo("/dns-query");
        assertThat(servers.get(0).get("detour").asString()).isEqualTo("proxy");

        assertThat(servers.get(1).get("tag").asString()).isEqualTo("direct-dns");
        assertThat(servers.get(1).get("type").asString()).isEqualTo("https");
        assertThat(servers.get(1).get("server").asString()).isEqualTo("223.5.5.5");
        assertThat(servers.get(1).get("path").asString()).isEqualTo("/dns-query");
        // sing-box 1.13 rejects detour: "direct" pointing at an empty direct
        // outbound, so direct-dns no longer sets the detour field.
        assertThat(servers.get(1).has("detour")).isFalse();

        // Local resolver so localhost / *.local resolve on the LAN via the OS
        // (mDNS) instead of the remote DoH server, which can't answer them.
        assertThat(servers.get(2).get("tag").asString()).isEqualTo("local-dns");
        assertThat(servers.get(2).get("type").asString()).isEqualTo("local");

        JsonNode dnsRules = dns.get("rules");
        assertThat(dnsRules).isNotNull();
        assertThat(dnsRules.size()).isEqualTo(1);
        assertThat(dnsRules.get(0).get("domain").get(0).asString()).isEqualTo("localhost");
        assertThat(dnsRules.get(0).get("domain_suffix").get(0).asString()).isEqualTo(".local");
        assertThat(dnsRules.get(0).get("server").asString()).isEqualTo("local-dns");

        // sing-box 1.13 uses dns.final instead of a match-all rule
        assertThat(dns.get("final").asString()).isEqualTo("proxy-dns");
        assertThat(dns.get("strategy").asString()).isEqualTo("prefer_ipv4");
    }

    @Test
    void systemProxyMode_doesNotIncludeTunInbound() throws Exception {
        String json = generator.generate(createVlessServer(), systemProxySettings());
        JsonNode inbounds = parse(json).get("inbounds");

        assertThat(inbounds.size()).isEqualTo(2);

        for (JsonNode inbound : inbounds) {
            assertThat(inbound.get("type").asString()).isNotEqualTo("tun");
        }
    }

    @Test
    void systemProxyMode_doesNotIncludeDnsSection() throws Exception {
        String json = generator.generate(createVlessServer(), systemProxySettings());
        JsonNode root = parse(json);

        assertThat(root.has("dns")).isFalse();
    }

    @Test
    void tunMode_customDnsServersAreReflectedInConfig() throws Exception {
        AppSettings settings = tunSettings();
        settings.setProxyDns("https://8.8.8.8/dns-query");
        settings.setDirectDns("https://9.9.9.9/dns-query");
        settings.setDnsStrategy("prefer_ipv6");

        String json = generator.generate(createVlessServer(), settings);
        JsonNode dns = parse(json).get("dns");

        assertThat(dns.get("servers").get(0).get("server").asString()).isEqualTo("8.8.8.8");
        assertThat(dns.get("servers").get(0).get("type").asString()).isEqualTo("https");
        assertThat(dns.get("servers").get(1).get("server").asString()).isEqualTo("9.9.9.9");
        assertThat(dns.get("servers").get(1).get("type").asString()).isEqualTo("https");
        assertThat(dns.get("strategy").asString()).isEqualTo("prefer_ipv6");
    }

    @Test
    void tunMode_customTunInterfaceNameIsReflectedInConfig() throws Exception {
        AppSettings settings = tunSettings();
        settings.setTunInterfaceName("utun42");

        String json = generator.generate(createVlessServer(), settings);
        JsonNode inbounds = parse(json).get("inbounds");

        JsonNode tun = null;
        for (JsonNode inbound : inbounds) {
            if ("tun".equals(inbound.get("type").asString())) {
                tun = inbound;
                break;
            }
        }
        assertThat(tun).isNotNull();
        assertThat(tun.get("interface_name").asString()).isEqualTo("utun42");
    }

    @Test
    void tunMode_noRoutingConfig_essentialsAddLanBypass() throws Exception {
        // With no RoutingConfig passed, TUN mode still needs the LAN bypass
        // for local services to stay reachable through the TUN device. The
        // essentials block is the only thing that adds rules in this path.
        String json = generator.generate(createVlessServer(), tunSettings());
        JsonNode rules = parse(json).get("route").get("rules");

        // sniff, hijack-dns, local-domain, ip_is_private — in that exact order.
        assertThat(rules.size()).isEqualTo(4);
        assertThat(rules.get(0).get("action").asString()).isEqualTo("sniff");
        assertThat(rules.get(1).get("action").asString()).isEqualTo("hijack-dns");
        assertThat(rules.get(2).get("domain_suffix").get(0).asString()).isEqualTo(".local");
        assertThat(rules.get(2).get("outbound").asString()).isEqualTo("direct");
        assertThat(rules.get(3).get("ip_is_private").asBoolean()).isTrue();
        assertThat(rules.get(3).get("outbound").asString()).isEqualTo("direct");
    }

    @Test
    void tunMode_withRoutingConfig_noDuplicatePrivateRule() throws Exception {
        // buildRoute always emits the LAN bypass now; TUN essentials must
        // dedup and not prepend a second copy. Sniff and hijack-dns are
        // always added regardless.
        RoutingConfig routingConfig = new RoutingConfig();

        String json = generator.generate(createVlessServer(), tunSettings(), routingConfig);
        JsonNode rules = parse(json).get("route").get("rules");

        int privateCount = 0;
        int localDomainCount = 0;
        for (int i = 0; i < rules.size(); i++) {
            JsonNode privateFlag = rules.get(i).get("ip_is_private");
            if (privateFlag != null && privateFlag.asBoolean()) {
                privateCount++;
            }
            JsonNode suffix = rules.get(i).get("domain_suffix");
            if (suffix != null && suffix.isArray()) {
                for (JsonNode s : suffix) {
                    if (".local".equals(s.asString())) {
                        localDomainCount++;
                    }
                }
            }
        }
        // Neither local-bypass rule is duplicated by the TUN essentials.
        assertThat(privateCount).isEqualTo(1);
        assertThat(localDomainCount).isEqualTo(1);

        // Order: sniff, hijack-dns (TUN-only), then the local-bypass rules
        // buildRoute already emitted.
        assertThat(rules.get(0).get("action").asString()).isEqualTo("sniff");
        assertThat(rules.get(1).get("action").asString()).isEqualTo("hijack-dns");
        assertThat(rules.get(2).get("domain_suffix").get(0).asString()).isEqualTo(".local");
        assertThat(rules.get(3).get("ip_is_private").asBoolean()).isTrue();
    }

    @Test
    void tunMode_customTunIpv4AddressIsReflectedInConfig() throws Exception {
        AppSettings settings = tunSettings();
        settings.setTunIpv4Address("10.10.0.1/24");

        String json = generator.generate(createVlessServer(), settings);
        JsonNode inbounds = parse(json).get("inbounds");

        JsonNode tun = null;
        for (JsonNode inbound : inbounds) {
            if ("tun".equals(inbound.get("type").asString())) {
                tun = inbound;
                break;
            }
        }
        assertThat(tun).isNotNull();
        assertThat(tun.get("address").get(0).asString()).isEqualTo("10.10.0.1/24");
    }

    /**
     * The direct resolver used to be declared and never referenced: every
     * query fell through to {@code dns.final}, so a name the user routes
     * around the tunnel was still resolved through the proxy — and the
     * "Direct DNS" field in Settings changed nothing. Resolution now follows
     * the same split as the traffic: the bypass list, custom direct rules and
     * the country bypass resolve through {@code direct-dns}.
     */
    @Test
    void tunMode_directRoutedNamesResolveThroughDirectDns() throws Exception {
        RoutingConfig routingConfig = new RoutingConfig();
        routingConfig.setBypassList(List.of("*.example.org", "10.0.0.0/8"));
        routingConfig.setBypassCountries(List.of("ru"));
        routingConfig.setRules(List.of(
                new RoutingRule(RoutingRule.RuleType.DOMAIN_SUFFIX, ".direct.example",
                        RoutingRule.RuleAction.DIRECT),
                new RoutingRule(RoutingRule.RuleType.GEOSITE, "cn",
                        RoutingRule.RuleAction.DIRECT),
                new RoutingRule(RoutingRule.RuleType.IP_CIDR, "192.0.2.0/24",
                        RoutingRule.RuleAction.DIRECT),
                new RoutingRule(RoutingRule.RuleType.DOMAIN, "proxied.example",
                        RoutingRule.RuleAction.PROXY),
                new RoutingRule(RoutingRule.RuleType.DOMAIN, "blocked.example",
                        RoutingRule.RuleAction.BLOCK)));

        JsonNode root = parse(generator.generate(createVlessServer(), tunSettings(),
                routingConfig));
        JsonNode dnsRules = root.get("dns").get("rules");

        // localhost/*.local stays first, and the default stays the proxy DNS.
        assertThat(dnsRules.get(0).get("server").asString()).isEqualTo("local-dns");
        assertThat(root.get("dns").get("final").asString()).isEqualTo("proxy-dns");

        List<JsonNode> direct = new java.util.ArrayList<>();
        dnsRules.forEach(rule -> {
            if ("direct-dns".equals(rule.path("server").asString())) {
                direct.add(rule);
            }
        });
        String all = direct.toString();
        assertThat(all).contains("example.org");          // bypass list, by suffix
        assertThat(all).contains(".direct.example");      // custom direct rule
        assertThat(all).contains("geosite-cn");           // custom geosite rule
        assertThat(all).doesNotContain("10.0.0.0/8");     // a query carries a name
        assertThat(all).doesNotContain("192.0.2.0/24");
        assertThat(all).doesNotContain("proxied.example"); // proxy rules stay on proxy-dns
        assertThat(all).doesNotContain("blocked.example");
        assertThat(direct).noneMatch(rule -> rule.has("ip_cidr"));

        // Every rule-set a DNS rule references is declared in the route.
        java.util.Set<String> declared = new java.util.HashSet<>();
        root.get("route").get("rule_set").forEach(set -> declared.add(set.get("tag").asString()));
        for (JsonNode rule : direct) {
            if (rule.has("rule_set")) {
                for (JsonNode tag : rule.get("rule_set")) {
                    assertThat(declared).contains(tag.asString());
                }
            }
        }
        // The country bypass contributes its geosite aggregate.
        assertThat(direct).anyMatch(rule -> rule.has("rule_set")
                && rule.get("rule_set").toString().contains("ru"));
    }

    @Test
    void tunMode_bootstrapResolverIsTheOsResolver() throws Exception {
        // The proxy server's hostname and remote rule-set hosts must resolve
        // on whatever network the machine is on. A hardcoded public DoH here
        // (the old direct-dns default, 223.5.5.5) killed TUN startup on
        // networks that block it before the tunnel even came up.
        String json = generator.generate(createVlessServer(), tunSettings());

        assertThat(parse(json).get("route").get("default_domain_resolver").asString())
                .isEqualTo("local-dns");
    }

    @Test
    void cacheFile_enabledAndPerProxyMode() throws Exception {
        // Cached rule-sets make offline/blocked restarts survivable. Per-mode
        // paths because the TUN core may run as root and a root-owned bbolt
        // file would break the next user-mode system-proxy run.
        AppSettings tun = tunSettings();
        JsonNode tunCache = parse(generator.generate(createVlessServer(), tun))
                .get("experimental").get("cache_file");
        assertThat(tunCache.get("enabled").asBoolean()).isTrue();
        assertThat(tunCache.get("path").asString()).endsWith("sing-box-tun.db");

        AppSettings sysProxy = new AppSettings();
        JsonNode proxyCache = parse(generator.generate(createVlessServer(), sysProxy))
                .get("experimental").get("cache_file");
        assertThat(proxyCache.get("path").asString()).endsWith("sing-box-system_proxy.db");

        assertThat(tunCache.get("path").asString())
                .isNotEqualTo(proxyCache.get("path").asString());
    }
}
