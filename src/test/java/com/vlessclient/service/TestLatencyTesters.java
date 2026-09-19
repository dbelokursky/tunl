package com.vlessclient.service;

import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.outbound.OutboundTags;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * {@link LatencyTester} doubles for tests outside this package, which cannot
 * reach the constructor that takes the probe.
 */
public final class TestLatencyTesters {

    private TestLatencyTesters() {
    }

    /**
     * A tester that measures {@code millis} through the proxy for each server,
     * once that server's gate opens: a test decides when each result lands.
     * Nothing goes on the network.
     *
     * @param gates  a gate per server; a server without one answers at once
     * @param millis the delay every server measures
     * @return the tester
     */
    public static LatencyTester gated(Map<ServerConfig, CountDownLatch> gates, long millis) {
        Map<String, CountDownLatch> byTag = gates.entrySet().stream()
                .collect(Collectors.toMap(entry -> OutboundTags.server(entry.getKey()),
                        Map.Entry::getValue));
        ClashApiDelayProbe probe = new ClashApiDelayProbe() {
            @Override
            public Answer measure(int port, String secret, String tag) {
                CountDownLatch gate = byTag.get(tag);
                try {
                    if (gate != null && !gate.await(30, TimeUnit.SECONDS)) {
                        return new Answer.NoAnswer();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Answer.NoAnswer();
                }
                return new Answer.Delay(millis);
            }
        };
        LatencyTester tester = new LatencyTester(probe);
        tester.setApiEndpointSupplier(() -> new LatencyTester.ApiEndpoint(9, "test-secret"));
        return tester;
    }
}
