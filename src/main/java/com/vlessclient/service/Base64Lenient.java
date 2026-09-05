package com.vlessclient.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Base64 as share links and subscriptions actually write it.
 *
 * <p>Providers mix the standard and URL-safe alphabets, drop the padding, and
 * wrap long payloads across lines. The same four-step fallback (standard,
 * URL-safe, then both again with padding restored) lived in two copies, one in
 * the share-link parser and one in the subscription service.</p>
 */
public final class Base64Lenient {

    private Base64Lenient() {
    }

    /**
     * Decodes {@code encoded} to UTF-8 text, tolerating either alphabet,
     * missing padding and embedded whitespace.
     *
     * @param encoded the base64 text
     * @return the decoded text
     * @throws IllegalArgumentException if no variant decodes
     */
    public static String decodeUtf8(String encoded) {
        return new String(decode(encoded), StandardCharsets.UTF_8);
    }

    /**
     * Decodes {@code encoded}, tolerating either alphabet, missing padding and
     * embedded whitespace.
     *
     * @param encoded the base64 text
     * @return the decoded bytes
     * @throws IllegalArgumentException if no variant decodes
     */
    public static byte[] decode(String encoded) {
        String cleaned = encoded.replaceAll("\\s+", "");
        try {
            return Base64.getDecoder().decode(cleaned);
        } catch (IllegalArgumentException standard) {
            try {
                return Base64.getUrlDecoder().decode(cleaned);
            } catch (IllegalArgumentException urlSafe) {
                String padded = cleaned;
                int remainder = padded.length() % 4;
                if (remainder > 0) {
                    padded = padded + "=".repeat(4 - remainder);
                }
                try {
                    return Base64.getDecoder().decode(padded);
                } catch (IllegalArgumentException paddedStandard) {
                    return Base64.getUrlDecoder().decode(padded);
                }
            }
        }
    }
}
