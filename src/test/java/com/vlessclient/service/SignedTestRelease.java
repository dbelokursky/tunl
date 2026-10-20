package com.vlessclient.service;

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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/**
 * A release of this platform's installer, signed with a key made for the test
 * over the manifest release.yml signs, and served by a {@link ServingClient}.
 *
 * <p>The release key is the maintainer's, so without this every download a
 * test could make ended at "signature does not match", and the path that
 * stages an update never ran.</p>
 */
final class SignedTestRelease {

    final String version;
    final byte[] bytes;
    final String asset;
    final String url;
    final String digest;
    final ServingClient client;

    /** A release of {@code version} signed by {@code signer}, served by a client of its own. */
    SignedTestRelease(String version, PrivateKey signer) throws Exception {
        this(version, signer, new ServingClient());
    }

    /** A release of {@code version} signed by {@code signer}, served by {@code client}. */
    SignedTestRelease(String version, PrivateKey signer, ServingClient client) throws Exception {
        this.version = version;
        this.bytes = ("an installer of " + version).getBytes(StandardCharsets.UTF_8);
        this.asset = "tunl_" + version + "_" + UpdateManager.currentArchToken()
                + UpdateManager.installerExtension();
        this.url = UpdateManager.RELEASE_DOWNLOAD_PREFIX + "v" + version + "/" + asset;
        this.digest = "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        this.client = client;
        Signature ed = Signature.getInstance("Ed25519");
        ed.initSign(signer);
        ed.update(("tunl-release-v1\n" + version + "\n" + asset + "\n" + digest)
                .getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(ed.sign());
        client.serve(url, bytes);
        client.serve(signatureUrl(), signature.getBytes(StandardCharsets.US_ASCII));
    }

    /** Where the release publishes the installer's manifest signature. */
    String signatureUrl() {
        return url + ReleaseSignature.MANIFEST_SIGNATURE_SUFFIX;
    }

    /** The Releases API's answer naming this release as the latest. */
    String releaseJson() {
        return """
                {"tag_name":"v%s","assets":[
                  {"name":"%s","browser_download_url":"%s","digest":"%s"}]}
                """.formatted(version, asset, url, digest);
    }

    /** A fresh Ed25519 key pair. */
    static KeyPair ed25519() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    /** The app's check, with {@code key}'s public half in place of the release key. */
    static UpdateStaging.ReleaseCheck checkWith(KeyPair key) {
        String publicKey = Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
        return (version, asset, digest, signature) -> ReleaseSignature.verify(publicKey,
                ReleaseSignature.manifestFor(version, asset, digest), signature);
    }

    /**
     * Answers the URLs it was given from memory and 404 for any other; can be
     * told to fail or hold the next requests to a URL.
     */
    static final class ServingClient extends HttpClient {

        private final Map<URI, byte[]> files = new ConcurrentHashMap<>();
        private final Map<URI, Deque<Object>> scripted = new ConcurrentHashMap<>();
        private final List<URI> requests = new java.util.concurrent.CopyOnWriteArrayList<>();

        /** Serves {@code body} at {@code url}. */
        void serve(String url, byte[] body) {
            files.put(URI.create(url), body);
        }

        /** The next {@code times} requests to {@code url} fail with {@code failure}. */
        void failNext(String url, int times, IOException failure) {
            for (int i = 0; i < times; i++) {
                script(url).add(failure);
            }
        }

        /** The next {@code times} requests to {@code url} answer {@code status}, no body. */
        void answerNext(String url, int times, int status) {
            for (int i = 0; i < times; i++) {
                script(url).add(status);
            }
        }

        /**
         * The next request to {@code url} waits in {@code send} until the
         * returned gate opens, then is served as usual.
         */
        Gate holdNext(String url) {
            Gate gate = new Gate();
            script(url).add(gate);
            return gate;
        }

        /** How many requests have been made to {@code url}. */
        long requestsTo(String url) {
            URI uri = URI.create(url);
            return requests.stream().filter(uri::equals).count();
        }

        private Deque<Object> script(String url) {
            return scripted.computeIfAbsent(URI.create(url), u -> new ArrayDeque<>());
        }

        private Object nextScripted(URI uri) {
            Deque<Object> queue = scripted.get(uri);
            if (queue == null) {
                return null;
            }
            synchronized (queue) {
                return queue.poll();
            }
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request,
                                        HttpResponse.BodyHandler<T> handler) throws IOException {
            requests.add(request.uri());
            Object scriptedStep = nextScripted(request.uri());
            if (scriptedStep instanceof IOException failure) {
                throw failure;
            }
            if (scriptedStep instanceof Integer status) {
                return respond(request, handler, status, new byte[0]);
            }
            if (scriptedStep instanceof Gate gate) {
                gate.hold();
            }
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

    /** Holds one request inside {@code send} until the test opens it. */
    static final class Gate {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch open = new CountDownLatch(1);

        /** Waits until the held request has reached {@code send}. */
        void awaitEntered() throws InterruptedException {
            if (!entered.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("the held request never arrived");
            }
        }

        /** Lets the held request go on. */
        void open() {
            open.countDown();
        }

        private void hold() throws IOException {
            entered.countDown();
            try {
                if (!open.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("the test never opened the gate");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while held", e);
            }
        }
    }
}
