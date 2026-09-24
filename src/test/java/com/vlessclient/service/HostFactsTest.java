package com.vlessclient.service;

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
}
