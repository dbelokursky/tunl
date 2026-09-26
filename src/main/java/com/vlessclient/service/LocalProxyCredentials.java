package com.vlessclient.service;

import java.net.Authenticator;
import java.net.InetAddress;
import java.net.PasswordAuthentication;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The user name and password the local SOCKS and HTTP proxies ask for in TUN
 * mode, new at every start of the app and known to it alone.
 *
 * <p>In TUN mode the tunnel carries every program's traffic already, and the
 * two local proxies are there for the app's own requests: updates,
 * subscriptions, the health checks. Without a password any program on the
 * machine could send traffic through the user's server over them, a
 * malicious one included, and so could every other account on a shared
 * machine. Settings can open them to other programs again
 * ({@code share_local_proxy_in_tun}).</p>
 *
 * <p>The JDK sends a password to a proxy that tunnels HTTPS only once
 * {@value #TUNNELING_PROPERTY} no longer names Basic, which the launcher
 * sees to before any client exists. The password is sent in the clear, to
 * 127.0.0.1 alone.</p>
 */
public final class LocalProxyCredentials {

    /** The JDK property that keeps Basic proxy authentication off HTTPS tunnels. */
    public static final String TUNNELING_PROPERTY = "jdk.http.auth.tunneling.disabledSchemes";

    private static final String USERNAME = "tunl";
    /** Declared before {@link #PASSWORD}, which is made from it. */
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PASSWORD = mintPassword();

    /** Answers the local proxy's challenge, and no other. */
    private static final Authenticator AUTHENTICATOR = new Authenticator() {
        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            if (getRequestorType() != RequestorType.PROXY || !isLoopback(getRequestingHost())) {
                return null;
            }
            return new PasswordAuthentication(USERNAME, PASSWORD.toCharArray());
        }
    };

    private LocalProxyCredentials() {
    }

    /** The user name the local proxies ask for. */
    public static String username() {
        return USERNAME;
    }

    /** The password the local proxies ask for, this run's. */
    public static String password() {
        return PASSWORD;
    }

    /** The {@code Proxy-Authorization} value a raw request to the local HTTP proxy carries. */
    public static String basicHeader() {
        return "Basic " + Base64.getEncoder().encodeToString(
                (USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The authenticator the app's own HTTP clients carry: it answers a proxy
     * on the loopback address, and nothing else, with this run's password.
     *
     * @return the authenticator
     */
    public static Authenticator authenticator() {
        return AUTHENTICATOR;
    }

    /**
     * Lets the JDK send the password to the local proxy for HTTPS requests,
     * which it tunnels: by default it refuses Basic there. Called by the
     * launcher before any HTTP client is built, since the JDK reads the
     * property once.
     */
    public static void allowBasicThroughTunnels() {
        System.setProperty(TUNNELING_PROPERTY, "");
    }

    private static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static String mintPassword() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
