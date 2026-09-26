package com.vlessclient.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.TrayIconService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * With no core installed, the tray still shows the app, and a window closed
 * with no tray icon to reopen it from quits instead of hiding.
 *
 * <p>The app handed the tray a lookup that threw for a missing engine, so on
 * a first run without the core, or after its download failed, the tray icon
 * was never created. The window's close then hid the app anyway, since the
 * desktop had a tray, and only a second launch brought it back.</p>
 */
class TrayWithoutCoreTest {

    @Test
    void theTrayFollowsNoEngineUntilACoreIsInstalled() {
        SingBoxEngine previous = ServiceLocator.find(SingBoxEngine.class).orElse(null);
        ServiceLocator.remove(SingBoxEngine.class);
        try {
            assertThat(VlessClientApp.trayEngine().get()).as("before a core is installed")
                    .isNull();

            SingBoxEngine installed = new SingBoxEngine(Path.of("target", "no-such-sing-box"));
            ServiceLocator.register(SingBoxEngine.class, installed);
            assertThat(VlessClientApp.trayEngine().get()).as("once one is")
                    .isSameAs(installed);
        } finally {
            // The locator is process-wide: leave it as this test found it.
            if (previous != null) {
                ServiceLocator.register(SingBoxEngine.class, previous);
            } else {
                ServiceLocator.remove(SingBoxEngine.class);
            }
        }
    }

    @Test
    void closingTheWindowHidesItOnlyWithATrayIconShowing() {
        assertThat(VlessClientApp.closeHidesToTray(null)).as("no tray service").isFalse();
        TrayIconService notInstalled = new TrayIconService(() -> null, null, null, null, null,
                null);
        assertThat(notInstalled.isShowing()).isFalse();
        assertThat(VlessClientApp.closeHidesToTray(notInstalled))
                .as("a tray service whose icon is not showing").isFalse();
    }
}
