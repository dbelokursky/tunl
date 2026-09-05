package com.vlessclient.service;

import com.vlessclient.testing.Await;
import com.vlessclient.testing.FxToolkitExtension;
import java.time.Duration;
import java.util.Set;
import javafx.application.Platform;
import javafx.collections.FXCollections;
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
