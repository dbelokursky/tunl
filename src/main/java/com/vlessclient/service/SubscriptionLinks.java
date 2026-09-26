package com.vlessclient.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * The subscription URL inside the links other clients use.
 *
 * <p>Providers hand out one-tap links for the apps they recommend, and each
 * link wraps the subscription URL: {@code happ://add/<url>},
 * {@code <app>://install-config?url=<url>} (Clash, v2rayNG, Streisand and
 * others), {@code sing-box://import-remote-profile?url=<url>#<name>}. Pasted
 * into Tunl, such a link was taken for a server that did not parse, or saved
 * as a subscription whose every refresh then failed on the scheme.</p>
 *
 * <p>Happ also has links it encrypts for itself ({@code happ://crypt…}): no
 * other client can read the URL in them, and the user has to ask the
 * provider for the URL itself.</p>
 */
public final class SubscriptionLinks {

    private SubscriptionLinks() {
    }

    /**
     * The subscription URL a link means: an http or https URL itself, or the
     * one another client's link wraps.
     *
     * @param link what was pasted; null reads as nothing
     * @return the http or https URL, or empty when the link holds none
     */
    public static Optional<String> subscriptionUrl(String link) {
        if (link == null) {
            return Optional.empty();
        }
        String text = link.strip();
        if (isWebUrl(text)) {
            return Optional.of(text);
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("happ://add/")) {
            return web(decodedIfEncoded(text.substring("happ://add/".length())));
        }
        int scheme = lower.indexOf("://");
        if (scheme <= 0) {
            return Optional.empty();
        }
        String rest = lower.substring(scheme + 3);
        if (rest.startsWith("install-config?") || rest.startsWith("import-remote-profile?")) {
            return web(queryUrl(text.substring(scheme + 3)));
        }
        return Optional.empty();
    }

    /**
     * Whether the link is one Happ encrypted for itself: {@code happ://crypt},
     * {@code happ://crypt2} and on.
     *
     * @param link what was pasted; null reads as nothing
     * @return true for Happ's encrypted links
     */
    public static boolean encryptedForHapp(String link) {
        return link != null && link.strip().toLowerCase(Locale.ROOT).startsWith("happ://crypt");
    }

    /**
     * The value of the {@code url} parameter, decoded. Taken up to the next
     * {@code &} or {@code #}: a wrapped URL is percent-encoded, so its own
     * query and fragment do not end the parameter early.
     */
    private static String queryUrl(String afterScheme) {
        int query = afterScheme.indexOf('?');
        String params = afterScheme.substring(query + 1);
        for (String param : params.split("&")) {
            if (param.regionMatches(true, 0, "url=", 0, 4)) {
                String value = param.substring(4);
                int fragment = value.indexOf('#');
                if (fragment >= 0) {
                    value = value.substring(0, fragment);
                }
                return decodedIfEncoded(value);
            }
        }
        return "";
    }

    private static String decodedIfEncoded(String value) {
        if (!value.contains("%")) {
            return value;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static Optional<String> web(String candidate) {
        String url = candidate.strip();
        return isWebUrl(url) ? Optional.of(url) : Optional.empty();
    }

    private static boolean isWebUrl(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return (lower.startsWith("https://") || lower.startsWith("http://"))
                && lower.length() > lower.indexOf("://") + 3;
    }
}
