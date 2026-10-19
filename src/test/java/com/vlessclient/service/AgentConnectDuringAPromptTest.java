package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.platform.TunLauncher;
import com.vlessclient.service.mcp.DefaultAppControlService;
import com.vlessclient.service.mcp.McpToolException;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.FxToolkitExtension;
import com.vlessclient.testing.TestServers;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * An agent's connect while the user's TUN start waits at an elevation prompt,
 * with the real engine. The macOS sudoers setup and the Linux capability
 * grant prompt inside the launch, before any process exists, so the
 * running-tunnel check saw none: the agent's connect saved its server and
 * mode, waited for the prompt, and was refused as "already running", and the
 * user's new tunnel was then moved to the agent's server.
 */
@ExtendWith(FxToolkitExtension.class)
@EnabledOnOs({OS.MAC, OS.LINUX})
class AgentConnectDuringAPromptTest {

    private static ServerConfig server(String id, String name) {
        return TestServers.server().id(id).name(name).protocol(Protocol.VLESS)
                .address(name.toLowerCase(Locale.ROOT) + ".example.com").port(443)
                .uuid("11111111-1111-1111-1111-111111111111").build();
    }

    /**
     * UAC, the osascript dialog and pkexec prompt inside the launcher's own
     * process, which the launch returns at once: the tunnel counts as running
     * then, and is answered as one, at once and with nothing changed.
     */
    @Test
    void anAgentsConnectAtAnEveryLaunchPromptIsAnsweredAsARunningTunnel(
            @TempDir(cleanup = CleanupMode.NEVER) Path dir) throws Exception {
        Path core = dir.resolve("sing-box");
        Files.writeString(core, "#!/bin/sh\n[ \"$1\" = check ] && exit 0\nsleep 30\n");
        Files.setPosixFilePermissions(core, PosixFilePermissions.fromString("rwx------"));
        // Waits for its consent file, as a launcher waits at its prompt.
        Path wrapper = dir.resolve("tun-wrapper.sh");
        Files.writeString(wrapper, "#!/bin/sh\n"
                + "while [ ! -f \"$2\" ] && [ ! -f \"$1\" ]; do sleep 0.1; done\n"
                + "echo 'sing-box started'\n"
                + "while [ ! -f \"$1\" ]; do sleep 0.1; done\n");
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwx------"));
        Path consent = dir.resolve("consent");
        CountDownLatch atPrompt = new CountDownLatch(1);
        TunLauncher launcher = (binary, configFile, prompt) -> {
            Path stop = dir.resolve("stop-" + UUID.randomUUID());
            Process process = new ProcessBuilder("/bin/sh", wrapper.toString(), stop.toString(),
                    consent.toString()).redirectErrorStream(true).start();
            atPrompt.countDown();
            return new TunLauncher.Launched(process, stop, true);
        };
        ConfigStore store = new ConfigStore(dir.resolve("data"));
        store.getSettings().setHealthCheckEnabled(false);
        store.getSettings().setProxyMode(ProxyMode.TUN);
        store.addServer(server("srv-1", "Tokyo"));
        store.addServer(server("srv-2", "Osaka"));
        store.setActiveServer("srv-1");
        SingBoxEngine engine = new SingBoxEngine(core);
        engine.setSystemProxyGuard((host, port) -> { });
        engine.setTunLauncher(launcher);
        ConnectionService connection = new ConnectionService(store, new SingBoxConfigGenerator(),
                new RoutingService(), engine);
        DefaultAppControlService control = new DefaultAppControlService(store, null, null,
                new RoutingService(), connection, null, null, engine);
        try {
            assertThat(connection.connect().started()).as("the launcher, at its prompt").isTrue();
            assertThat(atPrompt.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> control.connect("srv-2", "system_proxy", false))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("already running in tun mode, and nothing was changed");

            assertThat(List.of(store.getServerById("srv-1").orElseThrow().isActive(),
                            store.getSettings().getProxyMode()))
                    .as("[Tokyo picked, the saved mode]")
                    .containsExactly(true, ProxyMode.TUN);
        } finally {
            Files.createFile(consent);
            connection.disconnect();
            connection.getRecoveryService().close();
        }
    }

    @Test
    void anAgentsConnectAtTheSetupPromptIsRefusedAndChangesNothing(
            @TempDir(cleanup = CleanupMode.NEVER) Path dir) throws Exception {
        Path core = dir.resolve("sing-box");
        Files.writeString(core, "#!/bin/sh\n"
                + "[ \"$1\" = check ] && exit 0\n"
                + "echo 'sing-box started'\n"
                + "while true; do sleep 0.1; done\n");
        Files.setPosixFilePermissions(core, PosixFilePermissions.fromString("rwx------"));
        // Runs until it is told to stop, as a TUN wrapper does.
        Path wrapper = dir.resolve("tun-wrapper.sh");
        Files.writeString(wrapper, "#!/bin/sh\n"
                + "echo 'sing-box started'\n"
                + "while [ ! -f \"$1\" ]; do sleep 0.1; done\n");
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwx------"));
        CountDownLatch atSetup = new CountDownLatch(1);
        CountDownLatch setupAnswered = new CountDownLatch(1);
        List<String> launches = new CopyOnWriteArrayList<>();
        TunLauncher launcher = (binary, configFile, prompt) -> {
            launches.add(configFile.getFileName().toString());
            if (launches.size() == 1) {
                atSetup.countDown();
                try {
                    setupAnswered.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("called off");
                }
            }
            Path stop = dir.resolve("stop-" + UUID.randomUUID());
            Process process = new ProcessBuilder("/bin/sh", wrapper.toString(), stop.toString())
                    .redirectErrorStream(true).start();
            return new TunLauncher.Launched(process, stop, false);
        };
        ConfigStore store = new ConfigStore(dir.resolve("data"));
        store.getSettings().setHealthCheckEnabled(false);
        store.getSettings().setProxyMode(ProxyMode.TUN);
        store.addServer(server("srv-1", "Tokyo"));
        store.addServer(server("srv-2", "Osaka"));
        store.setActiveServer("srv-1");
        SingBoxEngine engine = new SingBoxEngine(core);
        engine.setSystemProxyGuard((host, port) -> { });
        engine.setTunLauncher(launcher);
        ConnectionService connection = new ConnectionService(store, new SingBoxConfigGenerator(),
                new RoutingService(), engine);
        DefaultAppControlService control = new DefaultAppControlService(store, null, null,
                new RoutingService(), connection, null, null, engine);
        try {
            AtomicReference<Object> user = new AtomicReference<>();
            Thread usersConnect = Thread.startVirtualThread(() -> {
                try {
                    user.set(connection.connect().outcome());
                } catch (IOException | RuntimeException e) {
                    user.set(e);
                }
            });
            assertThat(atSetup.await(10, TimeUnit.SECONDS))
                    .as("the user's start at its setup prompt").isTrue();
            assertThat(engine.isRunning()).as("a process during the setup prompt").isFalse();

            assertThatThrownBy(() -> control.connect("srv-2", "system_proxy", false))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("nothing was changed");

            setupAnswered.countDown();
            usersConnect.join(10_000);
            assertThat(user.get()).isEqualTo(ConnectionService.Outcome.STARTED);
            Await.until("connected", () -> FxExecutor.get(
                    () -> engine.connectionStateProperty().get()) == ConnectionState.CONNECTED,
                    Duration.ofSeconds(10));
            // A pick made meanwhile would be applied now, at Connected.
            Thread.sleep(500);
            assertThat(List.of(store.getServerById("srv-1").orElseThrow().isActive(),
                            store.getSettings().getProxyMode(),
                            String.valueOf(connection.appliedServerId())))
                    .as("[Tokyo picked, the saved mode, the server the core runs]")
                    .containsExactly(true, ProxyMode.TUN, "srv-1");
            assertThat(launches).as("TUN launches").hasSize(1);
        } finally {
            setupAnswered.countDown();
            connection.disconnect();
            connection.getRecoveryService().close();
        }
    }
}
