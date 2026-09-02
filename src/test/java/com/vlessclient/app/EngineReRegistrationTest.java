package com.vlessclient.app;

import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.TrayIconService;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Installing sing-box from inside the app replaces the engine object, and
 * everything bound to it has to be told.
 *
 * <p>{@code ConnectionService}, the MCP control facade and the log bridge were
 * already re-pointed here. The tray was not, so its icon followed the engine
 * that never had a binary for the rest of the run.</p>
 */
class EngineReRegistrationTest {

    private TrayIconService previousTray;

    @AfterEach
    void restoreLocator() {
        // The locator is process-wide; a double left behind would follow every
        // later test in this JVM.
        if (previousTray != null) {
            ServiceLocator.register(TrayIconService.class, previousTray);
        }
    }

    @Test
    @DisplayName("registering an engine tells the tray to move its listener")
    void registeringAnEngineRebindsTheTray() {
        try {
            previousTray = ServiceLocator.get(TrayIconService.class);
        } catch (IllegalArgumentException e) {
            previousTray = null;
        }
        RecordingTray tray = new RecordingTray();
        ServiceLocator.register(TrayIconService.class, tray);

        ServiceLocator.registerSingBoxEngine(Path.of("target", "no-such-sing-box"));

        assertThat(tray.rebinds)
                .as("without this the tray keeps listening to the engine that "
                        + "never had a binary, silently, until the next launch")
                .isEqualTo(1);
    }

    /**
     * Records the call. Constructed with nulls on purpose: the constructor only
     * assigns fields and {@code install()} is never reached, so this stays
     * headless.
     */
    private static final class RecordingTray extends TrayIconService {

        private int rebinds;

        RecordingTray() {
            super(() -> null, null, null, null, null);
        }

        @Override
        public void rebindEngineListener() {
            rebinds++;
        }
    }
}
