package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * A single proxy server definition, including its protocol, address, credentials,
 * and transport and TLS settings.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServerConfig extends KeepsUnknownFields {

    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("protocol")
    private Protocol protocol;

    @JsonProperty("address")
    private String address;

    @JsonProperty("port")
    private int port;

    @JsonProperty("uuid")
    private String uuid;

    @JsonProperty("encryption")
    private String encryption = "none";

    @JsonProperty("flow")
    private String flow;

    @JsonProperty("transport")
    private TransportConfig transport;

    @JsonProperty("tls")
    private TlsConfig tls;

    /**
     * A Shadowsocks SIP003 plugin name, such as {@code obfs-local} or
     * {@code v2ray-plugin}. Kept verbatim: the core validates it, and a link
     * that names a plugin does not work without it.
     */
    @JsonProperty("plugin")
    private String plugin;

    /** The plugin's options, as the {@code plugin_opts} string sing-box takes. */
    @JsonProperty("plugin_opts")
    private String pluginOpts;

    @JsonProperty("active")
    private boolean active;

    /**
     * The subscription this server came from; null for one the user added.
     *
     * <p>Membership used to live only in the subscription's list of server
     * ids, which is saved after the servers: a quit between the two saves
     * left a refresh's new servers in no list, and the next refresh added
     * them again, as duplicates no refresh or delete would reach. Stamped on
     * the server, membership is saved with the server.</p>
     */
    @JsonProperty("subscription_id")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String subscriptionId;

    /**
     * Hysteria2 port hopping: every port the server listens on, as links write
     * them ({@code 443,20000-50000}); null for a server on {@link #port} alone.
     */
    @JsonProperty("server_ports")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String serverPorts;

    /**
     * Creates a server with a fresh random id and default transport and TLS config.
     */
    public ServerConfig() {
        this.id = UUID.randomUUID().toString();
        this.transport = new TransportConfig();
        this.tls = new TlsConfig();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Protocol getProtocol() {
        return protocol;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getEncryption() {
        return encryption;
    }

    public void setEncryption(String encryption) {
        this.encryption = encryption;
    }

    public String getFlow() {
        return flow;
    }

    public void setFlow(String flow) {
        this.flow = flow;
    }

    public TransportConfig getTransport() {
        return transport;
    }

    public void setTransport(TransportConfig transport) {
        this.transport = transport == null ? null : new TransportConfig(transport);
    }

    public TlsConfig getTls() {
        return tls;
    }

    public void setTls(TlsConfig tls) {
        this.tls = tls == null ? null : new TlsConfig(tls);
    }

    public String getPlugin() {
        return plugin;
    }

    public void setPlugin(String plugin) {
        this.plugin = plugin;
    }

    public String getPluginOpts() {
        return pluginOpts;
    }

    public void setPluginOpts(String pluginOpts) {
        this.pluginOpts = pluginOpts;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public void setSubscriptionId(String subscriptionId) {
        this.subscriptionId = subscriptionId;
    }

    /** Hysteria2's hopping ports as a link lists them, or null for none. */
    public String getServerPorts() {
        return serverPorts;
    }

    public void setServerPorts(String serverPorts) {
        this.serverPorts = serverPorts;
    }

    @Override
    public String toString() {
        return name != null ? name : address + ":" + port;
    }
}
