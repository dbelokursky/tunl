package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.Subscription;
import com.vlessclient.platform.InMemorySecretSealer;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a subscription refresh is allowed to destroy, and what it must cost.
 *
 * <p>{@code diffAndApply} deletes every stored server the fetch did not return,
 * so the parse result is load-bearing: an unparsed body used to wipe the whole
 * subscription and then report success. And it used to route each change
 * through a per-server save, each of which re-sealed the entire list.</p>
 */
class SubscriptionRefreshSafetyTest {

    private static final String TWO_SERVERS =
            "vless://uuid1@server1.example:443?security=tls&type=tcp#One\n"
                    + "vless://uuid2@server2.example:443?security=tls&type=tcp#Two\n";

    @TempDir
    Path tempDir;

    private ConfigStore configStore;
    private InMemorySecretSealer sealer;
    private StubSubscriptionService service;

    @BeforeEach
    void setUp() {
        sealer = new InMemorySecretSealer();
        configStore = new ConfigStore(tempDir, sealer);
        service = new StubSubscriptionService(configStore, new ShareLinkParser(), tempDir);
    }

    /** Imports the two-server list and returns the subscription holding it. */
    private Subscription withTwoServersImported() {
        service.serve(TWO_SERVERS);
        service.addSubscription("Provider", "https://provider.example/sub");
        Subscription sub = service.getSubscriptions().get(0);
        assertThat(configStore.getServers()).hasSize(2);
        assertThat(sub.getServerIds()).hasSize(2);
        return sub;
    }

    @Nested
    @DisplayName("a body it cannot parse")
    class UnparseableBody {

        @Test
        @DisplayName("keeps the servers and records the failure")
        void htmlBodyDoesNotWipeTheList() {
            Subscription sub = withTwoServersImported();

            // What a captive portal or an expired-token page actually returns:
            // HTTP 200, and a body with no links in it.
            service.serve("<html><body>Your token has expired.</body></html>");
            service.refreshSubscription(sub.getId());

            assertThat(configStore.getServers())
                    .as("the stored servers survive a response we could not read")
                    .hasSize(2);
            assertThat(sub.getServerIds()).hasSize(2);
            assertThat(sub.getLastError())
                    .as("and the refresh is not reported as a success")
                    .isNotBlank();
        }

        @Test
        @DisplayName("does not stamp lastRefreshedAt")
        void doesNotLookFreshAfterwards() {
            Subscription sub = withTwoServersImported();
            long refreshedAtImport = sub.getLastRefreshedAt();

            service.serve("garbage that is not a share link at all");
            service.refreshSubscription(sub.getId());

            assertThat(sub.getLastRefreshedAt()).isEqualTo(refreshedAtImport);
        }
    }

    @Nested
    @DisplayName("a body it can parse")
    class ParseableBody {

        @Test
        @DisplayName("an empty response really does clear the subscription")
        void emptyBodyStillClears() {
            Subscription sub = withTwoServersImported();

            // No ambiguity here: the provider returned nothing, so it has
            // nothing. This is the case the guard must not swallow.
            service.serve("");
            service.refreshSubscription(sub.getId());

            assertThat(configStore.getServers()).isEmpty();
            assertThat(sub.getServerIds()).isEmpty();
            assertThat(sub.getLastError()).isNull();
        }

        @Test
        @DisplayName("a genuine removal still removes exactly that server")
        void partialRemovalStillApplies() {
            Subscription sub = withTwoServersImported();

            service.serve("vless://uuid1@server1.example:443?security=tls&type=tcp#One\n");
            service.refreshSubscription(sub.getId());

            assertThat(configStore.getServers()).hasSize(1);
            assertThat(configStore.getServers().get(0).getAddress())
                    .isEqualTo("server1.example");
            assertThat(sub.getServerIds()).hasSize(1);
            assertThat(sub.getLastError()).isNull();
        }

        @Test
        @DisplayName("an added server is imported and the list keeps the old one")
        void additionsStillApply() {
            Subscription sub = withTwoServersImported();

            service.serve(TWO_SERVERS
                    + "vless://uuid3@server3.example:443?security=tls&type=tcp#Three\n");
            service.refreshSubscription(sub.getId());

            assertThat(configStore.getServers()).hasSize(3);
            assertThat(sub.getServerIds()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("a body it can only partly parse")
    class PartiallyParseableBody {

        @Test
        @DisplayName("keeps every server and removes none")
        void oneUnreadableLineDoesNotDeleteItsServer() {
            Subscription sub = withTwoServersImported();

            // The provider still lists both servers, but one line is in a shape
            // the parser cannot read - a format change, a new field, a
            // truncated response. To diffAndApply that line is indistinguishable
            // from a server that was withdrawn.
            service.serve("vless://uuid1@server1.example:443?security=tls&type=tcp#One\n"
                    + "vless://uuid2@server2.example:443?security=tls&type=zzz-unknown\n");
            service.refreshSubscription(sub.getId());

            assertThat(configStore.getServers())
                    .as("a line we could not read is not evidence the server is gone")
                    .hasSize(2);
            assertThat(sub.getServerIds())
                    .as("and the kept server must stay owned by this subscription, "
                            + "or no later refresh or delete could ever reach it")
                    .hasSize(2);
        }

        @Test
        @DisplayName("does not report the refresh as clean")
        void aPartialParseIsRecorded() {
            Subscription sub = withTwoServersImported();

            service.serve("vless://uuid1@server1.example:443?security=tls&type=tcp#One\n"
                    + "not a share link at all\n");
            service.refreshSubscription(sub.getId());

            assertThat(sub.getLastError())
                    .as("silently succeeding is how this used to lose servers")
                    .isNotBlank()
                    .contains("no servers were removed");
        }
    }

    @Nested
    @DisplayName("what a refresh costs")
    class RefreshCost {

        @Test
        @DisplayName("re-seals nothing when no credential changed")
        void unchangedRefreshSealsNothing() {
            Subscription sub = withTwoServersImported();
            sealer.resetSealCalls();

            service.serve(TWO_SERVERS);
            service.refreshSubscription(sub.getId());

            // Every seal is a forked `security` process on macOS. Nothing
            // changed, so nothing needs resealing.
            assertThat(sealer.sealCalls()).isZero();
        }

        @Test
        @DisplayName("writes servers.json once for the whole refresh")
        void refreshWritesOnce() {
            CountingConfigStore counting = new CountingConfigStore(tempDir, sealer);
            StubSubscriptionService svc =
                    new StubSubscriptionService(counting, new ShareLinkParser(), tempDir);
            svc.serve(TWO_SERVERS);
            svc.addSubscription("Provider", "https://provider.example/sub");

            counting.saves = 0;
            svc.serve(TWO_SERVERS
                    + "vless://uuid3@server3.example:443?security=tls&type=tcp#Three\n"
                    + "vless://uuid4@server4.example:443?security=tls&type=tcp#Four\n");
            svc.refreshSubscription(svc.getSubscriptions().get(0).getId());

            assertThat(counting.getServers()).hasSize(4);
            // Two additions used to mean two saves, each a full serialization
            // plus a temp-file-and-atomic-move of the entire list.
            assertThat(counting.saves).isEqualTo(1);
        }

        @Test
        @DisplayName("seals each changed credential once, not once per server per save")
        void growingListStaysLinear() {
            StringBuilder links = new StringBuilder();
            for (int i = 1; i <= 20; i++) {
                links.append("vless://uuid").append(i)
                        .append("@server").append(i)
                        .append(".example:443?security=tls&type=tcp#S").append(i)
                        .append('\n');
            }
            service.serve(links.toString());
            service.addSubscription("Provider", "https://provider.example/sub");

            assertThat(configStore.getServers()).hasSize(20);
            // One seal per new credential. The old path saved once per server
            // and re-sealed the whole list on each save: 20 servers cost 210
            // seals on import and 400 on every refresh afterwards.
            assertThat(sealer.sealCalls()).isEqualTo(20);
        }
    }

    /** Counts how many times a change was flushed to servers.json. */
    private static final class CountingConfigStore extends ConfigStore {

        private int saves;

        CountingConfigStore(Path dataDir, InMemorySecretSealer sealer) {
            super(dataDir, sealer);
        }

        @Override
        synchronized void saveServers() {
            saves++;
            super.saveServers();
        }
    }

    /** Serves canned content instead of making an HTTP request. */
    private static final class StubSubscriptionService extends SubscriptionService {

        private String body = "";

        StubSubscriptionService(ConfigStore configStore, ShareLinkParser parser, Path dataDir) {
            super(configStore, parser, dataDir, HttpClient.newHttpClient());
        }

        void serve(String body) {
            this.body = body;
        }

        @Override
        String fetchContent(String url) {
            return body;
        }
    }

    /** Guards the batch contract the refresh now depends on. */
    @Nested
    @DisplayName("applyServerBatch")
    class Batch {

        @Test
        @DisplayName("activates the first server when nothing is active, like addServer")
        void firstServerBecomesActive() {
            ServerConfig first = server("s1");
            ServerConfig second = server("s2");

            configStore.applyServerBatch(List.of(first, second), List.of());

            assertThat(configStore.getServers()).hasSize(2);
            assertThat(configStore.getServers().get(0).isActive()).isTrue();
            assertThat(configStore.getServers().get(1).isActive()).isFalse();
        }

        @Test
        @DisplayName("replaces by id instead of appending a duplicate")
        void upsertReplacesInPlace() {
            ServerConfig original = server("s1");
            configStore.applyServerBatch(List.of(original), List.of());

            ServerConfig updated = server("s1");
            updated.setId(original.getId());
            updated.setName("renamed");
            configStore.applyServerBatch(List.of(updated), List.of());

            assertThat(configStore.getServers()).hasSize(1);
            assertThat(configStore.getServers().get(0).getName()).isEqualTo("renamed");
        }

        @Test
        @DisplayName("ignores unknown removal ids without touching the list")
        void unknownRemovalsAreNoOps() {
            configStore.applyServerBatch(List.of(server("s1")), List.of());

            configStore.applyServerBatch(List.of(), List.of("no-such-id"));

            assertThat(configStore.getServers()).hasSize(1);
        }

        private ServerConfig server(String name) {
            ServerConfig config = new ServerConfig();
            config.setName(name);
            config.setAddress(name + ".example");
            config.setPort(443);
            config.setUuid("uuid-of-" + name);
            return config;
        }
    }
}
