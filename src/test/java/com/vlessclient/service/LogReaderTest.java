package com.vlessclient.service;

import com.vlessclient.testing.Await;
import com.vlessclient.testing.FxToolkitExtension;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.vlessclient.testing.FxTestSupport.flushFxEvents;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(FxToolkitExtension.class)
class LogReaderTest {

    /**
     * The core logs "started" for each inbound it brings up, before the rest
     * are; only its own line says the whole core is up. Taking the first read
     * the session as Connected before the core answered, and recovery tore the
     * fresh tunnel down seconds later.
     */
    @Test
    void onlyTheCoresOwnStartedLineCounts() {
        assertThat(LogReader.isStartedMessage(
                "+0200 2026-09-24 19:02:06 INFO inbound/tun[tun-in]: started at utun99"))
                .isFalse();
        assertThat(LogReader.isStartedMessage(
                "INFO inbound/socks[socks-in]: tcp server started at 127.0.0.1:1080"))
                .isFalse();
        assertThat(LogReader.isStartedMessage(
                "+0200 2026-09-24 19:02:07 INFO sing-box started (1.37s)")).isTrue();
    }

    @Test
    void appendsLinesFromInputStreamToObservableList() throws Exception {
        String input = "line one\nline two\nline three\n";
        InputStream stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        ObservableList<String> logLines = FXCollections.observableArrayList();

        Set<Thread> before = Await.liveThreadsNamed(READER_THREAD);
        LogReader reader = new LogReader(stream, logLines, 100, line -> { });
        reader.start();

        awaitReaderFinished(before);
        flushFxEvents();

        assertThat(logLines).containsExactly("line one", "line two", "line three");
    }

    @Test
    void invokesStartedCallbackWhenLineContainsStartedCaseInsensitive() throws Exception {
        String input = "initializing\nSing-Box STARTED successfully\nrunning\n";
        InputStream stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        ObservableList<String> logLines = FXCollections.observableArrayList();
        AtomicReference<String> detected = new AtomicReference<>();
        CountDownLatch startedLatch = new CountDownLatch(1);

        LogReader reader = new LogReader(stream, logLines, 100, line -> {
            detected.set(line);
            startedLatch.countDown();
        });
        reader.start();

        assertThat(startedLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(detected.get()).containsIgnoringCase("started");
    }

    /**
     * The core logs its "started" line once, and once it is seen the reader
     * stops looking: checked on every line, it lower-cased every line of the
     * session to find a line long past.
     */
    @Test
    void reportsTheStartedLineOnceAndStopsLooking() throws Exception {
        String input = "+0200 INFO sing-box started (1.37s)\n"
                + "+0200 INFO sing-box started (again, as a quoted log line)\n"
                + "+0200 INFO dns: exchanged A example.com\n";
        InputStream stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        ObservableList<String> logLines = FXCollections.observableArrayList();
        java.util.concurrent.atomic.AtomicInteger reported =
                new java.util.concurrent.atomic.AtomicInteger();

        Set<Thread> before = Await.liveThreadsNamed(READER_THREAD);
        LogReader reader = new LogReader(stream, logLines, 100, line -> reported.incrementAndGet());
        reader.start();
        awaitReaderFinished(before);
        flushFxEvents();

        assertThat(reported.get()).isEqualTo(1);
        assertThat(logLines).hasSize(3);
    }

    @Test
    void aLineWithoutAnEscapeIsLeftAsItIsAndOneWithEscapesIsStripped() {
        String plain = "+0200 INFO outbound/vless[proxy]: outbound connection to 1.2.3.4:443";
        assertThat(LogReader.stripAnsi(plain)).isSameAs(plain);
        String esc = String.valueOf((char) 0x1B);
        assertThat(LogReader.stripAnsi(esc + "[36mINFO" + esc + "[0m ready")).isEqualTo("INFO ready");
        assertThat(LogReader.stripAnsi("")).isEmpty();
        assertThat(LogReader.stripAnsi(null)).isNull();
    }

    @Test
    void trimsListToMaxLinesActingAsRingBuffer() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("line-").append(i).append('\n');
        }
        InputStream stream = new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8));
        ObservableList<String> logLines = FXCollections.observableArrayList();

        Set<Thread> before = Await.liveThreadsNamed(READER_THREAD);
        LogReader reader = new LogReader(stream, logLines, 5, line -> { });
        reader.start();

        awaitReaderFinished(before);
        flushFxEvents();

        assertThat(logLines).hasSize(5);
        assertThat(logLines).containsExactly(
                "line-15", "line-16", "line-17", "line-18", "line-19");
    }

    @Test
    void stopsGracefullyWhenInputStreamIsClosed() throws Exception {
        String input = "first\nsecond\n";
        InputStream stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        ObservableList<String> logLines = FXCollections.observableArrayList();

        Set<Thread> before = Await.liveThreadsNamed(READER_THREAD);
        LogReader reader = new LogReader(stream, logLines, 100, line -> { });
        reader.start();

        awaitReaderFinished(before);
        flushFxEvents();

        // Calling stop() after the stream is exhausted must not throw
        reader.stop();

        assertThat(logLines).containsExactly("first", "second");
    }

    /**
     * One FX task per line flooded the FX queue: each line was an addition and,
     * once the buffer was full, a removal, and the Logs view reacted to both.
     * Lines read while the FX thread is busy now reach the list in one change.
     */
    @Test
    void linesReadWhileTheFxThreadIsBusyArriveAsOneAddition() throws Exception {
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            input.append("line-").append(i).append('\n');
        }
        ObservableList<String> logLines = FXCollections.observableArrayList();
        List<String> changes = new CopyOnWriteArrayList<>();
        logLines.addListener((ListChangeListener<String>) change -> {
            while (change.next()) {
                changes.add(change.wasAdded()
                        ? "added " + change.getAddedSize()
                        : "removed " + change.getRemovedSize());
            }
        });
        CountDownLatch fxHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Platform.runLater(() -> {
            fxHeld.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(fxHeld.await(5, TimeUnit.SECONDS)).isTrue();

        Set<Thread> before = Await.liveThreadsNamed(READER_THREAD);
        LogReader reader = new LogReader(
                new ByteArrayInputStream(input.toString().getBytes(StandardCharsets.UTF_8)),
                logLines, 100, line -> { });
        try {
            reader.start();
            awaitReaderFinished(before);
        } finally {
            release.countDown();
        }
        flushFxEvents();

        assertThat(logLines).hasSize(100).startsWith("line-400").endsWith("line-499");
        assertThat(changes).as("changes the list reported").containsExactly("added 100");
    }

    /** LogReader reads on a daemon thread with this name and exposes no join. */
    private static final String READER_THREAD = "singbox-log-reader";

    /**
     * Waits for the reader thread started since the snapshot to exit. It
     * ends at EOF, after the last line has been handed to Platform.runLater,
     * so once it is gone a flush of the FX queue is all that is left.
     * A drained stream was only a hint: the thread could still be between
     * the last read and the last runLater, which a 50 ms sleep papered over.
     */
    private static void awaitReaderFinished(Set<Thread> before) {
        Await.untilThreadsFinished(READER_THREAD, before, Duration.ofSeconds(5));
    }
}
