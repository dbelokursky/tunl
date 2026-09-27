package com.vlessclient.testing;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * A certificate for {@code localhost} and {@code 127.0.0.1}, made by the JDK's
 * own keytool, for a test that needs a TLS server of its own: the context its
 * server presents it with, and one for a client that trusts it and nothing
 * else.
 */
public final class SelfSignedTls {

    private static final String PASSWORD = "changeit";

    private final KeyStore keys;

    private SelfSignedTls(KeyStore keys) {
        this.keys = keys;
    }

    /**
     * Makes the certificate.
     *
     * @param dir where its key store is written
     * @return the certificate's contexts
     */
    public static SelfSignedTls in(Path dir) throws Exception {
        Path store = dir.resolve("localhost.p12");
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                windows ? "keytool.exe" : "keytool");
        Process process = new ProcessBuilder(keytool.toString(), "-genkeypair",
                "-alias", "localhost", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool did not make the certificate: " + output);
        }
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            keys.load(in, PASSWORD.toCharArray());
        }
        return new SelfSignedTls(keys);
    }

    /** The context a server presents the certificate with. */
    public SSLContext server() throws Exception {
        KeyManagerFactory keyManagers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }

    /** A client's context that trusts the certificate, and nothing else. */
    public SSLContext client() throws Exception {
        TrustManagerFactory trustManagers =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(keys);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);
        return context;
    }
}
