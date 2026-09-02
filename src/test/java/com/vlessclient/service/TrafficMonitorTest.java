package com.vlessclient.service;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TrafficMonitorTest {

    @BeforeAll
    static void initJavaFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
            latch.await(5, TimeUnit.SECONDS);
        } catch (IllegalStateException e) {
            // Platform already initialized
        }
    }

    // --- formatSpeed tests ---

    @Test
    void formatSpeed_zero() {
        assertThat(TrafficMonitor.formatSpeed(0)).isEqualTo("0 B/s");
    }

    @Test
    void formatSpeed_bytes() {
        assertThat(TrafficMonitor.formatSpeed(1023)).isEqualTo("1023 B/s");
    }

    @Test
    void formatSpeed_kilobytes() {
        assertThat(TrafficMonitor.formatSpeed(1024)).isEqualTo("1.0 KB/s");
    }

    @Test
    void formatSpeed_megabytes() {
        assertThat(TrafficMonitor.formatSpeed(1048576)).isEqualTo("1.0 MB/s");
    }

    @Test
    void formatSpeed_gigabytes() {
        assertThat(TrafficMonitor.formatSpeed(1073741824L)).isEqualTo("1.00 GB/s");
    }

    @Test
    void formatSpeed_negative() {
        assertThat(TrafficMonitor.formatSpeed(-1)).isEqualTo("0 B/s");
    }

    @Test
    void formatSpeed_midRange() {
        // 1.5 MB/s = 1572864 bytes/sec
        assertThat(TrafficMonitor.formatSpeed(1572864)).isEqualTo("1.5 MB/s");
    }

    @Test
    void formatSpeed_256KB() {
        // 256 KB/s = 262144 bytes/sec
        assertThat(TrafficMonitor.formatSpeed(262144)).isEqualTo("256.0 KB/s");
    }

    // --- formatBytes tests ---

    @Test
    void formatBytes_zero() {
        assertThat(TrafficMonitor.formatBytes(0)).isEqualTo("0 B");
    }

    @Test
    void formatBytes_bytes() {
        assertThat(TrafficMonitor.formatBytes(500)).isEqualTo("500 B");
    }

    @Test
    void formatBytes_kilobytes() {
        assertThat(TrafficMonitor.formatBytes(1024)).isEqualTo("1.0 KB");
    }

    @Test
    void formatBytes_megabytes() {
        assertThat(TrafficMonitor.formatBytes(1048576)).isEqualTo("1.0 MB");
    }

    @Test
    void formatBytes_gigabytes() {
        assertThat(TrafficMonitor.formatBytes(1073741824L)).isEqualTo("1.00 GB");
    }

    @Test
    void formatBytes_largeGigabytes() {
        // 1.23 GB
        assertThat(TrafficMonitor.formatBytes(1320702444L)).isEqualTo("1.23 GB");
    }

    @Test
    void formatBytes_456MB() {
        // 456 MB = 478150656 bytes
        assertThat(TrafficMonitor.formatBytes(478150656L)).isEqualTo("456.0 MB");
    }

    @Test
    void formatBytes_negative() {
        assertThat(TrafficMonitor.formatBytes(-100)).isEqualTo("0 B");
    }

    // --- SSE JSON parsing tests ---

    @Test
    void processTrafficLine_parsesUpDown() throws InterruptedException {
        TrafficMonitor monitor = new TrafficMonitor();

        monitor.processTrafficLine("{\"up\":1234,\"down\":5678}");

        // Wait for Platform.runLater to execute
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        latch.await(2, TimeUnit.SECONDS);

        assertThat(monitor.uploadSpeedProperty().get()).isEqualTo(1234);
        assertThat(monitor.downloadSpeedProperty().get()).isEqualTo(5678);
        assertThat(monitor.totalUploadProperty().get()).isEqualTo(1234);
        assertThat(monitor.totalDownloadProperty().get()).isEqualTo(5678);
    }

    @Test
    void processTrafficLine_accumulatesTotal() throws InterruptedException {
        TrafficMonitor monitor = new TrafficMonitor();

        monitor.processTrafficLine("{\"up\":100,\"down\":200}");
        CountDownLatch latch1 = new CountDownLatch(1);
        Platform.runLater(latch1::countDown);
        latch1.await(2, TimeUnit.SECONDS);

        monitor.processTrafficLine("{\"up\":300,\"down\":400}");
        CountDownLatch latch2 = new CountDownLatch(1);
        Platform.runLater(latch2::countDown);
        latch2.await(2, TimeUnit.SECONDS);

        assertThat(monitor.uploadSpeedProperty().get()).isEqualTo(300);
        assertThat(monitor.downloadSpeedProperty().get()).isEqualTo(400);
        assertThat(monitor.totalUploadProperty().get()).isEqualTo(400);
        assertThat(monitor.totalDownloadProperty().get()).isEqualTo(600);
    }

    @Test
    void processTrafficLine_ignoresBlankLines() throws InterruptedException {
        TrafficMonitor monitor = new TrafficMonitor();

        monitor.processTrafficLine("");
        monitor.processTrafficLine("   ");

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        latch.await(2, TimeUnit.SECONDS);

        assertThat(monitor.uploadSpeedProperty().get()).isEqualTo(0);
        assertThat(monitor.downloadSpeedProperty().get()).isEqualTo(0);
    }

    @Test
    void processTrafficLine_handlesInvalidJson() throws InterruptedException {
        TrafficMonitor monitor = new TrafficMonitor();

        monitor.processTrafficLine("not-json");

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        latch.await(2, TimeUnit.SECONDS);

        assertThat(monitor.uploadSpeedProperty().get()).isEqualTo(0);
        assertThat(monitor.downloadSpeedProperty().get()).isEqualTo(0);
    }

    @Test
    void processTrafficLine_handlesMissingFields() throws InterruptedException {
        TrafficMonitor monitor = new TrafficMonitor();

        monitor.processTrafficLine("{\"up\":999}");

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        latch.await(2, TimeUnit.SECONDS);

        assertThat(monitor.uploadSpeedProperty().get()).isEqualTo(999);
        assertThat(monitor.downloadSpeedProperty().get()).isEqualTo(0);
    }

    @Test
    void stop_zeroesTheReadoutsEvenWhenTheStreamAlreadyEnded() throws InterruptedException {
        TrafficMonitor monitor = new TrafficMonitor();
        monitor.processTrafficLine("{\"up\":1234,\"down\":5678}");
        awaitFx();
        assertThat(monitor.uploadSpeedProperty().get()).isEqualTo(1234);

        // stop() on a monitor whose running flag is already false — which is
        // what a dropped SSE stream leaves behind, since the streaming thread
        // clears the flag itself. The early return used to skip the reset, so
        // the dashboard kept showing the last speeds as if traffic were still
        // flowing over a tunnel that is gone.
        monitor.stop();
        awaitFx();

        assertThat(monitor.uploadSpeedProperty().get()).isZero();
        assertThat(monitor.downloadSpeedProperty().get()).isZero();
    }

    /** Lets the pending Platform.runLater work land. */
    private static void awaitFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        latch.await(2, TimeUnit.SECONDS);
    }
}
