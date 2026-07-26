package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.LatencyTester;
import com.vlessclient.service.TestConfigStores;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the latency-test toggle state machine in {@link LatencyTestSession}.
 */
class LatencyTestSessionTest {

    @TempDir
    static Path tempDir;

    private static ConfigStore priorStore;

    /** Returns a caller-controlled future instead of probing real servers. */
    private static final class FakeTester extends LatencyTester {
        private final CompletableFuture<Map<String, LatencyTester.Result>> next =
                new CompletableFuture<>();

        @Override
        public CompletableFuture<Map<String, LatencyTester.Result>> testAll(
                List<ServerConfig> servers) {
            return next;
        }
    }

    @BeforeAll
    static void initJfx() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException ignored) {
            // Platform already started
        }
        try {
            priorStore = ServiceLocator.get(ConfigStore.class);
        } catch (IllegalArgumentException e) {
            priorStore = null;
        }
    }

    @AfterAll
    static void restoreServices() {
        ServiceLocator.register(ConfigStore.class,
                priorStore != null ? priorStore : TestConfigStores.at(tempDir));
    }

    private static void onFxAndWait(Runnable action) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static void flushFxEvents() throws InterruptedException {
        onFxAndWait(() -> { });
    }

    private static String statusText(VBox list) {
        assertThat(list.getChildren()).hasSize(1);
        return ((Label) list.getChildren().get(0)).getText();
    }

    private static ServerConfig server(String name) {
        ServerConfig config = new ServerConfig();
        config.setName(name);
        config.setAddress("192.0.2.1");
        config.setPort(443);
        return config;
    }

    @Test
    void nullTesterShowsUnavailableStatus() throws Exception {
        Button button = new Button("x");
        VBox list = new VBox();
        LatencyTestSession session = new LatencyTestSession(null, button, list);

        onFxAndWait(session::toggle);

        assertThat(statusText(list)).isEqualTo(I18n.get("dashboard.latency.unavailable"));
        assertThat(list.isVisible()).isTrue();
    }

    @Test
    void emptyServerListShowsNoServersStatus() throws Exception {
        ServiceLocator.register(ConfigStore.class,
                TestConfigStores.at(tempDir.resolve("empty-store")));
        Button button = new Button("x");
        VBox list = new VBox();
        LatencyTestSession session = new LatencyTestSession(new FakeTester(), button, list);

        onFxAndWait(session::toggle);

        assertThat(statusText(list)).isEqualTo(I18n.get("dashboard.no.servers"));
        assertThat(button.getText()).isEqualTo("x");
    }

    @Test
    void toggleStartsThenStopsTheLoop() throws Exception {
        ConfigStore store = TestConfigStores.at(tempDir.resolve("one-server"));
        store.addServer(server("s1"));
        ServiceLocator.register(ConfigStore.class, store);

        Button button = new Button("x");
        VBox list = new VBox();
        LatencyTestSession session = new LatencyTestSession(new FakeTester(), button, list);

        onFxAndWait(session::toggle);
        assertThat(button.getText()).isEqualTo(I18n.get("dashboard.stop"));
        assertThat(statusText(list)).isEqualTo(I18n.get("dashboard.testing"));

        onFxAndWait(session::toggle);
        assertThat(button.getText()).isEqualTo(I18n.get("button.test.latency"));
    }

    @Test
    void lateResultArrivingAfterStopIsDropped() throws Exception {
        ConfigStore store = TestConfigStores.at(tempDir.resolve("late-result"));
        ServerConfig config = server("s1");
        store.addServer(config);
        ServiceLocator.register(ConfigStore.class, store);

        Button button = new Button("x");
        VBox list = new VBox();
        FakeTester tester = new FakeTester();
        LatencyTestSession session = new LatencyTestSession(tester, button, list);

        onFxAndWait(session::toggle);   // start; first tick's future stays pending
        onFxAndWait(session::toggle);   // stop while the tick is in flight

        tester.next.complete(
                Map.of(config.getId(), new LatencyTester.Result(42L, true)));
        flushFxEvents();

        // The late result must not repopulate the list after Stop.
        assertThat(statusText(list)).isEqualTo(I18n.get("dashboard.testing"));
        assertThat(button.getText()).isEqualTo(I18n.get("button.test.latency"));
    }
}
