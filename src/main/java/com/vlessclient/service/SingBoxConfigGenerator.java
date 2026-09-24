package com.vlessclient.service;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.RouteMode;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.RoutingRule;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.ServerSelection;
import com.vlessclient.platform.Ipv6Uplink;
import com.vlessclient.service.outbound.Hysteria2OutboundBuilder;
import com.vlessclient.service.outbound.OutboundTags;
import com.vlessclient.service.outbound.ShadowsocksOutboundBuilder;
import com.vlessclient.service.outbound.TrojanOutboundBuilder;
import com.vlessclient.service.outbound.VlessOutboundBuilder;
import com.vlessclient.service.outbound.VmessOutboundBuilder;
import com.vlessclient.service.outbound.WireguardEndpointBuilder;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the JSON configuration handed to the sing-box core from a
 * {@link ServerConfig}, {@link AppSettings} and optional {@link RoutingConfig}.
 *
 * <p>Emits the sing-box 1.14 schema: log, DNS, inbounds (TUN/SOCKS/HTTP),
 * outbounds or a WireGuard endpoint, routing rules with remote rule-sets, and
 * the experimental Clash API / cache-file blocks.</p>
 *
 * <p>Per-protocol outbound/endpoint construction is delegated to the builders
 * in {@code com.vlessclient.service.outbound}; this class keeps the document
 * assembly (log, DNS, inbounds, route, experimental).</p>
 */
public class SingBoxConfigGenerator {

    private static final Logger log = LoggerFactory.getLogger(SingBoxConfigGenerator.class);

    /**
     * Probe used by the automatic group types. A 204-no-content endpoint is
     * the convention here: it is tiny, unauthenticated, and widely reachable,
     * so a failure means the server, not the target.
     */
    private static final String PROBE_URL = "https://www.gstatic.com/generate_204";
    private static final String PROBE_IDLE_TIMEOUT = "10m";
    private static final int PROBE_TOLERANCE_MS = 50;

    /** The runetfreedom lists of what is blocked in Russia: names first, then addresses. */
    private static final List<String> BLOCKED_IN_RUSSIA_LISTS =
            List.of("geosite-ru-blocked", "geoip-ru-blocked");
    private static final String BLOCKED_LISTS_URL = "https://raw.githubusercontent.com/"
            + "runetfreedom/russia-v2ray-rules-dat/release/sing-box/";

    /** Most servers the "Fastest" mode probes. */
    static final int MAX_AUTOMATIC_MEMBERS = 30;

    /**
     * How fast a server answered when it was last measured.
     */
    @FunctionalInterface
    public interface LatencyRanking {

        /** Nothing measured: the list's own order. */
        LatencyRanking NONE = serverId -> java.util.OptionalLong.empty();

        /**
         * The server's last latency.
         *
         * @param serverId the server's id
         * @return its latency in milliseconds; {@link Long#MAX_VALUE} when it
         *     did not answer; empty when it was not measured
         */
        java.util.OptionalLong lastLatency(String serverId);
    }

    private final ObjectMapper mapper;
    private final com.vlessclient.platform.SystemProxySupport systemProxySupport;
    private final Ipv6Uplink ipv6Uplink;
    private final VlessOutboundBuilder vlessBuilder;
    private final VmessOutboundBuilder vmessBuilder;
    private final TrojanOutboundBuilder trojanBuilder;
    private final ShadowsocksOutboundBuilder shadowsocksBuilder;
    private final Hysteria2OutboundBuilder hysteria2Builder;
    private final WireguardEndpointBuilder wireguardBuilder;
    private final LatencyRanking ranking;

    public SingBoxConfigGenerator() {
        this(LatencyRanking.NONE);
    }

    /**
     * Creates a generator whose "Fastest" mode keeps the servers that last
     * answered fastest.
     *
     * @param ranking the servers' last measurements
     */
    public SingBoxConfigGenerator(LatencyRanking ranking) {
        this(com.vlessclient.platform.SystemProxySupport.current(), Ipv6Uplink.current(),
                ranking);
    }

    /** Test seam: inject the host's system-proxy capability check. */
    SingBoxConfigGenerator(com.vlessclient.platform.SystemProxySupport systemProxySupport) {
        this(systemProxySupport, Ipv6Uplink.current(), LatencyRanking.NONE);
    }

    /** Test seam: inject the host's network as well, which the TUN device's IPv6 depends on. */
    SingBoxConfigGenerator(com.vlessclient.platform.SystemProxySupport systemProxySupport,
                           Ipv6Uplink ipv6Uplink) {
        this(systemProxySupport, ipv6Uplink, LatencyRanking.NONE);
    }

    private SingBoxConfigGenerator(com.vlessclient.platform.SystemProxySupport systemProxySupport,
                                   Ipv6Uplink ipv6Uplink, LatencyRanking ranking) {
        this.ranking = ranking;
        this.mapper = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
        this.systemProxySupport = systemProxySupport;
        this.ipv6Uplink = ipv6Uplink;
        this.vlessBuilder = new VlessOutboundBuilder(mapper);
        this.vmessBuilder = new VmessOutboundBuilder(mapper);
        this.trojanBuilder = new TrojanOutboundBuilder(mapper);
        this.shadowsocksBuilder = new ShadowsocksOutboundBuilder(mapper);
        this.hysteria2Builder = new Hysteria2OutboundBuilder(mapper);
        this.wireguardBuilder = new WireguardEndpointBuilder(mapper);
    }

    public String generate(ServerConfig server, AppSettings settings) {
        return generate(server, settings, null);
    }

    /**
     * Generates the sing-box configuration JSON for the given server, settings
     * and optional routing configuration.
     *
     * @param server        the server to build the proxy outbound/endpoint from
     * @param settings      app settings controlling proxy mode, DNS and ports
     * @param routingConfig routing rules to apply, or {@code null} for defaults
     * @return the sing-box configuration serialized as a JSON string
     */
    public String generate(ServerConfig server, AppSettings settings,
                           RoutingConfig routingConfig) {
        return generate(List.of(server), server, settings, routingConfig);
    }

    /**
     * Generates the configuration for a set of candidate servers.
     *
     * <p>Which of them actually carries traffic depends on
     * {@link AppSettings#getServerSelection()}: pinned to {@code active}, or
     * chosen by the core among all candidates. Membership is derived here at
     * generate time rather than stored, so a subscription refresh cannot leave
     * a stale member behind.</p>
     *
     * @param candidates every server the group may use, including manual switches
     * @param active     the pinned server, and the fallback when a mode needs
     *                   one specific server
     * @param settings   app settings, including the selection mode
     * @param routingConfig routing rules, or {@code null} for defaults
     * @return the sing-box configuration serialized as a JSON string
     */
    public String generate(List<ServerConfig> candidates, ServerConfig active,
                           AppSettings settings, RoutingConfig routingConfig) {
        return generate(candidates, active, settings, routingConfig, hostFacts());
    }

    /**
     * Generates the configuration from facts about the host that the caller
     * keeps: a running core's, so that generating again to compare asks the
     * host nothing and reads the host as it was at the start.
     *
     * @param candidates    every server the group may use
     * @param active        the pinned server
     * @param settings      app settings
     * @param routingConfig routing rules, or {@code null} for defaults
     * @param host          what the host was like; see {@link #hostFacts()}
     * @return the sing-box configuration serialized as a JSON string
     */
    public String generate(List<ServerConfig> candidates, ServerConfig active,
                           AppSettings settings, RoutingConfig routingConfig,
                           HostFacts host) {
        ObjectNode root = mapper.createObjectNode();
        // Decided once: the device's address and the DNS strategy must agree.
        boolean tunIpv6 = tunTakesIpv6(settings, host);

        root.set("log", buildLog(settings));

        if (settings.getProxyMode() == ProxyMode.TUN) {
            root.set("dns", buildDns(settings, routingConfig, tunIpv6));
        }

        root.set("inbounds", buildInbounds(settings, tunIpv6, host));

        // WireGuard is not an outbound anymore: sing-box 1.13 removed the
        // legacy wireguard outbound (deprecated since 1.11) in favor of a
        // top-level endpoints entry. It carries its own server tag and the
        // proxy group references it, exactly like an outbound member — a
        // selector may point at an endpoint (verified against the real core).
        List<ServerConfig> members = groupMembers(candidates, active,
                settings.getServerSelection());
        ArrayNode endpoints = mapper.createArrayNode();
        for (ServerConfig member : members) {
            if (member.getProtocol() == Protocol.WIREGUARD) {
                endpoints.add(wireguardBuilder.build(member, OutboundTags.server(member)));
            }
        }
        if (!endpoints.isEmpty()) {
            root.set("endpoints", endpoints);
        }
        root.set("outbounds", buildOutbounds(members, settings.getServerSelection(), active));

        if (routingConfig != null) {
            ObjectNode route = buildRoute(routingConfig);
            ensureTunRouteEssentials(route, settings);
            resolveNamesBeforeIpRules(root, route, settings, routingConfig);
            root.set("route", route);
        } else if (settings.getProxyMode() == ProxyMode.TUN) {
            // sing-box 1.13 needs a route block with default_domain_resolver
            // plus auto_detect_interface so DNS and outbound dial can escape
            // the TUN interface via the physical network.
            ObjectNode route = mapper.createObjectNode();
            ensureTunRouteEssentials(route, settings);
            root.set("route", route);
        }

        // Always enter through the group, even without user routing rules.
        // Otherwise the first outbound bypasses the selector and API switches
        // change the reported pick without changing where traffic goes.
        ObjectNode route = (ObjectNode) root.get("route");
        if (route == null) {
            route = mapper.createObjectNode();
            root.set("route", route);
        }
        if (!route.has("final")) {
            route.put("final", OutboundTags.PROXY);
        }

        root.set("experimental", buildExperimental(settings));

        try {
            return mapper.writeValueAsString(root);
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to generate sing-box config", e);
        }
    }

    /**
     * The core's own verbosity, from {@link AppSettings#getCoreLogLevel()}.
     * It was pinned to {@code info} here, which made the Logs tab's debug
     * filter permanently empty: no debug line ever reached it.
     */
    private ObjectNode buildLog(AppSettings settings) {
        ObjectNode log = mapper.createObjectNode();
        log.put("level", settings.getCoreLogLevel().getValue());
        log.put("timestamp", true);
        return log;
    }

    private ObjectNode buildDns(AppSettings settings, RoutingConfig routingConfig,
                                boolean tunIpv6) {
        ObjectNode proxyDns = mapper.createObjectNode();
        proxyDns.put("tag", "proxy-dns");
        populateDnsServerAddress(proxyDns, settings.getProxyDns());
        proxyDns.put("detour", "proxy");

        ArrayNode servers = mapper.createArrayNode();
        servers.add(proxyDns);

        ObjectNode directDns = mapper.createObjectNode();
        directDns.put("tag", "direct-dns");
        if (isSystemDns(settings.getDirectDns())) {
            // The OS resolver: the network's own, from the address the direct
            // connection leaves anyway.
            directDns.put("type", "local");
        } else {
            populateDnsServerAddress(directDns, settings.getDirectDns());
        }
        // Deliberately NOT setting detour:"direct" here — sing-box 1.13
        // rejects DNS servers that detour to an "empty" direct outbound with
        // FATAL "detour to an empty direct outbound makes no sense".
        // Omitting detour lets the server dial through the default outbound
        // route, which for a bare system resolver address does the right thing.
        if (directDns.has("server") && !isIpLiteral(directDns.get("server").asString())) {
            // The core refuses a server named by host that it has no way to
            // resolve ("missing domain resolver for domain server address"),
            // and route.default_domain_resolver does not reach DNS servers, so
            // TUN mode never started. Resolve the name through the OS, like the
            // other bootstrap lookups. Proxy DNS needs no resolver: it dials
            // through the proxy, which takes the name as it is.
            directDns.put("domain_resolver", "local-dns");
        }
        servers.add(directDns);

        // Local resolver (OS/mDNS). Used only for localhost and *.local so
        // those names resolve on the LAN instead of being sent to the remote
        // DoH server, which cannot answer link-local mDNS queries.
        ObjectNode localDns = mapper.createObjectNode();
        localDns.put("tag", "local-dns");
        localDns.put("type", "local");
        servers.add(localDns);

        ObjectNode dns = mapper.createObjectNode();
        dns.set("servers", servers);

        // Resolve localhost / *.local via the local resolver; everything else
        // falls through to dns.final (proxy DNS).
        ObjectNode localRule = mapper.createObjectNode();
        ArrayNode localDomains = mapper.createArrayNode();
        localDomains.add("localhost");
        localRule.set("domain", localDomains);
        ArrayNode localSuffixes = mapper.createArrayNode();
        localSuffixes.add(".local");
        localRule.set("domain_suffix", localSuffixes);
        localRule.put("server", "local-dns");
        ArrayNode dnsRules = mapper.createArrayNode();
        dnsRules.add(localRule);
        directDnsRules(routingConfig).forEach(dnsRules::add);
        boolean blockedOnly = routingConfig != null
                && routingConfig.getMode() == RouteMode.BLOCKED_IN_RUSSIA;
        if (blockedOnly) {
            // A blocked name gets the address of a stub page from the
            // provider's resolver, so it resolves through the tunnel.
            ObjectNode blockedNames = mapper.createObjectNode();
            blockedNames.putArray("rule_set").add(BLOCKED_IN_RUSSIA_LISTS.getFirst());
            blockedNames.put("server", "proxy-dns");
            dnsRules.add(blockedNames);
        }
        dns.set("rules", dnsRules);

        // In sing-box 1.13 the dns.rules[].outbound match-all form and
        // the string address shortcut were removed. Use dns.final to route
        // all queries through the proxy DNS by default, and directly when
        // only the blocked lists go through the tunnel.
        dns.put("final", blockedOnly ? "direct-dns" : "proxy-dns");

        // A TUN device without an IPv6 address routes IPv4 alone, and the core
        // drops AAAA answers only for ipv4_only: any other strategy handed the
        // system IPv6 addresses, which it reached around the tunnel.
        boolean tunWithoutIpv6 = settings.getProxyMode() == ProxyMode.TUN && !tunIpv6;
        dns.put("strategy", tunWithoutIpv6 ? "ipv4_only" : settings.getDnsStrategy());

        return dns;
    }

    /** The TUN device's IPv6 address: sing-box's documented ULA example, a /126 like the v4 /30. */
    static final String TUN_IPV6_ADDRESS = "fdfe:dcba:9876::1/126";

    /** The name-based matchers a route rule can carry over into a DNS rule. */
    private static final List<String> DNS_NAME_MATCHERS =
            List.of("domain", "domain_suffix", "domain_keyword", "domain_regex");

    /**
     * DNS rules that send the names of direct-routed traffic to
     * {@code direct-dns}.
     *
     * <p>The direct DNS server was declared and never referenced: every query
     * fell through to {@code dns.final}, so a domain the user routes around
     * the tunnel was still resolved through the proxy resolver — the remote
     * side saw the query, and a geo-aware answer came back for the wrong
     * location. The Settings field for it changed nothing. These rules mirror
     * the route rules that go direct (the bypass list, custom direct rules,
     * the country bypass), so resolution follows the same split as the
     * traffic. IP-based rules have no DNS counterpart: a query carries a name,
     * not an address, and the rule-set tags referenced here are the same ones
     * {@link #buildRoute} declares.</p>
     */
    private List<ObjectNode> directDnsRules(RoutingConfig routingConfig) {
        List<ObjectNode> out = new ArrayList<>();
        if (routingConfig == null) {
            return out;
        }

        ObjectNode bypass = buildBypassRule(routingConfig.getBypassList());
        if (bypass != null) {
            ObjectNode rule = copyNameMatchers(bypass);
            if (!rule.isEmpty()) {
                rule.put("server", "direct-dns");
                out.add(rule);
            }
        }

        List<RoutingRule> customRules = routingConfig.getRules();
        if (customRules != null) {
            for (RoutingRule custom : customRules) {
                if (custom.getAction() != RoutingRule.RuleAction.DIRECT || unusable(custom)) {
                    continue;
                }
                ObjectNode routeRule = buildCustomRule(custom, new LinkedHashSet<>());
                ObjectNode rule = copyNameMatchers(routeRule);
                if (custom.getType() == RoutingRule.RuleType.GEOSITE) {
                    rule.set("rule_set", routeRule.get("rule_set"));
                }
                if (!rule.isEmpty()) {
                    rule.put("server", "direct-dns");
                    out.add(rule);
                }
            }
        }

        List<String> countries = routingConfig.getBypassCountries();
        if (countries != null && !countries.isEmpty()) {
            Set<String> geositeTags = new LinkedHashSet<>();
            for (String country : countries) {
                String tag = geositeTagFor(country);
                if (tag != null) {
                    geositeTags.add(tag);
                }
            }
            if (!geositeTags.isEmpty()) {
                ObjectNode rule = mapper.createObjectNode();
                ArrayNode refs = mapper.createArrayNode();
                geositeTags.forEach(refs::add);
                rule.set("rule_set", refs);
                rule.put("server", "direct-dns");
                out.add(rule);
            }
        }
        return out;
    }

    private ObjectNode copyNameMatchers(ObjectNode routeRule) {
        ObjectNode rule = mapper.createObjectNode();
        for (String matcher : DNS_NAME_MATCHERS) {
            if (routeRule.has(matcher)) {
                rule.set(matcher, routeRule.get(matcher));
            }
        }
        return rule;
    }

    /**
     * Adds the minimum fields sing-box 1.13 needs for a working TUN route:
     *
     * <ul>
     *   <li>{@code default_domain_resolver} — so outbound dial targets can be
     *       resolved. Points at {@code local-dns} (the OS resolver): no DNS
     *       loop through the proxy, and no dependence on a public DoH the
     *       local network might block. It carries the user's DNS strategy,
     *       not the dns block's, which is IPv4 only without IPv6 on the
     *       device.</li>
     *   <li>{@code auto_detect_interface: true} — lets sing-box pick the
     *       physical network interface for outbound dial and DNS, so those
     *       connections escape the TUN device instead of looping back.</li>
     *   <li>{@code rules} with {@code action: "sniff"} + DNS hijack — 1.13
     *       expresses protocol sniffing and DNS interception as route rules
     *       rather than inbound flags.</li>
     * </ul>
     */
    private void ensureTunRouteEssentials(ObjectNode route, AppSettings settings) {
        if (settings.getProxyMode() != ProxyMode.TUN) {
            return;
        }
        if (!route.has("default_domain_resolver")) {
            // Bootstrap resolution (the proxy server's own hostname, DoH
            // endpoints, rule-set hosts) through the OS resolver: it works on
            // whatever network the machine is on, unlike a hardcoded public
            // DoH that local networks may block — a 223.5.5.5 timeout here
            // used to kill TUN startup before the tunnel even came up.
            //
            // With a strategy of its own: named alone, the resolver takes the
            // dns block's, which is ipv4_only when the device has no IPv6, and
            // a server with only an AAAA record could not be reached. The
            // server is dialled on the physical network, not through the
            // tunnel, so its name keeps the strategy the user chose.
            ObjectNode resolver = route.putObject("default_domain_resolver");
            resolver.put("server", "local-dns");
            String strategy = settings.getDnsStrategy();
            resolver.put("strategy",
                    strategy == null || strategy.isBlank() ? "prefer_ipv4" : strategy);
        }
        if (!route.has("auto_detect_interface")) {
            route.put("auto_detect_interface", true);
        }

        ArrayNode rules = (ArrayNode) route.get("rules");
        if (rules == null) {
            rules = mapper.createArrayNode();
            route.set("rules", rules);
        }

        // Prepend the protocol-sniffing and DNS-hijack rules. The private-IP
        // direct rule is only added here when buildRoute didn't already emit
        // one — i.e. when generate() was called without a RoutingConfig.
        // In the normal path buildRoute emits it unconditionally, so the
        // dedup check below skips this branch.
        ArrayNode prepended = mapper.createArrayNode();

        ObjectNode sniffRule = mapper.createObjectNode();
        sniffRule.put("action", "sniff");
        prepended.add(sniffRule);

        ObjectNode hijackDns = mapper.createObjectNode();
        hijackDns.put("protocol", "dns");
        hijackDns.put("action", "hijack-dns");
        prepended.add(hijackDns);

        // Local hostnames (localhost, *.local) direct, then private IPs direct.
        // Added here only when buildRoute didn't already emit them (no-config
        // path); the dedup checks keep the normal path from duplicating.
        if (!hasLocalDomainDirectRule(rules)) {
            prepended.add(buildLocalDomainDirectRule());
        }
        if (!hasPrivateIpDirectRule(rules)) {
            prepended.add(buildPrivateIpDirectRule());
        }

        // Preserve any pre-existing rules after our essentials.
        for (int i = 0; i < rules.size(); i++) {
            prepended.add(rules.get(i));
        }
        route.set("rules", prepended);
    }

    /**
     * True if the given rules array already contains a rule that sends
     * {@code ip_is_private: true} traffic to the {@code direct} outbound.
     * Used by {@link #ensureTunRouteEssentials} to avoid duplicating the
     * LAN-bypass rule that {@link #buildRoute} may have already emitted.
     */
    private boolean hasPrivateIpDirectRule(ArrayNode rules) {
        if (rules == null) {
            return false;
        }
        for (int i = 0; i < rules.size(); i++) {
            JsonNode rule = rules.get(i);
            if (rule == null) {
                continue;
            }
            JsonNode privateFlag = rule.get("ip_is_private");
            JsonNode outbound = rule.get("outbound");
            if (privateFlag != null && privateFlag.asBoolean()
                    && outbound != null && "direct".equals(outbound.asString())) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode buildPrivateIpDirectRule() {
        ObjectNode privateIp = mapper.createObjectNode();
        privateIp.put("ip_is_private", true);
        privateIp.put("action", "route");
        privateIp.put("outbound", "direct");
        return privateIp;
    }

    /**
     * Behind the system proxy a browser asks for a name ({@code CONNECT
     * host:443}), and the core matches IP rules against the destination address
     * alone: without a lookup a country bypass or a GEOIP / IP_CIDR rule never
     * matched named traffic. A {@code resolve} action right before the first
     * such rule gives them an address, and rules above it still match by name
     * without one. It names no server, so the DNS rules pick one: the system
     * resolver for LAN names, proxy DNS for the rest, so a name bound for the
     * tunnel is not asked of the local network. The direct outbound still
     * resolves a name itself, so bypassed traffic keeps a nearby CDN node.
     *
     * <p>A name the chosen resolver cannot answer is dropped at that rule; it
     * used to reach the proxy, which could not resolve it either. TUN mode
     * needs none of this: its connections carry an address already.</p>
     */
    private void resolveNamesBeforeIpRules(ObjectNode root, ObjectNode route,
                                           AppSettings settings, RoutingConfig routingConfig) {
        if (settings.getProxyMode() == ProxyMode.TUN) {
            return;
        }
        ArrayNode rules = (ArrayNode) route.get("rules");
        int first = firstIpRuleBelowLanBypass(rules);
        if (first < 0) {
            return;
        }
        ObjectNode resolve = mapper.createObjectNode();
        resolve.put("action", "resolve");
        rules.insert(first, resolve);
        // A LAN name only becomes a private address here, below the rule that
        // sends private addresses direct.
        rules.insert(first + 1, buildPrivateIpDirectRule());

        ArrayNode lanSuffixes = mapper.createArrayNode();
        lanSuffixes.add(".lan");
        lanSuffixes.add(".home.arpa");
        lanSuffixes.add(".internal");
        ObjectNode lanNames = mapper.createObjectNode();
        lanNames.set("domain_suffix", lanSuffixes);
        lanNames.put("server", "local-dns");
        // No TUN device in this mode, so none with an IPv6 address either.
        ObjectNode dns = buildDns(settings, routingConfig, false);
        ((ArrayNode) dns.get("rules")).insert(1, lanNames);
        root.set("dns", dns);
        // The core demands one once a dns block exists, and the OS resolver
        // keeps the proxy server's own name off the proxy.
        route.put("default_domain_resolver", "local-dns");
    }

    /**
     * Index of the first rule that matches on the destination address below the
     * universal LAN bypass, or -1. The bypass list above it is the user's own
     * list of hosts and stays a match by name.
     */
    private static int firstIpRuleBelowLanBypass(ArrayNode rules) {
        int start = 0;
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).path("ip_is_private").asBoolean()) {
                start = i + 1;
                break;
            }
        }
        for (int i = start; i < rules.size(); i++) {
            JsonNode rule = rules.get(i);
            if (rule.has("ip_cidr")) {
                return i;
            }
            for (JsonNode tag : rule.path("rule_set")) {
                if (tag.asString().startsWith("geoip-")) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Sends {@code localhost} and any {@code *.local} (mDNS) host <em>by name</em>
     * to the direct outbound. The private-IP rule only catches local traffic
     * once it is already an IP; a bare hostname like {@code printer.local} or
     * {@code localhost} reaches the proxy as a domain and would otherwise be
     * tunnelled to the exit node, which cannot resolve link-local mDNS names —
     * so the connection dead-ends. Matters most in system-proxy mode, where the
     * app hands the hostname to the proxy instead of pre-resolving it.
     */
    private ObjectNode buildLocalDomainDirectRule() {
        ObjectNode rule = mapper.createObjectNode();
        ArrayNode domains = mapper.createArrayNode();
        domains.add("localhost");
        rule.set("domain", domains);
        ArrayNode suffixes = mapper.createArrayNode();
        suffixes.add(".local");
        rule.set("domain_suffix", suffixes);
        rule.put("action", "route");
        rule.put("outbound", "direct");
        return rule;
    }

    /**
     * True if the rules already contain a {@code .local} domain-suffix rule,
     * so {@link #ensureTunRouteEssentials} doesn't duplicate what
     * {@link #buildRoute} may have emitted.
     */
    private boolean hasLocalDomainDirectRule(ArrayNode rules) {
        if (rules == null) {
            return false;
        }
        for (int i = 0; i < rules.size(); i++) {
            JsonNode suffix = rules.get(i) != null ? rules.get(i).get("domain_suffix") : null;
            if (suffix != null && suffix.isArray()) {
                for (JsonNode s : suffix) {
                    if (".local".equals(s.asString())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The CIDR list fed to {@code route_exclude_address} on the TUN inbound.
     * Covers everything that should structurally never traverse the VPN:
     * RFC1918, link-local unicast and multicast for both IPv4 and IPv6.
     * 127/8 and ::1 are already excluded by sing-box's auto_route, so we
     * don't list them again.
     */
    private ArrayNode buildLanExcludeList() {
        ArrayNode exclude = mapper.createArrayNode();
        exclude.add("10.0.0.0/8");
        exclude.add("172.16.0.0/12");
        exclude.add("192.168.0.0/16");
        exclude.add("169.254.0.0/16");
        // IPv4 multicast — covers mDNS (224.0.0.251), SSDP, etc.
        exclude.add("224.0.0.0/4");
        exclude.add("fc00::/7");
        exclude.add("fe80::/10");
        exclude.add("ff00::/8");
        return exclude;
    }

    /**
     * Populates a DNS server object using the sing-box 1.13 schema. Accepts
     * both the legacy URL-style address (e.g. {@code https://1.1.1.1/dns-query})
     * and bare IPs/hostnames. A bare address is read as a {@code udp://} one,
     * so a port it carries ({@code 8.8.8.8:53}) is split off; left whole, it
     * reached the core as a name that nothing could resolve.
     */
    void populateDnsServerAddress(ObjectNode server, String address) {
        if (address == null || address.isBlank()) {
            server.put("type", "udp");
            server.put("server", "1.1.1.1");
            return;
        }
        String url = address.contains("://") ? address : "udp://" + address;
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "udp";
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                server.put("type", "udp");
                server.put("server", address);
                return;
            }
            server.put("type", scheme);
            server.put("server", host);
            if (uri.getPort() > 0) {
                server.put("server_port", uri.getPort());
            }
            if ("https".equals(scheme) || "h3".equals(scheme) || "quic".equals(scheme)) {
                String path = uri.getRawPath();
                if (path != null && !path.isEmpty()) {
                    server.put("path", path);
                }
            }
        } catch (URISyntaxException e) {
            log.warn("Could not parse DNS address {}, falling back to UDP", address);
            server.put("type", "udp");
            server.put("server", address);
        }
    }

    /** Whether a DNS setting names the OS resolver, {@link AppSettings#SYSTEM_DNS}. */
    static boolean isSystemDns(String address) {
        return address != null && AppSettings.SYSTEM_DNS.equalsIgnoreCase(address.trim());
    }

    /**
     * Whether a DNS server's address is an IP literal, with or without the
     * brackets a URL puts around IPv6, rather than a name.
     */
    private static boolean isIpLiteral(String server) {
        String bare = server.startsWith("[") && server.endsWith("]")
                ? server.substring(1, server.length() - 1)
                : server;
        try {
            InetAddress.ofLiteral(bare);
            return true;
        } catch (IllegalArgumentException notAnIpLiteral) {
            return false;
        }
    }

    /**
     * Whether the TUN device takes an IPv6 address: the user's switch, on a
     * network that carries IPv6 at all.
     *
     * <p>Without an IPv6 uplink the address drew apps into IPv6 connections the
     * direct outbound could not dial, so every site the rules bypass broke in
     * browsers (see {@link Ipv6Uplink}). The switch is there to keep IPv6 from
     * going around the tunnel, and such a network has no IPv6 to go around it;
     * what the device gives up there is reaching IPv6-only hosts through the
     * proxy, which is rarer than every bypassed site failing.</p>
     */
    static boolean tunTakesIpv6(AppSettings settings, HostFacts host) {
        return settings.getProxyMode() == ProxyMode.TUN && settings.isTunIpv6Enabled()
                && host.ipv6Uplink();
    }

    /**
     * Fresh facts about this host, each asked the first time a configuration
     * needs it. A caller that generates again to compare keeps the instance.
     *
     * @return facts not yet asked
     */
    public HostFacts hostFacts() {
        return new HostFacts(ipv6Uplink, systemProxySupport);
    }

    private ArrayNode buildInbounds(AppSettings settings, boolean tunIpv6, HostFacts host) {
        ArrayNode inbounds = mapper.createArrayNode();

        if (settings.getProxyMode() == ProxyMode.TUN) {
            ObjectNode tun = mapper.createObjectNode();
            tun.put("type", "tun");
            tun.put("tag", "tun-in");
            tun.put("interface_name", settings.getTunInterfaceName());
            // sing-box 1.13 removed inet4_address/inet6_address in favor of
            // a CIDR array under `address`.
            ArrayNode address = mapper.createArrayNode();
            address.add(settings.getTunIpv4Address());
            // Without an IPv6 address auto_route only covers IPv4, so on a
            // dual-stack network every IPv6 destination bypassed the tunnel
            // while the card said "Connected". The private and link-local v6
            // ranges stay excluded below, like their IPv4 counterparts. A
            // network without IPv6 keeps the device IPv4-only: tunTakesIpv6.
            if (tunIpv6) {
                address.add(TUN_IPV6_ADDRESS);
            }
            tun.set("address", address);
            // 1.14 defaults to hijack, but keep this explicit: Tunl relies on
            // native interface DNS plus port-53 interception to prevent DNS
            // leaks. With dns_address omitted, sing-box derives the next TUN
            // address and redirects it into the configured DNS module.
            tun.put("dns_mode", "hijack");
            tun.put("auto_route", true);
            // Deliberately NOT setting strict_route: true. Combined with
            // route_exclude_address it caused widespread direct-outbound
            // timeouts in v0.1.6 — every connection that should escape the
            // TUN (sing-box's own direct outbound for RU/geosite routes,
            // host-level dials to the proxy IP, anything in the LAN
            // exclude list) got blocked. The route-rule ip_is_private→
            // direct already keeps RFC1918 going direct, and TUN routes
            // are scoped to this app's utun device, so leaks outside the
            // exclude list aren't a realistic concern.
            //
            // stack=gvisor (userspace TCP/IP) instead of "system" because
            // on macOS the system stack relies on PF redirect rules that
            // strict_route used to install — without strict_route the
            // system stack silently drops all TCP from the TUN (UDP still
            // works through a different kernel path, which is why YouTube
            // QUIC kept working in v0.1.7 while everything TCP timed out).
            // gvisor has its own self-contained TCP/IP implementation, so
            // it does not depend on PF and works without strict_route.
            // Slightly slower than system but absolutely fine for a VPN
            // client at typical broadband speeds.
            tun.put("stack", "gvisor");
            // Always keep LAN / link-local / multicast off the OS routing
            // tables sing-box installs. Without this, auto_route's
            // /1+/2+… coverage of 0.0.0.0/0 swallows 192.168.0.0/16 etc.
            // into the TUN device — printers, NAS, Screen Sharing to
            // .local hosts all hang. There is no useful "VPN my LAN"
            // workflow for this client, so the exclude list is unconditional.
            tun.set("route_exclude_address", buildLanExcludeList());

            inbounds.add(tun);
        }

        ObjectNode socks = mapper.createObjectNode();
        socks.put("type", "socks");
        socks.put("tag", "socks-in");
        socks.put("listen", "127.0.0.1");
        socks.put("listen_port", settings.listenSocksPort());
        inbounds.add(socks);

        ObjectNode http = mapper.createObjectNode();
        http.put("type", "http");
        http.put("tag", "http-in");
        http.put("listen", "127.0.0.1");
        http.put("listen_port", settings.listenHttpPort());
        // In SYSTEM_PROXY mode sing-box itself registers this inbound as the
        // OS proxy on start and restores the previous state on a graceful
        // stop — one cross-platform mechanism (networksetup on macOS, WinINET
        // on Windows, GNOME gsettings on Linux) instead of app-side platform
        // code. SystemProxyGuard covers the non-graceful exits. Gated on host
        // capability: without the GNOME schema sing-box FATALs at startup, so
        // on such hosts the flag is omitted and the local listeners still
        // serve manually-configured clients.
        if (settings.getProxyMode() == ProxyMode.SYSTEM_PROXY
                && settings.isSystemProxyAutoConfig()) {
            if (host.systemProxyAutoConfigurable()) {
                http.put("set_system_proxy", true);
            } else {
                // The user asked for the OS proxy and is not getting it. Saying
                // so here is the difference between a diagnosable report and
                // "it says connected but nothing is proxied".
                log.warn("Host has no usable OS proxy store: traffic is not "
                        + "proxied system-wide. Point clients at the HTTP proxy "
                        + "on 127.0.0.1:{}", settings.listenHttpPort());
            }
        }
        inbounds.add(http);

        return inbounds;
    }

    /**
     * The servers the group may use, decided here rather than stored.
     *
     * <p>Every mode includes the configured candidates; a manual selector pins
     * its default to the active server and permits later API switches.
     * Deriving it per connect is what keeps a
     * subscription refresh from leaving a stale member behind, since there is
     * no membership list to go stale.</p>
     *
     * <p>An automatic mode with nothing to choose between falls back to the
     * active server: emitting an empty group would be rejected by the core,
     * and refusing to connect over a mode toggle would be worse than ignoring
     * it.</p>
     */
    private List<ServerConfig> groupMembers(List<ServerConfig> candidates, ServerConfig active,
                                            ServerSelection selection) {
        if (candidates == null) {
            return List.of(active);
        }
        // De-duplicate by id: the same server must not appear twice in a
        // group, and callers pass whatever list they hold.
        java.util.Map<String, ServerConfig> byId = new java.util.LinkedHashMap<>();
        for (ServerConfig candidate : candidates) {
            if (candidate != null && candidate.getId() != null) {
                byId.putIfAbsent(candidate.getId(), candidate);
            }
        }
        byId.putIfAbsent(active.getId(), active);
        List<ServerConfig> all = byId.isEmpty() ? List.of(active) : List.copyOf(byId.values());
        return selection != null && selection.isAutomatic() && all.size() > MAX_AUTOMATIC_MEMBERS
                ? fastest(all, active)
                : all;
    }

    /**
     * The servers the "Fastest" mode probes: those that last answered
     * fastest, then those not measured yet in the list's order, then those
     * that did not answer, and the picked server among them. The core probed
     * every server every three minutes: with 500, about 100 MB an hour.
     */
    private List<ServerConfig> fastest(List<ServerConfig> all, ServerConfig active) {
        List<ServerConfig> ranked = new ArrayList<>(all);
        // Stable: servers with equal keys keep the list's order.
        ranked.sort(java.util.Comparator.comparingLong(
                server -> ranking.lastLatency(server.getId()).orElse(Long.MAX_VALUE - 1)));
        List<ServerConfig> members = new ArrayList<>(ranked.subList(0, MAX_AUTOMATIC_MEMBERS));
        if (!members.contains(active)) {
            members.set(MAX_AUTOMATIC_MEMBERS - 1, active);
        }
        return List.copyOf(members);
    }

    /**
     * How often the "Fastest" mode probes its group: one server about every
     * twenty seconds, and no server more often than every three minutes.
     */
    private static String probeInterval(int members) {
        long seconds = Math.max(180, members * 20L);
        return seconds % 60 == 0 ? seconds / 60 + "m" : seconds + "s";
    }

    /**
     * Emits every member's own outbound plus the group that fronts them.
     *
     * <p>The group carries the {@code proxy} tag in every mode, so route, DNS
     * and rule-sets resolve the same name whether one server is pinned or the
     * core is choosing.</p>
     */
    private ArrayNode buildOutbounds(List<ServerConfig> members, ServerSelection selection,
                                     ServerConfig active) {
        ArrayNode outbounds = mapper.createArrayNode();
        ArrayNode memberTags = mapper.createArrayNode();

        for (ServerConfig member : members) {
            String memberTag = OutboundTags.server(member);
            memberTags.add(memberTag);
            // WireGuard contributes an endpoint instead; the group still
            // references its tag.
            if (member.getProtocol() != Protocol.WIREGUARD) {
                outbounds.add(buildProxyOutbound(member, memberTag));
            }
        }

        outbounds.add(buildProxyGroup(selection, memberTags, active));

        ObjectNode direct = mapper.createObjectNode();
        direct.put("type", "direct");
        direct.put("tag", OutboundTags.DIRECT);
        outbounds.add(direct);

        return outbounds;
    }

    /**
     * The proxy group itself. The automatic types need a probe target and an
     * interval; without them sing-box would never re-measure and the mode
     * would silently behave like a plain selector.
     */
    private ObjectNode buildProxyGroup(ServerSelection selection, ArrayNode memberTags,
                                       ServerConfig active) {
        ObjectNode group = mapper.createObjectNode();
        group.put("type", selection.singBoxType());
        group.put("tag", OutboundTags.PROXY);
        group.set("outbounds", memberTags);

        if (selection.isAutomatic()) {
            group.put("url", PROBE_URL);
            group.put("interval", probeInterval(memberTags.size()));
            // Probing stops after this long without a connection through the
            // group, and starts again with the next one.
            group.put("idle_timeout", PROBE_IDLE_TIMEOUT);
            // Only switch away from the current pick when a candidate is
            // meaningfully faster, so traffic does not hop between servers
            // whose latencies are within noise of each other.
            group.put("tolerance", PROBE_TOLERANCE_MS);
        } else {
            group.put("default", OutboundTags.server(active));
            // A manual switch reaches the running core through this selector.
            // Without this, connections already open stayed on the server the
            // user switched away from while the UI named the new one. The
            // automatic group keeps them: it re-picks as latencies move, and
            // cutting every connection at each re-pick would break downloads.
            group.put("interrupt_exist_connections", true);
        }
        return group;
    }

    private ObjectNode buildProxyOutbound(ServerConfig server, String tag) {
        return switch (server.getProtocol()) {
            case VLESS -> vlessBuilder.build(server, tag);
            case VMESS -> vmessBuilder.build(server, tag);
            case TROJAN -> trojanBuilder.build(server, tag);
            case SHADOWSOCKS -> shadowsocksBuilder.build(server, tag);
            case HYSTERIA2 -> hysteria2Builder.build(server, tag);
            case WIREGUARD -> throw new IllegalStateException(
                    "WireGuard is emitted as an endpoint, not an outbound");
        };
    }

    ObjectNode buildRoute(RoutingConfig routingConfig) {
        ArrayNode rules = mapper.createArrayNode();

        // sing-box 1.12 removed the legacy geosite/geoip database form entirely;
        // country rules must reference remote rule_sets by tag. Collect every
        // rule_set tag used by any rule so we can emit the matching route.rule_set
        // block once at the end (deduped, in insertion order).
        Set<String> ruleSetTags = new LinkedHashSet<>();

        // Sections compose: custom rules first (most specific — the user
        // wrote them), then the country bypass. An empty section simply
        // contributes nothing; everything empty = route all through the VPN.
        List<RoutingRule> customRules = routingConfig.getRules();
        if (customRules != null) {
            for (RoutingRule rule : customRules) {
                if (!unusable(rule)) {
                    rules.add(buildCustomRule(rule, ruleSetTags));
                }
            }
        }

        // Only what is blocked in Russia goes through the tunnel, after the
        // user's own rules; everything else goes direct, by route.final.
        boolean blockedOnly = routingConfig.getMode() == RouteMode.BLOCKED_IN_RUSSIA;
        if (blockedOnly) {
            ArrayNode blocked = mapper.createArrayNode();
            for (String tag : BLOCKED_IN_RUSSIA_LISTS) {
                ruleSetTags.add(tag);
                blocked.add(tag);
            }
            ObjectNode blockedRule = mapper.createObjectNode();
            blockedRule.set("rule_set", blocked);
            blockedRule.put("outbound", "proxy");
            rules.add(blockedRule);
        }

        List<String> countries = routingConfig.getBypassCountries();
        if (countries != null && !countries.isEmpty()) {
            // One geosite rule and one geoip rule cover every selected
            // country: entries of a rule's rule_set list are OR-ed by
            // sing-box, so a single rule per kind keeps the config flat no
            // matter how many countries are picked. geosite aggregates exist
            // only for some countries (see geositeTagFor); the rest match by
            // geoip alone, which is still useful at the IP level.
            ArrayNode geositeRefs = mapper.createArrayNode();
            ArrayNode geoipRefs = mapper.createArrayNode();
            for (String country : countries) {
                String geositeTag = geositeTagFor(country);
                if (geositeTag != null && ruleSetTags.add(geositeTag)) {
                    geositeRefs.add(geositeTag);
                }
                String geoipTag = "geoip-" + country;
                if (ruleSetTags.add(geoipTag)) {
                    geoipRefs.add(geoipTag);
                }
            }

            if (!geositeRefs.isEmpty()) {
                ObjectNode geositeRule = mapper.createObjectNode();
                geositeRule.set("rule_set", geositeRefs);
                geositeRule.put("outbound", "direct");
                rules.add(geositeRule);
            }

            ObjectNode geoipRule = mapper.createObjectNode();
            geoipRule.set("rule_set", geoipRefs);
            geoipRule.put("outbound", "direct");
            rules.add(geoipRule);

            // The dedicated ip_is_private rule lives in the universal
            // LAN-bypass block below; not duplicated here.
        }

        // Universal LAN-bypass rule. Unconditional, so system-proxy mode
        // also keeps local traffic off the VPN whatever the sections say.
        // Prepended so it precedes the section rules, but the user's bypass
        // list still wins above it. There is no toggle for this — sending
        // LAN through a remote VPN dead-ends in TUN and breaks local
        // services in any mode, and no realistic use case for this client
        // justifies the breakage.
        rules.insert(0, buildPrivateIpDirectRule());

        // Local hostnames (localhost, *.local) also go direct, above the
        // private-IP rule so a name that never becomes a local IP (the
        // system-proxy case) still bypasses the tunnel.
        rules.insert(0, buildLocalDomainDirectRule());

        // User bypass list: matching hosts always go direct, above every
        // other rule.
        ObjectNode bypassRule = buildBypassRule(routingConfig.getBypassList());
        if (bypassRule != null) {
            rules.insert(0, bypassRule);
        }

        ObjectNode route = mapper.createObjectNode();
        route.set("rules", rules);
        route.put("final", blockedOnly ? "direct" : "proxy");
        route.put("auto_detect_interface", true);

        if (!ruleSetTags.isEmpty()) {
            ArrayNode ruleSetNodes = mapper.createArrayNode();
            for (String tag : ruleSetTags) {
                ruleSetNodes.add(buildRemoteRuleSet(tag));
            }
            route.set("rule_set", ruleSetNodes);
        }

        return route;
    }

    /**
     * Resolves the geosite rule-set tag to use for a given country, or
     * {@code null} if no sing-geosite aggregate exists for it. The sing-geosite
     * {@code rule-set} branch is inconsistent: CN keeps its legacy short name
     * ({@code geosite-cn.srs}), RU/IR are under the {@code category-*} prefix,
     * and most other countries (US, DE, GB, JP, …) simply have no geosite
     * aggregate at all. Returning null lets the caller skip the geosite rule
     * and rely on geoip alone — an IP-level match is still useful.
     */
    private static String geositeTagFor(String country) {
        return switch (country) {
            case "cn" -> "geosite-cn";
            case "ru", "ir" -> "geosite-category-" + country;
            default -> null;
        };
    }

    /**
     * Builds a single {@code route.rule_set} entry that tells sing-box where
     * to fetch the binary rule set from. Tags are used verbatim as the file
     * name ({@code <tag>.srs}) under
     * {@code github.com/SagerNet/sing-{geoip|geosite}/rule-set/}; the kind is
     * taken from the first path segment of the tag. The download goes through
     * the proxy group, by the entry's {@code http_client.detour}.
     */
    private ObjectNode buildRemoteRuleSet(String tag) {
        if (BLOCKED_IN_RUSSIA_LISTS.contains(tag)) {
            return buildBlockedListRuleSet(tag);
        }
        // Kind ('geoip' or 'geosite') is the first dash-separated segment
        // of the tag; the entire tag is used verbatim as the .srs filename,
        // so multi-segment tags like 'geosite-category-ru' resolve to
        // 'geosite-category-ru.srs' in the sing-geosite repo.
        int dash = tag.indexOf('-');
        String kind = dash > 0 ? tag.substring(0, dash) : tag;

        ObjectNode entry = mapper.createObjectNode();
        entry.put("tag", tag);
        entry.put("type", "remote");
        entry.put("format", "binary");
        entry.put("url", "https://raw.githubusercontent.com/SagerNet/sing-"
                + kind + "/rule-set/" + tag + ".srs");
        // Through the tunnel: on the networks this client is for, GitHub raw
        // is often blocked or poisoned when dialed directly. The proxy
        // outbound is up by the time sing-box fetches rule-sets, and after
        // the first success the cache_file serves them offline anyway. Set on
        // http_client: download_detour is deprecated, and a core one minor
        // release before its removal stops at rule-set start over it, which
        // `sing-box check` never reaches.
        entry.putObject("http_client").put("detour", "proxy");
        return entry;
    }

    /**
     * A runetfreedom list of what is blocked in Russia. Rebuilt upstream
     * every six hours, and fetched through the tunnel like the other lists:
     * GitHub's raw host is what a blocking network is likeliest to cut.
     */
    private ObjectNode buildBlockedListRuleSet(String tag) {
        String kind = tag.substring(0, tag.indexOf('-'));
        ObjectNode entry = mapper.createObjectNode();
        entry.put("tag", tag);
        entry.put("type", "remote");
        entry.put("format", "binary");
        entry.put("url", BLOCKED_LISTS_URL + "rule-set-" + kind + "/" + tag + ".srs");
        entry.putObject("http_client").put("detour", "proxy");
        entry.put("update_interval", "6h");
        return entry;
    }

    /**
     * Collapses all bypass-list entries into a single sing-box route rule that
     * sends matching traffic to the {@code direct} outbound. Returns
     * {@code null} if the list is empty or contains no recognizable patterns.
     *
     * <p>A sing-box rule can carry multiple sibling matchers ({@code domain},
     * {@code domain_suffix}, {@code domain_keyword}, {@code ip_cidr}) at once;
     * they are OR'd together. Grouping all entries into one rule keeps the
     * generated config compact.</p>
     */
    ObjectNode buildBypassRule(List<String> bypassList) {
        if (bypassList == null || bypassList.isEmpty()) {
            return null;
        }

        ArrayNode domains = mapper.createArrayNode();
        ArrayNode domainSuffixes = mapper.createArrayNode();
        ArrayNode domainKeywords = mapper.createArrayNode();
        ArrayNode domainRegexes = mapper.createArrayNode();
        ArrayNode ipCidrs = mapper.createArrayNode();

        for (String raw : bypassList) {
            BypassPatternParser.Parsed parsed = BypassPatternParser.parse(raw);
            if (parsed == null) {
                continue;
            }
            switch (parsed.kind()) {
                case DOMAIN -> domains.add(parsed.value());
                case DOMAIN_SUFFIX -> domainSuffixes.add(parsed.value());
                case DOMAIN_KEYWORD -> domainKeywords.add(parsed.value());
                case DOMAIN_REGEX -> domainRegexes.add(parsed.value());
                case IP_CIDR -> ipCidrs.add(parsed.value());
                default -> throw new IllegalStateException("Unexpected: " + parsed.kind());
            }
        }

        if (domains.isEmpty() && domainSuffixes.isEmpty() && domainKeywords.isEmpty()
                && domainRegexes.isEmpty() && ipCidrs.isEmpty()) {
            return null;
        }

        ObjectNode rule = mapper.createObjectNode();
        if (!domains.isEmpty()) {
            rule.set("domain", domains);
        }
        if (!domainSuffixes.isEmpty()) {
            rule.set("domain_suffix", domainSuffixes);
        }
        if (!domainKeywords.isEmpty()) {
            rule.set("domain_keyword", domainKeywords);
        }
        if (!domainRegexes.isEmpty()) {
            rule.set("domain_regex", domainRegexes);
        }
        if (!ipCidrs.isEmpty()) {
            rule.set("ip_cidr", ipCidrs);
        }
        rule.put("action", "route");
        rule.put("outbound", "direct");
        return rule;
    }

    /**
     * Whether a stored rule is one the core would refuse. Such a rule stopped
     * every server from connecting until it was found and deleted; the
     * Routing screen and MCP no longer store one, and one stored before is
     * left out of the configuration, with a warning in the log.
     */
    private static boolean unusable(RoutingRule rule) {
        Optional<String> problem = RoutingRuleCheck.problem(rule.getType(), rule.getValue());
        problem.ifPresent(reason -> log.warn("Leaving out routing rule {} ({}): {}",
                rule.getId(), rule.getType(), reason));
        return problem.isPresent();
    }

    private ObjectNode buildCustomRule(RoutingRule rule, Set<String> ruleSetTags) {
        ObjectNode ruleNode = mapper.createObjectNode();

        switch (rule.getType()) {
            case DOMAIN -> {
                ArrayNode values = mapper.createArrayNode();
                values.add(rule.getValue());
                ruleNode.set("domain", values);
            }
            case DOMAIN_SUFFIX -> {
                ArrayNode values = mapper.createArrayNode();
                values.add(rule.getValue());
                ruleNode.set("domain_suffix", values);
            }
            case DOMAIN_KEYWORD -> {
                ArrayNode values = mapper.createArrayNode();
                values.add(rule.getValue());
                ruleNode.set("domain_keyword", values);
            }
            case DOMAIN_REGEX -> {
                ArrayNode values = mapper.createArrayNode();
                values.add(rule.getValue());
                ruleNode.set("domain_regex", values);
            }
            case GEOSITE -> {
                // Legacy geosite: [code] removed in 1.12 — reference a remote
                // rule_set by a stable tag, and record the tag so the caller
                // can emit the matching route.rule_set entry.
                String tag = "geosite-" + RoutingRuleCheck.ruleSetName(rule.getValue());
                ruleSetTags.add(tag);
                ArrayNode refs = mapper.createArrayNode();
                refs.add(tag);
                ruleNode.set("rule_set", refs);
            }
            case IP_CIDR -> {
                ArrayNode values = mapper.createArrayNode();
                values.add(rule.getValue());
                ruleNode.set("ip_cidr", values);
            }
            case GEOIP -> {
                // Same migration as GEOSITE — references a geoip-<code>
                // remote rule_set instead of the retired geoip: [] matcher.
                String tag = "geoip-" + RoutingRuleCheck.ruleSetName(rule.getValue());
                ruleSetTags.add(tag);
                ArrayNode refs = mapper.createArrayNode();
                refs.add(tag);
                ruleNode.set("rule_set", refs);
            }
            default -> throw new IllegalStateException("Unexpected: " + rule.getType());
        }

        if (rule.getAction() == RoutingRule.RuleAction.BLOCK) {
            // A block rule used to say outbound: "block" — and no outbound of
            // that tag exists in the config (sing-box 1.11 retired the block
            // outbound type). The core started anyway and dropped matching
            // connections only as the side effect of an unresolvable tag,
            // silently, the same way outbound: "nonexistent" would. The reject
            // action is the supported form: TCP gets a reset and UDP an ICMP
            // unreachable, so a blocked destination fails fast and visibly.
            ruleNode.put("action", "reject");
        } else {
            ruleNode.put("action", "route");
            ruleNode.put("outbound", rule.getAction().getValue());
        }
        return ruleNode;
    }

    private ObjectNode buildExperimental(AppSettings settings) {
        ObjectNode experimental = mapper.createObjectNode();

        ObjectNode clashApi = mapper.createObjectNode();
        clashApi.put("external_controller", "127.0.0.1:" + settings.listenClashApiPort());
        // Require a token so another local user can't read traffic stats or
        // control the core over 127.0.0.1:<port>. TrafficMonitor sends it.
        String clashSecret = settings.getClashApiSecret();
        if (clashSecret != null && !clashSecret.isBlank()) {
            clashApi.put("secret", clashSecret);
        }
        experimental.set("clash_api", clashApi);

        // Persist downloaded rule-sets (and other core state) across
        // restarts: without the cache every start re-fetches the .srs files
        // and an offline or blocked network turns into a startup FATAL. The
        // path is per proxy mode because the TUN core may run as root
        // (macOS sudo wrapper, Linux pkexec fallback) and a root-owned bbolt
        // file would break the next user-mode system-proxy run.
        ObjectNode cacheFile = mapper.createObjectNode();
        cacheFile.put("enabled", true);
        // Created here, not only by the engine: the smoke suites hand a
        // generated config straight to a TUN launcher, and the core refuses
        // to start when the directory its cache file points into is missing.
        ensureCacheDir();
        cacheFile.put("path", cacheDir().resolve("sing-box-"
                + settings.getProxyMode().name().toLowerCase(java.util.Locale.ROOT)
                + ".db").toString());
        experimental.set("cache_file", cacheFile);

        return experimental;
    }

    /** Where the core keeps its cache file, under the app's data directory. */
    public static Path cacheDir() {
        return com.vlessclient.platform.PlatformPaths.current().dataDir().resolve("cache");
    }

    /**
     * Creates the cache directory the generated config points at. Best
     * effort: a failure is logged and the core reports the real error at
     * start.
     */
    public static void ensureCacheDir() {
        Path cacheDir = cacheDir();
        try {
            java.nio.file.Files.createDirectories(cacheDir);
        } catch (java.io.IOException e) {
            log.warn("Could not create cache dir {}: {}", cacheDir, e.getMessage());
        }
    }
}
