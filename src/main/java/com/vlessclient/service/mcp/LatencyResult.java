package com.vlessclient.service.mcp;

/**
 * Latency measurement for one server, returned by {@code measure_latency}.
 *
 * @param serverId  the server id
 * @param name      the server display name
 * @param latencyMs measured round-trip in milliseconds, or -1 if unreachable
 */
public record LatencyResult(String serverId, String name, long latencyMs) {
}
