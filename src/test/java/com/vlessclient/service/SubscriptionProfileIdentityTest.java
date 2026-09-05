package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.ServerConfig;
import com.vlessclient.platform.SecretSealers;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubscriptionProfileIdentityTest {
    @TempDir Path dir;
    private ConfigStore store;
    private Feed service;

    @BeforeEach
    void setUp() {
        store = new ConfigStore(dir, SecretSealers.disabled());
        service = new Feed(store, dir);
    }

    @Test
    void keepsDifferentCredentialsOnOneEndpointAndTheirIdsAfterReordering() {
        importFeed(link("a", "One", ""), link("b", "Two", ""));
        assertThat(store.getServers()).hasSize(2);
        String one = byName("One").getId();
        String two = byName("Two").getId();
        store.setActiveServer(two);

        refresh(link("b", "Renamed", ""), link("a", "One", ""));

        assertThat(byName("Renamed").getId()).isEqualTo(two);
        assertThat(byName("Renamed").isActive()).isTrue();
        assertThat(byName("One").getId()).isEqualTo(one);
    }

    @Test
    void keepsTransportAndTlsVariantsWithTheSameCredential() {
        importFeed(link("a", "One", "&sni=one.example"),
                link("a", "Two", "&sni=two.example"),
                "vless://a@shared.example:443?security=tls&type=ws&path=%2Fws#WebSocket");

        assertThat(store.getServers()).hasSize(3);
        assertThat(service.getSubscriptions().getFirst().getServerIds()).hasSize(3);
    }

    @Test
    void exactDuplicateDoesNotCreateAnotherProfile() {
        importFeed(link("a", "One", ""), link("a", "One", ""));
        assertThat(store.getServers()).hasSize(1);
    }

    @Test
    void rotatingCredentialsKeepsDistinctNamedProfilesAndSelection() {
        importFeed(link("a", "One", ""), link("b", "Two", ""));
        String one = byName("One").getId();
        String two = byName("Two").getId();
        store.setActiveServer(two);

        refresh(link("new-b", "Two", ""), link("new-a", "One", ""));

        assertThat(byName("One").getId()).isEqualTo(one);
        assertThat(byName("Two").getId()).isEqualTo(two);
        assertThat(byName("Two").isActive()).isTrue();
        assertThat(byName("Two").getUuid()).isEqualTo("new-b");
    }

    @Test
    void uniqueEndpointCanBeRenamedAndReconfiguredWithoutLosingItsId() {
        importFeed(link("a", "Old", ""));
        String id = byName("Old").getId();
        refresh(link("new", "New", "&sni=new.example"));
        assertThat(byName("New").getId()).isEqualTo(id);
        assertThat(byName("New").isActive()).isTrue();
    }

    @Test
    void removingOneVariantPreservesTheExactRemainingVariant() {
        importFeed(link("a", "One", ""), link("b", "Two", ""));
        String two = byName("Two").getId();
        refresh(link("b", "Two", ""));
        assertThat(store.getServers()).singleElement()
                .extracting(ServerConfig::getId).isEqualTo(two);
    }

    @Test
    void ambiguousCredentialRotationDoesNotAssignOldIdsArbitrarily() {
        importFeed(link("a", "Same", ""), link("b", "Same", ""));
        List<String> oldIds = store.getServers().stream().map(ServerConfig::getId).toList();
        refresh(link("new-a", "Same", ""), link("new-b", "Same", ""));
        assertThat(store.getServers()).hasSize(2)
                .extracting(ServerConfig::getId).doesNotContainAnyElementsOf(oldIds);
    }

    private ServerConfig byName(String name) {
        return store.getServers().stream().filter(s -> ("[Provider] " + name).equals(s.getName()))
                .findFirst().orElseThrow();
    }

    private void importFeed(String... links) {
        service.body = String.join("\n", links);
        service.addSubscription("Provider", "https://provider.example/sub");
    }

    private void refresh(String... links) {
        service.body = String.join("\n", links);
        service.refreshSubscription(service.getSubscriptions().getFirst().getId());
    }

    private static String link(String credential, String name, String extra) {
        return "vless://" + credential + "@shared.example:443?security=tls&type=tcp"
                + extra + "#" + name;
    }

    private static final class Feed extends SubscriptionService {
        String body;

        Feed(ConfigStore store, Path dir) {
            super(store, new ShareLinkParser(), dir, HttpClient.newHttpClient());
        }

        @Override
        String fetchContent(String url) {
            return body;
        }
    }
}
