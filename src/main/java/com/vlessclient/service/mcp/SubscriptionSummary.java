package com.vlessclient.service.mcp;

/**
 * Compact view of a subscription, returned by {@code list_subscriptions}.
 *
 * @param id              stable subscription id
 * @param name            display name
 * @param url             subscription URL
 * @param serverCount     number of servers this subscription contributes
 * @param lastRefreshedAt epoch millis of the last successful refresh (0 if never)
 */
public record SubscriptionSummary(
        String id,
        String name,
        String url,
        int serverCount,
        long lastRefreshedAt) {
}
