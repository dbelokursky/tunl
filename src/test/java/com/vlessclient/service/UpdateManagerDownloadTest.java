package com.vlessclient.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every rejection path in {@link UpdateManager#fetchAndStage}, which decides
 * whether a downloaded installer is allowed to become the next thing this app
 * runs as the user.
 *
 * <p>The whole post-network block was uncovered: the package-private
 * {@code HttpClient} constructor existed as a seam and no test used it, so the
 * digest comparison, the delete-on-mismatch, the signature gate and the
 * delete-on-staging-failure were all unexercised. Each assertion here is on
 * the file system, not on the return value — "returned null" is not the
 * property that matters; "did not leave a runnable installer behind" is.</p>
 *
 * <p>The accepted path is deliberately absent: {@code ReleaseSignature}
 * enforces a real Ed25519 public key compiled into the app, so producing a
 * valid signature would need the release private key. That branch is reachable
 * only by a real signed release.</p>
 */
class UpdateManagerDownloadTest {

    private static final String URL =
            UpdateManager.RELEASE_DOWNLOAD_PREFIX + "v9.9.9/tunl_9.9.9.dmg";

    private static String sha256Of(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private UpdateManager managerFor(Path dir, HttpClient client) {
        return new UpdateManager(client, new UpdateStaging(dir));
    }

    private List<Path> filesIn(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.toList();
        }
    }

    @Test
    void aUrlOutsideTheReleaseHostIsRefusedBeforeAnyRequest(@TempDir Path dir) throws Exception {
        RoutingHttpClient client = new RoutingHttpClient(Map.of());

        Path result = managerFor(dir, client)
                .fetchAndStage("https://example.com/evil.dmg", "sha256:00");

        assertThat(result).isNull();
        assertThat(client.requests).as("nothing should be fetched at all").isEmpty();
        assertThat(filesIn(dir)).isEmpty();
    }

    @Test
    void aDownloadWithNoDigestIsRefusedBeforeAnyRequest(@TempDir Path dir) throws Exception {
        RoutingHttpClient client = new RoutingHttpClient(Map.of());

        assertThat(managerFor(dir, client).fetchAndStage(URL, null)).isNull();
        assertThat(managerFor(dir, client).fetchAndStage(URL, "md5:whatever")).isNull();
        assertThat(client.requests).isEmpty();
        assertThat(filesIn(dir)).isEmpty();
    }

    @Test
    void aNon200LeavesNothingOnDisk(@TempDir Path dir) throws Exception {
        RoutingHttpClient client = new RoutingHttpClient(
                Map.of(URL, new Canned(503, new byte[0])));

        assertThat(managerFor(dir, client).fetchAndStage(URL, "sha256:00")).isNull();
        assertThat(filesIn(dir)).isEmpty();
    }

    @Test
    void aDigestMismatchDeletesTheInstaller(@TempDir Path dir) throws Exception {
        byte[] payload = "not the release you were promised".getBytes(StandardCharsets.UTF_8);
        RoutingHttpClient client = new RoutingHttpClient(
                Map.of(URL, new Canned(200, payload)));

        Path result = managerFor(dir, client).fetchAndStage(URL, "sha256:" + "ab".repeat(32));

        assertThat(result).isNull();
        assertThat(filesIn(dir))
                .as("a rejected installer must not be left where the user could open it")
                .isEmpty();
    }

    @Test
    void aMissingSignatureDeletesTheInstallerEvenWhenTheDigestMatches(@TempDir Path dir)
            throws Exception {
        byte[] payload = "a genuine-looking installer".getBytes(StandardCharsets.UTF_8);
        RoutingHttpClient client = new RoutingHttpClient(Map.of(
                URL, new Canned(200, payload),
                URL + ReleaseSignature.SIGNATURE_SUFFIX, new Canned(404, new byte[0])));

        Path result = managerFor(dir, client).fetchAndStage(URL, sha256Of(payload));

        assertThat(result).isNull();
        assertThat(filesIn(dir))
                .as("matching bytes are not authorisation; an unsigned installer must go")
                .isEmpty();
    }

    @Test
    void aWrongSignatureDeletesTheInstaller(@TempDir Path dir) throws Exception {
        byte[] payload = "a genuine-looking installer".getBytes(StandardCharsets.UTF_8);
        String notASignature = java.util.Base64.getEncoder()
                .encodeToString(new byte[64]);
        RoutingHttpClient client = new RoutingHttpClient(Map.of(
                URL, new Canned(200, payload),
                URL + ReleaseSignature.SIGNATURE_SUFFIX,
                new Canned(200, notASignature.getBytes(StandardCharsets.UTF_8))));

        Path result = managerFor(dir, client).fetchAndStage(URL, sha256Of(payload));

        assertThat(result).isNull();
        assertThat(filesIn(dir)).isEmpty();
    }

    /** One canned reply. */
    private record Canned(int status, byte[] bytes) {
    }

    /**
     * Routes by URI, because the flow makes two calls with different body
     * handlers: the installer as a stream, then its {@code .sig} as a string.
     */
    private static final class RoutingHttpClient extends HttpClient {

        private final Map<String, Canned> replies;
        private final java.util.List<String> requests = new java.util.ArrayList<>();

        RoutingHttpClient(Map<String, Canned> replies) {
            this.replies = replies;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            String uri = request.uri().toString();
            requests.add(uri);
            Canned canned = replies.get(uri);
            if (canned == null) {
                throw new IllegalStateException("unexpected request: " + uri);
            }
            // Routed by URI, not by handler identity: BodyHandlers.ofInputStream()
            // returns a fresh instance per call, so comparing handlers would
            // silently always take one branch.
            Object body = uri.endsWith(ReleaseSignature.SIGNATURE_SUFFIX)
                    ? new String(canned.bytes(), StandardCharsets.UTF_8)
                    : new ByteArrayInputStream(canned.bytes());
            return (HttpResponse<T>) new CannedResponse<>(request, canned.status(), body);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return CompletableFuture.completedFuture(send(request, handler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, handler);
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
            throw new UnsupportedOperationException();
        }

        @Override
        public SSLParameters sslParameters() {
            throw new UnsupportedOperationException();
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

    /** Minimal {@link HttpResponse} carrying a status and a body. */
    private record CannedResponse<T>(HttpRequest req, int status, Object payload)
            implements HttpResponse<T> {

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return req;
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (a, b) -> true);
        }

        @Override
        @SuppressWarnings("unchecked")
        public T body() {
            return (T) payload;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return req.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
