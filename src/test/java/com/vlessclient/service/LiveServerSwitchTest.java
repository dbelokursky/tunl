package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.ServerSelection;
import com.vlessclient.service.outbound.OutboundTags;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class LiveServerSwitchTest {
    @TempDir Path dir;

    @Test
    void switchesThroughAuthenticatedApiAndRestartsAfterConfigChangesOrApiRejection()
            throws Exception {
        ConfigStore store = new ConfigStore(dir);
        ServerConfig first = server("first");
        ServerConfig second = server("second");
        store.addServer(first);
        store.addServer(second);
        store.getSettings().setProxyMode(ProxyMode.SYSTEM_PROXY);
        AtomicReference<String> selected = new AtomicReference<>();
        AtomicInteger puts = new AtomicInteger();
        AtomicInteger status = new AtomicInteger(204);
        RecordingEngine engine = new RecordingEngine(dir.resolve("core"));
        ConnectionService service = new ConnectionService(store, new SingBoxConfigGenerator(),
                null, engine);
        HttpServer api = null;
        try {
            service.connect();
            // The fake stands in for the core's control endpoint, so it takes
            // the port the connect settled on. In a real run nothing holds
            // that port until the core binds it, and holding it beforehand
            // now moves the app off it. It checks the secret of the core
            // running at the time: every core gets its own.
            api = HttpServer.create(new InetSocketAddress(
                    "127.0.0.1", store.getSettings().listenClashApiPort()), 0);
            api.createContext("/proxies/", exchange -> {
                assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                        .isEqualTo("Bearer " + store.getSettings().getClashApiSecret());
                if (exchange.getRequestMethod().equals("PUT")) {
                    puts.incrementAndGet();
                    selected.set(JsonMapper.builder().build().readTree(
                            exchange.getRequestBody().readAllBytes()).path("name").asString());
                    exchange.sendResponseHeaders(status.get(), -1);
                } else {
                    byte[] body = ("{\"now\":\"" + selected.get() + "\"}")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            api.start();

            String initialGroup = service.getProxyGroupTag();
            store.setActiveServer(second.getId());
            assertThat(service.switchToActiveServer().outcome())
                    .isEqualTo(ConnectionService.Outcome.SWITCHED);
            assertThat(selected.get()).isEqualTo(OutboundTags.server(second));
            assertThat(engine.starts).isEqualTo(1);
            assertThat(engine.stops).isZero();
            assertThat(service.getProxyGroupTag()).isEqualTo(initialGroup);

            second.setUuid("11111111-1111-1111-1111-111111111112");
            assertThat(service.switchToActiveServer().started()).isTrue();
            assertThat(puts).hasValue(1);
            assertThat(engine.starts).isEqualTo(2);
            assertThat(service.getProxyGroupTag()).isNotEqualTo(initialGroup);

            status.set(401);
            store.setActiveServer(first.getId());
            assertThat(service.switchToActiveServer().started()).isTrue();
            assertThat(engine.starts).isEqualTo(3);
            assertThat(engine.stops).isEqualTo(2);

            store.getSettings().setServerSelection(ServerSelection.AUTO_BEST);
            int before = puts.get();
            assertThat(service.switchToActiveServer().started()).isTrue();
            assertThat(puts).hasValue(before);
        } finally {
            service.disconnect();
            service.getRecoveryService().close();
            if (api != null) {
                api.stop(0);
            }
        }
    }

    @Test
    void onlyTheManualDefaultMayChangeWithoutRestarting() {
        var generator = new SingBoxConfigGenerator();
        var settings = new com.vlessclient.model.AppSettings();
        settings.setProxyMode(ProxyMode.TUN);
        var a = server("a");
        var b = server("b");
        var servers = java.util.List.of(a, b);
        String config = generator.generate(servers, a, settings, null);
        var live = new LiveSelector(config);
        assertThat(live.accepts(generator.generate(servers, b, settings, null))).isTrue();
        assertThat(live.accepts(generator.generate(java.util.List.of(b), b, settings, null)))
                .isFalse();
        settings.setProxyDns("9.9.9.9");
        assertThat(live.accepts(generator.generate(servers, b, settings, null))).isFalse();
        assertThat(new LiveSelector(config).groupTag()).isNotEqualTo(live.groupTag());
        var runtime = JsonMapper.builder().build().readTree(live.config());
        assertThat(runtime.path("route").path("final").asString()).isEqualTo(live.groupTag());
        assertThat(runtime.path("dns").path("servers").get(0).path("detour").asString())
                .isEqualTo(live.groupTag());
    }

    @Test
    void remoteRuleSetsUseTheRuntimeGroupForTheirDownload() {
        var routing = new com.vlessclient.model.RoutingConfig();
        routing.setBypassCountries(java.util.List.of("ru"));
        var settings = new com.vlessclient.model.AppSettings();
        String generated = new SingBoxConfigGenerator().generate(server("a"), settings, routing);
        var live = new LiveSelector(generated);
        var root = JsonMapper.builder().build().readTree(live.config());
        assertThat(root.path("route").path("rule_set")).isNotEmpty();
        for (var ruleSet : root.path("route").path("rule_set")) {
            assertThat(ruleSet.path("http_client").path("detour").asString())
                    .isEqualTo(live.groupTag());
        }
    }

    private static ServerConfig server(String id) {
        ServerConfig result = new ServerConfig();
        result.setId(id);
        result.setProtocol(com.vlessclient.model.Protocol.VLESS);
        result.setAddress("127.0.0.1");
        result.setUuid("11111111-1111-1111-1111-111111111111");
        result.setPort(443);
        return result;
    }

    private static class RecordingEngine extends SingBoxEngine {
        int starts;
        int stops;
        boolean running;
        RecordingEngine(Path path) { super(path); }
        @Override public void start(String config, ProxyMode mode) { starts++; running = true; }
        @Override public void stop() { stops++; running = false; }
        @Override public boolean isRunning() { return running; }
        @Override public boolean awaitStopped(Duration timeout) { return !running; }
    }
}
