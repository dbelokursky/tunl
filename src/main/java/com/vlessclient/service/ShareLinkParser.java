package com.vlessclient.service;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TransportType;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses protocol-specific share link URIs into {@link ServerConfig} instances.
 */
public class ShareLinkParser {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    /**
     * Parses a share link URI, dispatching by its scheme.
     *
     * @param uri the share link URI to parse
     * @return the parsed server configuration
     * @throws IllegalArgumentException if {@code uri} is null, blank, malformed, or has an
     *         unsupported scheme
     */
    public ServerConfig parse(String uri) {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("URI must not be null or blank");
        }
        int schemeEnd = uri.indexOf("://");
        if (schemeEnd < 0) {
            throw new IllegalArgumentException("Share link URI must contain ://");
        }
        String scheme = uri.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        return switch (scheme) {
            case "vless" -> parseVless(uri);
            case "vmess" -> parseVmess(uri);
            case "trojan" -> parseTrojan(uri);
            case "ss" -> parseShadowsocks(uri);
            case "hysteria2", "hy2" -> parseHysteria2(uri);
            default -> throw new UnsupportedSchemeException(scheme);
        };
    }

    /** Longest display name kept from a link; anything past it is noise. */
    static final int MAX_NAME_LENGTH = 200;

    /**
     * A display name safe to store, render and log.
     *
     * <p>The name comes straight out of the link's fragment, URL-decoded, and
     * went into {@code tunl.log} verbatim on every connect. A fragment holding
     * {@code %0A} forged a line in the file people attach to bug reports, and
     * an unbounded one could be as long as the provider liked. Control
     * characters become spaces and the result is trimmed and capped.</p>
     *
     * @param raw the decoded fragment or JSON name, possibly null
     * @return the cleaned name, empty when nothing usable remains
     */
    static String cleanName(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ").trim();
        return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH) : cleaned;
    }

    /** The fragment as the name, or {@code host:port} when it yields nothing. */
    private static String displayName(String fragment, String host, int port) {
        String cleaned = cleanName(fragment);
        return cleaned.isEmpty() ? host + ":" + port : cleaned;
    }

    /**
     * A link that is well-formed but uses a protocol this client does not
     * implement.
     *
     * <p>Kept distinct from the other {@link IllegalArgumentException}s so a
     * caller reading a whole list — a subscription — can tell "this provider
     * also hands out TUIC" from "this line is garbage". The first is not
     * evidence that the list was truncated or corrupted, and must not be
     * treated as one.</p>
     */
    public static final class UnsupportedSchemeException extends IllegalArgumentException {

        private final String scheme;

        /**
         * Creates the exception for a scheme.
         *
         * @param scheme the lower-cased scheme of the rejected link
         */
        public UnsupportedSchemeException(String scheme) {
            super("Unsupported protocol scheme: " + scheme);
            this.scheme = scheme;
        }

        /** The scheme of the rejected link, e.g. {@code tuic}. */
        public String scheme() {
            return scheme;
        }
    }

    /**
     * Parses a {@code vless://} share link URI.
     *
     * @param uri the VLESS share link URI
     * @return the parsed server configuration
     * @throws IllegalArgumentException if the URI is malformed or missing required fields
     */
    public ServerConfig parseVless(String uri) {
        if (!uri.toLowerCase().startsWith("vless://")) {
            throw new IllegalArgumentException("URI must start with vless://");
        }

        // Extract fragment (server name) before parsing
        String fragment = null;
        int fragmentIndex = uri.indexOf('#');
        if (fragmentIndex >= 0) {
            fragment = URLDecoder.decode(uri.substring(fragmentIndex + 1), StandardCharsets.UTF_8);
        }

        // Parse the URI using a workaround: replace vless:// with http:// so java.net.URI can parse it
        String httpUri = "http://" + uri.substring("vless://".length());
        // Remove fragment for URI parsing to avoid issues
        if (fragmentIndex >= 0) {
            httpUri = "http://" + uri.substring("vless://".length(), fragmentIndex);
        }
        URI parsed;
        try {
            parsed = URI.create(httpUri);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid VLESS URI format: " + e.getMessage());
        }

        String userInfo = parsed.getUserInfo();
        if (userInfo == null || userInfo.isBlank()) {
            throw new IllegalArgumentException("Missing UUID in VLESS URI");
        }

        String host = parsed.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Missing host in VLESS URI");
        }

        int port = parsed.getPort();
        if (port < 0) {
            port = 443;
        }

        Map<String, String> params = parseQueryParams(parsed.getRawQuery());

        ServerConfig config = new ServerConfig();
        config.setProtocol(Protocol.VLESS);
        config.setUuid(userInfo);
        config.setAddress(host);
        config.setPort(port);
        config.setName(displayName(fragment, host, port));

        // Encryption
        String encryption = params.get("encryption");
        if (encryption != null && !encryption.isBlank()) {
            config.setEncryption(encryption);
        }

        // Flow
        String flow = params.get("flow");
        if (flow != null && !flow.isBlank()) {
            config.setFlow(flow);
        }

        // Transport
        applyTransportParams(config, params);

        // Security / TLS
        applyTlsParams(config, params);

        return config;
    }

    /**
     * Parses a {@code vmess://} share link URI with a Base64-encoded JSON payload.
     *
     * @param uri the VMess share link URI
     * @return the parsed server configuration
     * @throws IllegalArgumentException if the URI is malformed or missing required fields
     */
    public ServerConfig parseVmess(String uri) {
        if (!uri.toLowerCase().startsWith("vmess://")) {
            throw new IllegalArgumentException("URI must start with vmess://");
        }

        String encoded = uri.substring("vmess://".length());
        String json = decodeBase64(encoded);

        JsonNode node;
        try {
            node = OBJECT_MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Invalid vmess JSON: " + e.getMessage());
        }

        ServerConfig config = new ServerConfig();
        config.setProtocol(Protocol.VMESS);

        config.setName(cleanName(getJsonString(node, "ps", "")));
        config.setAddress(getJsonString(node, "add", ""));
        config.setPort(getJsonInt(node, "port", 443));
        config.setUuid(getJsonString(node, "id", ""));

        if (config.getAddress().isBlank()) {
            throw new IllegalArgumentException("Missing address in vmess URI");
        }
        if (config.getUuid().isBlank()) {
            throw new IllegalArgumentException("Missing UUID in vmess URI");
        }

        // Encryption
        String scy = getJsonString(node, "scy", "auto");
        config.setEncryption(scy);

        // Transport
        String net = getJsonString(node, "net", "tcp");
        TransportType transportType = parseTransportType(mapVmessNet(net));
        config.getTransport().setType(transportType);

        String path = getJsonString(node, "path", "");
        if (!path.isBlank()) {
            config.getTransport().setPath(path);
        }

        String host = getJsonString(node, "host", "");
        if (!host.isBlank()) {
            config.getTransport().setHost(host);
        }

        // TLS
        String tls = getJsonString(node, "tls", "");
        if ("tls".equalsIgnoreCase(tls)) {
            config.getTls().setEnabled(true);
        }

        String sni = getJsonString(node, "sni", "");
        if (!sni.isBlank()) {
            config.getTls().setServerName(sni);
        }

        String fp = getJsonString(node, "fp", "");
        if (!fp.isBlank()) {
            config.getTls().setFingerprint(fp);
        }

        String alpn = getJsonString(node, "alpn", "");
        if (!alpn.isBlank()) {
            config.getTls().setAlpn(alpn);
        }

        if (config.getName().isBlank()) {
            config.setName(config.getAddress() + ":" + config.getPort());
        }

        return config;
    }

    /**
     * Parses a {@code trojan://} share link URI.
     *
     * @param uri the Trojan share link URI
     * @return the parsed server configuration
     * @throws IllegalArgumentException if the URI is malformed or missing required fields
     */
    public ServerConfig parseTrojan(String uri) {
        if (!uri.toLowerCase().startsWith("trojan://")) {
            throw new IllegalArgumentException("URI must start with trojan://");
        }

        String fragment = null;
        int fragmentIndex = uri.indexOf('#');
        if (fragmentIndex >= 0) {
            fragment = URLDecoder.decode(uri.substring(fragmentIndex + 1), StandardCharsets.UTF_8);
        }

        String httpUri = "http://" + uri.substring("trojan://".length());
        if (fragmentIndex >= 0) {
            httpUri = "http://" + uri.substring("trojan://".length(), fragmentIndex);
        }

        URI parsed;
        try {
            parsed = URI.create(httpUri);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Trojan URI format: " + e.getMessage());
        }

        String password = parsed.getUserInfo();
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Missing password in Trojan URI");
        }

        String host = parsed.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Missing host in Trojan URI");
        }

        int port = parsed.getPort();
        if (port < 0) {
            port = 443;
        }

        ServerConfig config = new ServerConfig();
        config.setProtocol(Protocol.TROJAN);
        config.setUuid(password);
        config.setAddress(host);
        config.setPort(port);
        config.setName(displayName(fragment, host, port));

        // Transport
        Map<String, String> params = parseQueryParams(parsed.getRawQuery());
        applyTransportParams(config, params);

        // Security / TLS - trojan defaults to TLS enabled
        String security = params.getOrDefault("security", "tls");
        if ("tls".equals(security)) {
            config.getTls().setEnabled(true);
        } else if ("reality".equals(security)) {
            config.getTls().setEnabled(true);
            config.getTls().setReality(true);
        }

        String sni = params.get("sni");
        if (sni != null && !sni.isBlank()) {
            config.getTls().setServerName(sni);
        }

        String fp = params.get("fp");
        if (fp != null && !fp.isBlank()) {
            config.getTls().setFingerprint(fp);
        }

        String alpn = params.get("alpn");
        if (alpn != null && !alpn.isBlank()) {
            config.getTls().setAlpn(alpn);
        }

        String allowInsecure = params.get("allowInsecure");
        if ("1".equals(allowInsecure)) {
            config.getTls().setAllowInsecure(true);
        }

        return config;
    }

    /**
     * Parses an {@code ss://} (Shadowsocks) share link URI in either SIP002 or legacy format.
     *
     * @param uri the Shadowsocks share link URI
     * @return the parsed server configuration
     * @throws IllegalArgumentException if the URI is malformed or missing required fields
     */
    public ServerConfig parseShadowsocks(String uri) {
        if (!uri.toLowerCase().startsWith("ss://")) {
            throw new IllegalArgumentException("URI must start with ss://");
        }

        String rest = uri.substring("ss://".length());

        // Extract fragment (name)
        String fragment = null;
        int fragmentIndex = rest.indexOf('#');
        if (fragmentIndex >= 0) {
            fragment = URLDecoder.decode(rest.substring(fragmentIndex + 1), StandardCharsets.UTF_8);
            rest = rest.substring(0, fragmentIndex);
        }

        String host;
        int port;
        String method;
        String password;

        // SIP002 format: BASE64(method:password)@host:port/?plugin=...
        // Legacy format: BASE64(method:password@host:port)
        int atSign = rest.indexOf('@');
        if (atSign >= 0) {
            // SIP002: userinfo is base64, then @host:port
            String userInfoEncoded = rest.substring(0, atSign);
            String userInfo = decodeBase64(userInfoEncoded);
            int colonIndex = userInfo.indexOf(':');
            if (colonIndex < 0) {
                throw new IllegalArgumentException("Invalid Shadowsocks userinfo format");
            }
            method = userInfo.substring(0, colonIndex);
            password = userInfo.substring(colonIndex + 1);

            String hostPort = rest.substring(atSign + 1);
            // Remove query string if present
            int queryIndex = hostPort.indexOf('?');
            if (queryIndex >= 0) {
                hostPort = hostPort.substring(0, queryIndex);
            }
            // Remove trailing slash
            if (hostPort.endsWith("/")) {
                hostPort = hostPort.substring(0, hostPort.length() - 1);
            }

            int lastColon = hostPort.lastIndexOf(':');
            if (lastColon < 0) {
                throw new IllegalArgumentException("Missing port in Shadowsocks URI");
            }
            host = hostPort.substring(0, lastColon);
            port = Integer.parseInt(hostPort.substring(lastColon + 1));
        } else {
            // Legacy: entire part is base64 encoded
            String decoded = decodeBase64(rest);
            // format: method:password@host:port
            int atInDecoded = decoded.lastIndexOf('@');
            if (atInDecoded < 0) {
                throw new IllegalArgumentException("Invalid Shadowsocks legacy format");
            }
            String userInfo = decoded.substring(0, atInDecoded);

            int colonIndex = userInfo.indexOf(':');
            if (colonIndex < 0) {
                throw new IllegalArgumentException("Invalid Shadowsocks userinfo format");
            }
            method = userInfo.substring(0, colonIndex);
            password = userInfo.substring(colonIndex + 1);

            String hostPort = decoded.substring(atInDecoded + 1);
            int lastColon = hostPort.lastIndexOf(':');
            if (lastColon < 0) {
                throw new IllegalArgumentException("Missing port in Shadowsocks URI");
            }
            host = hostPort.substring(0, lastColon);
            port = Integer.parseInt(hostPort.substring(lastColon + 1));
        }

        ServerConfig config = new ServerConfig();
        config.setProtocol(Protocol.SHADOWSOCKS);
        config.setEncryption(method);
        config.setUuid(password);
        config.setAddress(host);
        config.setPort(port);
        config.setName(displayName(fragment, host, port));

        return config;
    }

    /**
     * Parses a {@code hysteria2://} or {@code hy2://} share link URI.
     *
     * @param uri the Hysteria2 share link URI
     * @return the parsed server configuration
     * @throws IllegalArgumentException if the URI is malformed or missing required fields
     */
    public ServerConfig parseHysteria2(String uri) {
        String lower = uri.toLowerCase();
        if (!lower.startsWith("hysteria2://") && !lower.startsWith("hy2://")) {
            throw new IllegalArgumentException("URI must start with hysteria2:// or hy2://");
        }

        int schemeEnd = uri.indexOf("://");
        String fragment = null;
        int fragmentIndex = uri.indexOf('#');
        if (fragmentIndex >= 0) {
            fragment = URLDecoder.decode(uri.substring(fragmentIndex + 1), StandardCharsets.UTF_8);
        }

        String httpUri = "http://" + uri.substring(schemeEnd + 3);
        if (fragmentIndex >= 0) {
            httpUri = "http://" + uri.substring(schemeEnd + 3, fragmentIndex);
        }

        URI parsed;
        try {
            parsed = URI.create(httpUri);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Hysteria2 URI format: " + e.getMessage());
        }

        String password = parsed.getUserInfo();
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Missing password in Hysteria2 URI");
        }

        String host = parsed.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Missing host in Hysteria2 URI");
        }

        int port = parsed.getPort();
        if (port < 0) {
            port = 443;
        }

        ServerConfig config = new ServerConfig();
        config.setProtocol(Protocol.HYSTERIA2);
        config.setUuid(password);
        config.setAddress(host);
        config.setPort(port);
        config.setName(displayName(fragment, host, port));

        // TLS - hysteria2 defaults to TLS
        config.getTls().setEnabled(true);

        Map<String, String> params = parseQueryParams(parsed.getRawQuery());
        String sni = params.get("sni");
        if (sni != null && !sni.isBlank()) {
            config.getTls().setServerName(sni);
        }

        String allowInsecure = params.get("insecure");
        if ("1".equals(allowInsecure)) {
            config.getTls().setAllowInsecure(true);
        }

        // Obfuscation - store obfs-password in flow field
        String obfsPassword = params.get("obfs-password");
        if (obfsPassword != null && !obfsPassword.isBlank()) {
            config.setFlow(obfsPassword);
        }

        // Store obfs type in encryption field
        String obfs = params.get("obfs");
        if (obfs != null && !obfs.isBlank()) {
            config.setEncryption(obfs);
        }

        return config;
    }

    private void applyTransportParams(ServerConfig config, Map<String, String> params) {
        String type = params.getOrDefault("type", "tcp");
        TransportType transportType = parseTransportType(type);
        config.getTransport().setType(transportType);

        String path = params.get("path");
        if (path != null && !path.isBlank()) {
            config.getTransport().setPath(URLDecoder.decode(path, StandardCharsets.UTF_8));
        }

        String transportHost = params.get("host");
        if (transportHost != null && !transportHost.isBlank()) {
            config.getTransport().setHost(transportHost);
        }

        String serviceName = params.get("serviceName");
        if (serviceName != null && !serviceName.isBlank()) {
            config.getTransport().setServiceName(serviceName);
        }
    }

    private void applyTlsParams(ServerConfig config, Map<String, String> params) {
        String security = params.getOrDefault("security", "none");
        if ("tls".equals(security)) {
            config.getTls().setEnabled(true);
        } else if ("reality".equals(security)) {
            config.getTls().setEnabled(true);
            config.getTls().setReality(true);
        }

        String sni = params.get("sni");
        if (sni != null && !sni.isBlank()) {
            config.getTls().setServerName(sni);
        }

        String fp = params.get("fp");
        if (fp != null && !fp.isBlank()) {
            config.getTls().setFingerprint(fp);
        }

        String alpn = params.get("alpn");
        if (alpn != null && !alpn.isBlank()) {
            config.getTls().setAlpn(alpn);
        }

        String pbk = params.get("pbk");
        if (pbk != null && !pbk.isBlank()) {
            config.getTls().setRealityPublicKey(pbk);
        }

        String sid = params.get("sid");
        if (sid != null && !sid.isBlank()) {
            config.getTls().setRealityShortId(sid);
        }

        String allowInsecure = params.get("allowInsecure");
        if ("1".equals(allowInsecure)) {
            config.getTls().setAllowInsecure(true);
        }
    }

    private String mapVmessNet(String net) {
        return switch (net.toLowerCase()) {
            case "h2" -> "http";
            case "kcp" -> "tcp";
            default -> net;
        };
    }

    private String getJsonString(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        return value.asString(defaultValue);
    }

    private int getJsonInt(JsonNode node, String field, int defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (value.isString()) {
            try {
                return Integer.parseInt(value.asString());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return value.isNumber() ? value.asInt(defaultValue) : defaultValue;
    }

    private String decodeBase64(String encoded) {
        // Remove whitespace and newlines
        encoded = encoded.replaceAll("\\s+", "");
        // Try standard base64 first, then URL-safe
        try {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Try URL-safe base64
            try {
                return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e2) {
                // Try adding padding
                String padded = encoded;
                int pad = padded.length() % 4;
                if (pad > 0) {
                    padded = padded + "=".repeat(4 - pad);
                }
                try {
                    return new String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e3) {
                    return new String(Base64.getUrlDecoder().decode(padded),
                            StandardCharsets.UTF_8);
                }
            }
        }
    }

    private TransportType parseTransportType(String type) {
        for (TransportType t : TransportType.values()) {
            if (t.getValue().equalsIgnoreCase(type)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown transport type: " + type);
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return params;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                params.put(key, value);
            }
        }
        return params;
    }
}
