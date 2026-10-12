package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
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

    /**
     * A port takes effect when a core starts on it. Changed in Settings, or by
     * an agent, while a core ran, the app's own requests followed the new
     * port at once, where nothing listened: every subscription refresh, update
     * check and country database download failed until a reconnect, and a
     * program that held the new port was handed them as a proxy.
     */
    @Test
    void aPortChangedWhileConnectedLeavesTheRequestsOnThePortTheCoreListensOn() {
        AppSettings settings = new AppSettings();
        settings.setHttpPort(2081);
        // What a connect does before it starts the core: the chosen port is free.
        settings.listenOn(settings.getSocksPort(), 2081, settings.getClashApiPort(), 0);
        AppHttpClients.followTunnel(new ConnectedEngine(), () -> settings,
                new TunnelHealthState(), () -> true);
        assertThat(AppHttpClients.selector().select(PUBLIC)).containsExactly(TUNNEL);

        settings.setHttpPort(18080);

        assertThat(AppHttpClients.selector().select(PUBLIC))
                .as("the running core still listens on 2081")
                .containsExactly(TUNNEL);
    }

    /** An engine whose core is up and connected. */
    private static final class ConnectedEngine extends SingBoxEngine {

        ConnectedEngine() {
            super(Path.of("target", "connected-engine"));
        }

        @Override
        public ReadOnlyObjectProperty<ConnectionState> connectionStateProperty() {
            return new ReadOnlyObjectWrapper<>(ConnectionState.CONNECTED).getReadOnlyProperty();
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
