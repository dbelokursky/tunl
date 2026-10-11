package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.platform.PendingUpdate;
import com.vlessclient.platform.UpdateApplier;
import java.io.ByteArrayInputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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

/**
 * What the updater does across checks: the chain from finding a release to
 * fetching it had no test, so a candidate that outlived its release, an
 * older installer holding back a newer one, and a release fetched again after
 * it was given up on all passed the build.
 */
class UpdateManagerLifecycleTest {

    private static final String DIGEST = "sha256:" + "00".repeat(32);

    /** An applier that installs updates itself, whatever host the test runs on. */
    private static final UpdateApplier SELF_UPDATING = new UpdateApplier() {
        @Override
        public Outcome apply(PendingUpdate update) {
            return Outcome.FAILED;
        }

        @Override
        public boolean selfUpdates() {
            return true;
        }
    };

    /** A release carrying an installer for every platform and architecture. */
    private static String release(String version) {
        String base = UpdateManager.RELEASE_DOWNLOAD_PREFIX + "v" + version + "/";
        StringBuilder assets = new StringBuilder();
        for (String name : List.of("tunl_" + version + ".dmg", "tunl_" + version + ".msi",
                "tunl_" + version + "_amd64.deb", "tunl_" + version + "_arm64.deb")) {
            if (!assets.isEmpty()) {
                assets.append(',');
            }
            assets.append("{\"name\":\"").append(name)
                    .append("\",\"browser_download_url\":\"").append(base).append(name)
                    .append("\",\"digest\":\"").append(DIGEST).append("\"}");
        }
        return "{\"tag_name\":\"v" + version + "\",\"assets\":[" + assets + "]}";
    }

    private static UpdateManager manager(Path dir, RecordingHttpClient client) {
        return new UpdateManager(client, new UpdateStaging(dir, (v, a, d, s) -> true),
                () -> SELF_UPDATING);
    }

    /** Stages {@code version} as if an earlier run had downloaded it. */
    private static void stage(Path dir, String version) throws Exception {
        Path installer = dir.resolve("tunl_" + version + ".dmg");
        Files.createDirectories(dir);
        Files.write(installer, new byte[16]);
        new UpdateStaging(dir).stage(new PendingUpdate(version, installer, DIGEST), "");
    }

    @Test
    void aReleaseWithdrawnAfterBeingFoundIsForgotten(@TempDir Path dir) {
        RecordingHttpClient client = new RecordingHttpClient();
        UpdateManager manager = manager(dir, client);

        assertThat(manager.processReleaseResponse(release("9.9.9")))
                .isEqualTo(UpdateManager.CheckResult.UPDATE_AVAILABLE);
        assertThat(manager.updateAvailableProperty().get()).isTrue();

        assertThat(manager.processReleaseResponse(release("0.0.0")))
                .isEqualTo(UpdateManager.CheckResult.UP_TO_DATE);

        assertThat(manager.updateAvailableProperty().get()).isFalse();
        assertThat(manager.latestVersionProperty().get()).isEmpty();
        manager.autoDownloadIfAllowed();
        assertThat(client.requests).as("the withdrawn release is not fetched").isEmpty();
    }

    @Test
    void anInstallerStagedForAWithdrawnReleaseIsDiscarded(@TempDir Path dir) throws Exception {
        stage(dir, "9.9.9");
        UpdateManager manager = manager(dir, new RecordingHttpClient());

        manager.processReleaseResponse(release("0.0.0"));

        assertThat(new UpdateStaging(dir).pending()).isEmpty();
        assertThat(manager.hasStagedUpdate()).isFalse();
    }

    @Test
    void anOlderStagedInstallerGivesWayToANewerRelease(@TempDir Path dir) throws Exception {
        stage(dir, "9.9.8");
        RecordingHttpClient client = new RecordingHttpClient();
        UpdateManager manager = manager(dir, client);

        manager.processReleaseResponse(release("9.9.9"));
        manager.autoDownloadIfAllowed();

        assertThat(client.requests).as("the newer release is fetched")
                .anyMatch(uri -> uri.contains("/v9.9.9/"));
        assertThat(new UpdateStaging(dir).pending())
                .as("the superseded installer is not installed at the next restart")
                .isEmpty();
    }

    @Test
    void theReleaseAlreadyStagedIsNotFetchedAgain(@TempDir Path dir) throws Exception {
        stage(dir, "9.9.9");
        RecordingHttpClient client = new RecordingHttpClient();
        UpdateManager manager = manager(dir, client);

        manager.processReleaseResponse(release("9.9.9"));
        manager.autoDownloadIfAllowed();

        assertThat(client.requests).isEmpty();
        assertThat(manager.hasStagedUpdate()).isTrue();
    }

    /**
     * Giving up was not remembered: the count of attempts lived in the marker
     * the give-up deleted, so the check seconds later fetched the same release
     * again and the app quit on two launches out of three, with no end.
     */
    @Test
    void aReleaseGivenUpOnIsNeitherFetchedAgainNorPromised(@TempDir Path dir) throws Exception {
        stage(dir, "9.9.9");
        UpdateStaging staging = new UpdateStaging(dir, (v, a, d, s) -> true);
        staging.recordAttempt();
        staging.recordAttempt();
        assertThat(UpdateBootstrap.applyPendingUpdate(staging, SELF_UPDATING, "1.0.0")).isFalse();
        assertThat(staging.pending()).isEmpty();

        RecordingHttpClient client = new RecordingHttpClient();
        UpdateManager manager = manager(dir, client);
        manager.processReleaseResponse(release("9.9.9"));
        manager.autoDownloadIfAllowed();

        assertThat(client.requests).isEmpty();
        assertThat(manager.hold()).isEqualTo(UpdateApplier.Hold.INSTALL_MANUALLY);

        manager.processReleaseResponse(release("9.9.10"));
        assertThat(manager.hold()).as("a newer release is not held back").isEqualTo(
                UpdateApplier.Hold.NONE);
        manager.autoDownloadIfAllowed();
        assertThat(client.requests).anyMatch(uri -> uri.contains("/v9.9.10/"));
    }

    /** Records every URI it is asked for and answers each with a 404. */
    private static final class RecordingHttpClient extends HttpClient {

        private final List<String> requests = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            requests.add(request.uri().toString());
            return (HttpResponse<T>) new NotFound(request);
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

    /** A 404 with an empty body. */
    private record NotFound(HttpRequest request) implements HttpResponse<Object> {

        @Override
        public int statusCode() {
            return 404;
        }

        @Override
        public Optional<HttpResponse<Object>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (a, b) -> true);
        }

        @Override
        public Object body() {
            return new ByteArrayInputStream(new byte[0]);
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
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
