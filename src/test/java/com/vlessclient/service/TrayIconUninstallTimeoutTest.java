package com.vlessclient.service;

import org.junit.jupiter.api.Test;

import java.awt.EventQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stuck AWT event thread must not be able to hold the quit path open.
 *
 * <p>On macOS the caller of {@code uninstall()} is the FX application thread,
 * which is the process main thread. By the time {@code stop()} runs, JavaFX has
 * torn down the AppKit run loop that thread was pumping, so any EDT task that
 * needs the main thread can no longer finish. The previous {@code invokeAndWait}
 * then parked the main thread on that unfinishable task and the two waited on
 * each other forever: the app froze with no way out but SIGKILL, and the update
 * relay waiting for this process to exit gave up after 30s.</p>
 */
class TrayIconUninstallTimeoutTest {

    @Test
    void uninstallGivesUpWhenTheEventThreadNeverRunsTheRemoval() throws Exception {
        CountDownLatch wedged = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // Stands in for the EDT task blocked on a main thread that stopped
        // pumping its run loop. It occupies the queue ahead of the removal, so
        // the removal is enqueued but never dispatched.
        EventQueue.invokeLater(() -> {
            wedged.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(wedged.await(10, TimeUnit.SECONDS))
                .as("AWT event thread never picked up the blocking task")
                .isTrue();

        TrayIconService tray = new TrayIconService(() -> null, null, null, null, null);
        // On a separate thread so the old, unbounded behaviour fails this test
        // rather than hanging the whole suite on it.
        Thread caller = new Thread(tray::uninstall, "uninstall-caller");
        caller.setDaemon(true);
        try {
            caller.start();
            caller.join(TimeUnit.SECONDS.toMillis(10));

            assertThat(caller.isAlive())
                    .as("uninstall() is still waiting on the wedged event thread")
                    .isFalse();
        } finally {
            // Hand the shared event thread back, whatever the assertions did.
            release.countDown();
        }
    }
}
