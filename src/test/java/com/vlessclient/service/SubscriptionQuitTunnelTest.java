package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.Subscription;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Quitting lifted the routing that keeps a subscription fetch inside the
 * tunnel ({@link AppHttpClients#routeDirect}) first, and stopped the
 * subscription refreshes only after the core, seconds later. A refresh cycle
 * that went on meanwhile sent the next subscription's link, account token
 * and all, straight to the provider from the user's real address: the one
 * leak the tunnel guard in {@code fetchContent} exists to prevent.
 */
class SubscriptionQuitTunnelTest {

    @TempDir
    Path dir;

    /** Stands in for the core's local HTTP inbound: what the tunnel carried. */
    private HttpServer tunnel;
    /** Stands in for the open internet: what went around the tunnel. */
    private HttpServer direct;
    private final List<String> throughTunnel = new CopyOnWriteArrayList<>();
    private final List<String> aroundTunnel = new CopyOnWriteArrayList<>();
    private ProxySelector previousDefault;

    private volatile boolean quitOnFirstFetch;
    private final CountDownLatch engineStopping = new CountDownLatch(1);
    private final CountDownLatch releaseEngine = new CountDownLatch(1);
    private Thread quitter;

    @BeforeEach
    void network() throws IOException {
        ServiceLocator.shutdown();
        tunnel = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        tunnel.setExecutor(Executors.newCachedThreadPool());
        tunnel.createContext("/", exchange -> {
            String host = exchange.getRequestURI().getHost();
            throughTunnel.add(host);
            if (quitOnFirstFetch) {
                quitOnFirstFetch = false;
                // The user quits while this fetch is in flight, and the answer
                // comes while the core is being stopped.
                quitter = Thread.ofPlatform().start(ServiceLocator::shutdown);
                await(engineStopping);
            }
            answer(exchange, host);
        });
        tunnel.start();
        direct = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        direct.createContext("/", exchange -> {
            aroundTunnel.add(exchange.getRequestURI().getHost());
            answer(exchange, exchange.getRequestURI().getHost());
        });
        direct.start();

        // Whatever the selector sends DIRECT reaches the "internet" above.
        previousDefault = ProxySelector.getDefault();
        InetSocketAddress internet = direct.getAddress();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(new Proxy(Proxy.Type.HTTP, internet));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            }
        });
        int port = tunnel.getAddress().getPort();
        AppHttpClients.routeThroughTunnel(() -> OptionalInt.of(port));
        AppHttpClients.setTunnelBrokenProbe(() -> false);
    }

    @AfterEach
    void restore() throws InterruptedException {
        releaseEngine.countDown();
        if (quitter != null) {
            quitter.join(10_000);
        }
        ServiceLocator.shutdown();
        AppHttpClients.routeDirect();
        ProxySelector.setDefault(previousDefault);
        tunnel.stop(0);
        direct.stop(0);
    }

    @Test
    void aRefreshCycleUnderWayWhenTheUserQuitsSendsNothingAroundTheTunnel() throws Exception {
        SubscriptionService service = new SubscriptionService(new ConfigStore(dir),
                new ShareLinkParser(), dir, AppHttpClients.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5)).build());
        service.addSubscription("A", "http://a.provider.invalid/sub?token=AAAA");
        service.addSubscription("B", "http://b.provider.invalid/sub?token=BBBB");
        assertThat(throughTunnel).containsExactly("a.provider.invalid", "b.provider.invalid");
        throughTunnel.clear();
        for (Subscription sub : service.getSubscriptions()) {
            sub.setLastRefreshedAt(System.currentTimeMillis() - Duration.ofHours(2).toMillis());
        }
        ServiceLocator.register(SubscriptionService.class, service);
        ServiceLocator.register(SingBoxEngine.class, new SlowToStopEngine());

        quitOnFirstFetch = true;
        Thread cycle = Thread.ofPlatform().start(service::refreshDue);
        cycle.join(20_000);

        assertThat(cycle.isAlive()).as("the cycle ended").isFalse();
        assertThat(aroundTunnel)
                .as("no subscription was fetched outside the tunnel while the app quit")
                .isEmpty();
        assertThat(throughTunnel).containsExactly("a.provider.invalid");
    }

    /** A core whose stop takes a while, as a real one does: the window the leak used. */
    private final class SlowToStopEngine extends SingBoxEngine {

        SlowToStopEngine() {
            super(Path.of("target", "slow-to-stop-engine"));
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public void stop() {
            engineStopping.countDown();
            await(releaseEngine);
        }
    }

    private static void answer(HttpExchange exchange, String host) throws IOException {
        byte[] body = ("vless://11111111-1111-1111-1111-111111111111@" + host
                + ":443?security=tls&type=tcp#S\n").getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = exchange.getResponseBody()) {
            exchange.sendResponseHeaders(200, body.length);
            out.write(body);
        } catch (IOException gone) {
            // The client went away first, as a quitting app's does.
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
