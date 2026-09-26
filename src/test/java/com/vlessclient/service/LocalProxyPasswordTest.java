package com.vlessclient.service;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.testing.TestServers;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * In TUN mode the local SOCKS and HTTP proxies ask for a password only the
 * app knows, and the app's own requests answer with it.
 *
 * <p>They were open to every program on the machine, and to every other
 * account on a shared one, while the tunnel carried all of their traffic
 * already: an open proxy only let something send traffic through the
 * user's server unnoticed.</p>
 */
class LocalProxyPasswordTest {

    private final SingBoxConfigGenerator generator = new SingBoxConfigGenerator();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @AfterEach
    void unbind() {
        AppHttpClients.routeDirect();
    }

    @Test
    void inTunModeTheLocalProxiesAskForThisRunsPassword() {
        List<JsonNode> local = localInbounds(ProxyMode.TUN, false);

        assertThat(local).hasSize(2).allSatisfy(inbound -> {
            assertThat(inbound.path("users")).hasSize(1);
            assertThat(inbound.path("users").get(0).path("username").asString())
                    .isEqualTo(LocalProxyCredentials.username());
            assertThat(inbound.path("users").get(0).path("password").asString())
                    .isEqualTo(LocalProxyCredentials.password());
        });
    }

    @Test
    void sharedWithOtherProgramsTheyAskForNothing() {
        assertThat(localInbounds(ProxyMode.TUN, true))
                .allSatisfy(inbound -> assertThat(inbound.has("users")).isFalse());
    }

    /** The system's proxy cannot carry a password, so there it stays open. */
    @Test
    void inSystemProxyModeTheyAskForNothing() {
        assertThat(localInbounds(ProxyMode.SYSTEM_PROXY, false))
                .allSatisfy(inbound -> assertThat(inbound.has("users")).isFalse());
    }

    @Test
    void thePasswordIsLongAndNewForEveryRun() {
        assertThat(LocalProxyCredentials.password()).hasSizeGreaterThanOrEqualTo(32);
    }

    /**
     * The app's own HTTPS requests go through the proxy in a CONNECT tunnel,
     * and the JDK keeps Basic authentication off tunnels unless told: the
     * launcher tells it, and so does the test JVM's configuration.
     */
    @Test
    void theAppsOwnHttpsRequestsAnswerTheProxysChallenge() throws Exception {
        assertThat(System.getProperty(LocalProxyCredentials.TUNNELING_PROPERTY)).isEmpty();
        try (ChallengingProxy proxy = new ChallengingProxy()) {
            AppHttpClients.routeThroughTunnel(() -> OptionalInt.of(proxy.port()));
            HttpClient client = AppHttpClients.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build();

            // The proxy lets the tunnel up and closes it: the TLS handshake
            // fails, which is not what is being looked at.
            assertThatThrownBy(() -> client.send(
                    HttpRequest.newBuilder(URI.create("https://updates.example/latest"))
                            .timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.discarding()))
                    .isInstanceOf(IOException.class);

            assertThat(proxy.requests).anySatisfy(request -> assertThat(request)
                    .startsWith("CONNECT updates.example:443")
                    .contains("Proxy-Authorization: " + LocalProxyCredentials.basicHeader()));
        }
    }

    @Test
    void theHealthChecksCarryThePasswordToo() throws Exception {
        try (ChallengingProxy proxy = new ChallengingProxy()) {
            ServiceReachabilityChecker checker = new ServiceReachabilityChecker();
            try {
                List<ServiceReachabilityChecker.ProbeResult> results = checker.checkAll(
                        List.of(new com.vlessclient.model.HealthCheckTarget("DNS", "1.1.1.1:853")),
                        proxy.port()).get(20, TimeUnit.SECONDS);

                assertThat(results.get(0).reachable()).isTrue();
            } finally {
                checker.shutdown();
            }
        }
    }

    private List<JsonNode> localInbounds(ProxyMode mode, boolean shared) {
        AppSettings settings = new AppSettings();
        settings.setProxyMode(mode);
        settings.setShareLocalProxyInTun(shared);
        ServerConfig server = TestServers.vless("Tokyo").build();
        JsonNode inbounds = mapper.readTree(generator.generate(server, settings)).get("inbounds");
        return inbounds.valueStream()
                .filter(inbound -> List.of("socks", "http").contains(
                        inbound.path("type").asString()))
                .toList();
    }

    /**
     * A local HTTP proxy that answers 407 until a request carries this run's
     * password, then 200, and keeps every request's head.
     */
    private static final class ChallengingProxy implements AutoCloseable {
        final List<String> requests = new CopyOnWriteArrayList<>();
        private final ServerSocket server;

        ChallengingProxy() throws IOException {
            server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
            Thread thread = new Thread(this::serve, "challenging-proxy");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            while (!server.isClosed()) {
                try (Socket client = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(
                            client.getInputStream(), StandardCharsets.US_ASCII));
                    StringBuilder head = new StringBuilder();
                    for (String line = in.readLine(); line != null && !line.isEmpty();
                            line = in.readLine()) {
                        head.append(line).append('\n');
                    }
                    requests.add(head.toString());
                    boolean authorized = head.toString().contains(
                            "Proxy-Authorization: " + LocalProxyCredentials.basicHeader());
                    OutputStream out = client.getOutputStream();
                    out.write((authorized
                            ? "HTTP/1.1 200 Connection established\r\n\r\n"
                            : "HTTP/1.1 407 Proxy Authentication Required\r\n"
                                    + "Proxy-Authenticate: Basic realm=\"sing-box\"\r\n"
                                    + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } catch (IOException e) {
                    return;
                }
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
