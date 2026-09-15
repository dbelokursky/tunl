package com.vlessclient.service.outbound;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TlsConfig;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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

    /** The uTLS fingerprint a REALITY server gets when its link names none. */
    private static final String REALITY_FINGERPRINT = "chrome";

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

    private CoreSettings() {
    }

    /**
     * A reason the core would refuse a server however its settings are spelled.
     *
     * @param feature what the server asks for, short enough for a list of them,
     *                e.g. {@code flow xtls-rprx-vision-udp443}
     * @param reason  the sentence to show for one server
     */
    public record Refusal(String feature, String reason) {
    }

    /**
     * The uTLS fingerprint to send: the link's own, in the lower case the core
     * matches on, or Chrome for a REALITY server whose link names none. The
     * core refuses a REALITY client without uTLS, and a link that leaves the
     * fingerprint out means "any", not "none".
     *
     * @param tls the server's TLS settings
     * @return the fingerprint, or null when uTLS is not used
     */
    public static String fingerprint(TlsConfig tls) {
        String fingerprint = tls.getFingerprint();
        if (fingerprint != null && !fingerprint.isBlank()) {
            return fingerprint.strip().toLowerCase(Locale.ROOT);
        }
        return tls.isReality() ? REALITY_FINGERPRINT : null;
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
     * REALITY key or short ID the core cannot decode, an unknown Shadowsocks
     * cipher, or a Shadowsocks 2022 key of the wrong length. A setting the
     * spelling methods correct is not a refusal.
     *
     * @param server the server to check
     * @return the refusal, or empty when the core accepts the server
     */
    public static Optional<Refusal> refusal(ServerConfig server) {
        int port = server.getPort();
        if (port < 1 || port > 65535) {
            return refused("port " + port, "Port " + port + " is outside 1 to 65535.");
        }
        String flow = server.getFlow() == null ? "" : server.getFlow().strip();
        if (server.getProtocol() == Protocol.VLESS && !flow.isEmpty()
                && !VISION_FLOW.equals(flow)) {
            return refused("flow " + flow,
                    "sing-box does not support the VLESS flow " + flow + ".");
        }
        TlsConfig tls = server.getTls();
        if (tls != null && tls.isEnabled()) {
            Optional<Refusal> refusal = tlsRefusal(tls);
            if (refusal.isPresent()) {
                return refusal;
            }
        }
        if (server.getProtocol() == Protocol.SHADOWSOCKS) {
            return shadowsocksRefusal(server);
        }
        return Optional.empty();
    }

    private static Optional<Refusal> tlsRefusal(TlsConfig tls) {
        String fingerprint = fingerprint(tls);
        if (fingerprint != null && !FINGERPRINTS.contains(fingerprint)) {
            return refused("uTLS fingerprint " + fingerprint,
                    "sing-box does not know the uTLS fingerprint " + fingerprint + ".");
        }
        if (!tls.isReality()) {
            return Optional.empty();
        }
        String key = tls.getRealityPublicKey() == null ? "" : tls.getRealityPublicKey();
        if (decodedLength(key) != REALITY_KEY_BYTES) {
            return refused("REALITY public key",
                    "The REALITY public key has to be 32 bytes in base64.");
        }
        String shortId = tls.getRealityShortId() == null ? "" : tls.getRealityShortId().strip();
        if (!SHORT_ID.matcher(shortId).matches()) {
            return refused("REALITY short ID",
                    "The REALITY short ID has to be whole bytes in hex, at most 16 digits.");
        }
        return Optional.empty();
    }

    private static Optional<Refusal> shadowsocksRefusal(ServerConfig server) {
        String method = shadowsocksMethod(server.getEncryption());
        if (method == null || !SHADOWSOCKS_METHODS.contains(method)) {
            return refused("cipher " + method,
                    "sing-box does not know the cipher " + method + ".");
        }
        if (!method.startsWith("2022-")) {
            return Optional.empty();
        }
        int bytes = "2022-blake3-aes-128-gcm".equals(method) ? 16 : 32;
        String password = server.getUuid() == null ? "" : server.getUuid();
        // A multi-user server is given the server key and the user key, colon-separated.
        for (String key : password.split(":", -1)) {
            if (decodedLength(key) != bytes) {
                return refused("Shadowsocks 2022 key",
                        "A " + method + " key has to be " + bytes + " bytes in base64.");
            }
        }
        return Optional.empty();
    }

    /** How many bytes a base64 value in either alphabet decodes to, or -1 when it does not. */
    private static int decodedLength(String value) {
        try {
            return Base64.getUrlDecoder().decode(realityPublicKey(value)).length;
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    private static Optional<Refusal> refused(String feature, String reason) {
        return Optional.of(new Refusal(feature, reason));
    }
}
