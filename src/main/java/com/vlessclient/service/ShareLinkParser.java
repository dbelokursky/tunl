package com.vlessclient.service;

import com.vlessclient.app.I18n;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TransportType;
import com.vlessclient.service.outbound.CoreSettings;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses protocol-specific share link URIs into {@link ServerConfig} instances.
 */
public class ShareLinkParser {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    /** U+FEFF, which a file or a response saved with a byte order mark starts with. */
    private static final char BYTE_ORDER_MARK = 0xFEFF;

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
        // A list saved with a byte order mark hands it over with its first
        // link, and trimming the line leaves it there. It read as part of the
        // scheme, so the first server counted as an unsupported protocol.
        String link = uri.charAt(0) == BYTE_ORDER_MARK ? uri.substring(1) : uri;
        int schemeEnd = link.indexOf("://");
        if (schemeEnd < 0) {
            throw new IllegalArgumentException("Share link URI must contain ://");
        }
        String scheme = link.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        return switch (scheme) {
            case "vless" -> parseVless(link);
            case "vmess" -> parseVmess(link);
            case "trojan" -> parseTrojan(link);
            case "ss" -> parseShadowsocks(link);
            case "hysteria2", "hy2" -> parseHysteria2(link);
            default -> throw new UnsupportedSchemeException(scheme);
        };
    }

    /**
     * Parses a share link for a server that is about to be stored: like
     * {@link #parse}, and also refuses one the core would refuse however its
     * settings are spelled ({@link CoreSettings#refusal}). Such a server used
     * to be stored and then left out of every connect.
     *
     * @param uri the share link URI to parse
     * @return the parsed server configuration
     * @throws UnsupportedFeatureException if the core would refuse the server
     * @throws IllegalArgumentException    if {@code uri} does not parse
     */
    public ServerConfig parseForImport(String uri) {
        ServerConfig server = parse(uri);
        CoreSettings.Refusal refusal = CoreSettings.refusal(server).orElse(null);
        if (refusal != null) {
            throw new UnsupportedFeatureException(refusal.feature(), refusal.reason());
        }
        return server;
    }

    /** Longest display name kept from a link; anything past it is noise. */
    static final int MAX_NAME_LENGTH = 200;

    /** VLESS encryption values that ask for none, VMess words some panels put there included. */
    private static final Set<String> NO_VLESS_ENCRYPTION = Set.of("none", "auto", "zero");

    /** Longest part of a link's own value a refusal repeats. */
    private static final int MAX_FEATURE_LENGTH = 40;

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
     * Decodes a link's fragment, its display name, the way a form value is
     * decoded: {@code %XX} escapes as UTF-8, and {@code +} as a space. A
     * {@code %} that starts no escape is kept as it is. URLDecoder threw on
     * one, so a server named "100%" rejected the whole link.
     *
     * @param fragment the fragment as it appears in the link
     * @return the decoded name
     */
    static String decodeName(String fragment) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(fragment.length());
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < fragment.length()) {
            char c = fragment.charAt(i);
            if (c == '%' && i + 2 < fragment.length()
                    && HexFormat.isHexDigit(fragment.charAt(i + 1))
                    && HexFormat.isHexDigit(fragment.charAt(i + 2))) {
                bytes.writeBytes(text.toString().getBytes(StandardCharsets.UTF_8));
                text.setLength(0);
                bytes.write(HexFormat.fromHexDigits(fragment, i + 1, i + 3));
                i += 3;
            } else {
                text.append(c == '+' ? ' ' : c);
                i++;
            }
        }
        bytes.writeBytes(text.toString().getBytes(StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /**
     * The failure for a link whose {@code http://} form will not parse.
     *
     * <p>Built from the reason alone. The JDK's message ends with the whole
     * input, which here is {@code http://<uuid-or-password>@host…}, so passing
     * it on put the credential into the exception, and from there into
     * {@code tunl.log} and the import dialogs. Scrubbing URLs out afterwards
     * cannot stand in for this: the character that makes a link malformed, a
     * space or a quote, is exactly where a URL scanner stops, and the part of a
     * password after it was left behind. For the same reason {@code e} is not
     * attached as the cause, whose message a logged stack trace prints.</p>
     *
     * @param protocol     the protocol as the message names it
     * @param e            the parse failure of the rewritten link
     * @param schemeLength the length of the scheme and {@code ://} the rewrite
     *                     replaced, so the position counts from the start of
     *                     the link as given
     * @return the exception to throw
     */
    private static IllegalArgumentException invalidUri(String protocol, URISyntaxException e,
                                                       int schemeLength) {
        String where = e.getIndex() < 0 ? ""
                : " at index " + (e.getIndex() - "http://".length() + schemeLength);
        return new IllegalArgumentException(
                "Invalid " + protocol + " URI format: " + e.getReason() + where);
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
            // Shown in the import report, so worded in the language of the UI.
            super(I18n.get("import.scheme.unsupported", scheme));
            this.scheme = scheme;
        }

        /** The scheme of the rejected link, e.g. {@code tuic}. */
        public String scheme() {
            return scheme;
        }
    }

    /**
     * A link that parses but asks for something this client cannot run: a
     * transport sing-box does not implement, or a setting the core refuses
     * however it is spelled.
     *
     * <p>Counted with {@link UnsupportedSchemeException} rather than with
     * unreadable lines, for the same reason: a provider that also hands out
     * xhttp servers has not sent a truncated list, and treating it as one
     * stopped every later refresh from removing a withdrawn server.</p>
     */
    public static final class UnsupportedFeatureException extends IllegalArgumentException {

        private final String feature;

        /**
         * Creates the exception.
         *
         * @param feature what the link asks for, e.g. {@code transport xhttp}
         * @param message the sentence to show for this link
         */
        public UnsupportedFeatureException(String feature, String message) {
            super(message);
            this.feature = feature;
        }

        /** What the link asks for, short enough for a list, e.g. {@code transport xhttp}. */
        public String feature() {
            return feature;
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
            fragment = decodeName(uri.substring(fragmentIndex + 1));
        }

        // Parse the URI using a workaround: replace vless:// with http:// so java.net.URI can parse it
        String httpUri = "http://" + uri.substring("vless://".length());
        // Remove fragment for URI parsing to avoid issues
        if (fragmentIndex >= 0) {
            httpUri = "http://" + uri.substring("vless://".length(), fragmentIndex);
        }
        URI parsed;
        try {
            parsed = new URI(httpUri);
        } catch (URISyntaxException e) {
            throw invalidUri("VLESS", e, "vless://".length());
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
            refuseVlessEncryption(encryption);
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
            // Without Jackson's message: it quotes the token it could not read,
            // and in a payload whose id lost its quotes that token is the UUID.
            throw new IllegalArgumentException("Invalid vmess JSON");
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
        if (transportType == TransportType.TCP
                && "http".equalsIgnoreCase(getJsonString(node, "type", ""))) {
            throw unsupportedTcpHeader();
        }
        config.getTransport().setType(transportType);

        String path = getJsonString(node, "path", "");
        if (!path.isBlank()) {
            // A VMess link has no service-name field: v2rayN writes gRPC's
            // service name into path, and the core reads it as service_name.
            if (transportType == TransportType.GRPC) {
                config.getTransport().setServiceName(path);
            } else {
                config.getTransport().setPath(path);
            }
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
            fragment = decodeName(uri.substring(fragmentIndex + 1));
        }

        String httpUri = "http://" + uri.substring("trojan://".length());
        if (fragmentIndex >= 0) {
            httpUri = "http://" + uri.substring("trojan://".length(), fragmentIndex);
        }

        URI parsed;
        try {
            parsed = new URI(httpUri);
        } catch (URISyntaxException e) {
            throw invalidUri("Trojan", e, "trojan://".length());
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

        // Security / TLS: a Trojan link is TLS unless it says otherwise, and it
        // carries REALITY the same way a VLESS link does. This was a copy of
        // applyTlsParams that had drifted -- it read security and sni but
        // neither pbk nor sid, so the app could not import the REALITY links
        // its own export wrote.
        applyTlsParams(config, params, "tls");

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
            fragment = decodeName(rest.substring(fragmentIndex + 1));
            rest = rest.substring(0, fragmentIndex);
        }

        String host;
        int port;
        String method;
        String password;
        String query = null;

        // SIP002 format: BASE64(method:password)@host:port/?plugin=...
        // Legacy format: BASE64(method:password@host:port)
        int atSign = rest.indexOf('@');
        if (atSign >= 0) {
            // SIP002: userinfo is base64, then @host:port
            String userInfoEncoded = rest.substring(0, atSign);
            String userInfo = decodeUserInfo(userInfoEncoded);
            int colonIndex = userInfo.indexOf(':');
            if (colonIndex < 0) {
                throw new IllegalArgumentException("Invalid Shadowsocks userinfo format");
            }
            method = userInfo.substring(0, colonIndex);
            password = userInfo.substring(colonIndex + 1);

            String hostPort = rest.substring(atSign + 1);
            // The query carries the SIP003 plugin, which the server needs.
            int queryIndex = hostPort.indexOf('?');
            if (queryIndex >= 0) {
                query = hostPort.substring(queryIndex + 1);
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
        applyPlugin(config, query);
        refuseShadowsocksTransport(query);

        return config;
    }

    /**
     * Reads SIP002 userinfo: either base64 of {@code method:password}, or that
     * pair itself with the password percent-encoded.
     *
     * <p>The 2022 ciphers require the second form -- their key is base64
     * already, and wrapping the pair in another base64 is what breaks
     * interoperability -- and the app used to reject it outright. A base64
     * blob never contains a colon, so the colon tells the two apart. {@code +}
     * is left alone rather than read as a space: in a 2022 key it is a base64
     * character, and decoding it as a space would corrupt the credential.</p>
     */
    private static String decodeUserInfo(String encoded) {
        if (encoded.indexOf(':') >= 0) {
            return URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8);
        }
        return decodeBase64(encoded);
    }

    /**
     * Keeps the SIP003 plugin a link names: {@code plugin=<name>;<options>},
     * percent-encoded as one value, which sing-box takes as two fields.
     *
     * <p>Both are kept verbatim -- the core is what validates them. Dropping
     * them silently was worse than refusing the link: the server answers only
     * through its plugin, so the app reported a connected tunnel that carried
     * nothing.</p>
     */
    private static void applyPlugin(ServerConfig config, String query) {
        if (query == null || query.isBlank()) {
            return;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0 || !"plugin".equalsIgnoreCase(pair.substring(0, eq))) {
                continue;
            }
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            if (value.isBlank()) {
                return;
            }
            int semicolon = value.indexOf(';');
            config.setPlugin(semicolon < 0 ? value : value.substring(0, semicolon));
            if (semicolon >= 0 && semicolon + 1 < value.length()) {
                config.setPluginOpts(value.substring(semicolon + 1));
            }
            return;
        }
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
            fragment = decodeName(uri.substring(fragmentIndex + 1));
        }

        String httpUri = "http://" + uri.substring(schemeEnd + 3);
        if (fragmentIndex >= 0) {
            httpUri = "http://" + uri.substring(schemeEnd + 3, fragmentIndex);
        }

        URI parsed;
        try {
            parsed = new URI(httpUri);
        } catch (URISyntaxException e) {
            throw invalidUri("Hysteria2", e, schemeEnd + 3);
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
        if (transportType == TransportType.TCP
                && "http".equalsIgnoreCase(params.get("headerType"))) {
            throw unsupportedTcpHeader();
        }
        config.getTransport().setType(transportType);

        // Decoded once already, with the rest of the query. A second pass
        // turned an encoded + into a space and threw on an encoded %.
        String path = params.get("path");
        if (path != null && !path.isBlank()) {
            config.getTransport().setPath(path);
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
        applyTlsParams(config, params, "none");
    }

    /**
     * Applies the TLS and REALITY query parameters that every protocol
     * carrying them shares.
     *
     * <p>{@code defaultSecurity} is what a link means when it names none: a
     * VLESS link is plain unless it says otherwise, a Trojan link is TLS by
     * definition.</p>
     */
    private void applyTlsParams(ServerConfig config, Map<String, String> params,
                                String defaultSecurity) {
        String security = params.getOrDefault("security", defaultSecurity);
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

        // Both spellings circulate: allowInsecure=1 (v2rayN, Xray) and
        // insecure=1 (sing-box-flavoured links).
        if ("1".equals(params.get("allowInsecure")) || "1".equals(params.get("insecure"))) {
            config.getTls().setAllowInsecure(true);
        }
    }

    private String mapVmessNet(String net) {
        return switch (net.toLowerCase(Locale.ROOT)) {
            case "h2" -> "http";
            // sing-box has no mKCP, and dialling a KCP server over TCP never connects.
            case "kcp" -> throw unsupportedTransport("kcp");
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

    private static String decodeBase64(String encoded) {
        return Base64Lenient.decodeUtf8(encoded);
    }

    private TransportType parseTransportType(String type) {
        // Other names for transports the core has. Xray calls TCP "raw" since
        // v24.9, and Marzban writes the inbound's network into the link as it
        // is; "h2" is the name HTTP/2 went by. Both were refused as missing
        // transports, which a subscription reports in the log only.
        switch (type.strip().toLowerCase(Locale.ROOT)) {
            case "raw" -> {
                return TransportType.TCP;
            }
            case "h2" -> {
                return TransportType.HTTP2;
            }
            default -> {
                // Looked up by its own name below.
            }
        }
        for (TransportType t : TransportType.values()) {
            if (t.getValue().equalsIgnoreCase(type)) {
                return t;
            }
        }
        throw unsupportedTransport(type);
    }

    /**
     * A transport sing-box does not implement, such as Xray's xhttp. The link
     * itself is well-formed, so a list holding it is neither truncated nor
     * corrupt.
     */
    private static UnsupportedFeatureException unsupportedTransport(String type) {
        String name = type.toLowerCase(Locale.ROOT);
        return new UnsupportedFeatureException("transport " + name,
                I18n.get("refusal.transport", name));
    }

    /**
     * TCP disguised as HTTP ({@code headerType=http}). It used to be read as
     * plain TCP, which a server expecting the disguise does not answer.
     */
    private static UnsupportedFeatureException unsupportedTcpHeader() {
        return new UnsupportedFeatureException("TCP with an HTTP header",
                I18n.get("refusal.tcp.http.header"));
    }

    /**
     * Refuses Xray's VLESS encryption: a server set up for it takes no plain
     * VLESS, and sing-box has no other. The values that ask for none pass,
     * VMess words some panels write there included. The refusal names the
     * method only: the client's keys follow its first dot.
     */
    private static void refuseVlessEncryption(String encryption) {
        String value = encryption.strip();
        if (NO_VLESS_ENCRYPTION.contains(value.toLowerCase(Locale.ROOT))) {
            return;
        }
        String method = value.split("\\.", 2)[0];
        if (method.length() > MAX_FEATURE_LENGTH) {
            method = method.substring(0, MAX_FEATURE_LENGTH);
        }
        throw new UnsupportedFeatureException("VLESS encryption " + method,
                I18n.get("refusal.vless.encryption", method));
    }

    /**
     * Refuses Shadowsocks over another transport or over TLS, as 3x-ui hands
     * it out ({@code type=ws&security=tls}). sing-box runs Shadowsocks over
     * TCP only, and read as plain Shadowsocks such a link reached a server
     * that does not answer it.
     */
    private void refuseShadowsocksTransport(String query) {
        Map<String, String> params = parseQueryParams(query);
        String type = params.getOrDefault("type", "tcp").strip();
        if (!type.isEmpty() && !"tcp".equalsIgnoreCase(type) && !"raw".equalsIgnoreCase(type)) {
            throw unsupportedShadowsocksCarrier(type.toLowerCase(Locale.ROOT));
        }
        String security = params.getOrDefault("security", "none").strip();
        if (!security.isEmpty() && !"none".equalsIgnoreCase(security)) {
            throw unsupportedShadowsocksCarrier(security.toUpperCase(Locale.ROOT));
        }
        if ("http".equalsIgnoreCase(params.get("headerType"))) {
            throw unsupportedTcpHeader();
        }
    }

    private static UnsupportedFeatureException unsupportedShadowsocksCarrier(String name) {
        return new UnsupportedFeatureException("Shadowsocks over " + name,
                I18n.get("refusal.shadowsocks.transport", name));
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
