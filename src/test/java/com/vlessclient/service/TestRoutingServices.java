package com.vlessclient.service;

import java.nio.file.Path;

/**
 * Test-only access to {@link RoutingService}'s package-private data-directory
 * constructor for tests that live outside this package: the graph's own
 * service keeps its rules in the shared test data dir.
 */
public final class TestRoutingServices {

    private TestRoutingServices() {
    }

    /** Creates a RoutingService that keeps its rules under {@code dataDir}. */
    public static RoutingService at(Path dataDir) {
        return new RoutingService(dataDir);
    }
}
