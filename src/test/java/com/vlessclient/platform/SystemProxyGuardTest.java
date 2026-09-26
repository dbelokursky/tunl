package com.vlessclient.platform;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SystemProxyGuardTest {

    /** Records every invocation and replies via a programmed responder. */
    private static final class FakeRunner implements CommandRunner {
        final List<List<String>> calls = new ArrayList<>();
        private final Function<List<String>, CommandRunner.Result> responder;

        FakeRunner(Function<List<String>, CommandRunner.Result> responder) {
            this.responder = responder;
        }

        @Override
        public CommandRunner.Result run(List<String> command) {
            calls.add(List.copyOf(command));
            return responder.apply(command);
        }
    }

    private static CommandRunner.Result ok(String output) {
        return new CommandRunner.Result(0, output);
    }

    @Nested
    class WindowsGuard {

        private static final String ENABLED_ON =
                "    ProxyEnable    REG_DWORD    0x1";
        private static final String SERVER_OURS =
                "    ProxyServer    REG_SZ    127.0.0.1:1081";
        private static final String SERVER_CORPORATE =
                "    ProxyServer    REG_SZ    proxy.corp.example:8080";

        private static FakeRunner registry(String enableValue, String serverValue) {
            return new FakeRunner(cmd -> {
                if (cmd.contains("ProxyEnable") && cmd.contains("query")) {
                    return ok(enableValue);
                }
                if (cmd.contains("ProxyServer")) {
                    return ok(serverValue);
                }
                return ok("");
            });
        }

        @Test
        void disablesProxyWhenItPointsAtOurInbound() {
            FakeRunner reg = registry(ENABLED_ON, SERVER_OURS);

            new WindowsSystemProxyGuard(reg).clearIfPointsAt("127.0.0.1", 1081);

            List<String> last = reg.calls.getLast();
            assertThat(last).containsSequence("reg", "add");
            assertThat(last).containsSequence("/v", "ProxyEnable");
            assertThat(last).containsSequence("/d", "0");
        }

        @Test
        void leavesForeignProxyAlone() {
            FakeRunner reg = registry(ENABLED_ON, SERVER_CORPORATE);

            new WindowsSystemProxyGuard(reg).clearIfPointsAt("127.0.0.1", 1081);

            assertThat(reg.calls).noneMatch(cmd -> cmd.contains("add"));
        }

        @Test
        void doesNothingWhenProxyAlreadyDisabled() {
            FakeRunner reg = registry("    ProxyEnable    REG_DWORD    0x0", SERVER_OURS);

            new WindowsSystemProxyGuard(reg).clearIfPointsAt("127.0.0.1", 1081);

            // Only the ProxyEnable query ran; no ProxyServer read, no write.
            assertThat(reg.calls).hasSize(1);
        }

        @Test
        void neverThrowsWhenRegFails() {
            CommandRunner failing = cmd -> {
                throw new IOException("reg unavailable");
            };

            assertThatCode(() ->
                    new WindowsSystemProxyGuard(failing).clearIfPointsAt("127.0.0.1", 1081))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class LinuxGuard {

        private static FakeRunner gnome(String mode, String host, String port) {
            return new FakeRunner(cmd -> {
                if (cmd.contains("get") && cmd.contains("mode")) {
                    return ok(mode);
                }
                if (cmd.contains("get") && cmd.contains("host")) {
                    return ok(host);
                }
                if (cmd.contains("get") && cmd.contains("port")) {
                    return ok(port);
                }
                return ok("");
            });
        }

        @Test
        void disablesProxyWhenItPointsAtOurInbound() {
            FakeRunner gs = gnome("'manual'", "'127.0.0.1'", "1081");

            new LinuxSystemProxyGuard(gs).clearIfPointsAt("127.0.0.1", 1081);

            List<String> last = gs.calls.getLast();
            assertThat(last).containsSequence("gsettings", "set",
                    "org.gnome.system.proxy", "mode", "none");
        }

        @Test
        void leavesForeignProxyAlone() {
            FakeRunner gs = gnome("'manual'", "'proxy.corp.example'", "8080");

            new LinuxSystemProxyGuard(gs).clearIfPointsAt("127.0.0.1", 1081);

            assertThat(gs.calls).noneMatch(cmd -> cmd.contains("set"));
        }

        @Test
        void doesNothingWhenModeIsNone() {
            FakeRunner gs = gnome("'none'", "''", "0");

            new LinuxSystemProxyGuard(gs).clearIfPointsAt("127.0.0.1", 1081);

            // Only the mode query ran; host/port never read, no write.
            assertThat(gs.calls).hasSize(1);
        }

        @Test
        void neverThrowsWhenGsettingsMissing() {
            CommandRunner failing = cmd -> {
                throw new IOException("gsettings not found");
            };

            assertThatCode(() ->
                    new LinuxSystemProxyGuard(failing).clearIfPointsAt("127.0.0.1", 1081))
                    .doesNotThrowAnyException();
        }
    }

    /**
     * sing-box writes KDE's proxy too, in a KDE session, and only GNOME's was
     * looked at: after a dead core every KDE application stayed behind a
     * proxy that no longer answered.
     */
    @Nested
    class KdeGuard {

        /** A KDE whose kreadconfig answers as given; kreadconfig6 missing unless plasma6. */
        private static FakeRunner kde(boolean plasma6, String proxyType, String key,
                                      String value) {
            return new FakeRunner(cmd -> {
                String tool = cmd.getFirst();
                if (tool.equals("kreadconfig6") && !plasma6) {
                    throw new java.io.UncheckedIOException(new IOException("not found"));
                }
                if (tool.startsWith("kreadconfig") && cmd.contains("ProxyType")) {
                    return ok(proxyType);
                }
                if (tool.startsWith("kreadconfig") && cmd.contains(key)) {
                    return ok(value);
                }
                return ok("");
            });
        }

        /** The fake throws unchecked; the guard sees the IOException a missing tool gives. */
        private static CommandRunner unwrapping(FakeRunner fake) {
            return cmd -> {
                try {
                    return fake.run(cmd);
                } catch (java.io.UncheckedIOException e) {
                    throw e.getCause();
                }
            };
        }

        @Test
        void turnsOffAKdeProxyPointingAtOurInbound() {
            FakeRunner fake = kde(true, "1", "httpProxy", "http://127.0.0.1:1081");

            new LinuxSystemProxyGuard(unwrapping(fake), true).clearIfPointsAt("127.0.0.1", 1081);

            assertThat(fake.calls).anySatisfy(cmd -> assertThat(cmd).containsExactly(
                    "kwriteconfig6", "--file", "kioslaverc", "--group", "Proxy Settings",
                    "--key", "ProxyType", "0"));
            assertThat(fake.calls.getLast()).startsWith("dbus-send");
        }

        @Test
        void readsKdesOwnFormWithASpace() {
            FakeRunner fake = kde(true, "1", "socksProxy", "socks://127.0.0.1 1081");

            new LinuxSystemProxyGuard(unwrapping(fake), true).clearIfPointsAt("127.0.0.1", 1081);

            assertThat(fake.calls).anyMatch(cmd -> cmd.getFirst().equals("kwriteconfig6"));
        }

        @Test
        void usesPlasma5sToolsWhenPlasma6sAreMissing() {
            FakeRunner fake = kde(false, "1", "httpProxy", "http://127.0.0.1:1081");

            new LinuxSystemProxyGuard(unwrapping(fake), true).clearIfPointsAt("127.0.0.1", 1081);

            assertThat(fake.calls).anyMatch(cmd -> cmd.getFirst().equals("kwriteconfig5"));
            assertThat(fake.calls).noneMatch(cmd -> cmd.getFirst().equals("kwriteconfig6"));
        }

        @Test
        void leavesAForeignKdeProxyAlone() {
            FakeRunner fake = kde(true, "1", "httpProxy", "http://proxy.corp.example:8080");

            new LinuxSystemProxyGuard(unwrapping(fake), true).clearIfPointsAt("127.0.0.1", 1081);

            assertThat(fake.calls).noneMatch(cmd -> cmd.getFirst().startsWith("kwriteconfig"));
        }

        @Test
        void leavesKdeAloneOutsideAKdeSession() {
            FakeRunner fake = kde(true, "1", "httpProxy", "http://127.0.0.1:1081");

            new LinuxSystemProxyGuard(unwrapping(fake), false).clearIfPointsAt("127.0.0.1", 1081);

            assertThat(fake.calls).noneMatch(cmd -> cmd.getFirst().startsWith("k"));
        }

        @Test
        void aKdeSessionIsTheOneSingBoxWritesKdesProxyIn() {
            assertThat(LinuxSystemProxyGuard.isKdeSession(
                    java.util.Map.of("KDE_SESSION_VERSION", "6"))).isTrue();
            assertThat(LinuxSystemProxyGuard.isKdeSession(
                    java.util.Map.of("XDG_CURRENT_DESKTOP", "KDE"))).isTrue();
            assertThat(LinuxSystemProxyGuard.isKdeSession(
                    java.util.Map.of("XDG_CURRENT_DESKTOP", "ubuntu:GNOME"))).isFalse();
        }
    }

    @Nested
    class MacGuard {

        private static final String SERVICES = """
                An asterisk (*) denotes that a network service is disabled.
                Wi-Fi
                *Thunderbolt Bridge
                """;

        private static String proxyReport(boolean enabled, String host, int port) {
            return "Enabled: " + (enabled ? "Yes" : "No") + "\n"
                    + "Server: " + host + "\n"
                    + "Port: " + port + "\n";
        }

        @Test
        void disablesEveryProxyTypePointingAtOurInbound() {
            FakeRunner ns = new FakeRunner(cmd -> {
                if (cmd.contains("-listallnetworkservices")) {
                    return ok(SERVICES);
                }
                if (cmd.stream().anyMatch(a -> a.startsWith("-get"))) {
                    return ok(proxyReport(true, "127.0.0.1", 1081));
                }
                return ok("");
            });

            new MacSystemProxyGuard(ns).clearIfPointsAt("127.0.0.1", 1081);

            List<List<String>> offCalls = ns.calls.stream()
                    .filter(cmd -> cmd.contains("off"))
                    .toList();
            assertThat(offCalls).hasSize(3);
            assertThat(offCalls).allMatch(cmd -> cmd.contains("Wi-Fi"));
            assertThat(offCalls.stream().map(cmd -> cmd.get(1)))
                    .containsExactlyInAnyOrder("-setwebproxystate",
                            "-setsecurewebproxystate", "-setsocksfirewallproxystate");
        }

        @Test
        void skipsDisabledServicesAndForeignProxies() {
            FakeRunner ns = new FakeRunner(cmd -> {
                if (cmd.contains("-listallnetworkservices")) {
                    return ok(SERVICES);
                }
                if (cmd.stream().anyMatch(a -> a.startsWith("-get"))) {
                    return ok(proxyReport(true, "proxy.corp.example", 8080));
                }
                return ok("");
            });

            new MacSystemProxyGuard(ns).clearIfPointsAt("127.0.0.1", 1081);

            assertThat(ns.calls).noneMatch(cmd -> cmd.contains("off"));
            // Disabled service (*Thunderbolt Bridge) is never queried.
            assertThat(ns.calls).noneMatch(cmd -> cmd.contains("Thunderbolt Bridge"));
        }

        @Test
        void ignoresProxyThatIsConfiguredButOff() {
            FakeRunner ns = new FakeRunner(cmd -> {
                if (cmd.contains("-listallnetworkservices")) {
                    return ok(SERVICES);
                }
                if (cmd.stream().anyMatch(a -> a.startsWith("-get"))) {
                    return ok(proxyReport(false, "127.0.0.1", 1081));
                }
                return ok("");
            });

            new MacSystemProxyGuard(ns).clearIfPointsAt("127.0.0.1", 1081);

            assertThat(ns.calls).noneMatch(cmd -> cmd.contains("off"));
        }

        @Test
        void neverThrowsWhenNetworksetupFails() {
            CommandRunner failing = cmd -> {
                throw new IOException("networksetup unavailable");
            };

            assertThatCode(() ->
                    new MacSystemProxyGuard(failing).clearIfPointsAt("127.0.0.1", 1081))
                    .doesNotThrowAnyException();
        }
    }
}
