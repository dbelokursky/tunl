package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * Persisted application-wide preferences such as theme, language, local proxy
 * ports, proxy mode, DNS, TUN interface, and health-check settings.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppSettings {

    /**
     * Schema version of settings.json. Files written before versioning carry
     * no field and deserialize to this same value — today's format is v1.
     * Bump on the first incompatible change and migrate in
     * {@code ConfigStore.loadSettings} before the settings are used.
     */
    public static final int CURRENT_CONFIG_VERSION = 1;

    @JsonProperty("config_version")
    private int configVersion = CURRENT_CONFIG_VERSION;

    @JsonProperty("theme")
    private String theme = "auto";

    @JsonProperty("language")
    private String language = "en";

    @JsonProperty("auto_connect")
    private boolean autoConnect;

    @JsonProperty("last_server_id")
    private String lastServerId;

    @JsonProperty("socks_port")
    private int socksPort = 1080;

    @JsonProperty("http_port")
    private int httpPort = 1081;

    @JsonProperty("clash_api_port")
    private int clashApiPort = 9090;

    /**
     * Auth token for the local clash_api control endpoint, set into the
     * generated config's {@code clash_api.secret} and sent by TrafficMonitor.
     * {@code @JsonIgnore}: a runtime-only value that ConfigStore generates
     * fresh each app run and never writes to settings.json, so it neither
     * persists at rest nor lets another local user read traffic stats or
     * control the core via 127.0.0.1:{@code clash_api_port}.
     */
    @JsonIgnore
    private String clashApiSecret = "";

    @JsonProperty("proxy_mode")
    private ProxyMode proxyMode = ProxyMode.SYSTEM_PROXY;

    /**
     * Whether the active server carries the traffic, or the core picks among
     * all of them. Defaults to SINGLE so an existing install behaves exactly
     * as before until the user opts in.
     */
    @JsonProperty("server_selection")
    private ServerSelection serverSelection = ServerSelection.SINGLE;

    /**
     * In SYSTEM_PROXY mode, have sing-box register itself as the OS proxy on
     * connect (and restore the previous state on disconnect) instead of only
     * listening on the local ports.
     */
    @JsonProperty("system_proxy_auto_config")
    private boolean systemProxyAutoConfig = true;

    @JsonProperty("proxy_dns")
    private String proxyDns = "https://1.1.1.1/dns-query";

    @JsonProperty("direct_dns")
    private String directDns = "https://223.5.5.5/dns-query";

    @JsonProperty("dns_strategy")
    private String dnsStrategy = "prefer_ipv4";

    @JsonProperty("tun_interface_name")
    private String tunInterfaceName = defaultTunInterfaceName();

    /**
     * macOS requires TUN devices to be named {@code utunN}; Windows names the
     * wintun adapter freely, where a recognizable name beats a fake utun.
     */
    private static String defaultTunInterfaceName() {
        return com.vlessclient.platform.Platform.current().isWindows()
                ? "VlessClientTun"
                : "utun99";
    }

    @JsonProperty("tun_ipv4_address")
    private String tunIpv4Address = "172.19.0.1/30";

    @JsonProperty("health_check_enabled")
    private boolean healthCheckEnabled = true;

    @JsonProperty("health_check_auto_reconnect")
    private boolean healthCheckAutoReconnect = true;

    // How often the tunnel is re-probed while connected. Kept short so a dropped
    // connection is noticed quickly rather than only at connect time.
    @JsonProperty("health_check_interval_seconds")
    private int healthCheckIntervalSeconds = 5;

    @JsonProperty("health_check_delay_seconds")
    private int healthCheckDelaySeconds = 10;

    @JsonProperty("health_check_targets")
    private List<HealthCheckTarget> healthCheckTargets = defaultHealthCheckTargets();

    // Seal server credentials (Keychain / DPAPI / Secret Service) instead of
    // writing them into servers.json as plaintext. On platforms without a
    // usable backend the app silently keeps writing plaintext.
    @JsonProperty("store_secrets_securely")
    private boolean storeSecretsSecurely = true;

    /** Whether the local MCP control server is enabled. Off by default. */
    @JsonProperty("mcp_enabled")
    private boolean mcpEnabled;

    /** Loopback TCP port the MCP server listens on. High port to avoid clashes. */
    @JsonProperty("mcp_port")
    private int mcpPort = 55555;

    /**
     * Whether MCP tools that change state (add/edit servers, routing, connect)
     * are permitted. When {@code false} the server exposes read-only tools only.
     * The most dangerous actions (TUN connect, delete) always require an explicit
     * {@code confirm} argument regardless of this flag.
     */
    @JsonProperty("mcp_allow_mutations")
    private boolean mcpAllowMutations = true;

    public AppSettings() {
    }

    private static List<HealthCheckTarget> defaultHealthCheckTargets() {
        List<HealthCheckTarget> targets = new ArrayList<>();
        // generate_204 returns an empty 204 — the cheapest possible reachability ping.
        targets.add(new HealthCheckTarget("Google", "https://www.google.com/generate_204"));
        targets.add(new HealthCheckTarget("X", "https://x.com"));
        return targets;
    }

    public int getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(int configVersion) {
        this.configVersion = configVersion;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public boolean isAutoConnect() {
        return autoConnect;
    }

    public void setAutoConnect(boolean autoConnect) {
        this.autoConnect = autoConnect;
    }

    public String getLastServerId() {
        return lastServerId;
    }

    public void setLastServerId(String lastServerId) {
        this.lastServerId = lastServerId;
    }

    public int getSocksPort() {
        return socksPort;
    }

    public void setSocksPort(int socksPort) {
        this.socksPort = socksPort;
    }

    public int getHttpPort() {
        return httpPort;
    }

    public void setHttpPort(int httpPort) {
        this.httpPort = httpPort;
    }

    public int getClashApiPort() {
        return clashApiPort;
    }

    public void setClashApiPort(int clashApiPort) {
        this.clashApiPort = clashApiPort;
    }

    public String getClashApiSecret() {
        return clashApiSecret;
    }

    public void setClashApiSecret(String clashApiSecret) {
        this.clashApiSecret = clashApiSecret;
    }

    public ProxyMode getProxyMode() {
        return proxyMode;
    }

    public void setProxyMode(ProxyMode proxyMode) {
        this.proxyMode = proxyMode;
    }

    public ServerSelection getServerSelection() {
        return serverSelection;
    }

    public void setServerSelection(ServerSelection serverSelection) {
        this.serverSelection = serverSelection != null ? serverSelection : ServerSelection.SINGLE;
    }

    public boolean isSystemProxyAutoConfig() {
        return systemProxyAutoConfig;
    }

    public void setSystemProxyAutoConfig(boolean systemProxyAutoConfig) {
        this.systemProxyAutoConfig = systemProxyAutoConfig;
    }

    public String getProxyDns() {
        return proxyDns;
    }

    public void setProxyDns(String proxyDns) {
        this.proxyDns = proxyDns;
    }

    public String getDirectDns() {
        return directDns;
    }

    public void setDirectDns(String directDns) {
        this.directDns = directDns;
    }

    public String getDnsStrategy() {
        return dnsStrategy;
    }

    public void setDnsStrategy(String dnsStrategy) {
        this.dnsStrategy = dnsStrategy;
    }

    public String getTunInterfaceName() {
        return tunInterfaceName;
    }

    public void setTunInterfaceName(String tunInterfaceName) {
        this.tunInterfaceName = tunInterfaceName;
    }

    public String getTunIpv4Address() {
        return tunIpv4Address;
    }

    public void setTunIpv4Address(String tunIpv4Address) {
        this.tunIpv4Address = tunIpv4Address;
    }

    public boolean isHealthCheckEnabled() {
        return healthCheckEnabled;
    }

    public void setHealthCheckEnabled(boolean healthCheckEnabled) {
        this.healthCheckEnabled = healthCheckEnabled;
    }

    public boolean isHealthCheckAutoReconnect() {
        return healthCheckAutoReconnect;
    }

    public void setHealthCheckAutoReconnect(boolean healthCheckAutoReconnect) {
        this.healthCheckAutoReconnect = healthCheckAutoReconnect;
    }

    public boolean isStoreSecretsSecurely() {
        return storeSecretsSecurely;
    }

    public void setStoreSecretsSecurely(boolean storeSecretsSecurely) {
        this.storeSecretsSecurely = storeSecretsSecurely;
    }

    public int getHealthCheckIntervalSeconds() {
        return healthCheckIntervalSeconds;
    }

    public void setHealthCheckIntervalSeconds(int healthCheckIntervalSeconds) {
        this.healthCheckIntervalSeconds = healthCheckIntervalSeconds;
    }

    public int getHealthCheckDelaySeconds() {
        return healthCheckDelaySeconds;
    }

    public void setHealthCheckDelaySeconds(int healthCheckDelaySeconds) {
        this.healthCheckDelaySeconds = healthCheckDelaySeconds;
    }

    public List<HealthCheckTarget> getHealthCheckTargets() {
        return healthCheckTargets;
    }

    public void setHealthCheckTargets(List<HealthCheckTarget> healthCheckTargets) {
        this.healthCheckTargets = healthCheckTargets;
    }

    public boolean isMcpEnabled() {
        return mcpEnabled;
    }

    public void setMcpEnabled(boolean mcpEnabled) {
        this.mcpEnabled = mcpEnabled;
    }

    public int getMcpPort() {
        return mcpPort;
    }

    public void setMcpPort(int mcpPort) {
        this.mcpPort = mcpPort;
    }

    public boolean isMcpAllowMutations() {
        return mcpAllowMutations;
    }

    public void setMcpAllowMutations(boolean mcpAllowMutations) {
        this.mcpAllowMutations = mcpAllowMutations;
    }
}
