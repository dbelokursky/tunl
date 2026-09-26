package com.vlessclient.service.outbound;

import com.vlessclient.app.I18n;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TlsConfig;
import com.vlessclient.model.TransportConfig;
import com.vlessclient.model.TransportType;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * What sing-box accepts in a server's settings, in one place: how a setting
 * has to be spelled for the core, and which settings it refuses however they
 * are spelled.
 *
 * <p>The builders write settings through the spelling methods, so a link that
 * spells one differently still connects. {@link #refusal} answers for the
 * rest before a server is stored: the core refuses a whole configuration over
 * one server it cannot build, and a link it will refuse is better reported at
 * import, with the reason, than stored and then left out of every connect.
 * The rules follow sing-box 1.14's own answers, and
 * {@code SingBoxRealBinarySmokeTest} checks that they still agree with the
 * pinned core.</p>
 */
public final class CoreSettings {

    /** The uTLS fingerprint a server gets when its link names none. */
    private static final String DEFAULT_FINGERPRINT = "chrome";

    /** The uTLS fingerprints the core knows. */
    private static final Set<String> FINGERPRINTS = Set.of(
            "chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android",
            "random", "randomized", "chrome_psk", "chrome_psk_shuffle",
            "chrome_padding_psk_shuffle", "chrome_pq", "chrome_pq_psk");

    /** The Shadowsocks ciphers the core knows, under its own names. */
    private static final Set<String> SHADOWSOCKS_METHODS = Set.of(
            "none", "aes-128-gcm", "aes-192-gcm", "aes-256-gcm",
            "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
            "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm",
            "2022-blake3-chacha20-poly1305",
            "aes-128-ctr", "aes-192-ctr", "aes-256-ctr",
            "aes-128-cfb", "aes-192-cfb", "aes-256-cfb",
            "rc4-md5", "chacha20-ietf", "xchacha20");

    /** The one VLESS flow the core implements. */
    private static final String VISION_FLOW = "xtls-rprx-vision";

    /** A REALITY short ID: whole bytes in hex, at most eight of them. */
    private static final Pattern SHORT_ID = Pattern.compile("(?:[0-9a-fA-F]{2}){0,8}");

    private static final int REALITY_KEY_BYTES = 32;

    /** The protocols whose servers carry a V2Ray transport block. */
    private static final Set<Protocol> TRANSPORT_PROTOCOLS =
            Set.of(Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN);

    /** The modes of obfs-local the core runs. */
    private static final Set<String> OBFS_MODES = Set.of("http", "tls");

    /** The modes of v2ray-plugin the core runs; quic only with tls. */
    private static final Set<String> V2RAY_PLUGIN_MODES = Set.of("websocket", "quic");

    private CoreSettings() {
    }

    /**
     * A reason the core would refuse a server however its settings are spelled.
     *
     * @param feature what the server asks for, short enough for a list of them,
     *                e.g. {@code flow xtls-rprx-vision-udp443}
     * @param reason  the sentence to show for one server, in the language of
     *                the UI when the refusal was made
     */
    public record Refusal(String feature, String reason) {
    }

    /**
     * The uTLS fingerprint to send: the link's own, in the lower case the core
     * matches on, or Chrome's when the link names none. A link that leaves the
     * fingerprint out means "any", not "none". The core refuses a REALITY
     * client without uTLS, and plain TLS without it went out with Go's own
     * ClientHello, which no browser sends and DPI tells apart; Chrome is also
     * the default of other clients. Over WebSocket the core still offers
     * HTTP/1.1 alone, which a front needs to upgrade the connection.
     *
     * @param tls the server's TLS settings
     * @return the fingerprint
     */
    public static String fingerprint(TlsConfig tls) {
        String fingerprint = tls.getFingerprint();
        if (fingerprint != null && !fingerprint.isBlank()) {
            return fingerprint.strip().toLowerCase(Locale.ROOT);
        }
        return DEFAULT_FINGERPRINT;
    }

    /**
     * The uTLS fingerprint to send for this server: {@link #fingerprint}, or
     * none when its TLS runs inside QUIC, Hysteria2's or the QUIC transport's.
     * The core passes {@code check} with uTLS there, then fails every
     * connection with "unsupported usage for uTLS", for a fingerprint the
     * link set as for the default.
     *
     * @param server the server to connect to
     * @return the fingerprint, or null when uTLS cannot be used
     */
    public static String fingerprintToSend(ServerConfig server) {
        TransportConfig transport = server.getTransport();
        boolean overQuic = server.getProtocol() == Protocol.HYSTERIA2
                || transport != null && transport.getType() == TransportType.QUIC;
        return overQuic ? null : fingerprint(server.getTls());
    }

    /**
     * A REALITY key as the core decodes it: base64url without padding. Links
     * also carry the same bytes in the standard alphabet with padding, which
     * the core refuses.
     *
     * @param key the key as stored
     * @return the key in base64url without padding
     */
    public static String realityPublicKey(String key) {
        String converted = key.strip().replace('+', '-').replace('/', '_');
        int end = converted.length();
        while (end > 0 && converted.charAt(end - 1) == '=') {
            end--;
        }
        return converted.substring(0, end);
    }

    /**
     * The Shadowsocks cipher under the name the core knows. Links written for
     * Xray use its names for the same ciphers, and some spell them in capitals;
     * the core matches the exact lower-case name and refuses anything else.
     *
     * @param method the cipher as stored
     * @return the cipher under the core's name, or null when there is none
     */
    public static String shadowsocksMethod(String method) {
        if (method == null) {
            return null;
        }
        String name = method.strip().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "chacha20-poly1305" -> "chacha20-ietf-poly1305";
            case "xchacha20-poly1305" -> "xchacha20-ietf-poly1305";
            case "plain" -> "none";
            default -> name;
        };
    }

    /**
     * A REALITY short ID as the core decodes it: hex, without the spaces a
     * link or an edit can leave around it. The core refused " 0123abcd"
     * ("encoding/hex: invalid byte"), while {@link #refusal}, which strips
     * it, let the server be stored.
     *
     * @param shortId the short ID as stored
     * @return the short ID, or an empty string when there is none
     */
    public static String realityShortId(String shortId) {
        return shortId == null ? "" : shortId.strip();
    }

    /**
     * A Shadowsocks password as the core reads it. A 2022 cipher takes one
     * key, or a server key and a user key separated by a colon, and decodes
     * each as standard base64 with padding; links also carry the same bytes
     * without the padding or in the URL alphabet, which the core refused
     * ("decode key: illegal base64 data"). The older ciphers take any string,
     * so their password is written as it is.
     *
     * @param method   the cipher as stored
     * @param password the password as stored
     * @return the password to write
     */
    public static String shadowsocksPassword(String method, String password) {
        String name = shadowsocksMethod(method);
        if (password == null || name == null || !name.startsWith("2022-")) {
            return password;
        }
        StringJoiner keys = new StringJoiner(":");
        for (String key : password.split(":", -1)) {
            keys.add(standardBase64(key));
        }
        return keys.toString();
    }

    /** Base64 in the standard alphabet, padded; a length no base64 has is left as it is. */
    private static String standardBase64(String value) {
        String converted = value.strip().replace('-', '+').replace('_', '/');
        int end = converted.length();
        while (end > 0 && converted.charAt(end - 1) == '=') {
            end--;
        }
        String bare = converted.substring(0, end);
        return switch (bare.length() % 4) {
            case 2 -> bare + "==";
            case 3 -> bare + "=";
            default -> bare;
        };
    }

    /**
     * A SIP003 plugin under the name the core knows: simple-obfs is the old
     * name of obfs-local, and the core knows only the new one ("plugin not
     * found: simple-obfs").
     *
     * @param plugin the plugin as stored
     * @return the plugin to write
     */
    public static String shadowsocksPlugin(String plugin) {
        if (plugin == null) {
            return null;
        }
        String name = plugin.strip();
        return "simple-obfs".equalsIgnoreCase(name) ? "obfs-local" : name;
    }

    /**
     * A WireGuard interface address as the prefix the core requires. An
     * address a {@code .conf} file or the form gives without one means a
     * single host, so it gets /32 or /128; the core refused the bare address
     * outright.
     *
     * @param address the address as stored
     * @return the address with a prefix length
     */
    public static String interfaceAddress(String address) {
        if (address.contains("/")) {
            return address;
        }
        return address + (address.contains(":") ? "/128" : "/32");
    }

    /**
     * Why the core would refuse this server however its settings are spelled:
     * a port outside 1 to 65535 (the check lets 0 through, but nothing dials
     * it), a VLESS flow other than Vision, an unknown uTLS fingerprint, a
     * REALITY key or short ID the core cannot decode, the QUIC transport
     * without TLS, an unknown Shadowsocks cipher, a Shadowsocks 2022 key of
     * the wrong length, or a SIP003 plugin or plugin mode the core does not
     * have. A setting the spelling methods correct is not a refusal.
     *
     * @param server the server to check
     * @return the refusal, or empty when the core accepts the server
     */
    public static Optional<Refusal> refusal(ServerConfig server) {
        int port = server.getPort();
        if (port < 1 || port > 65535) {
            return refused("port " + port, "refusal.port", String.valueOf(port));
        }
        String flow = server.getFlow() == null ? "" : server.getFlow().strip();
        if (server.getProtocol() == Protocol.VLESS && !flow.isEmpty()
                && !VISION_FLOW.equals(flow)) {
            return refused("flow " + flow, "refusal.flow", flow);
        }
        TlsConfig tls = server.getTls();
        boolean tlsEnabled = tls != null && tls.isEnabled();
        if (tlsEnabled) {
            Optional<Refusal> refusal = tlsRefusal(tls);
            if (refusal.isPresent()) {
                return refusal;
            }
        }
        if (!tlsEnabled && TRANSPORT_PROTOCOLS.contains(server.getProtocol())
                && server.getTransport() != null
                && server.getTransport().getType() == TransportType.QUIC) {
            // "create client transport: quic: TLS required"
            return refused("QUIC without TLS", "refusal.quic.tls");
        }
        if (server.getProtocol() == Protocol.SHADOWSOCKS) {
            return shadowsocksRefusal(server);
        }
        if (server.getProtocol() == Protocol.HYSTERIA2) {
            return hysteria2Refusal(server);
        }
        return Optional.empty();
    }

    private static Optional<Refusal> hysteria2Refusal(ServerConfig server) {
        String ports = server.getServerPorts();
        if (ports != null && !HysteriaPorts.isValid(ports)) {
            // "bad port range"
            return refused("server ports " + ports, "refusal.hysteria2.ports", ports);
        }
        String obfs = server.getEncryption() == null ? ""
                : server.getEncryption().strip().toLowerCase(java.util.Locale.ROOT);
        boolean obfuscated = server.getFlow() != null && !server.getFlow().isBlank();
        if (obfuscated && !obfs.isEmpty() && !"salamander".equals(obfs)
                && !"gecko".equals(obfs) && !"none".equals(obfs)) {
            // "unknown obfs type"
            return refused("obfs " + obfs, "refusal.hysteria2.obfs", obfs);
        }
        return Optional.empty();
    }

    private static Optional<Refusal> tlsRefusal(TlsConfig tls) {
        String fingerprint = fingerprint(tls);
        if (fingerprint != null && !FINGERPRINTS.contains(fingerprint)) {
            return refused("uTLS fingerprint " + fingerprint,
                    "refusal.fingerprint", fingerprint);
        }
        if (!tls.isReality()) {
            return Optional.empty();
        }
        String key = tls.getRealityPublicKey() == null ? "" : tls.getRealityPublicKey();
        if (decodedLength(key) != REALITY_KEY_BYTES) {
            return refused("REALITY public key", "refusal.reality.key");
        }
        String shortId = tls.getRealityShortId() == null ? "" : tls.getRealityShortId().strip();
        if (!SHORT_ID.matcher(shortId).matches()) {
            return refused("REALITY short ID", "refusal.reality.short.id");
        }
        return Optional.empty();
    }

    private static Optional<Refusal> shadowsocksRefusal(ServerConfig server) {
        String method = shadowsocksMethod(server.getEncryption());
        if (method == null || !SHADOWSOCKS_METHODS.contains(method)) {
            return refused("cipher " + method, "refusal.cipher", method);
        }
        if (method.startsWith("2022-")) {
            int bytes = "2022-blake3-aes-128-gcm".equals(method) ? 16 : 32;
            String password = server.getUuid() == null ? "" : server.getUuid();
            // A multi-user server is given the server key and the user key, colon-separated.
            for (String key : password.split(":", -1)) {
                if (decodedLength(key) != bytes) {
                    return refused("Shadowsocks 2022 key", "refusal.ss2022.key",
                            method, String.valueOf(bytes));
                }
            }
        }
        return pluginRefusal(server.getPlugin(), server.getPluginOpts());
    }

    /**
     * Why the core would refuse a SIP003 plugin: one it does not have ("plugin
     * not found"), or a mode it does not run. It has obfs-local, in http and
     * tls mode ("unknown obfs mode"), and v2ray-plugin, in websocket mode and
     * in quic mode with tls ("unknown mode", "TLS required"). Names and modes
     * match exactly, as in the core.
     */
    private static Optional<Refusal> pluginRefusal(String plugin, String options) {
        String name = shadowsocksPlugin(plugin);
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> settings = pluginOptions(options);
        switch (name) {
            case "obfs-local" -> {
                String mode = settings.getOrDefault("obfs", "http");
                if (!OBFS_MODES.contains(mode)) {
                    return refused("obfs-local mode " + mode, "refusal.plugin.mode", name, mode);
                }
                return Optional.empty();
            }
            case "v2ray-plugin" -> {
                String mode = settings.getOrDefault("mode", "websocket");
                if ("quic".equals(mode) && !settings.containsKey("tls")) {
                    return refused("v2ray-plugin QUIC without TLS", "refusal.plugin.quic.tls");
                }
                if (!V2RAY_PLUGIN_MODES.contains(mode)) {
                    return refused("v2ray-plugin mode " + mode, "refusal.plugin.mode", name, mode);
                }
                return Optional.empty();
            }
            default -> {
                return refused("plugin " + name, "refusal.plugin", name);
            }
        }
    }

    /**
     * SIP003 plugin options, {@code key=value;flag}, as the core splits them;
     * a flag such as {@code tls} maps to an empty value. Nothing is trimmed,
     * since the core trims nothing either.
     */
    private static Map<String, String> pluginOptions(String options) {
        Map<String, String> settings = new HashMap<>();
        if (options == null || options.isEmpty()) {
            return settings;
        }
        for (String option : options.split(";")) {
            int equals = option.indexOf('=');
            String key = equals < 0 ? option : option.substring(0, equals);
            if (!key.isEmpty()) {
                settings.put(key, equals < 0 ? "" : option.substring(equals + 1));
            }
        }
        return settings;
    }

    /** How many bytes a base64 value in either alphabet decodes to, or -1 when it does not. */
    private static int decodedLength(String value) {
        try {
            return Base64.getUrlDecoder().decode(realityPublicKey(value)).length;
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    /**
     * A refusal whose reason is worded in the language of the UI, since it is
     * shown to the user: in the import report, the server form and the
     * Dashboard. The feature stays English, for the log.
     */
    private static Optional<Refusal> refused(String feature, String reasonKey, Object... args) {
        return Optional.of(new Refusal(feature, I18n.get(reasonKey, args)));
    }
}
