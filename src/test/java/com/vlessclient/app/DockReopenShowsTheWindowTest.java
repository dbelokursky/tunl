package com.vlessclient.app;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * On macOS a click on the Dock icon, or opening Tunl again from Finder or
 * Spotlight, does not start a second process: LaunchServices sends the
 * running app a reopen event, which AWT, the delegate since it started
 * first, hands to its reopen listeners. There were none, so a window closed
 * to the menu bar stayed hidden and the app looked broken. The listener the
 * app registers shows the window.
 *
 * <p>Only the listener is tested: AWT supports the event on macOS alone, and
 * these tests run headless on every OS.</p>
 */
class DockReopenShowsTheWindowTest {

    @Test
    void aReopenEventShowsTheWindow() {
        AtomicInteger shown = new AtomicInteger();

        VlessClientApp.reopenListener(shown::incrementAndGet).appReopened(null);

        assertThat(shown).hasValue(1);
    }
}
