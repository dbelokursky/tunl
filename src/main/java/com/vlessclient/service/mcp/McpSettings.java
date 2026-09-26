package com.vlessclient.service.mcp;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.CoreLogLevel;
import com.vlessclient.model.ServerSelection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The settings {@code set_setting} changes, one entry per key.
 *
 * <p>The keys used to be listed by hand four times over: in the setter's
 * switch, in its error text, in the tool's description and, for reading, in
 * {@link SettingsInfo}. They drifted: {@code tun_ipv6_enabled} could be set
 * but was never reported back, and {@code server_selection},
 * {@code tun_ipv4_address} and {@code system_proxy_auto_config} could be
 * neither set nor read. The table is now what the setter, its error text and
 * the description all read, and a test holds {@link SettingsInfo} and every
 * persisted setting to it.</p>
 */
final class McpSettings {

    /** Applies one JSON value to one setting, or says why it cannot. */
    @FunctionalInterface
    private interface Writer {
        void write(AppSettings settings, JsonNode value, String key) throws McpToolException;
    }

    private static final Map<String, Writer> WRITERS = new LinkedHashMap<>();

    /** The themes, as the Settings screen offers them. */
    private static final List<String> THEMES = List.of("auto", "light", "dark");

    /** The languages the app has. */
    private static final List<String> LANGUAGES = List.of("en", "ru");

    static {
        WRITERS.put("theme", (s, v, k) -> s.setTheme(theme(v, k)));
        WRITERS.put("language", (s, v, k) -> s.setLanguage(oneOf(v, k, LANGUAGES)));
        WRITERS.put("auto_connect", (s, v, k) -> s.setAutoConnect(flag(v, k)));
        WRITERS.put("socks_port", (s, v, k) -> s.setSocksPort(port(v, k)));
        WRITERS.put("http_port", (s, v, k) -> s.setHttpPort(port(v, k)));
        WRITERS.put("clash_api_port", (s, v, k) -> s.setClashApiPort(port(v, k)));
        WRITERS.put("server_selection", (s, v, k) -> s.setServerSelection(selection(v, k)));
        WRITERS.put("proxy_dns", (s, v, k) -> s.setProxyDns(text(v, k)));
        WRITERS.put("direct_dns", (s, v, k) -> s.setDirectDns(text(v, k)));
        WRITERS.put("dns_strategy",
                (s, v, k) -> s.setDnsStrategy(oneOf(v, k, AppSettings.DNS_STRATEGIES)));
        WRITERS.put("system_proxy_auto_config",
                (s, v, k) -> s.setSystemProxyAutoConfig(flag(v, k)));
        WRITERS.put("tun_interface_name", (s, v, k) -> s.setTunInterfaceName(text(v, k)));
        WRITERS.put("tun_ipv4_address", (s, v, k) -> s.setTunIpv4Address(tunCidr(v, k)));
        WRITERS.put("tun_ipv6_enabled", (s, v, k) -> s.setTunIpv6Enabled(flag(v, k)));
        WRITERS.put("core_log_level", (s, v, k) -> s.setCoreLogLevel(logLevel(v, k)));
        WRITERS.put("health_check_enabled", (s, v, k) -> s.setHealthCheckEnabled(flag(v, k)));
        WRITERS.put("mcp_allow_mutations", (s, v, k) -> s.setMcpAllowMutations(flag(v, k)));
    }

    private McpSettings() {
    }

    /** The keys {@code set_setting} takes, in the order they are listed to agents. */
    static Set<String> writableKeys() {
        return Collections.unmodifiableSet(WRITERS.keySet());
    }

    /**
     * Applies {@code value} to the setting {@code key} names. Keys are matched
     * ignoring case and underscores, so {@code autoConnect} and
     * {@code auto_connect} are the same key, as they always were.
     *
     * @throws McpToolException for an unknown key or a value the setting refuses
     */
    static void apply(AppSettings settings, String key, JsonNode value)
            throws McpToolException {
        String wanted = normalized(key);
        for (Map.Entry<String, Writer> entry : WRITERS.entrySet()) {
            if (normalized(entry.getKey()).equals(wanted)) {
                entry.getValue().write(settings, value, entry.getKey());
                return;
            }
        }
        throw new McpToolException("Setting '" + key + "' is not settable via MCP. Allowed: "
                + String.join(", ", WRITERS.keySet()) + ". (mcp_enabled/mcp_port require the "
                + "Settings screen — they restart the server; proxy_mode has set_proxy_mode.)");
    }

    private static String normalized(String key) {
        return key.replace("_", "").toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode value, String key) throws McpToolException {
        if (!value.isString()) {
            throw new McpToolException("Setting '" + key + "' expects a string.");
        }
        return value.asString();
    }

    /**
     * A JSON boolean, or the string {@code "true"} or {@code "false"}.
     * {@code asBoolean} read anything else as false, so {@code "yes"} or
     * {@code "on"} switched a setting off and reported success.
     */
    private static boolean flag(JsonNode value, String key) throws McpToolException {
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isString()) {
            String raw = value.asString().trim();
            if (raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false")) {
                return Boolean.parseBoolean(raw.toLowerCase(Locale.ROOT));
            }
        }
        throw new McpToolException("Setting '" + key + "' expects true or false.");
    }

    /**
     * One of {@code allowed}, in any case. A theme, language or DNS strategy
     * outside them was stored as it came: the theme and language fell back at
     * the next start without a word, and the strategy stopped the core.
     */
    private static String oneOf(JsonNode value, String key, List<String> allowed)
            throws McpToolException {
        String raw = text(value, key).strip().toLowerCase(Locale.ROOT);
        if (!allowed.contains(raw)) {
            throw new McpToolException("Setting '" + key + "' expects one of: "
                    + String.join(", ", allowed) + ".");
        }
        return raw;
    }

    /** A theme; the legacy {@code system} is {@code auto}, as it is at startup. */
    private static String theme(JsonNode value, String key) throws McpToolException {
        if (value.isString() && "system".equalsIgnoreCase(value.asString().strip())) {
            return "auto";
        }
        return oneOf(value, key, THEMES);
    }

    private static int port(JsonNode value, String key) throws McpToolException {
        if (!value.isInt() || value.asInt() < 1 || value.asInt() > 65535) {
            throw new McpToolException("Setting '" + key + "' expects a port (1-65535).");
        }
        return value.asInt();
    }

    /**
     * Unlike {@link CoreLogLevel#fromValue(String)}, which forgives an unknown
     * value so a settings file can never block start-up, this rejects one: an
     * agent that asked for {@code verbose} and was silently given {@code info}
     * would go on to read a log that does not hold what it went looking for.
     */
    private static CoreLogLevel logLevel(JsonNode value, String key) throws McpToolException {
        String raw = text(value, key).trim();
        for (CoreLogLevel level : CoreLogLevel.values()) {
            if (level.getValue().equalsIgnoreCase(raw)) {
                return level;
            }
        }
        throw new McpToolException("Setting '" + key
                + "' expects one of: debug, info, warn, error.");
    }

    /** Rejects an unknown mode for the same reason as {@link #logLevel}. */
    private static ServerSelection selection(JsonNode value, String key)
            throws McpToolException {
        String raw = text(value, key).trim();
        for (ServerSelection mode : ServerSelection.values()) {
            if (mode.getValue().equalsIgnoreCase(raw)) {
                return mode;
            }
        }
        throw new McpToolException("Setting '" + key + "' expects one of: "
                + String.join(", ", java.util.Arrays.stream(ServerSelection.values())
                        .map(ServerSelection::getValue).toList()) + ".");
    }

    /**
     * An IPv4 network for the TUN device, such as {@code 172.19.0.1/30}: an
     * address the core cannot parse fails every TUN start, and a prefix longer
     * than /30 leaves no room for the peer.
     */
    private static String tunCidr(JsonNode value, String key) throws McpToolException {
        String raw = text(value, key).trim();
        int slash = raw.indexOf('/');
        boolean valid = false;
        if (slash > 0) {
            try {
                InetAddress address = InetAddress.ofLiteral(raw.substring(0, slash));
                int prefix = Integer.parseInt(raw.substring(slash + 1));
                valid = address instanceof Inet4Address && prefix >= 8 && prefix <= 30;
            } catch (IllegalArgumentException notAnAddressOrPrefix) {
                valid = false;
            }
        }
        if (!valid) {
            throw new McpToolException("Setting '" + key
                    + "' expects an IPv4 network such as 172.19.0.1/30 (prefix 8-30).");
        }
        return raw;
    }
}
