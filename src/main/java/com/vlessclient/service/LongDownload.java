package com.vlessclient.service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fetches a body that may take minutes to arrive, bounded by how long it
 * stalls rather than by how long it takes.
 *
 * <p>{@link HttpRequest.Builder#timeout} cannot do this. On this JDK it runs
 * from the moment the request starts until the body has been read, so it is
 * a deadline on the whole transfer: a hundred-megabyte installer on a link
 * slower than about 170 KB/s hit its ten minutes mid-body, every time, and
 * failed as "IOException: closed" while bytes were still arriving. The
 * networks this client exists for are the slow ones. A download here gets
 * {@link #HEADERS} to answer and then as long as it keeps moving; it fails
 * only once {@link #STALL} passes without a byte.</p>
 */
final class LongDownload {

    /** How long the server may take to answer with a status and headers. */
    static final Duration HEADERS = Duration.ofSeconds(30);

    /** How long the body may go without delivering a byte. */
    static final Duration STALL = Duration.ofSeconds(60);

    private LongDownload() {
    }

    /**
     * Sends {@code request}, which must carry no timeout of its own, waiting
     * at most {@link #HEADERS} for the response to start.
     *
     * @param client  the client to send with
     * @param request the GET to send
     * @return the response; read its body through {@link #guard(InputStream)}
     * @throws IOException          if the request fails or the server does not answer
     * @throws InterruptedException if the calling thread is interrupted
     */
    static HttpResponse<InputStream> send(HttpClient client, HttpRequest request)
            throws IOException, InterruptedException {
        return send(client, request, HEADERS);
    }

    /** {@link #send(HttpClient, HttpRequest)} with an explicit wait for the headers. */
    static HttpResponse<InputStream> send(HttpClient client, HttpRequest request,
                                          Duration headers)
            throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<InputStream>> pending =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        try {
            return pending.get(headers.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new HttpTimeoutException("No response from " + request.uri().getHost()
                    + " within " + headers.toSeconds() + " s");
        } catch (InterruptedException e) {
            pending.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException(cause);
        }
    }

    /**
     * Wraps a response body so that a read fails once no byte has arrived for
     * {@link #STALL}. Closing the returned stream closes {@code body}.
     *
     * @param body the response body
     * @return the same bytes, failing with {@link HttpTimeoutException} on a stall
     */
    static InputStream guard(InputStream body) {
        return guard(body, STALL);
    }

    /** {@link #guard(InputStream)} with an explicit stall limit. */
    static InputStream guard(InputStream body, Duration stall) {
        return new StallGuard(body, stall);
    }

    /**
     * Closes the stream under a read that has waited too long. The JDK's
     * response stream answers a close from another thread by failing the
     * blocked read at once, which is the one way to bound a blocking read
     * without a deadline on the whole transfer.
     */
    private static final class StallGuard extends FilterInputStream {

        private final Duration stall;
        private final Thread watchdog;
        private volatile long lastProgressNanos = System.nanoTime();
        private volatile boolean stalled;
        private volatile boolean closed;

        StallGuard(InputStream body, Duration stall) {
            super(body);
            this.stall = stall;
            long checkEveryMs = Math.max(10, stall.toMillis() / 4);
            this.watchdog = Thread.ofVirtual().name("download-stall-watch")
                    .start(() -> watch(checkEveryMs));
        }

        private void watch(long checkEveryMs) {
            try {
                while (!closed) {
                    Thread.sleep(checkEveryMs);
                    if (!closed && System.nanoTime() - lastProgressNanos > stall.toNanos()) {
                        stalled = true;
                        try {
                            in.close();
                        } catch (IOException ignored) {
                            // The read it unblocks reports the stall.
                        }
                        return;
                    }
                }
            } catch (InterruptedException e) {
                // Closed by the reader: nothing left to watch.
            }
        }

        @Override
        public int read() throws IOException {
            try {
                int b = in.read();
                lastProgressNanos = System.nanoTime();
                return b;
            } catch (IOException e) {
                throw stalled ? stallFailure(e) : e;
            }
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            try {
                int n = in.read(buffer, off, len);
                lastProgressNanos = System.nanoTime();
                return n;
            } catch (IOException e) {
                throw stalled ? stallFailure(e) : e;
            }
        }

        @Override
        public long transferTo(OutputStream out) throws IOException {
            // Through read(), so every chunk counts as progress; the inherited
            // version may hand the copy to the wrapped stream directly.
            long total = 0;
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = read(buffer, 0, buffer.length)) != -1) {
                out.write(buffer, 0, n);
                total += n;
            }
            return total;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            watchdog.interrupt();
            super.close();
        }

        private IOException stallFailure(IOException cause) {
            HttpTimeoutException failure = new HttpTimeoutException(
                    "No data for " + stall.toSeconds() + " s; the download stalled");
            failure.initCause(cause);
            return failure;
        }
    }
}
