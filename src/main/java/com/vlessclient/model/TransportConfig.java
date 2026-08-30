package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.Map;

/**
 * Transport-layer settings for a server connection, such as the transport type
 * and its path, host, service name, and headers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransportConfig {

    @JsonProperty("type")
    private TransportType type = TransportType.TCP;

    @JsonProperty("path")
    private String path;

    @JsonProperty("host")
    private String host;

    @JsonProperty("service_name")
    private String serviceName;

    @JsonProperty("headers")
    private Map<String, String> headers = new HashMap<>();

    public TransportConfig() {
    }

    TransportConfig(TransportConfig source) {
        this.type = source.type;
        this.path = source.path;
        this.host = source.host;
        this.serviceName = source.serviceName;
        this.headers = new HashMap<>(source.headers);
    }

    public TransportType getType() {
        return type;
    }

    public void setType(TransportType type) {
        this.type = type;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers == null ? new HashMap<>() : new HashMap<>(headers);
    }
}
