package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which server is picked is the store's to say at the moment a refresh is
 * applied, not what it said when the refresh read its members. The flag was
 * copied from that earlier read, so a server picked in between (in the list,
 * the tray or by an agent over MCP) was undone, or left two servers picked,
 * and the connect took the first of them.
 */
class SubscriptionPickDuringRefreshTest {

    private static final String LIST =
            "vless://11111111-1111-1111-1111-111111111111@a.example:443?security=tls&type=tcp#A\n"
            + "vless://22222222-2222-2222-2222-222222222222@b.example:443?security=tls&type=tcp#B\n";

    @TempDir
    Path tempDir;

    /** Lands a pick just before the refresh's batch, where the FX queue can put it. */
    private static final class PickingStore extends ConfigStore {
        volatile String pickBeforeTheBatch;

        PickingStore(Path dir) {
            super(dir);
        }

        @Override
        public void applyServerBatch(List<ServerConfig> upserts, List<String> removalIds) {
            String pick = pickBeforeTheBatch;
            pickBeforeTheBatch = null;
            if (pick != null) {
                setActiveServer(pick);
            }
            super.applyServerBatch(upserts, removalIds);
        }
    }

    private PickingStore store;
    private SubscriptionService service;

    private void subscribe() {
        store = new PickingStore(tempDir);
        service = new SubscriptionService(store, new ShareLinkParser(), tempDir,
                HttpClient.newHttpClient()) {
            @Override
            String fetchContent(String url) {
                return LIST;
            }
        };
        service.addSubscription("P", "https://provider.example/sub");
        assertThat(picked()).containsExactly("[P] A");
    }

    @Test
    void theUsersOwnServerPickedMeanwhileStaysTheOnlyOnePicked() {
        subscribe();
        ServerConfig own = new ServerConfig();
        own.setName("My own");
        own.setProtocol(Protocol.VLESS);
        own.setAddress("mine.example");
        own.setPort(443);
        own.setUuid("33333333-3333-3333-3333-333333333333");
        store.addServer(own);

        store.pickBeforeTheBatch = own.getId();
        service.refreshSubscription(service.getSubscriptions().getFirst().getId());

        assertThat(picked()).containsExactly("My own");
    }

    @Test
    void anotherServerOfTheSubscriptionPickedMeanwhileStaysPicked() {
        subscribe();
        String b = store.getServers().stream()
                .filter(server -> server.getName().equals("[P] B"))
                .findFirst().orElseThrow().getId();

        store.pickBeforeTheBatch = b;
        service.refreshSubscription(service.getSubscriptions().getFirst().getId());

        assertThat(picked()).containsExactly("[P] B");
    }

    private List<String> picked() {
        return store.getServers().stream().filter(ServerConfig::isActive)
                .map(ServerConfig::getName).toList();
    }
}
