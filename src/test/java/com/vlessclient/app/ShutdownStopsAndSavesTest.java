package com.vlessclient.app;

import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.platform.SecretSealers;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ShareLinkParser;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.SubscriptionService;
import com.vlessclient.service.TrafficHistoryStore;
import com.vlessclient.service.UpdateManager;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.FxToolkitExtension;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a quit has to do before the process goes: stop the core, wait for a
 * server-list save on its way to disk, and write the traffic counted since
 * the history's last flush. Only the SIGTERM hook was tested for any of it;
 * {@link ServiceLocator#shutdown()} ran in tests as cleanup alone, so a
 * change that dropped the state save, or moved the core's stop below the
 * steps that can take seconds (an update check, a subscription refresh's
 * grace), left the suite green while every quit orphaned a core or lost the
 * history.
 *
 * <p>macOS and Linux only: the stand-in core is a shell script.</p>
 */
@EnabledOnOs({OS.MAC, OS.LINUX})
@ExtendWith(FxToolkitExtension.class)
class ShutdownStopsAndSavesTest {

    @TempDir
    Path dir;

    /** Leaves the graph the UI tests build, which a later test in this JVM may expect. */
    @AfterEach
    void restoreTheTestGraph() {
        UiTestServices.initialize();
    }

    @Test
    void aQuitStopsTheCoreBeforeTheSlowStepsAndSavesWhatOnlyMemoryHolds() throws Exception {
        ServiceLocator.shutdown();
        ServiceLocator.initialize(ServiceLocator.StartupMode.TEST);
        SingBoxEngine engine = new SingBoxEngine(fakeCore());
        ServiceLocator.register(SingBoxEngine.class, engine);
        AtomicBoolean writesAwaited = new AtomicBoolean();
        ConfigStore store = new ConfigStore(dir) {
            @Override
            public boolean awaitPendingWrites(long millis) {
                writesAwaited.set(true);
                return super.awaitPendingWrites(millis);
            }
        };
        ServiceLocator.register(ConfigStore.class, store);
        TrafficHistoryStore history = new TrafficHistoryStore(dir, Clock.systemDefaultZone());
        ServiceLocator.register(TrafficHistoryStore.class, history);
        List<String> coreRunningAt = new CopyOnWriteArrayList<>();
        ServiceLocator.register(UpdateManager.class, new UpdateManager() {
            @Override
            public void shutdown() {
                coreRunningAt.add("updates: " + engine.isRunning());
                super.shutdown();
            }
        });
        ServiceLocator.register(SubscriptionService.class, new SubscriptionService(
                store, new ShareLinkParser(), SecretSealers.disabled()) {
            @Override
            public void shutdown() {
                coreRunningAt.add("subscriptions: " + engine.isRunning());
                super.shutdown();
            }
        });

        engine.start("{}", ProxyMode.SYSTEM_PROXY);
        Await.until("the core running", engine::isRunning, Duration.ofSeconds(10));
        ServerConfig server = new ServerConfig();
        server.setId("quit-probe");
        server.setName("Probe");
        history.record(server, 4_000, 60_000);

        ServiceLocator.shutdown();

        assertThat(engine.isRunning()).as("the core stopped").isFalse();
        assertThat(coreRunningAt).as("whether the core still ran at each slow step")
                .containsExactly("updates: false", "subscriptions: false");
        assertThat(writesAwaited).as("the server list's pending save waited for").isTrue();
        Path saved = dir.resolve(TrafficHistoryStore.HISTORY_FILE);
        assertThat(saved).as("the traffic counted since the last flush").exists();
        assertThat(Files.readString(saved)).contains("quit-probe").contains("60000");
    }

    /** A core that passes the check and runs until it is stopped. */
    private Path fakeCore() throws Exception {
        Path core = dir.resolve("sing-box");
        Files.writeString(core, "#!/bin/bash\n"
                + "[ \"$1\" = check ] && exit 0\n"
                + "echo 'sing-box started'\n"
                + "sleep 30\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(core, PosixFilePermissions.fromString("rwxr-xr-x"));
        return core;
    }
}
