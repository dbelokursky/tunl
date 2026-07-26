package com.vlessclient.service;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.Subscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionServiceTest {

    @TempDir
    Path tempDir;

    private ConfigStore configStore;
    private ShareLinkParser shareLinkParser;
    private TestableSubscriptionService service;

    @BeforeEach
    void setUp() {
        configStore = new ConfigStore(tempDir);
        shareLinkParser = new ShareLinkParser();
        service = new TestableSubscriptionService(configStore, shareLinkParser, tempDir);
    }

    @Test
    void parseContent_base64EncodedLinks() {
        String links = "vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n"
                + "vless://uuid2@server2.com:443?security=tls&type=tcp#Server2\n";
        String base64 = Base64.getEncoder().encodeToString(links.getBytes(StandardCharsets.UTF_8));

        List<ServerConfig> servers = service.parseContent(base64);

        assertThat(servers).hasSize(2);
        assertThat(servers.get(0).getAddress()).isEqualTo("server1.com");
        assertThat(servers.get(0).getPort()).isEqualTo(443);
        assertThat(servers.get(1).getAddress()).isEqualTo("server2.com");
    }

    @Test
    void parseContent_plainTextLinks() {
        String content = "vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n"
                + "vless://uuid2@server2.com:8443?security=tls&type=tcp#Server2\n";

        List<ServerConfig> servers = service.parseContent(content);

        assertThat(servers).hasSize(2);
        assertThat(servers.get(0).getName()).isEqualTo("Server1");
        assertThat(servers.get(0).getProtocol()).isEqualTo(Protocol.VLESS);
        assertThat(servers.get(1).getAddress()).isEqualTo("server2.com");
        assertThat(servers.get(1).getPort()).isEqualTo(8443);
    }

    @Test
    void parseContent_skipsInvalidLines() {
        String content = "vless://uuid1@server1.com:443?security=tls&type=tcp#Good\n"
                + "this is not a valid link\n"
                + "vless://uuid2@server2.com:443?security=tls&type=tcp#AlsoGood\n";

        List<ServerConfig> servers = service.parseContent(content);

        assertThat(servers).hasSize(2);
        assertThat(servers.get(0).getName()).isEqualTo("Good");
        assertThat(servers.get(1).getName()).isEqualTo("AlsoGood");
    }

    @Test
    void parseContent_emptyContent() {
        assertThat(service.parseContent("")).isEmpty();
        assertThat(service.parseContent(null)).isEmpty();
        assertThat(service.parseContent("   ")).isEmpty();
    }

    @Test
    void refreshSubscription_addsNewServersAndRemovesOld() {
        // Initial content with one server
        String initialContent = "vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n";
        service.setFetchedContent(Base64.getEncoder()
                .encodeToString(initialContent.getBytes(StandardCharsets.UTF_8)));

        service.addSubscription("TestSub", "https://example.com/sub");
        Subscription sub = service.getSubscriptions().get(0);
        assertThat(sub.getServerIds()).hasSize(1);
        assertThat(configStore.getServers()).hasSize(1);
        assertThat(configStore.getServers().get(0).getAddress()).isEqualTo("server1.com");

        // Refresh with different servers
        String updatedContent = "vless://uuid2@server2.com:443?security=tls&type=tcp#Server2\n"
                + "vless://uuid3@server3.com:443?security=tls&type=tcp#Server3\n";
        service.setFetchedContent(Base64.getEncoder()
                .encodeToString(updatedContent.getBytes(StandardCharsets.UTF_8)));

        service.refreshSubscription(sub.getId());

        // server1 removed, server2 and server3 added
        assertThat(sub.getServerIds()).hasSize(2);
        assertThat(configStore.getServers()).hasSize(2);
        assertThat(configStore.getServers())
                .extracting(ServerConfig::getAddress)
                .containsExactlyInAnyOrder("server2.com", "server3.com");
    }

    /**
     * A failed refresh used to vanish into the log: the row kept its old
     * timestamp and looked identical to a healthy subscription, so a dead URL
     * or an expired token went unnoticed indefinitely.
     */
    @Test
    void refreshSubscription_recordsWhyItFailedAndClearsItOnSuccess() {
        String content = "vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n";
        service.setFetchedContent(Base64.getEncoder()
                .encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        service.addSubscription("TestSub", "https://example.com/sub");
        Subscription sub = service.getSubscriptions().get(0);
        assertThat(sub.getLastError()).isNull();

        service.failFetchWith("HTTP 403");
        service.refreshSubscription(sub.getId());

        assertThat(sub.getLastError()).contains("403");
        // Persisted, so the failure is still visible after a restart.
        assertThat(new TestableSubscriptionService(configStore, shareLinkParser, tempDir)
                .getSubscriptions().get(0).getLastError()).contains("403");

        // A later success clears it — the row must not keep warning forever.
        service.setFetchedContent(Base64.getEncoder()
                .encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        service.refreshSubscription(sub.getId());
        assertThat(sub.getLastError()).isNull();
    }

    @Test
    void removeSubscription_removesAssociatedServers() {
        String content = "vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n"
                + "vless://uuid2@server2.com:443?security=tls&type=tcp#Server2\n";
        service.setFetchedContent(Base64.getEncoder()
                .encodeToString(content.getBytes(StandardCharsets.UTF_8)));

        service.addSubscription("TestSub", "https://example.com/sub");
        assertThat(configStore.getServers()).hasSize(2);

        String subId = service.getSubscriptions().get(0).getId();
        service.removeSubscription(subId);

        assertThat(service.getSubscriptions()).isEmpty();
        assertThat(configStore.getServers()).isEmpty();
    }

    @Test
    void persistenceRoundTrip_savesAndLoadsSubscriptions() {
        String content = "vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n";
        service.setFetchedContent(Base64.getEncoder()
                .encodeToString(content.getBytes(StandardCharsets.UTF_8)));

        service.addSubscription("PersistTest", "https://example.com/sub");
        assertThat(service.getSubscriptions()).hasSize(1);

        // Create a new instance that loads from the same directory
        TestableSubscriptionService reloaded =
                new TestableSubscriptionService(configStore, shareLinkParser, tempDir);
        assertThat(reloaded.getSubscriptions()).hasSize(1);

        Subscription loaded = reloaded.getSubscriptions().get(0);
        assertThat(loaded.getName()).isEqualTo("PersistTest");
        assertThat(loaded.getUrl()).isEqualTo("https://example.com/sub");
        assertThat(loaded.getServerIds()).hasSize(1);
        assertThat(loaded.getLastRefreshedAt()).isGreaterThan(0);
    }

    @Test
    void addSubscription_prefixesServerNamesWithSubscriptionName() {
        String content = "vless://uuid1@server1.com:443?security=tls&type=tcp#OriginalName\n";
        service.setFetchedContent(Base64.getEncoder()
                .encodeToString(content.getBytes(StandardCharsets.UTF_8)));

        service.addSubscription("MySub", "https://example.com/sub");

        assertThat(configStore.getServers()).hasSize(1);
        assertThat(configStore.getServers().get(0).getName()).isEqualTo("[MySub] OriginalName");
    }

    @Test
    void parseContent_multipleProtocols() {
        String content = "vless://uuid1@vless.com:443?security=tls&type=tcp#VlessServer\n"
                + "trojan://pass@trojan.com:443?security=tls&type=tcp#TrojanServer\n";

        List<ServerConfig> servers = service.parseContent(content);

        assertThat(servers).hasSize(2);
        assertThat(servers.get(0).getProtocol()).isEqualTo(Protocol.VLESS);
        assertThat(servers.get(1).getProtocol()).isEqualTo(Protocol.TROJAN);
    }

    /**
     * A testable subclass that overrides fetchContent to return
     * pre-configured content instead of making HTTP calls.
     */
    private static class TestableSubscriptionService extends SubscriptionService {

        private String fetchedContent = "";
        private RuntimeException fetchFailure;

        TestableSubscriptionService(ConfigStore configStore, ShareLinkParser shareLinkParser,
                                     Path dataDir) {
            super(configStore, shareLinkParser, dataDir,
                    java.net.http.HttpClient.newHttpClient());
        }

        void setFetchedContent(String content) {
            this.fetchedContent = content;
            this.fetchFailure = null;
        }

        /** Makes the next fetch fail, the way a dead URL or bad token would. */
        void failFetchWith(String message) {
            this.fetchFailure = new RuntimeException(message);
        }

        @Override
        String fetchContent(String url) {
            if (fetchFailure != null) {
                throw fetchFailure;
            }
            return fetchedContent;
        }
    }
}
