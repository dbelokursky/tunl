package com.vlessclient.service.mcp;

/**
 * Latency measurement for one server, returned by {@code measure_latency}.
 *
 * @param serverId  the server id
 * @param name      the server display name
 * @param latencyMs measured round-trip in milliseconds, or -1 when there is no
 *                  number: {@code outcome} says why
 * @param outcome   {@code measured}; {@code unreachable}, when the server did
 *                  not answer; {@code not_measured}, when nothing could be
 *                  measured, such as a server over UDP, which a TCP connect
 *                  says nothing about; or {@code timed_out}, when the test of
 *                  a long list ran out of time before this server's turn
 *                  ended. The middle two used to read as the same -1, and a
 *                  list that ran out of time failed whole.
 */
public record LatencyResult(String serverId, String name, long latencyMs, String outcome) {

    /** Outcome of a round-trip that was taken. */
    public static final String MEASURED = "measured";
    /** Outcome of a server that did not answer. */
    public static final String UNREACHABLE = "unreachable";
    /** Outcome of a server nothing could be measured for. */
    public static final String NOT_MEASURED = "not_measured";
    /** Outcome of a server whose result was not in when the test's time ran out. */
    public static final String TIMED_OUT = "timed_out";

    /** A measurement that was taken, or a server that did not answer when -1. */
    public LatencyResult(String serverId, String name, long latencyMs) {
        this(serverId, name, latencyMs, latencyMs >= 0 ? MEASURED : UNREACHABLE);
    }
}
