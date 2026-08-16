package com.vlessclient.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks that a downloaded installer was released by whoever holds the project
 * signing key, using an Ed25519 public key compiled into this build.
 *
 * <p>This is the trust anchor the SHA-256 check alone cannot be. That digest
 * arrives in the same API response as the download URL, so it proves only that
 * the bytes are the ones GitHub is serving — anyone able to alter the release
 * alters both together. A signature made with a key that never appears in the
 * release pipeline's output cannot be produced that way.</p>
 *
 * <p>What is signed is the digest string, {@code sha256:<hex>}, not the
 * installer: Ed25519 has no streaming mode, so signing the file itself would
 * mean holding a hundred megabytes in memory on both sides. The verifier never
 * reads that string from the network — it builds it from the bytes it hashed
 * while downloading, so there is nothing in the signature file to trust.</p>
 *
 * <p>Scope, stated plainly: with the private key held in CI secrets this stops
 * a swapped release asset and a tampered API response, not an attacker who has
 * taken over the repository itself and can run the signing workflow. Moving
 * signing to an offline key closes that too, and needs no change here — only
 * the key that produced the signature changes.</p>
 *
 * @see <a href="file:../../../../../docs/SIGNING.md">docs/SIGNING.md</a>
 */
public final class ReleaseSignature {

    private static final Logger log = LoggerFactory.getLogger(ReleaseSignature.class);

    /**
     * The project's Ed25519 public key, base64 of its X.509
     * {@code SubjectPublicKeyInfo} form — the output of
     * {@code openssl pkey -in tunl-release.key -pubout -outform DER | base64}.
     *
     * <p>An empty value means unverified, which is what this was until the key
     * pair existed: releases published before signing started carry no
     * signature, and refusing them would have broken updating for everyone
     * still on those builds. Now that it is set, every update this build
     * accepts must carry a signature made by the matching private key — which
     * is why it arrived in the same change that made the release workflow
     * publish them.</p>
     *
     * <p>Replacing it is a key rotation, and reaches users only through a
     * release signed by the key it replaces. See {@code docs/SIGNING.md}.</p>
     */
    static final String PUBLIC_KEY = "MCowBQYDK2VwAyEAvICg0uIqKv0NMWhmhSMDoAkcybN1k3ageF1itsRZSCQ=";

    /** The suffix the release workflow gives a signature asset. */
    public static final String SIGNATURE_SUFFIX = ".sig";

    private ReleaseSignature() {
    }

    /**
     * Whether downloads must carry a valid signature to be accepted.
     *
     * @return true once a public key is compiled in
     */
    public static boolean enforced() {
        return !PUBLIC_KEY.isBlank();
    }

    /**
     * Verifies a signature over a downloaded installer's digest.
     *
     * @param digest          the {@code sha256:<hex>} computed from the bytes
     *                        that were actually downloaded
     * @param signatureBase64 the contents of the release's signature asset
     * @return true when the signature is valid for this build's key
     */
    public static boolean verifyDigest(String digest, String signatureBase64) {
        return verify(PUBLIC_KEY, digest, signatureBase64);
    }

    /**
     * Verifies a detached Ed25519 signature over an ASCII message.
     *
     * <p>Every failure is one answer — false — and none of them say which:
     * a malformed key, malformed base64 and a genuinely bad signature are all
     * "this did not come from the publisher".</p>
     *
     * @param publicKeyBase64 base64 X.509 encoding of the Ed25519 public key
     * @param message         the signed message
     * @param signatureBase64 base64 of the 64-byte signature
     * @return true when the signature verifies
     */
    static boolean verify(String publicKeyBase64, String message, String signatureBase64) {
        if (publicKeyBase64 == null || publicKeyBase64.isBlank()
                || message == null || message.isBlank()
                || signatureBase64 == null || signatureBase64.isBlank()) {
            return false;
        }
        try {
            Base64.Decoder decoder = Base64.getDecoder();
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(decoder.decode(publicKeyBase64.strip())));

            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(message.getBytes(StandardCharsets.US_ASCII));
            return verifier.verify(decoder.decode(signatureBase64.strip()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.error("Release signature check failed: {}", e.getMessage());
            return false;
        }
    }
}
