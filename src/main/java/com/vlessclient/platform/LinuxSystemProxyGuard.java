package com.vlessclient.platform;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Linux proxy guard over the two proxy stores sing-box's
 * {@code set_system_proxy} writes: GNOME's ({@code org.gnome.system.proxy}:
 * mode {@code manual} plus per-protocol host/port keys, via {@code gsettings})
 * and, in a KDE session, Plasma's ({@code kioslaverc}: {@code ProxyType=1}
 * plus per-protocol proxy URLs, via {@code kwriteconfig5} or
 * {@code kwriteconfig6}).
 *
 * <p>Only GNOME's was looked at, so on KDE a core that died left every KDE
 * application behind a dead proxy until the user found the setting. A
 * missing tool or schema just logs and returns.</p>
 */
public final class LinuxSystemProxyGuard implements SystemProxyGuard {

    private static final Logger log = LoggerFactory.getLogger(LinuxSystemProxyGuard.class);

    private static final String SCHEMA = "org.gnome.system.proxy";

    private static final String KDE_FILE = "kioslaverc";
    private static final String KDE_GROUP = "Proxy Settings";

    /** The keys of the proxies KDE applications use, one per protocol. */
    private static final List<String> KDE_PROXY_KEYS =
            List.of("httpProxy", "httpsProxy", "socksProxy");

    private final CommandRunner runner;
    private final boolean kdeSession;

    public LinuxSystemProxyGuard() {
        this(CommandRunner.system(), isKdeSession(System.getenv()));
    }

    LinuxSystemProxyGuard(CommandRunner runner) {
        this(runner, false);
    }

    LinuxSystemProxyGuard(CommandRunner runner, boolean kdeSession) {
        this.runner = runner;
        this.kdeSession = kdeSession;
    }

    /**
     * Whether the desktop is KDE's, as sing-box decides where to write the
     * proxy: {@code KDE_SESSION_VERSION}, or KDE in {@code XDG_CURRENT_DESKTOP}.
     */
    static boolean isKdeSession(Map<String, String> env) {
        String desktop = env.getOrDefault("XDG_CURRENT_DESKTOP", "");
        return env.containsKey("KDE_SESSION_VERSION")
                || desktop.toUpperCase(Locale.ROOT).contains("KDE");
    }

    @Override
    public void clearIfPointsAt(String host, int port) {
        clearGnomeIfPointsAt(host, port);
        if (kdeSession) {
            clearKdeIfPointsAt(host, port);
        }
    }

    private void clearGnomeIfPointsAt(String host, int port) {
        try {
            if (!proxyPointsAt(host, port)) {
                return;
            }
            CommandRunner.Result result = runner.run(List.of(
                    "gsettings", "set", SCHEMA, "mode", "none"));
            if (result.exitCode() != 0) {
                log.warn("Could not disable stale GNOME proxy: {}", result.output());
            } else {
                log.info("Disabled stale GNOME proxy {}:{} left by a dead core", host, port);
            }
        } catch (IOException e) {
            log.warn("System proxy guard failed (no gsettings?): {}", e.getMessage());
        }
    }

    /**
     * True when the GNOME proxy mode is {@code manual} and the http proxy
     * points at {@code host:port} — i.e. it is the entry sing-box registered,
     * not a user-configured proxy.
     */
    private boolean proxyPointsAt(String host, int port) throws IOException {
        CommandRunner.Result mode = runner.run(List.of(
                "gsettings", "get", SCHEMA, "mode"));
        if (mode.exitCode() != 0 || !mode.output().contains("manual")) {
            return false;
        }
        CommandRunner.Result httpHost = runner.run(List.of(
                "gsettings", "get", SCHEMA + ".http", "host"));
        CommandRunner.Result httpPort = runner.run(List.of(
                "gsettings", "get", SCHEMA + ".http", "port"));
        return httpHost.exitCode() == 0 && httpPort.exitCode() == 0
                && httpHost.output().contains(host)
                && httpPort.output().strip().equals(String.valueOf(port));
    }

    /**
     * Turns KDE's proxy off when it is on and one of its proxies points at
     * {@code host:port}. Plasma 6 ships {@code kreadconfig6}, Plasma 5
     * {@code kreadconfig5}; whichever answers is the one in use.
     */
    private void clearKdeIfPointsAt(String host, int port) {
        for (String version : List.of("6", "5")) {
            String read = "kreadconfig" + version;
            CommandRunner.Result type;
            try {
                type = runner.run(kdeRead(read, "ProxyType"));
            } catch (IOException notThisPlasma) {
                continue;
            }
            try {
                if (type.exitCode() != 0 || !"1".equals(type.output().strip())
                        || !kdeProxyPointsAt(read, host, port)) {
                    return;
                }
                CommandRunner.Result off = runner.run(List.of("kwriteconfig" + version,
                        "--file", KDE_FILE, "--group", KDE_GROUP, "--key", "ProxyType", "0"));
                if (off.exitCode() != 0) {
                    log.warn("Could not disable stale KDE proxy: {}", off.output());
                    return;
                }
                // Running KDE applications read the file again on this signal.
                runner.run(List.of("dbus-send", "--type=signal", "/KIO/Scheduler",
                        "org.kde.KIO.Scheduler.reparseSlaveConfiguration", "string:"));
                log.info("Disabled stale KDE proxy {}:{} left by a dead core", host, port);
            } catch (IOException e) {
                log.warn("KDE proxy guard failed: {}", e.getMessage());
            }
            return;
        }
    }

    private boolean kdeProxyPointsAt(String read, String host, int port) throws IOException {
        for (String key : KDE_PROXY_KEYS) {
            CommandRunner.Result value = runner.run(kdeRead(read, key));
            if (value.exitCode() == 0 && kdePointsAt(value.output(), host, port)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> kdeRead(String tool, String key) {
        return List.of(tool, "--file", KDE_FILE, "--group", KDE_GROUP, "--key", key);
    }

    /**
     * Whether a KDE proxy value names {@code host:port}: KDE's own settings
     * write {@code http://127.0.0.1 1081}, with a space, and sing-box writes
     * {@code http://127.0.0.1:1081}.
     */
    static boolean kdePointsAt(String value, String host, int port) {
        String address = value.strip();
        int scheme = address.indexOf("://");
        if (scheme >= 0) {
            address = address.substring(scheme + 3);
        }
        return address.equals(host + ":" + port) || address.equals(host + " " + port);
    }
}
