package com.vlessclient.service;

import com.vlessclient.platform.InterfaceRoute;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HostFactsTest {

    /** A fact the configuration never needs is never asked; one it does, once. */
    @Test
    void eachFactIsAskedOnceAndOnlyWhenNeeded() {
        AtomicInteger uplinkAsked = new AtomicInteger();
        AtomicInteger proxyAsked = new AtomicInteger();
        HostFacts facts = new HostFacts(() -> {
            uplinkAsked.incrementAndGet();
            return false;
        }, () -> {
            proxyAsked.incrementAndGet();
            return true;
        });

        assertThat(facts.ipv6Uplink()).isFalse();
        assertThat(facts.ipv6Uplink()).isFalse();

        assertThat(uplinkAsked).hasValue(1);
        assertThat(proxyAsked).as("never needed").hasValue(0);
        assertThat(facts.systemProxyAutoConfigurable()).isTrue();
        assertThat(proxyAsked).hasValue(1);
    }

    /**
     * The route to a WireGuard peer is the run's too: asked afresh, a network
     * gaining an interface mid-session read as settings changed, and every
     * update of the card on the FX thread looked a route up.
     */
    @Test
    void aPeersRouteIsAskedOncePerPeer() {
        List<InetAddress> asked = new ArrayList<>();
        InterfaceRoute.Route veth = new InterfaceRoute.Route("veth0",
                InetAddress.ofLiteral("198.18.0.1"));
        HostFacts facts = new HostFacts(() -> false, () -> false,
                SingBoxConfigGenerator.LatencyRanking.NONE, destination -> {
                    asked.add(destination);
                    return Optional.of(veth);
                });
        InetAddress peer = InetAddress.ofLiteral("198.18.0.2");

        assertThat(facts.routeOffDefault(peer)).contains(veth);
        assertThat(facts.routeOffDefault(InetAddress.ofLiteral("198.18.0.2"))).contains(veth);
        facts.routeOffDefault(InetAddress.ofLiteral("198.18.0.3"));

        assertThat(asked).containsExactly(peer, InetAddress.ofLiteral("198.18.0.3"));
    }
}
