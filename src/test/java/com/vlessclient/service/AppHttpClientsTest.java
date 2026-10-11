package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AppHttpClientsTest {

    private static final URI PUBLIC = URI.create("https://api.github.com/x");

    private static final Proxy TUNNEL = new Proxy(Proxy.Type.HTTP,
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 2081));

    /**
     * Where a request goes when no tunnel carries it: wherever the JVM's own
     * selector sends it. That is DIRECT on a bare JVM, but a machine or a CI
     * sandbox that sets {@code https.proxyHost} (often through
     * {@code JAVA_TOOL_OPTIONS}) gets its proxy here, and asserting NO_PROXY
     * failed there with nothing wrong in the code.
     */
    private static List<Proxy> untunneled() {
        return ProxySelector.getDefault().select(PUBLIC);
    }

    @AfterEach
    void unbind() {
        AppHttpClients.routeDirect();
    }

    @Test
    void aClientBuiltBeforeTheTunnelExistsStillFollowsIt() {
        // The order that matters in production: ServiceLocator builds
        // SingBoxInstaller's client first and only binds the port later, once
        // the engine and the health state exist.
        var client = AppHttpClients.newBuilder().build();
        assertThat(client.proxy()).isPresent();

        AppHttpClients.routeThroughTunnel(() -> OptionalInt.of(2081));

        assertThat(client.proxy().orElseThrow().select(PUBLIC)).containsExactly(TUNNEL);
    }

    @Test
    void routeDirectPutsTheClientsBackTheWayTheyWere() {
        AppHttpClients.routeThroughTunnel(() -> OptionalInt.of(2081));
        AppHttpClients.routeDirect();

        assertThat(AppHttpClients.selector().select(PUBLIC))
                .doesNotContain(TUNNEL)
                .containsExactlyElementsOf(untunneled());
    }

    @Test
    void aNullSupplierIsTreatedAsNoTunnel() {
        AppHttpClients.routeThroughTunnel(null);

        assertThat(AppHttpClients.selector().select(PUBLIC))
                .doesNotContain(TUNNEL)
                .containsExactlyElementsOf(untunneled());
    }

    @Test
    void followTunnelStaysDirectUntilAnEngineIsRegistered() {
        AppHttpClients.followTunnel(SingBoxEngine.withoutCore(),
                com.vlessclient.model.AppSettings::new,
                new TunnelHealthState(), () -> false);

        assertThat(AppHttpClients.selector().select(PUBLIC))
                .doesNotContain(TUNNEL)
                .containsExactlyElementsOf(untunneled());
    }

    /**
     * Only a core that was up and declared broken counted as not carrying
     * traffic; while it was down or restarting, which recovery stretches to
     * minutes, a subscription fetch sent its token out direct.
     */
    @Test
    void aTunnelWantedWithNoCoreUpCarriesNothing() {
        java.util.concurrent.atomic.AtomicBoolean wanted =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        AppHttpClients.followTunnel(SingBoxEngine.withoutCore(),
                com.vlessclient.model.AppSettings::new,
                new TunnelHealthState(), wanted::get);
        try {
            assertThat(AppHttpClients.isTunnelWantedButNotCarrying())
                    .as("wanted, no core running").isTrue();
            wanted.set(false);
            assertThat(AppHttpClients.isTunnelWantedButNotCarrying())
                    .as("disconnected by the user").isFalse();
        } finally {
            AppHttpClients.routeDirect();
        }
    }

    @Test
    void everyInternetFacingServiceSharesTheSameSelector() {
        AppHttpClients.routeThroughTunnel(() -> OptionalInt.of(2081));
        List<java.net.http.HttpClient> clients = List.of(
                AppHttpClients.newBuilder().build(),
                AppHttpClients.newBuilder().build());

        for (var client : clients) {
            assertThat(client.proxy()).containsSame(AppHttpClients.selector());
        }
    }
}
