package com.vlessclient.service;

import com.vlessclient.app.I18n;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TransportType;
import com.vlessclient.service.outbound.CoreSettings;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Serializes a {@link ServerConfig} into a protocol-specific share link URI.
 */
public class ShareLinkExporter {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    /**
     * Exports the given server configuration as a share link, dispatching by protocol.
     *
     * @param config the server configuration to export
     * @return the share link URI for the configuration's protocol
     * @throws IllegalArgumentException if {@code config} is null or its protocol is unsupported
     */
    public String export(ServerConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("ServerConfig must not be null");
        }
        if (CoreSettings.hasUnreadableCredential(config)) {
            // The link would carry the sealed tag where the credential goes.
            throw new IllegalArgumentException(I18n.get("refusal.credential.unreadable"));
        }
        return switch (config.getProtocol()) {
            case VLESS -> exportVless(config);
            case VMESS -> exportVmess(config);
            case TROJAN -> exportTrojan(config);
            case SHADOWSOCKS -> exportShadowsocks(config);
            case HYSTERIA2 -> exportHysteria2(config);
            default -> throw new IllegalArgumentException(
                    "Export not supported for protocol: " + config.getProtocol());
        };
    }

    /**
     * Exports the configuration as a {@code vless://} share link.
     *
     * @param config the server configuration to export
     * @return the VLESS share link URI
     */
    public String exportVless(ServerConfig config) {
        Map<String, String> params = new LinkedHashMap<>();

        // Transport type (omit if tcp, which is default)
        if (config.getTransport() != null
                && config.getTransport().getType() != null
                && config.getTransport().getType() != TransportType.TCP) {
            params.put("type", config.getTransport().getType().getValue());
        }

        // Security
        String security = resolveSecurityParam(config);
        if (!"none".equals(security)) {
            params.put("security", security);
        }

        // TLS-related params
        addTlsParams(params, config);

        // Encryption (omit if "none", which is default)
        if (config.getEncryption() != null
                && !config.getEncryption().isBlank()
                && !"none".equalsIgnoreCase(config.getEncryption())) {
            params.put("encryption", config.getEncryption());
        }

        // Flow
        putIfPresent(params, "flow", config.getFlow());

        // Transport params
        addTransportParams(params, config);

        return buildStandardUri("vless", config.getUuid(), config, params);
    }

    /**
     * Exports the configuration as a {@code vmess://} share link with a Base64 JSON payload.
     *
     * @param config the server configuration to export
     * @return the VMess share link URI
     */
    public String exportVmess(ServerConfig config) {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("v", "2");
        node.put("ps", config.getName() != null ? config.getName() : "");
        node.put("add", config.getAddress());
        node.put("port", String.valueOf(config.getPort()));
        node.put("id", config.getUuid());
        node.put("aid", "0");
        node.put("scy", config.getEncryption() != null ? config.getEncryption() : "auto");

        // Transport
        String net = "tcp";
        if (config.getTransport() != null && config.getTransport().getType() != null) {
            net = config.getTransport().getType().getValue();
        }
        node.put("net", net);
        node.put("type", "none");

        if (config.getTransport() != null) {
            node.put("host", config.getTransport().getHost() != null
                    ? config.getTransport().getHost() : "");
            // gRPC's service name travels in path, where the parser reads it back.
            String serviceName = config.getTransport().getServiceName();
            String path = config.getTransport().getType() == TransportType.GRPC
                    && serviceName != null && !serviceName.isBlank()
                    ? serviceName : config.getTransport().getPath();
            node.put("path", path != null ? path : "");
        } else {
            node.put("host", "");
            node.put("path", "");
        }

        // TLS
        boolean tlsEnabled = config.getTls() != null && config.getTls().isEnabled();
        node.put("tls", tlsEnabled ? "tls" : "");

        if (config.getTls() != null) {
            node.put("sni", config.getTls().getServerName() != null
                    ? config.getTls().getServerName() : "");
            node.put("alpn", config.getTls().getAlpn() != null
                    ? config.getTls().getAlpn() : "");
            node.put("fp", config.getTls().getFingerprint() != null
                    ? config.getTls().getFingerprint() : "");
        }

        String json = node.toString();
        String encoded = Base64.getEncoder().encodeToString(
                json.getBytes(StandardCharsets.UTF_8));
        return "vmess://" + encoded;
    }

    /**
     * Exports the configuration as a {@code trojan://} share link.
     *
     * @param config the server configuration to export
     * @return the Trojan share link URI
     */
    public String exportTrojan(ServerConfig config) {
        Map<String, String> params = new LinkedHashMap<>();

        // Transport type
        if (config.getTransport() != null
                && config.getTransport().getType() != null
                && config.getTransport().getType() != TransportType.TCP) {
            params.put("type", config.getTransport().getType().getValue());
        }

        // Written even as "none": a Trojan link without it means TLS, so
        // leaving it out turned TLS back on for a server that runs without.
        params.put("security", resolveSecurityParam(config));

        // TLS-related params
        addTlsParams(params, config);

        // Transport params
        addTransportParams(params, config);

        return buildStandardUri("trojan", config.getUuid(), config, params);
    }

    /**
     * Exports the configuration as an {@code ss://} (Shadowsocks) share link in SIP002 format.
     *
     * @param config the server configuration to export
     * @return the Shadowsocks share link URI
     */
    public String exportShadowsocks(ServerConfig config) {
        String method = config.getEncryption() != null ? config.getEncryption() : "none";
        String password = config.getUuid() != null ? config.getUuid() : "";
        String userInfo = method + ":" + password;
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(userInfo.getBytes(StandardCharsets.UTF_8));

        StringBuilder sb = new StringBuilder();
        sb.append("ss://");
        sb.append(encoded);
        sb.append("@");
        sb.append(config.getAddress());
        sb.append(":");
        sb.append(config.getPort());
        // The plugin goes back into the link: exporting without it produced a
        // link that looked fine and connected to nothing.
        if (config.getPlugin() != null && !config.getPlugin().isBlank()) {
            String plugin = config.getPlugin();
            if (config.getPluginOpts() != null && !config.getPluginOpts().isBlank()) {
                plugin = plugin + ";" + config.getPluginOpts();
            }
            sb.append("/?plugin=").append(URLEncoder.encode(plugin, StandardCharsets.UTF_8));
        }

        String name = config.getName();
        if (name != null && !name.isBlank()) {
            sb.append("#");
            sb.append(URLEncoder.encode(name, StandardCharsets.UTF_8));
        }

        return sb.toString();
    }

    /**
     * Exports the configuration as a {@code hysteria2://} share link.
     *
     * @param config the server configuration to export
     * @return the Hysteria2 share link URI
     */
    public String exportHysteria2(ServerConfig config) {
        Map<String, String> params = new LinkedHashMap<>();

        // SNI
        if (config.getTls() != null && config.getTls().getServerName() != null
                && !config.getTls().getServerName().isBlank()) {
            params.put("sni", config.getTls().getServerName());
        }

        // Insecure
        if (config.getTls() != null && config.getTls().isAllowInsecure()) {
            params.put("insecure", "1");
        }

        // Obfuscation
        if (config.getEncryption() != null && !config.getEncryption().isBlank()
                && !"none".equalsIgnoreCase(config.getEncryption())) {
            params.put("obfs", config.getEncryption());
        }

        if (config.getFlow() != null && !config.getFlow().isBlank()) {
            params.put("obfs-password", config.getFlow());
        }

        // As mport, not in the authority: a reader that does not hop still
        // reads the host and the port.
        if (config.getServerPorts() != null && !config.getServerPorts().isBlank()) {
            params.put("mport", config.getServerPorts());
        }

        return buildStandardUri("hysteria2", config.getUuid(), config, params);
    }

    private String buildStandardUri(String scheme, String userInfo,
                                    ServerConfig config, Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://");
        sb.append(encodeUserInfo(userInfo));
        sb.append("@");
        sb.append(config.getAddress());
        sb.append(":");
        sb.append(config.getPort());

        if (!params.isEmpty()) {
            sb.append("?");
            sb.append(params.entrySet().stream()
                    .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                    .collect(Collectors.joining("&")));
        }

        String name = config.getName();
        if (name != null && !name.isBlank()) {
            sb.append("#");
            sb.append(URLEncoder.encode(name, StandardCharsets.UTF_8));
        }

        return sb.toString();
    }

    private void addTlsParams(Map<String, String> params, ServerConfig config) {
        if (config.getTls() != null && config.getTls().isEnabled()) {
            putIfPresent(params, "sni", config.getTls().getServerName());
            putIfPresent(params, "fp", config.getTls().getFingerprint());
            putIfPresent(params, "alpn", config.getTls().getAlpn());

            if (config.getTls().isReality()) {
                putIfPresent(params, "pbk", config.getTls().getRealityPublicKey());
                putIfPresent(params, "sid", config.getTls().getRealityShortId());
            }

            if (config.getTls().isAllowInsecure()) {
                params.put("allowInsecure", "1");
            }
        }
    }

    private void addTransportParams(Map<String, String> params, ServerConfig config) {
        if (config.getTransport() != null) {
            putIfPresent(params, "path", config.getTransport().getPath());
            putIfPresent(params, "host", config.getTransport().getHost());
            putIfPresent(params, "serviceName", config.getTransport().getServiceName());
        }
    }

    private String resolveSecurityParam(ServerConfig config) {
        if (config.getTls() == null || !config.getTls().isEnabled()) {
            return "none";
        }
        if (config.getTls().isReality()) {
            return "reality";
        }
        return "tls";
    }

    private void putIfPresent(Map<String, String> params, String key, String value) {
        if (value != null && !value.isBlank()) {
            params.put(key, value);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String encodeUserInfo(String value) {
        return encode(value).replace("+", "%20");
    }
}
