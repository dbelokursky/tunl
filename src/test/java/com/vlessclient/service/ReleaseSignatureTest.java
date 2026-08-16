package com.vlessclient.service;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The check that makes an automatic install defensible: the SHA-256 digest
 * proves only that the bytes match what the release API said, and whoever can
 * change one can change the other. A signature over that digest, made with a
 * key that never appears in the release output, cannot be forged that way.
 *
 * <p>The key pair here is generated per run rather than checked in — a test
 * that shipped a private key would be teaching the wrong lesson about where
 * these live.</p>
 */
class ReleaseSignatureTest {

    private static final String DIGEST =
            "sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    private static KeyPair keyPair;
    private static String publicKeyBase64;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        // The same encoding the release key is pasted in as: X.509
        // SubjectPublicKeyInfo, base64.
        publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    private static String sign(String message) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(message.getBytes(StandardCharsets.US_ASCII));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    @Test
    void acceptsASignatureOverTheDigestItWasMadeFor() throws Exception {
        assertThat(ReleaseSignature.verify(publicKeyBase64, DIGEST, sign(DIGEST))).isTrue();
    }

    @Test
    void rejectsASignatureMadeForADifferentDigest() throws Exception {
        // The attack this stops: a valid signature lifted from one release and
        // presented alongside a different installer.
        String otherDigest =
                "sha256:0000000000000000000000000000000000000000000000000000000000000000";

        assertThat(ReleaseSignature.verify(publicKeyBase64, otherDigest, sign(DIGEST))).isFalse();
    }

    @Test
    void rejectsASignatureFromSomeoneElsesKey() throws Exception {
        KeyPair impostor = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(impostor.getPrivate());
        signer.update(DIGEST.getBytes(StandardCharsets.US_ASCII));
        String forged = Base64.getEncoder().encodeToString(signer.sign());

        assertThat(ReleaseSignature.verify(publicKeyBase64, DIGEST, forged)).isFalse();
    }

    @Test
    void rejectsATamperedSignature() throws Exception {
        byte[] raw = Base64.getDecoder().decode(sign(DIGEST));
        raw[0] ^= 0x01;

        assertThat(ReleaseSignature.verify(
                publicKeyBase64, DIGEST, Base64.getEncoder().encodeToString(raw))).isFalse();
    }

    @Test
    void surroundingWhitespaceInTheSignatureFileIsTolerated() throws Exception {
        // The signature arrives as the body of an HTTP response, and a stray
        // trailing newline is not a forgery.
        assertThat(ReleaseSignature.verify(publicKeyBase64, DIGEST, sign(DIGEST) + "\n")).isTrue();
    }

    @Test
    void malformedInputIsRejectedRatherThanThrowing() {
        // Everything downstream treats false as "not from the publisher", so
        // no malformed input may escape as an exception.
        assertThat(ReleaseSignature.verify(publicKeyBase64, DIGEST, "not base64 at all")).isFalse();
        assertThat(ReleaseSignature.verify("not a key", DIGEST, "AAAA")).isFalse();
        assertThat(ReleaseSignature.verify(publicKeyBase64, DIGEST, "")).isFalse();
        assertThat(ReleaseSignature.verify(publicKeyBase64, "", "AAAA")).isFalse();
        assertThat(ReleaseSignature.verify(null, null, null)).isFalse();
    }

    @Test
    void thisBuildRequiresAValidSignature() {
        // The one assertion that would catch the whole feature being switched
        // off: an empty key silently means "accept anything", and nothing else
        // in the suite would fail if the constant were cleared.
        assertThat(ReleaseSignature.enforced()).isTrue();
    }

    @Test
    void theCompiledInKeyRejectsSignaturesItDidNotMake() throws Exception {
        // Signed with a key generated here, not the project's — which is the
        // position an attacker without the release key is in.
        assertThat(ReleaseSignature.verifyDigest(DIGEST, sign(DIGEST))).isFalse();
        assertThat(ReleaseSignature.verifyDigest(DIGEST, "AAAA")).isFalse();
    }

    @Test
    void theCompiledInKeyIsAUsableEd25519PublicKey() throws Exception {
        // A mistyped or truncated key would fail every update with the same
        // generic "not from the publisher" a real attack produces, and would
        // be diagnosed as one. Parsed here through the path production uses,
        // so a bad paste fails the build instead.
        PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(ReleaseSignature.PUBLIC_KEY)));
        // Throws unless the key is one an Ed25519 verifier can actually use.
        Signature.getInstance("Ed25519").initVerify(key);

        // Re-encoding to exactly the constant proves it is a canonical
        // SubjectPublicKeyInfo, not something that merely survived base64.
        assertThat(Base64.getEncoder().encodeToString(key.getEncoded()))
                .isEqualTo(ReleaseSignature.PUBLIC_KEY);
    }
}
