package com.vlessclient.service.mcp;

import java.util.List;

/**
 * Snapshot of the routing configuration, returned by {@code get_routing}.
 *
 * @param preset     the active routing preset (e.g. {@code route_all})
 * @param bypassList domains/patterns routed directly, bypassing the tunnel
 * @param rules      ordered custom routing rules
 */
public record RoutingInfo(
        String preset,
        List<String> bypassList,
        List<RuleInfo> rules) {

    /**
     * One routing rule.
     *
     * @param id     rule id
     * @param type   match type (e.g. {@code domain}, {@code geoip})
     * @param value  the value to match
     * @param action what to do on match (e.g. {@code proxy}, {@code direct})
     */
    public record RuleInfo(String id, String type, String value, String action) {
    }
}
