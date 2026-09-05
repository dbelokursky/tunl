package com.vlessclient.service;

import com.vlessclient.testing.FxToolkitExtension;
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

        LogReader reader = new LogReader(stream, logLines, 100, line -> { });
        reader.start();

        // Wait until reader thread finishes
        waitForStreamConsumed(stream, 2000);
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

        LogReader reader = new LogReader(stream, logLines, 5, line -> { });
        reader.start();

        waitForStreamConsumed(stream, 2000);
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

        LogReader reader = new LogReader(stream, logLines, 100, line -> { });
        reader.start();

        waitForStreamConsumed(stream, 2000);
        flushFxEvents();

        // Calling stop() after the stream is exhausted must not throw
        reader.stop();

        assertThat(logLines).containsExactly("first", "second");
    }

    /**
     * Spin-waits until the given ByteArrayInputStream has been fully consumed
     * (available() == 0) or the timeout expires.
     */
    private static void waitForStreamConsumed(InputStream stream, long timeoutMillis)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (stream.available() == 0) {
                // Give the reader thread a moment to enqueue the final Platform.runLater calls
                Thread.sleep(50);
                return;
            }
            Thread.sleep(10);
        }
    }
}
