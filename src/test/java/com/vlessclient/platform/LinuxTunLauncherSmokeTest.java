package com.vlessclient.platform;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.SingBoxConfigGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Linux-only smoke: both {@link LinuxTunLauncher} paths against the REAL
 * bundled sing-box on a real kernel. CI runners have promptless sudo, which
 * doubles as the capability granter (fast path) and as a stand-in for
 * PolicyKit's pkexec (fallback path, no auth agent on CI). Dev boxes without
 * non-interactive sudo skip.
 *
 * <p>Probes run with {@code auto_route} off so the host's routing table is
 * never touched.</p>
 */
@Tag("smoke")
@EnabledOnOs(OS.LINUX)
class LinuxTunLauncherSmokeTest {

    private static Path binary;
    private static final SingBoxConfigGenerator generator = new SingBoxConfigGenerator();

    @BeforeAll
    static void locateBundledBinary() {
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String arch = osArch.contains("aarch64") || osArch.contains("arm64")
                ? "arm64" : "amd64";
        CorePlatform core = CorePlatform.current();
        binary = Path.of("target", "classes", "native",
                        core.osKey() + "-" + arch, core.binaryName())
                .toAbsolutePath();
        assumeTrue(Files.isExecutable(binary),
                "bundled sing-box not found at " + binary
                        + " — run the generate-resources phase first");
        assumeTrue(promptlessSudo(),
                "needs non-interactive sudo (CI runner) for the TUN probes");
    }

    /**
     * Fast path: after a one-time {@code setcap cap_net_admin+ep}, the core
     * creates its TUN adapter as a plain user process — the launcher takes
     * the promptless direct path and the stop-file contract shuts it down
     * gracefully.
     */
    @Test
    void fastPath_capabilityGrantsTunWithoutRoot() throws Exception {
        // A private copy so the xattr never leaks into the build output.
        Path capBinary = Files.createTempFile("sing-box-cap-", "");
        Files.copy(binary, capBinary, StandardCopyOption.REPLACE_EXISTING);
        assertThat(capBinary.toFile().setExecutable(true)).isTrue();
        Process grant = new ProcessBuilder(
                "sudo", "-n", "setcap", "cap_net_admin+ep", capBinary.toString()).start();
        assertThat(grant.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(grant.exitValue()).isZero();

        int clashPort = freePort();
        Path configFile = Files.createTempFile("smoke-tun-cap-", ".json");
        Files.writeString(configFile, tunProbeConfig(clashPort));

        LinuxTunLauncher launcher = new LinuxTunLauncher();
        assertThat(launcher.hasNetAdminCapability(capBinary)).isTrue();

        TunLauncher.Launched launched = launcher.launch(capBinary, configFile);
        try {
            awaitPort(clashPort, launched.process());

            Files.createFile(launched.stopSignalFile());
            assertThat(launched.process().waitFor(15, TimeUnit.SECONDS))
                    .as("wrapper exits after the stop file appears")
                    .isTrue();
            assertThat(launched.process().exitValue()).isZero();
        } finally {
            launched.process().destroyForcibly();
            Files.deleteIfExists(configFile);
            Files.deleteIfExists(capBinary);
            Files.deleteIfExists(launched.stopSignalFile());
        }
    }

    /**
     * Fallback path: capability check forced negative and the grant refused,
     * so the launcher elevates the whole wrapper. Proves the elevated wrapper
     * streams logs through the pipes and honors the stop file.
     */
    @Test
    void fallback_elevatedWrapperFullCycle() throws Exception {
        // getcap "fails" and the setcap grant "is declined": fallback path.
        LinuxTunLauncher launcher = new LinuxTunLauncher(
                cmd -> new CommandRunner.Result(1, "refused"), "sudo");

        int clashPort = freePort();
        Path configFile = Files.createTempFile("smoke-tun-sudo-", ".json");
        Files.writeString(configFile, tunProbeConfig(clashPort));

        TunLauncher.Launched launched = launcher.launch(binary, configFile);
        try {
            awaitPort(clashPort, launched.process());

            Files.createFile(launched.stopSignalFile());
            assertThat(launched.process().waitFor(15, TimeUnit.SECONDS))
                    .as("elevated wrapper exits after the stop file appears")
                    .isTrue();
        } finally {
            launched.process().destroyForcibly();
            Files.deleteIfExists(configFile);
            Files.deleteIfExists(launched.stopSignalFile());
        }
    }

    // ===== helpers =====

    private static boolean promptlessSudo() {
        try {
            Process p = new ProcessBuilder("sudo", "-n", "true").start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** TUN config with auto_route off so probes never touch the host routing. */
    private static String tunProbeConfig(int clashPort) throws Exception {
        AppSettings settings = new AppSettings();
        settings.setProxyMode(ProxyMode.TUN);
        settings.setSocksPort(freePort());
        settings.setHttpPort(freePort());
        settings.setClashApiPort(clashPort);

        ServerConfig server = new ServerConfig();
        server.setProtocol(Protocol.VLESS);
        server.setAddress("203.0.113.10");
        server.setPort(443);
        server.setUuid("b1c2d3e4-f5a6-7890-abcd-ef1234567890");
        server.getTls().setEnabled(true);
        server.getTls().setServerName("example.com");

        tools.jackson.databind.ObjectMapper mapper =
                tools.jackson.databind.json.JsonMapper.builder().build();
        tools.jackson.databind.node.ObjectNode config = (tools.jackson.databind.node.ObjectNode)
                mapper.readTree(generator.generate(server, settings));
        for (tools.jackson.databind.JsonNode inbound : config.get("inbounds")) {
            if ("tun".equals(inbound.path("type").asString())) {
                ((tools.jackson.databind.node.ObjectNode) inbound)
                        .put("auto_route", false);
            }
        }
        return mapper.writeValueAsString(config);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Waits until the port accepts connections; fails fast if the process dies. */
    private static void awaitPort(int port, Process proc) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        StringBuilder output = new StringBuilder();
        byte[] buf = new byte[8192];
        while (System.currentTimeMillis() < deadline) {
            // Drain stdout so the wrapper never blocks on a full pipe and the
            // failure message carries the core's log lines.
            int available = proc.getInputStream().available();
            if (available > 0) {
                int read = proc.getInputStream().read(buf, 0, Math.min(available, buf.length));
                if (read > 0) {
                    output.append(new String(buf, 0, read));
                }
            }
            if (!proc.isAlive()) {
                throw new AssertionError("TUN launch chain exited early (code "
                        + proc.exitValue() + "):\n" + output);
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (IOException retry) {
                Thread.sleep(200);
            }
        }
        throw new AssertionError("clash_api port " + port + " did not open within 15s:\n" + output);
    }
}
