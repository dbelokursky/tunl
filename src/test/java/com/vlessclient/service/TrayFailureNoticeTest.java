package com.vlessclient.service;

import com.vlessclient.model.ConnectionState;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tray's system notification for a failed tunnel, counted through the
 * tray's own state listener. Headless: install() is never called, so there is
 * no tray icon and nothing is shown.
 */
class TrayFailureNoticeTest {

    /**
     * A core that exits at every start, on a port another program holds say,
     * is restarted by recovery at every backoff step, and each failure used to
     * raise another notification.
     */
    @Test
    void aFailureStreakRaisesOneNotification() {
        ScriptedEngine engine = new ScriptedEngine();
        CountingTray tray = new CountingTray(engine);
        tray.followEngine();

        for (int start = 0; start < 3; start++) {
            engine.reach(ConnectionState.CONNECTING);
            engine.reach(ConnectionState.ERROR);
        }

        assertThat(tray.notices)
                .as("notifications for three failed starts in a row")
                .hasValue(1);
    }

    /** A tunnel that came up and dropped again is a new failure, and says so. */
    @Test
    void aTunnelThatCameUpAndDroppedAgainIsNotifiedAgain() {
        ScriptedEngine engine = new ScriptedEngine();
        CountingTray tray = new CountingTray(engine);
        tray.followEngine();

        engine.reach(ConnectionState.CONNECTING);
        engine.reach(ConnectionState.ERROR);
        engine.reach(ConnectionState.CONNECTING);
        engine.reach(ConnectionState.CONNECTED);
        engine.reach(ConnectionState.ERROR);

        assertThat(tray.notices)
                .as("notifications for two drops with the tunnel up in between")
                .hasValue(2);
    }

    /** An engine whose connection state the test sets. */
    private static final class ScriptedEngine extends SingBoxEngine {
        private final ReadOnlyObjectWrapper<ConnectionState> state =
                new ReadOnlyObjectWrapper<>(ConnectionState.DISCONNECTED);

        ScriptedEngine() {
            super(Path.of("target", "scripted-engine"));
        }

        @Override
        public ReadOnlyObjectProperty<ConnectionState> connectionStateProperty() {
            return state.getReadOnlyProperty();
        }

        void reach(ConnectionState next) {
            state.set(next);
        }
    }

    /** A tray that counts its failure notices instead of showing them. */
    private static final class CountingTray extends TrayIconService {
        final AtomicInteger notices = new AtomicInteger();

        CountingTray(SingBoxEngine engine) {
            super(engine, null, null, null, null, null);
        }

        @Override
        void notifyTunnelFailed() {
            notices.incrementAndGet();
        }
    }
}
