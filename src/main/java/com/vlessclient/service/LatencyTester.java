package com.vlessclient.service;

import com.vlessclient.model.ServerConfig;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Measures how long each server takes to answer, on a bounded pool of daemon
 * threads.
 *
 * <p>Two measurements, in order of usefulness. While the tunnel is up the core
 * is asked for the delay <em>through</em> each proxy it knows about, which is
 * the number that actually predicts browsing speed. Otherwise — or for servers
 * absent from the running config — it falls back to a TCP connect, which only
 * measures the path to the address and will happily report 40 ms for a server
 * that is reachable but unusable.</p>
 *
 * <p>{@link Result#throughProxy()} says which one produced a given number, so
 * the UI can be honest about what it is showing.</p>
 */
public class LatencyTester {

    private static final Logger log = LoggerFactory.getLogger(LatencyTester.class);
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int MAX_CONCURRENT_TESTS = 10;

    private final ExecutorService executor;
    private final ClashApiDelayProbe delayProbe;

    /** Supplies the running core's Clash API details, or null when it is down. */
    private volatile java.util.function.Supplier<ApiEndpoint> endpointSupplier = () -> null;

    /** Where to reach the Clash API of the core that is currently running. */
    public record ApiEndpoint(int port, String secret) { }

    /**
     * One server's measurement.
     *
     * @param millis      round-trip in milliseconds, or -1 when unreachable
     * @param throughProxy true when measured through the proxy itself rather
     *                     than by connecting to its address
     */
    public record Result(long millis, boolean throughProxy) {
        public boolean reachable() {
            return millis >= 0;
        }
    }

    /**
     * Creates a tester backed by a fixed pool of daemon threads.
     */
    public LatencyTester() {
        this(new ClashApiDelayProbe());
    }

    /** Test seam: inject the probe. */
    LatencyTester(ClashApiDelayProbe delayProbe) {
        this.delayProbe = delayProbe;
        this.executor = Executors.newFixedThreadPool(MAX_CONCURRENT_TESTS, r -> {
            Thread t = new Thread(r, "latency-tester");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Tells the tester how to reach the running core. Supplying null (or a
     * supplier that returns null) keeps every measurement on the TCP path.
     */
    public void setApiEndpointSupplier(java.util.function.Supplier<ApiEndpoint> supplier) {
        this.endpointSupplier = supplier != null ? supplier : () -> null;
    }

    /**
     * Measures one server, preferring the through-proxy probe.
     */
    public CompletableFuture<Result> measure(ServerConfig server) {
        return CompletableFuture.supplyAsync(() -> measureBest(server), executor);
    }

    private Result measureBest(ServerConfig server) {
        if (server == null) {
            return new Result(-1, false);
        }
        ApiEndpoint endpoint = endpointSupplier.get();
        if (endpoint != null) {
            String tag = com.vlessclient.service.outbound.OutboundTags.server(server);
            java.util.Optional<Long> throughProxy =
                    delayProbe.measure(endpoint.port(), endpoint.secret(), tag);
            if (throughProxy.isPresent()) {
                return new Result(throughProxy.get(), true);
            }
        }
        return new Result(measureLatency(server), false);
    }

    /**
     * The TCP-only measurement, deliberately not public: it answers "is the
     * address reachable", which is the weaker question. Callers that want a
     * number to rank servers by should use {@link #measure} or {@link #testAll},
     * both of which prefer the through-proxy probe. Kept as a seam so tests can
     * pin the fallback path directly.
     */
    CompletableFuture<Long> measureTcp(ServerConfig server) {
        return CompletableFuture.supplyAsync(() -> measureLatency(server), executor);
    }

    /**
     * Measures every given server concurrently, each one preferring the
     * through-proxy probe and falling back to TCP.
     *
     * @param servers the servers to test; may be null or empty
     * @return a future of a map from server id to its result
     */
    public CompletableFuture<Map<String, Result>> testAll(List<ServerConfig> servers) {
        if (servers == null || servers.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        List<CompletableFuture<Map.Entry<String, Result>>> futures = servers.stream()
                .map(server -> CompletableFuture.supplyAsync(
                        () -> Map.entry(server.getId(), measureBest(server)), executor))
                .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    Map<String, Result> results = new HashMap<>();
                    for (CompletableFuture<Map.Entry<String, Result>> future : futures) {
                        Map.Entry<String, Result> entry = future.join();
                        results.put(entry.getKey(), entry.getValue());
                    }
                    return results;
                });
    }

    private long measureLatency(ServerConfig server) {
        if (server == null) {
            log.warn("Latency test skipped: server is null");
            return -1;
        }
        String address = server.getAddress();
        int port = server.getPort();
        if (address == null || address.isBlank()) {
            log.warn("Latency test skipped for server '{}': address is null or blank",
                    server.getName());
            return -1;
        }
        if (port < 1 || port > 65535) {
            log.warn("Latency test skipped for server {} ({}): invalid port {}",
                    server.getName(), address, port);
            return -1;
        }
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            log.debug("Latency to {}:{} = {} ms", address, port, elapsed);
            return elapsed;
        } catch (Exception e) {
            log.debug("Latency test failed for server {} ({}:{}): {}",
                    server.getName(), address, port, e.getMessage());
            return -1;
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
