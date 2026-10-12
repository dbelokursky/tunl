package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javafx.collections.ListChangeListener;
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
 * <p>Each replacement and each removal was also a change of the list of its
 * own, and every change runs every listener over the whole list, as the
 * tray's copy of it does: with a listener attached, the hourly refresh of
 * such a subscription took seconds even when it changed nothing.</p>
 *
 * <p>Counted in id reads and change events rather than timed, so a loaded
 * machine cannot fail it and a quadratic walk cannot pass it.</p>
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

    /** The same server as a refresh hands it in again: a new instance, the stored id. */
    private static ServerConfig again(ServerConfig stored, int n) {
        ServerConfig replacement = server(n);
        replacement.setId(stored.getId());
        return replacement;
    }

    /** Counts the changes the list's listeners hear, and copies the list on each, as the tray. */
    private static AtomicInteger changesOf(ConfigStore store) {
        AtomicInteger changes = new AtomicInteger();
        store.getServers().addListener((ListChangeListener<ServerConfig>) change -> {
            changes.incrementAndGet();
            List.copyOf(change.getList());
        });
        return changes;
    }

    private static List<ServerConfig> firstRefresh(ConfigStore store) {
        List<ServerConfig> first = new ArrayList<>();
        for (int i = 0; i < SERVERS; i++) {
            first.add(server(i));
        }
        store.applyServerBatch(first, List.of());
        return first;
    }

    @Test
    void aBatchReadsEachIdAFewTimesNotOncePerStoredServer() {
        ConfigStore store = new ConfigStore(tempDir);
        List<ServerConfig> first = firstRefresh(store);

        // The refresh after it: every server replaced, the last half withdrawn.
        List<ServerConfig> again = new ArrayList<>();
        List<String> withdrawn = new ArrayList<>();
        for (int i = 0; i < SERVERS; i++) {
            ServerConfig replacement = again(first.get(i), i);
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

    @Test
    void aFirstRefreshIsOneChange() {
        ConfigStore store = new ConfigStore(tempDir);
        AtomicInteger changes = changesOf(store);

        firstRefresh(store);

        assertThat(store.getServers()).hasSize(SERVERS);
        assertThat(changes.get()).as("changes heard for %d servers added", SERVERS)
                .isEqualTo(1);
    }

    /** The usual hourly refresh: the provider sends what it sent before. */
    @Test
    void aRefreshThatChangesNothingIsNoChange() {
        ConfigStore store = new ConfigStore(tempDir);
        List<ServerConfig> first = firstRefresh(store);
        List<ServerConfig> again = new ArrayList<>();
        for (int i = 0; i < SERVERS; i++) {
            again.add(again(first.get(i), i));
        }
        AtomicInteger changes = changesOf(store);

        store.applyServerBatch(again, List.of());

        assertThat(changes.get()).as("changes heard for %d servers sent again", SERVERS)
                .isZero();
        assertThat(store.getServers()).as("the stored servers, kept")
                .containsExactlyElementsOf(first);
    }

    /**
     * A refresh that changes every server, adds some and withdraws others,
     * as one that rotates the account's credential does, is still one
     * change, with the list as the batch leaves it.
     */
    @Test
    void aRefreshThatChangesEverythingIsOneChange() {
        ConfigStore store = new ConfigStore(tempDir);
        List<ServerConfig> first = firstRefresh(store);
        List<ServerConfig> again = new ArrayList<>();
        List<String> withdrawn = new ArrayList<>();
        for (int i = 0; i < SERVERS; i++) {
            ServerConfig rotated = again(first.get(i), i);
            rotated.setUuid("22222222-2222-2222-2222-222222222222");
            if (i % 2 == 0) {
                again.add(rotated);
            } else {
                withdrawn.add(rotated.getId());
            }
        }
        for (int i = SERVERS; i < SERVERS + 100; i++) {
            again.add(server(i));
        }
        AtomicInteger changes = changesOf(store);

        store.applyServerBatch(again, withdrawn);

        assertThat(changes.get()).as("changes heard for a batch over %d servers", SERVERS)
                .isEqualTo(1);
        assertThat(store.getServers()).containsExactlyElementsOf(again);
        assertThat(store.getServers()).filteredOn(ServerConfig::isActive)
                .as("the first server, picked as before").containsExactly(again.getFirst());
    }

    /**
     * One server changed among thousands is a change of that one alone, not
     * of the whole list: the Servers page keeps its selection and its place.
     */
    @Test
    void oneServerChangedIsAChangeOfThatServer() {
        ConfigStore store = new ConfigStore(tempDir);
        List<ServerConfig> first = firstRefresh(store);
        List<ServerConfig> again = new ArrayList<>();
        for (int i = 0; i < SERVERS; i++) {
            again.add(again(first.get(i), i));
        }
        again.get(SERVERS / 2).setName("Renamed");
        List<ListChangeListener.Change<? extends ServerConfig>> heard = new ArrayList<>();
        List<String> spans = new ArrayList<>();
        store.getServers().addListener((ListChangeListener<ServerConfig>) change -> {
            heard.add(change);
            while (change.next()) {
                spans.add(change.getFrom() + "-" + change.getTo() + " removed "
                        + change.getRemovedSize());
            }
        });

        store.applyServerBatch(again, List.of());

        assertThat(heard).hasSize(1);
        assertThat(spans).containsExactly(SERVERS / 2 + "-" + (SERVERS / 2 + 1) + " removed 1");
        assertThat(store.getServers().get(SERVERS / 2).getName()).isEqualTo("Renamed");
    }
}
