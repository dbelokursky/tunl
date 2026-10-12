package com.vlessclient.service;

import com.vlessclient.platform.PendingUpdate;
import com.vlessclient.platform.UpdateApplier;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The half of update verification that says yes: a newer release, its
 * installer, a signature the key accepts, and the installer staged for the
 * next start.
 *
 * <p>Only rejections were tested. The release key is the maintainer's, so
 * every download a test could make ended at "signature does not match", and a
 * change that signed or checked the wrong manifest (the API's digest for the
 * downloaded one, the version and the asset swapped) left every test green
 * while every installed copy refused every update. Here the release is signed
 * with a key made for the test, over the manifest release.yml signs.</p>
 */
class UpdateAcceptanceTest {

    private static final String VERSION = "999.0.0";

    @TempDir
    Path dir;

    @Test
    void aNewerReleaseSignedForThisInstallerIsDownloadedAndStaged() throws Exception {
        KeyPair release = ed25519();
        Fixture fixture = new Fixture(release.getPrivate());
        UpdateStaging.ReleaseCheck check = checkWith(release);
        UpdateStaging staging = new UpdateStaging(dir.resolve("staging"), check);
        UpdateManager manager = new UpdateManager(fixture.client, staging,
                UpdateApplier::current, check);

        assertThat(manager.processReleaseResponse(fixture.releaseJson()))
                .isEqualTo(UpdateManager.CheckResult.UPDATE_AVAILABLE);
        Path installer = manager.downloadUpdate(fixture.url, fixture.digest);

        assertThat(installer).as("downloaded and accepted").isNotNull().exists();
        assertThat(installer).hasBinaryContent(fixture.bytes);
        assertThat(manager.hasStagedUpdate()).isTrue();
        PendingUpdate staged = staging.pending().orElseThrow();
        assertThat(staged.version()).isEqualTo(VERSION);
        assertThat(staging.verify(staged)).as("and verified again before it is run").isTrue();
    }

    @Test
    void theManifestIsTheOneReleaseYmlSigns() {
        // printf 'tunl-release-v1\n%s\n%s\nsha256:%s' "$V" "$asset" "$hex"
        assertThat(ReleaseSignature.manifestFor(VERSION, "tunl_999.0.0.dmg", "sha256:ab12"))
                .isEqualTo("tunl-release-v1\n999.0.0\ntunl_999.0.0.dmg\nsha256:ab12");
    }

    @Test
    void anInstallerSignedWithAnotherKeyIsNotStaged() throws Exception {
        Fixture fixture = new Fixture(ed25519().getPrivate());
        UpdateStaging.ReleaseCheck check = checkWith(ed25519());
        UpdateStaging staging = new UpdateStaging(dir.resolve("staging"), check);
        UpdateManager manager = new UpdateManager(fixture.client, staging,
                UpdateApplier::current, check);
        manager.processReleaseResponse(fixture.releaseJson());

        assertThat(manager.downloadUpdate(fixture.url, fixture.digest)).isNull();
        assertThat(manager.hasStagedUpdate()).isFalse();
        assertThat(staging.pending()).isEmpty();
    }

    private static KeyPair ed25519() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    /** The app's check, with the test's public key in place of the release key. */
    private static UpdateStaging.ReleaseCheck checkWith(KeyPair key) {
        String publicKey = Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
        return (version, asset, digest, signature) -> ReleaseSignature.verify(publicKey,
                ReleaseSignature.manifestFor(version, asset, digest), signature);
    }

    /** A release of this platform's installer, signed the way release.yml signs it. */
    private static final class Fixture {

        final byte[] bytes = "an installer".getBytes(StandardCharsets.UTF_8);
        final String asset = "tunl_" + VERSION + "_" + UpdateManager.currentArchToken()
                + UpdateManager.installerExtension();
        final String url = UpdateManager.RELEASE_DOWNLOAD_PREFIX + "v" + VERSION + "/" + asset;
        final String digest;
        final ServingClient client = new ServingClient();

        Fixture(PrivateKey signer) throws Exception {
            digest = "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
            Signature ed = Signature.getInstance("Ed25519");
            ed.initSign(signer);
            ed.update(("tunl-release-v1\n" + VERSION + "\n" + asset + "\n" + digest)
                    .getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getEncoder().encodeToString(ed.sign());
            client.serve(url, bytes);
            client.serve(url + ReleaseSignature.MANIFEST_SIGNATURE_SUFFIX,
                    signature.getBytes(StandardCharsets.US_ASCII));
        }

        String releaseJson() {
            return """
                    {"tag_name":"v%s","assets":[
                      {"name":"%s","browser_download_url":"%s","digest":"%s"}]}
                    """.formatted(VERSION, asset, url, digest);
        }
    }

    /** Answers the URLs it was given from memory, and 404 for any other. */
    private static final class ServingClient extends HttpClient {

        private final Map<URI, byte[]> files = new ConcurrentHashMap<>();

        void serve(String url, byte[] body) {
            files.put(URI.create(url), body);
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request,
                                        HttpResponse.BodyHandler<T> handler) throws IOException {
            byte[] body = files.get(request.uri());
            int status = body == null ? 404 : 200;
            return respond(request, handler, status, body == null ? new byte[0] : body);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            try {
                return CompletableFuture.completedFuture(send(request, handler));
            } catch (IOException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushes) {
            return sendAsync(request, handler);
        }

        private static <T> HttpResponse<T> respond(HttpRequest request,
                                                   HttpResponse.BodyHandler<T> handler,
                                                   int status, byte[] bytes) {
            HttpHeaders headers = HttpHeaders.of(Map.of(), (name, value) -> true);
            HttpResponse.BodySubscriber<T> subscriber = handler.apply(new HttpResponse.ResponseInfo() {
                @Override
                public int statusCode() {
                    return status;
                }

                @Override
                public HttpHeaders headers() {
                    return headers;
                }

                @Override
                public Version version() {
                    return Version.HTTP_1_1;
                }
            });
            subscriber.onSubscribe(new Flow.Subscription() {
                private boolean delivered;

                @Override
                public void request(long n) {
                    if (!delivered) {
                        delivered = true;
                        subscriber.onNext(List.of(ByteBuffer.wrap(bytes)));
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    delivered = true;
                }
            });
            T body = subscriber.getBody().toCompletableFuture().join();
            return new HttpResponse<>() {
                @Override
                public int statusCode() {
                    return status;
                }

                @Override
                public HttpRequest request() {
                    return request;
                }

                @Override
                public Optional<HttpResponse<T>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return headers;
                }

                @Override
                public T body() {
                    return body;
                }

                @Override
                public Optional<SSLSession> sslSession() {
                    return Optional.empty();
                }

                @Override
                public URI uri() {
                    return request.uri();
                }

                @Override
                public Version version() {
                    return Version.HTTP_1_1;
                }
            };
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }

    @Test
    void theFixtureServesWhatItWasGiven() throws Exception {
        ServingClient client = new ServingClient();
        client.serve("https://example.invalid/a", "x".getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> found = client.send(
                HttpRequest.newBuilder(URI.create("https://example.invalid/a")).build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> missing = client.send(
                HttpRequest.newBuilder(URI.create("https://example.invalid/b")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(found.statusCode()).isEqualTo(200);
        assertThat(found.body()).isEqualTo("x");
        assertThat(missing.statusCode()).isEqualTo(404);
    }
}
