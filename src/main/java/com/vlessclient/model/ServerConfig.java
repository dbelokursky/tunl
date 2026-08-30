package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * A single proxy server definition, including its protocol, address, credentials,
 * and transport and TLS settings.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServerConfig {

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

    @JsonProperty("active")
    private boolean active;

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

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public String toString() {
        return name != null ? name : address + ":" + port;
    }
}
