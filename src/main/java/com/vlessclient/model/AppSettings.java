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
public class AppSettings extends KeepsUnknownFields {

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
     * Verbosity the sing-box core runs at. Defaults to INFO, which is the
     * level the generated config hardcoded before this was configurable, so a
     * settings file written by an older build produces byte-identical output.
     */
    @JsonProperty("core_log_level")
    private CoreLogLevel coreLogLevel = CoreLogLevel.INFO;

    /**
     * In SYSTEM_PROXY mode, have sing-box register itself as the OS proxy on
     * connect (and restore the previous state on disconnect) instead of only
     * listening on the local ports.
     */
    @JsonProperty("system_proxy_auto_config")
    private boolean systemProxyAutoConfig = true;

    /**
     * Whether the dashboard's traffic-history panel is expanded. Persisted so
     * the card comes back the way it was left: the panel is opened from a
     * small line of text, and re-finding that click on every launch is the
     * kind of friction that makes a feature go unused.
     */
    @JsonProperty("traffic_history_expanded")
    private boolean trafficHistoryExpanded;

    @JsonProperty("proxy_dns")
    private String proxyDns = "https://1.1.1.1/dns-query";

    /**
     * The direct DNS value that means the operating system's resolver: the
     * one the network the machine is on offers, which is what the sites the
     * rules send direct are resolved by anyway, from the same address.
     */
    public static final String SYSTEM_DNS = "system";

    /**
     * Direct DNS until 1.22: AliDNS over DoH. Every name the rules send
     * direct (a .ru bank, Gosuslugi, Yandex) went from the user's real
     * address to Alibaba's resolver, answers came from its view of the CDNs,
     * and where DoH to it was blocked those sites stopped resolving. Settings
     * still carrying it move to {@link #SYSTEM_DNS}.
     */
    public static final String RETIRED_DIRECT_DNS_DEFAULT = "https://223.5.5.5/dns-query";

    @JsonProperty("direct_dns")
    private String directDns = SYSTEM_DNS;

    /**
     * The DNS strategies the core takes, the default first. A value outside
     * them stopped the core at startup, whichever server was picked.
     */
    public static final List<String> DNS_STRATEGIES =
            List.of("prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only");

    @JsonProperty("dns_strategy")
    private String dnsStrategy = DNS_STRATEGIES.getFirst();

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

    /**
     * Whether the TUN device also takes an IPv6 address, so auto_route
     * captures IPv6 traffic. Off, IPv6 destinations on a dual-stack network
     * bypassed the tunnel entirely while the card said "Connected".
     */
    @JsonProperty("tun_ipv6_enabled")
    private boolean tunIpv6Enabled = true;

    /**
     * Whether other programs may use the local SOCKS and HTTP proxies in TUN
     * mode. Off, the proxies ask for a password only the app knows, as the
     * tunnel carries every program's traffic already.
     */
    @JsonProperty("share_local_proxy_in_tun")
    private boolean shareLocalProxyInTun;

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

    /**
     * A random id this install sends to subscription providers as
     * {@code x-hwid}, so a panel that limits devices per plan serves it. Not
     * taken from the hardware; a new one can be drawn in Settings, which a
     * provider then counts as a new device. Drawn by ConfigStore#deviceId the
     * first time it is needed.
     */
    @JsonProperty("device_id")
    private String deviceId;

    /** Whether the local MCP control server is enabled. Off by default. */
    @JsonProperty("mcp_enabled")
    private boolean mcpEnabled;

    /** Loopback TCP port the MCP server listens on. High port to avoid clashes. */
    @JsonProperty("mcp_port")
    private int mcpPort = 55555;

    /**
     * Whether MCP tools that change state (add/edit servers, routing, connect)
     * are permitted. When {@code false} the server exposes read-only tools only.
     *
     * <p>Off by default. An agent can be steered by what it reads, and these
     * tools let it add a server and send all traffic through it. The
     * {@code confirm} argument that TUN connect and delete require is supplied
     * by the agent itself, so it catches a slip, not that. A settings file that
     * already allows changes keeps its value.</p>
     */
    @JsonProperty("mcp_allow_mutations")
    private boolean mcpAllowMutations;

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

    /**
     * The UI language for a system locale: Russian for a Russian system,
     * English for any other, since the app has those two.
     *
     * @param locale the system's locale
     * @return {@code "ru"} or {@code "en"}
     */
    public static String languageFor(java.util.Locale locale) {
        return locale != null && "ru".equals(locale.getLanguage()) ? "ru" : "en";
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

    /**
     * Ports this run listens on in place of chosen ones another program held,
     * or null where the run uses the chosen port. Runtime only: settings are
     * saved whole, and a moved port written into the chosen one became the
     * user's choice at the next save of anything. The file keeps what the user
     * chose, and the next start tries it again.
     *
     * <p>Plain fields, like every other one here: the connect thread sets them
     * before the core starts, and the readers get to them through the same
     * hand-offs that carry the rest of the settings between threads. A
     * volatile field makes SpotBugs treat the whole class as shared state and
     * flag each of its primitive setters.</p>
     */
    @JsonIgnore
    private Integer runSocksPort;
    @JsonIgnore
    private Integer runHttpPort;
    @JsonIgnore
    private Integer runClashApiPort;

    /** The SOCKS port this run listens on: the chosen one, or where it moved. */
    public int listenSocksPort() {
        Integer run = runSocksPort;
        return run != null ? run : socksPort;
    }

    /** The HTTP port this run listens on: the chosen one, or where it moved. */
    public int listenHttpPort() {
        Integer run = runHttpPort;
        return run != null ? run : httpPort;
    }

    /** The control port this run listens on: the chosen one, or where it moved. */
    public int listenClashApiPort() {
        Integer run = runClashApiPort;
        return run != null ? run : clashApiPort;
    }

    /**
     * Sets the ports this run listens on, leaving the chosen ones as they are.
     *
     * @param socks    the SOCKS port for the run
     * @param http     the HTTP port for the run
     * @param clashApi the control port for the run
     */
    public void listenOn(int socks, int http, int clashApi) {
        runSocksPort = socks == socksPort ? null : socks;
        runHttpPort = http == httpPort ? null : http;
        runClashApiPort = clashApi == clashApiPort ? null : clashApi;
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

    public CoreLogLevel getCoreLogLevel() {
        return coreLogLevel;
    }

    public void setCoreLogLevel(CoreLogLevel coreLogLevel) {
        this.coreLogLevel = coreLogLevel != null ? coreLogLevel : CoreLogLevel.INFO;
    }

    public boolean isSystemProxyAutoConfig() {
        return systemProxyAutoConfig;
    }

    public void setSystemProxyAutoConfig(boolean systemProxyAutoConfig) {
        this.systemProxyAutoConfig = systemProxyAutoConfig;
    }

    public boolean isTrafficHistoryExpanded() {
        return trafficHistoryExpanded;
    }

    public void setTrafficHistoryExpanded(boolean trafficHistoryExpanded) {
        this.trafficHistoryExpanded = trafficHistoryExpanded;
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

    public boolean isTunIpv6Enabled() {
        return tunIpv6Enabled;
    }

    public void setTunIpv6Enabled(boolean tunIpv6Enabled) {
        this.tunIpv6Enabled = tunIpv6Enabled;
    }

    public boolean isShareLocalProxyInTun() {
        return shareLocalProxyInTun;
    }

    public void setShareLocalProxyInTun(boolean shareLocalProxyInTun) {
        this.shareLocalProxyInTun = shareLocalProxyInTun;
    }

    /**
     * Whether the local proxies ask for this run's password: in TUN mode,
     * unless the user opened them to other programs.
     *
     * @return true when the core is to be given the password
     */
    public boolean localProxyNeedsPassword() {
        return proxyMode == ProxyMode.TUN && !shareLocalProxyInTun;
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

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
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
        this.healthCheckTargets = healthCheckTargets == null
                ? new ArrayList<>() : new ArrayList<>(healthCheckTargets);
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
