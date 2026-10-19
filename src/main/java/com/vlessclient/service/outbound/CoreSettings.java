package com.vlessclient.service.outbound;

import com.vlessclient.app.I18n;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TlsConfig;
import com.vlessclient.model.TransportConfig;
import com.vlessclient.model.TransportType;
import com.vlessclient.platform.SecretSealer;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** The fingerprint that picks one browser's hello for the whole run. */
    public static final String RANDOM_PICK = "random";

    /**
     * What a REALITY server is sent in place of a fingerprint whose hello
     * lacks the X25519MLKEM768 key share that Xray 26.9.8 and later require
     * ahead of X25519. The core's hellos for these are Edge 85, QQ 11.1,
     * iOS 14, 360 7.5 and OkHttp on Android 11, the last two TLS 1.2 only,
     * with which REALITY never connected; {@link #RANDOM_PICK} picks one
     * hello for the whole run, Edge's and iOS's among them. Every browser on
     * iOS uses Apple's TLS, so {@code ios} goes as Safari 26.3's hello. The
     * only current hello the core has for the Chromium browsers and Android
     * is Chrome's, which TSPU is reported to single out, and a link that
     * names another browser may do so to stay clear of it; so they go as
     * {@code randomized}, as {@code random} does, which stays random and
     * which the bundled core gives the share.
     */
    private static final Map<String, String> REALITY_REPLACEMENTS = Map.of(
            RANDOM_PICK, "randomized",
            "edge", "randomized",
            "qq", "randomized",
            "360", "randomized",
            "android", "randomized",
            "ios", "safari");

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

    /** A WireGuard key, as {@code wg genkey} writes it: 32 bytes in padded base64. */
    private static final int WIREGUARD_KEY_BYTES = 32;
    private static final int WIREGUARD_KEY_CHARS = 44;

    /** The protocols whose servers carry a V2Ray transport block. */
    private static final Set<Protocol> TRANSPORT_PROTOCOLS =
            Set.of(Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN);

    /** The modes of obfs-local the core runs. */
    private static final Set<String> OBFS_MODES = Set.of("http", "tls");

    /** The modes of v2ray-plugin the core runs; quic only with tls. */
    private static final Set<String> V2RAY_PLUGIN_MODES = Set.of("websocket", "quic");

    /**
     * The VMess ciphers the core takes. Its documentation names aes-128-ctr
     * as the legacy one, which its check refuses ("unsupported security
     * type"): listed here, a link with that scy was sent as it was and never
     * connected, where any other cipher the core does not know goes as auto.
     */
    private static final Set<String> VMESS_CIPHERS = Set.of(
            "auto", "none", "zero", "aes-128-gcm", "chacha20-poly1305");

    /** The Shadowsocks 2022 cipher that takes one key, the server's, and no user key. */
    private static final String SS2022_CHACHA = "2022-blake3-chacha20-poly1305";

    /** A number as Go's strconv.Atoi reads it, the way the core reads v2ray-plugin's mux. */
    private static final Pattern GO_INT = Pattern.compile("[+-]?[0-9]+");

    /** The transports whose path the core reads as escaped, the way a URL holds it. */
    private static final Set<TransportType> ESCAPED_PATHS = Set.of(
            TransportType.WEBSOCKET, TransportType.HTTP2, TransportType.HTTPUPGRADE);

    /** The VMess ciphers that leave the payload as it is. */
    private static final Set<String> VMESS_CLEAR = Set.of("none", "zero");

    /** The parameter of a WebSocket path that Xray's links ask for early data with. */
    private static final String EARLY_DATA_PARAMETER = "ed";

    /** The header Xray's servers read WebSocket early data from. */
    public static final String EARLY_DATA_HEADER = "Sec-WebSocket-Protocol";

    /** The most digits an early data size can have and still be an int. */
    private static final int MAX_EARLY_DATA_DIGITS = 9;

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
     * the one {@link #realityReplacement} names in its place, or none when its
     * TLS runs inside QUIC, Hysteria2's or the QUIC transport's. The core
     * passes {@code check} with uTLS there, then fails every connection with
     * "unsupported usage for uTLS", for a fingerprint the link set as for the
     * default.
     *
     * @param server the server to connect to
     * @return the fingerprint, or null when uTLS cannot be used
     */
    public static String fingerprintToSend(ServerConfig server) {
        TransportConfig transport = server.getTransport();
        boolean overQuic = server.getProtocol() == Protocol.HYSTERIA2
                || transport != null && transport.getType() == TransportType.QUIC;
        if (overQuic) {
            return null;
        }
        return realityReplacement(server.getTls()).orElseGet(() -> fingerprint(server.getTls()));
    }

    /**
     * The fingerprint a REALITY server is sent in place of the one its link
     * names, when the core would answer that one with a hello Xray 26.9.8 and
     * later turn away: one without an X25519MLKEM768 key share ahead of
     * X25519 ({@link #REALITY_REPLACEMENTS} says which).
     *
     * <p>Such a server turns the link's fingerprint away, from Xray's own
     * client too, while the replacement connects to older servers as well.
     * None goes as Chrome's, the hello that would always work: links ask for
     * a random hello, or name another browser, where TSPU is reported to
     * single out the uTLS Chrome profile. The server keeps its link's value,
     * which a subscription refresh would bring back anyway; plain TLS sends
     * it as it is, since an ordinary TLS server takes any hello.
     * {@code packaging/sing-box/verify.sh} checks the hello of every
     * fingerprint this leaves a REALITY server with.</p>
     *
     * @param tls the server's TLS settings, or null when it has none
     * @return the fingerprint sent instead, or empty when the link's goes as it is
     */
    public static Optional<String> realityReplacement(TlsConfig tls) {
        if (tls == null || !tls.isEnabled() || !tls.isReality()) {
            return Optional.empty();
        }
        return Optional.ofNullable(REALITY_REPLACEMENTS.get(fingerprint(tls)));
    }

    /**
     * The uTLS fingerprints the core knows, as a link or the form may name
     * them.
     *
     * @return the fingerprints, unmodifiable
     */
    static Set<String> fingerprints() {
        return FINGERPRINTS;
    }

    /**
     * The VMess cipher to ask the core for: the server's own, as a link's
     * {@code scy} or the form sets it, or {@code auto} for one the core does
     * not know.
     *
     * <p>The two that leave the payload as it is, {@code none} and
     * {@code zero}, are asked for only inside TLS: without it, nothing else
     * encrypts the traffic. The core was given {@code auto} whatever the
     * server said, and a server made in the form was stored with
     * {@code none}, the form's default, so honouring it bare would have sent
     * those servers' traffic in the clear.</p>
     *
     * @param server the VMess server
     * @return the value of the outbound's {@code security}
     */
    public static String vmessSecurity(ServerConfig server) {
        String cipher = server.getEncryption() == null ? ""
                : server.getEncryption().strip().toLowerCase(Locale.ROOT);
        if (!VMESS_CIPHERS.contains(cipher)) {
            return "auto";
        }
        boolean tls = server.getTls() != null && server.getTls().isEnabled();
        return VMESS_CLEAR.contains(cipher) && !tls ? "auto" : cipher;
    }

    /**
     * The DNS strategy to ask the core for: the stored one, or the default,
     * {@code prefer_ipv4}, for one it does not take. An agent could store any
     * word through MCP, and the core refused the whole configuration over it.
     *
     * @param stored the stored strategy
     * @return one of {@link AppSettings#DNS_STRATEGIES}
     */
    public static String dnsStrategy(String stored) {
        String strategy = stored == null ? "" : stored.strip().toLowerCase(Locale.ROOT);
        return AppSettings.DNS_STRATEGIES.contains(strategy)
                ? strategy : AppSettings.DNS_STRATEGIES.getFirst();
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
     * The interface addresses of a WireGuard server, listed separated by
     * commas or spaces, as wg-quick reads them, each address once, in the
     * first spelling given. The core refuses an interface that has one
     * address twice, under any prefix length or spelling ("duplicate
     * address"), and the endpoint of every member of a group goes into one
     * configuration: a {@code .conf} that repeated its Address line, or a
     * form that listed an address twice, stopped every connect of the group.
     * Two addresses with a space between them were read as one, which the
     * core could not read and refused the configuration over.
     *
     * @param addresses the addresses as stored, separated by commas or spaces
     * @return the distinct addresses, stripped, as written
     */
    public static List<String> interfaceAddresses(String addresses) {
        Map<String, String> distinct = new LinkedHashMap<>();
        for (String part : addresses.split("[,\\s]+")) {
            String address = part.strip();
            if (!address.isEmpty()) {
                distinct.putIfAbsent(addressKey(address), address);
            }
        }
        return List.copyOf(distinct.values());
    }

    /**
     * The path of a transport as the core is to send it: for WebSocket and
     * HTTPUpgrade, without the {@code ed} parameter Xray's links ask for early
     * data with, {@code /ws?ed=2048}. Xray's client takes the parameter out
     * and its server compares the path without it, while the core sent the
     * path as stored, with the {@code ?} escaped, {@code GET /ws%3Fed=2048},
     * which such a server answers with 404. The stored path keeps the
     * parameter, so a copied link still asks the clients that read it for
     * early data. The other parameters of the path stay in it.
     *
     * @param transport the server's transport
     * @return the path to send, or null when there is none
     */
    public static String transportPath(TransportConfig transport) {
        if (transport.getType() != TransportType.WEBSOCKET
                && transport.getType() != TransportType.HTTPUPGRADE) {
            return transport.getPath();
        }
        return earlyDataIn(transport.getPath()).path();
    }

    /**
     * How many bytes of early data a WebSocket server takes, as its path asks
     * with {@code ed=}: the core sends them in {@link #EARLY_DATA_HEADER}, as
     * Xray does. The core's HTTPUpgrade has no early data, and a size that is
     * no number asks for none, as in Xray.
     *
     * @param transport the server's transport
     * @return the size, or 0 when the server is sent none
     */
    public static int webSocketEarlyData(TransportConfig transport) {
        if (transport.getType() != TransportType.WEBSOCKET) {
            return 0;
        }
        return earlyDataIn(transport.getPath()).size();
    }

    /** A path without its early data parameter, and the size that parameter asked for. */
    private record EarlyData(String path, int size) {
    }

    /**
     * Takes Xray's early data parameter out of a path, as Xray does: every
     * {@code ed} goes once the first one has a value, and a value that is no
     * number is a size of 0.
     */
    private static EarlyData earlyDataIn(String path) {
        int query = path == null ? -1 : path.indexOf('?');
        if (query < 0) {
            return new EarlyData(path, 0);
        }
        StringJoiner kept = new StringJoiner("&");
        String size = null;
        for (String parameter : path.substring(query + 1).split("&")) {
            int equals = parameter.indexOf('=');
            String name = equals < 0 ? parameter : parameter.substring(0, equals);
            if (!EARLY_DATA_PARAMETER.equals(name)) {
                if (!parameter.isEmpty()) {
                    kept.add(parameter);
                }
            } else if (size == null) {
                size = equals < 0 ? "" : parameter.substring(equals + 1);
            }
        }
        if (size == null || size.isEmpty()) {
            return new EarlyData(path, 0);
        }
        String rest = kept.toString();
        String stripped = path.substring(0, query) + (rest.isEmpty() ? "" : "?" + rest);
        boolean number = size.length() <= MAX_EARLY_DATA_DIGITS
                && size.chars().allMatch(c -> c >= '0' && c <= '9');
        return new EarlyData(stripped, number ? Integer.parseInt(size) : 0);
    }

    /**
     * An interface address without its prefix length, as the core compares
     * them: {@code FD42:42:0::2} and {@code fd42:42::2} are one address. An
     * IPv4 address written as IPv6 stays apart, as it does in the core.
     */
    private static String addressKey(String address) {
        int slash = address.indexOf('/');
        String ip = (slash >= 0 ? address.substring(0, slash) : address).strip();
        try {
            InetAddress literal = InetAddress.ofLiteral(ip);
            return (ip.indexOf(':') >= 0 ? "6 " : "4 ") + literal.getHostAddress();
        } catch (IllegalArgumentException e) {
            // No address the core takes either; compared as written.
            return ip.toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Why the core would refuse this server however its settings are spelled:
     * a port outside 1 to 65535 (the check lets 0 through, but nothing dials
     * it), a VLESS flow other than Vision, an unknown uTLS fingerprint, a
     * REALITY key or short ID the core cannot decode, REALITY over QUIC, the
     * QUIC transport without TLS, a transport path with a {@code %} that
     * starts no escape, an unknown Shadowsocks cipher, a Shadowsocks 2022 key
     * of the wrong length or a server key for its ChaCha20 cipher, a SIP003
     * plugin, plugin mode or v2ray-plugin mux the core does not have, or a
     * WireGuard key it cannot decode or a WireGuard server with no local
     * address. A setting the spelling methods correct is not a refusal.
     *
     * @param server the server to check
     * @return the refusal, or empty when the core accepts the server
     */
    public static Optional<Refusal> refusal(ServerConfig server) {
        if (server.getProtocol() == null) {
            // A servers.json entry edited by hand can lose its protocol. The
            // set lookups below threw on it, and as every server is checked
            // at every connect, no connect worked, whichever was picked.
            return refused("no protocol", "refusal.protocol");
        }
        if (hasUnreadableCredential(server)) {
            // Kept as its sealed tag so the entry recovers once the keychain
            // does; handed to the core, the tag failed in words about neither.
            return refused("unreadable credential", "refusal.credential.unreadable");
        }
        if (holdsLoneSurrogate(server)) {
            // Half an emoji, from a link whose JSON escaped one: the
            // configuration, which holds every server, could not be written
            // as UTF-8, and every connect failed with an error naming none.
            return refused("broken character", "refusal.broken.character");
        }
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
            if (tls.isReality() && fingerprintToSend(server) == null) {
                // "uTLS is required by reality client": the hello REALITY
                // needs goes out through uTLS, which QUIC cannot carry.
                return refused("REALITY over QUIC", "refusal.reality.quic");
            }
        }
        if (!tlsEnabled && TRANSPORT_PROTOCOLS.contains(server.getProtocol())
                && server.getTransport() != null
                && server.getTransport().getType() == TransportType.QUIC) {
            // "create client transport: quic: TLS required"
            return refused("QUIC without TLS", "refusal.quic.tls");
        }
        // A null type, which a servers.json or a backup written by hand can
        // hold, is plain TCP to the builders, and Set.of throws on a null
        // lookup: every connect, and every restore holding one, failed on it.
        if (TRANSPORT_PROTOCOLS.contains(server.getProtocol()) && server.getTransport() != null
                && server.getTransport().getType() != null
                && ESCAPED_PATHS.contains(server.getTransport().getType())
                && !isEscapedPath(transportPath(server.getTransport()))) {
            // "create client transport: ws: parse path: invalid URL escape "%"".
            // Not quoted: a path can hold an account's token.
            return refused("transport path", "refusal.transport.path");
        }
        if (server.getProtocol() == Protocol.SHADOWSOCKS) {
            return shadowsocksRefusal(server);
        }
        if (server.getProtocol() == Protocol.HYSTERIA2) {
            return hysteria2Refusal(server);
        }
        if (server.getProtocol() == Protocol.WIREGUARD) {
            return wireguardRefusal(server);
        }
        return Optional.empty();
    }

    /**
     * Why the core would not start a WireGuard server: a private or peer key
     * that is not 32 bytes in padded standard base64, no local address, or
     * one the core cannot read.
     *
     * <p>None of them was refused. A key the core cannot decode fails its
     * check, but a missing peer key or one of the wrong length passes it, and
     * the core then stops at start printing the interface's settings, the
     * private key among them, which the app log kept. A server with no local
     * address starts and carries nothing, and one the core cannot read as an
     * address with a prefix length, such as 10.0.0.2/33, made it refuse the
     * whole configuration.</p>
     */
    private static Optional<Refusal> wireguardRefusal(ServerConfig server) {
        if (!isWireguardKey(server.getUuid())) {
            // "decode private key: illegal base64 data at input byte 11"
            return refused("WireGuard private key", "refusal.wireguard.private.key");
        }
        if (!isWireguardKey(server.getEncryption())) {
            // "failed to get peer by public key: hex string does not fit the slice"
            return refused("WireGuard peer key", "refusal.wireguard.peer.key");
        }
        List<String> addresses = server.getFlow() == null
                ? List.of() : interfaceAddresses(server.getFlow());
        if (addresses.isEmpty()) {
            return refused("WireGuard local address", "refusal.wireguard.address");
        }
        for (String address : addresses) {
            if (!isInterfacePrefix(interfaceAddress(address))) {
                // "endpoints[0].address: ... netip.ParsePrefix("10.0.0.2/33"):
                // prefix length out of range"
                return refused("WireGuard local address", "refusal.wireguard.address.invalid",
                        shown(address));
            }
        }
        return Optional.empty();
    }

    /** The most of a stored value a refusal repeats. */
    private static final int MAX_SHOWN_LENGTH = 40;

    /** A stored value as a refusal repeats it: control characters as spaces, and capped. */
    private static String shown(String value) {
        String cleaned = value.replaceAll("\\p{Cntrl}", " ");
        return cleaned.length() > MAX_SHOWN_LENGTH
                ? cleaned.substring(0, MAX_SHOWN_LENGTH) + "…" : cleaned;
    }

    /**
     * Whether {@code prefix} is an address with a prefix length that the core
     * reads, as Go's netip.ParsePrefix does: an IPv4 address in four decimal
     * fields without leading zeros, or an IPv6 address without a zone, then
     * a length without leading zeros no longer than the address. Java's own
     * parsers take forms Go refuses, such as 010.0.0.2 or a group of five
     * digits.
     */
    private static boolean isInterfacePrefix(String prefix) {
        int slash = prefix.lastIndexOf('/');
        if (slash < 0) {
            return false;
        }
        String address = prefix.substring(0, slash);
        int maxBits = -1;
        // As netip.ParseAddr tells the families apart: by the first of these.
        for (int i = 0; i < address.length() && maxBits < 0; i++) {
            switch (address.charAt(i)) {
                case '.' -> maxBits = isIpv4(address) ? 32 : 0;
                case ':' -> maxBits = isIpv6(address) ? 128 : 0;
                case '%' -> maxBits = 0;
                default -> {
                    // Part of the first field.
                }
            }
        }
        String bits = prefix.substring(slash + 1);
        if (maxBits <= 0 || bits.isEmpty() || bits.length() > 3
                || bits.length() > 1 && bits.charAt(0) == '0'
                || !bits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return false;
        }
        return Integer.parseInt(bits) <= maxBits;
    }

    /** An IPv4 address as netip reads one: four fields of up to 255, no leading zeros. */
    private static boolean isIpv4(String address) {
        int dots = 0;
        int digits = 0;
        int value = 0;
        for (int i = 0; i < address.length(); i++) {
            char c = address.charAt(i);
            if (c >= '0' && c <= '9') {
                if (digits == 1 && value == 0) {
                    return false;
                }
                value = value * 10 + c - '0';
                digits++;
                if (value > 255) {
                    return false;
                }
            } else if (c == '.' && digits > 0 && dots < 3 && i < address.length() - 1) {
                dots++;
                digits = 0;
                value = 0;
            } else {
                return false;
            }
        }
        return dots == 3 && digits > 0;
    }

    /**
     * An IPv6 address as netip reads one, with no zone, which a prefix
     * cannot have: groups of one to four hex digits, at most one {@code ::}
     * standing for at least one group, and an IPv4 address in place of the
     * last two.
     */
    private static boolean isIpv6(String address) {
        if (address.indexOf('%') >= 0) {
            return false;
        }
        int groups = 0;
        int ellipsis = -1;
        int at = 0;
        if (address.startsWith("::")) {
            ellipsis = 0;
            at = 2;
            if (at == address.length()) {
                return true;
            }
        }
        while (groups < 8) {
            int end = at;
            while (end < address.length() && HexFormat.isHexDigit(address.charAt(end))) {
                end++;
            }
            if (end == at || end - at > 4) {
                return false;
            }
            if (end < address.length() && address.charAt(end) == '.') {
                if (ellipsis < 0 && groups != 6 || groups > 6
                        || !isIpv4(address.substring(at))) {
                    return false;
                }
                groups += 2;
                at = address.length();
                break;
            }
            groups++;
            at = end;
            if (at == address.length()) {
                break;
            }
            if (address.charAt(at) != ':' || at + 1 == address.length()) {
                return false;
            }
            at++;
            if (address.charAt(at) == ':') {
                if (ellipsis >= 0) {
                    return false;
                }
                ellipsis = groups;
                at++;
                if (at == address.length()) {
                    break;
                }
            }
        }
        if (at != address.length()) {
            return false;
        }
        return groups < 8 ? ellipsis >= 0 : ellipsis < 0;
    }

    /**
     * Whether {@code key} is a WireGuard key the core decodes. It reads the
     * standard alphabet and requires the padding, which Java's decoder does
     * not, so the length is checked first.
     */
    private static boolean isWireguardKey(String key) {
        if (key == null || key.length() != WIREGUARD_KEY_CHARS) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(key).length == WIREGUARD_KEY_BYTES;
        } catch (IllegalArgumentException e) {
            return false;
        }
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

    /**
     * Whether a value the core is given for {@code server} holds half of a
     * surrogate pair, which no UTF-8 text can: its id, which tags it, its
     * address, credentials and plugin, and its TLS and transport settings.
     * The name is not given to the core.
     */
    private static boolean holdsLoneSurrogate(ServerConfig server) {
        List<String> values = new ArrayList<>();
        Collections.addAll(values, server.getId(), server.getAddress(), server.getUuid(),
                server.getEncryption(), server.getFlow(), server.getPlugin(),
                server.getPluginOpts(), server.getServerPorts());
        TlsConfig tls = server.getTls();
        if (tls != null) {
            Collections.addAll(values, tls.getServerName(), tls.getAlpn(),
                    tls.getFingerprint(), tls.getRealityPublicKey(), tls.getRealityShortId());
        }
        TransportConfig transport = server.getTransport();
        if (transport != null) {
            Collections.addAll(values, transport.getPath(), transport.getHost(),
                    transport.getServiceName());
            if (transport.getHeaders() != null) {
                transport.getHeaders().forEach((name, value) -> {
                    values.add(name);
                    values.add(value);
                });
            }
        }
        return values.stream().anyMatch(CoreSettings::hasLoneSurrogate);
    }

    private static boolean hasLoneSurrogate(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a credential of {@code server} is the sealed tag the keychain
     * did not return on load, standing in for it until it does: the uuid, or
     * the flow, which carries Hysteria2's obfuscation password.
     *
     * @param server the server
     * @return true when a credential could not be read
     */
    public static boolean hasUnreadableCredential(ServerConfig server) {
        return SecretSealer.isSealed(server.getUuid()) || SecretSealer.isSealed(server.getFlow());
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
            String[] keys = password.split(":", -1);
            for (String key : keys) {
                if (decodedLength(key) != bytes) {
                    return refused("Shadowsocks 2022 key", "refusal.ss2022.key",
                            method, String.valueOf(bytes));
                }
            }
            if (SS2022_CHACHA.equals(method) && keys.length > 1) {
                // "Shadowsocks 2022 EIH support only available in AES ciphers"
                return refused("Shadowsocks 2022 ChaCha20 with a server key",
                        "refusal.ss2022.chacha.keys", method);
            }
        }
        return pluginRefusal(server.getPlugin(), server.getPluginOpts());
    }

    /**
     * Whether a transport path is one the core can send: every {@code %} in
     * it starts an escape of two hex digits, as the core unescapes it.
     */
    private static boolean isEscapedPath(String path) {
        if (path == null) {
            return true;
        }
        for (int i = path.indexOf('%'); i >= 0; i = path.indexOf('%', i + 1)) {
            if (i + 2 >= path.length() || !HexFormat.isHexDigit(path.charAt(i + 1))
                    || !HexFormat.isHexDigit(path.charAt(i + 2))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Why the core would refuse a SIP003 plugin: one it does not have ("plugin
     * not found"), options it cannot read ("parse plugin_opts"), or a mode it
     * does not run. It has obfs-local, in http and tls mode ("unknown obfs
     * mode"), and v2ray-plugin, in websocket mode and in quic mode with tls
     * ("unknown mode", "TLS required"), whose mux in websocket mode has to be
     * a number ("parse mux value") and whose path there is read as escaped,
     * as the WebSocket transport's is ("parse path"). Names and modes match
     * exactly, as in the core.
     */
    private static Optional<Refusal> pluginRefusal(String plugin, String options) {
        String name = shadowsocksPlugin(plugin);
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        if (!"obfs-local".equals(name) && !"v2ray-plugin".equals(name)) {
            return refused("plugin " + name, "refusal.plugin", name);
        }
        Map<String, String> settings = pluginOptions(options);
        if (settings == null) {
            // "parse plugin_opts: empty key in """, or "nothing following
            // final escape". Not quoted: the options can hold a path's token.
            return refused(name + " options", "refusal.plugin.options", name);
        }
        if ("obfs-local".equals(name)) {
            String mode = settings.getOrDefault("obfs", "http");
            if (!OBFS_MODES.contains(mode)) {
                return refused("obfs-local mode " + mode, "refusal.plugin.mode", name, mode);
            }
            return Optional.empty();
        }
        String mode = settings.getOrDefault("mode", "websocket");
        if ("quic".equals(mode) && !settings.containsKey("tls")) {
            return refused("v2ray-plugin QUIC without TLS", "refusal.plugin.quic.tls");
        }
        if (!V2RAY_PLUGIN_MODES.contains(mode)) {
            return refused("v2ray-plugin mode " + mode, "refusal.plugin.mode", name, mode);
        }
        String mux = settings.get("mux");
        if ("websocket".equals(mode) && mux != null && !isGoInt(mux)) {
            // "parse mux value: strconv.Atoi: parsing "true": invalid syntax"
            return refused("v2ray-plugin mux", "refusal.plugin.mux");
        }
        if ("websocket".equals(mode) && !isEscapedPath(settings.get("path"))) {
            // "parse path: invalid URL escape "%"". Not quoted, as above.
            return refused("v2ray-plugin path", "refusal.plugin.path");
        }
        return Optional.empty();
    }

    /**
     * SIP003 plugin options, {@code key=value;flag}, as the core's
     * ParsePluginOptions reads them: a backslash makes the character after it
     * plain, so a key or a value can hold {@code \;} or {@code \=}; a flag
     * such as {@code tls} maps to {@code 1}; and a key given twice keeps its
     * first value. Nothing is trimmed, and options that are blank are not
     * given to the core at all. Split at every {@code ;} without its escapes,
     * options the core refuses were taken, and mux=\1 was refused, which the
     * core reads as 1.
     *
     * @return the options, or null when the core cannot read them: an option
     *     without a key, from {@code ;;} or a {@code ;} at the start, or a
     *     backslash with nothing after it
     */
    private static Map<String, String> pluginOptions(String options) {
        Map<String, String> settings = new HashMap<>();
        if (options == null || options.isBlank()) {
            return settings;
        }
        int at = 0;
        while (at < options.length()) {
            StringBuilder key = new StringBuilder();
            at = readUnescaped(options, at, "=;", key);
            if (at < 0 || key.isEmpty()) {
                return null;
            }
            String value = "1";
            if (at < options.length() && options.charAt(at) == '=') {
                StringBuilder read = new StringBuilder();
                at = readUnescaped(options, at + 1, ";", read);
                if (at < 0) {
                    return null;
                }
                value = read.toString();
            }
            settings.putIfAbsent(key.toString(), value);
            at++;
        }
        return settings;
    }

    /**
     * Reads {@code text} from {@code start} up to the first of {@code stops}
     * that no backslash escapes, into {@code into} without the escapes.
     *
     * @return where it stopped, or -1 when a backslash ends the text
     */
    private static int readUnescaped(String text, int start, String stops, StringBuilder into) {
        int at = start;
        while (at < text.length() && stops.indexOf(text.charAt(at)) < 0) {
            if (text.charAt(at) == '\\') {
                at++;
                if (at == text.length()) {
                    return -1;
                }
            }
            into.append(text.charAt(at));
            at++;
        }
        return at;
    }

    /** Whether {@code value} is a number Go's strconv.Atoi reads: a sign, digits, an int64. */
    private static boolean isGoInt(String value) {
        if (!GO_INT.matcher(value).matches()) {
            return false;
        }
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException outOfRange) {
            return false;
        }
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
