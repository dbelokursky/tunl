package com.vlessclient.service;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * An {@link HttpClient} that sends the local proxy's password with every
 * request, rather than after the proxy asks for it.
 *
 * <p>Java's client answers a proxy's {@code 407} with its authenticator's
 * credentials, so a request reached the proxy without them first: a client's
 * first request, and the first after one that failed. In TUN mode sing-box
 * logs each such attempt as an ERROR ("authentication failed, no
 * Proxy-Authorization header"), and after every start the Logs page showed a
 * few of them, all the app's own.</p>
 *
 * <p>The header reaches a proxy alone. Java leaves {@code Proxy-*} headers out
 * of a request it sends straight to a site and out of the one inside a
 * {@code CONNECT} tunnel, and sends one with a {@code CONNECT} while
 * {@value LocalProxyCredentials#TUNNELING_PROPERTY} lets Basic through, which
 * the launcher sees to. A proxy that asks for nothing, in system proxy mode or
 * shared with other programs, ignores it. The authenticator stays, for a
 * request that reaches the proxy without the header.</p>
 */
final class ProxyPasswordClient extends HttpClient {

    static final String HEADER = "Proxy-Authorization";

    private final HttpClient delegate;

    ProxyPasswordClient(HttpClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * {@code request} with this run's password for the local proxy, unless it
     * names a proxy password of its own.
     *
     * @param request the request to send
     * @return the request to send instead
     */
    static HttpRequest withPassword(HttpRequest request) {
        if (request.headers().firstValue(HEADER).isPresent()) {
            return request;
        }
        return HttpRequest.newBuilder(request, (name, value) -> true)
                .header(HEADER, LocalProxyCredentials.basicHeader())
                .build();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        return delegate.send(withPassword(request), handler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        return delegate.sendAsync(withPassword(request), handler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        return delegate.sendAsync(withPassword(request), handler, pushPromiseHandler);
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return delegate.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return delegate.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return delegate.authenticator();
    }

    @Override
    public Version version() {
        return delegate.version();
    }

    @Override
    public Optional<Executor> executor() {
        return delegate.executor();
    }

    @Override
    public WebSocket.Builder newWebSocketBuilder() {
        return delegate.newWebSocketBuilder();
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public boolean awaitTermination(Duration duration) throws InterruptedException {
        return delegate.awaitTermination(duration);
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public void shutdownNow() {
        delegate.shutdownNow();
    }

    @Override
    public void close() {
        delegate.close();
    }

    /** Builds a {@link ProxyPasswordClient} around the client its delegate builds. */
    static final class Builder implements HttpClient.Builder {

        private final HttpClient.Builder delegate;

        Builder(HttpClient.Builder delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public Builder cookieHandler(CookieHandler cookieHandler) {
            delegate.cookieHandler(cookieHandler);
            return this;
        }

        @Override
        public Builder connectTimeout(Duration duration) {
            delegate.connectTimeout(duration);
            return this;
        }

        @Override
        public Builder sslContext(SSLContext sslContext) {
            delegate.sslContext(sslContext);
            return this;
        }

        @Override
        public Builder sslParameters(SSLParameters sslParameters) {
            delegate.sslParameters(sslParameters);
            return this;
        }

        @Override
        public Builder executor(Executor executor) {
            delegate.executor(executor);
            return this;
        }

        @Override
        public Builder followRedirects(Redirect policy) {
            delegate.followRedirects(policy);
            return this;
        }

        @Override
        public Builder version(Version version) {
            delegate.version(version);
            return this;
        }

        @Override
        public Builder priority(int priority) {
            delegate.priority(priority);
            return this;
        }

        @Override
        public Builder proxy(ProxySelector proxySelector) {
            delegate.proxy(proxySelector);
            return this;
        }

        @Override
        public Builder authenticator(Authenticator authenticator) {
            delegate.authenticator(authenticator);
            return this;
        }

        @Override
        public Builder localAddress(InetAddress localAddr) {
            delegate.localAddress(localAddr);
            return this;
        }

        @Override
        public HttpClient build() {
            return new ProxyPasswordClient(delegate.build());
        }
    }
}
