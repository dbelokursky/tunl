package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A batch runs on the FX thread, so its cost is a frozen window. It looked
 * each upsert's id up by walking the whole list, and each removal's too: a
 * refresh of a subscription with tens of thousands of servers (a broken or
 * hostile provider stays under the 4 MiB body cap with 40,000 links) held the
 * window for seconds on every refresh, and past the FX wait's ten seconds the
 * refresh failed half-applied.
 *
 * <p>Counted in id reads rather than timed, so a loaded machine cannot fail
 * it and a quadratic walk cannot pass it.</p>
 */
class ConfigStoreBatchScaleTest {

    private static final int SERVERS = 4_000;

    @TempDir
    Path tempDir;

    /** A server that counts how often its id is read. */
    private static final class Counted extends ServerConfig {
        static final AtomicLong READS = new AtomicLong();

        @Override
        public String getId() {
            READS.incrementAndGet();
            return super.getId();
        }
    }

    private static ServerConfig server(int n) {
        ServerConfig server = new Counted();
        server.setName("S" + n);
        server.setProtocol(Protocol.VLESS);
        server.setAddress("s" + n + ".example");
        server.setPort(443);
        server.setUuid("11111111-1111-1111-1111-111111111111");
        return server;
    }

    @Test
    void aBatchReadsEachIdAFewTimesNotOncePerStoredServer() {
        ConfigStore store = new ConfigStore(tempDir);
        List<ServerConfig> first = new ArrayList<>();
        for (int i = 0; i < SERVERS; i++) {
            first.add(server(i));
        }
        store.applyServerBatch(first, List.of());

        // The refresh after it: every server replaced, the last half withdrawn.
        List<ServerConfig> again = new ArrayList<>();
        List<String> withdrawn = new ArrayList<>();
        for (int i = 0; i < SERVERS; i++) {
            ServerConfig replacement = server(i);
            replacement.setId(first.get(i).getId());
            if (i < SERVERS / 2) {
                again.add(replacement);
            } else {
                withdrawn.add(replacement.getId());
            }
        }
        Counted.READS.set(0);
        store.applyServerBatch(again, withdrawn);

        assertThat(store.getServers()).hasSize(SERVERS / 2);
        assertThat(Counted.READS.get())
                .as("id reads for a batch over %d servers", SERVERS)
                .isLessThan(50L * SERVERS);
    }
}
