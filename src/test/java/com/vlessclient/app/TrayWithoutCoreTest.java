package com.vlessclient.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.TrayIconService;
import org.junit.jupiter.api.Test;

/**
 * A window closed with no tray icon to reopen it from quits instead of hiding.
 *
 * <p>The app handed the tray a lookup that threw for a missing engine, so on
 * a first run without the core, or after its download failed, the tray icon
 * was never created. The window's close then hid the app anyway, since the
 * desktop had a tray, and only a second launch brought it back. The engine is
 * there from the start now ({@link OneEnginePerRunTest}); this is the other
 * half, for a desktop whose tray does not take the icon.</p>
 */
class TrayWithoutCoreTest {

    @Test
    void closingTheWindowHidesItOnlyWithATrayIconShowing() {
        assertThat(VlessClientApp.closeHidesToTray(null)).as("no tray service").isFalse();
        TrayIconService notInstalled = new TrayIconService(SingBoxEngine.withoutCore(), null,
                null, null, null, null);
        assertThat(notInstalled.isShowing()).isFalse();
        assertThat(VlessClientApp.closeHidesToTray(notInstalled))
                .as("a tray service whose icon is not showing").isFalse();
    }
}
