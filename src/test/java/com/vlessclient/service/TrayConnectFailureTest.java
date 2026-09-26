package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.FxToolkitExtension;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * A connect from the tray that fails says why.
 *
 * <p>The tray is where a user with the window hidden connects, and a failed
 * start went to the log only: the icon stayed grey and nothing else happened,
 * while the same failure from the Dashboard put up its reason.</p>
 */
@ExtendWith(FxToolkitExtension.class)
class TrayConnectFailureTest {

    @TempDir
    Path tempDir;

    private final List<List<String>> notices = new ArrayList<>();

    private TrayIconService trayOver(ConnectionService connections) {
        TrayIconService tray = new TrayIconService(SingBoxEngine.withoutCore(), null,
                connections, null, null, null);
        tray.setNotifier((title, body) -> notices.add(List.of(title, body)));
        return tray;
    }

    @Test
    void aStartThatFailsShowsTheReason() {
        String reason = "sing-box refused the configuration: server Tokyo";
        TrayIconService tray = trayOver(new StubConnections(tempDir) {
            @Override
            public ConnectAttempt connect() throws IOException {
                throw new IOException(reason);
            }
        });

        tray.toggleConnection();

        assertThat(notices).containsExactly(
                List.of(I18n.get("dashboard.error.start.title"), reason));
    }

    @Test
    void aConnectWithoutTheCoreSaysSo() {
        TrayIconService tray = trayOver(new StubConnections(tempDir) {
            @Override
            public ConnectAttempt connect() {
                return new ConnectAttempt(Outcome.NO_CORE, null);
            }
        });

        tray.toggleConnection();

        assertThat(notices).containsExactly(List.of(I18n.get("error.singbox.not.found"),
                I18n.get("dashboard.error.singbox.body")));
    }

    @Test
    void aConnectThatStartsSaysNothing() {
        TrayIconService tray = trayOver(new StubConnections(tempDir) {
            @Override
            public ConnectAttempt connect() {
                return new ConnectAttempt(Outcome.ALREADY_RUNNING, null);
            }
        });

        tray.toggleConnection();

        assertThat(notices).isEmpty();
    }

    /**
     * Recovery giving up leaves the user's request for a tunnel standing,
     * with nothing restarting it and the subscriptions held back. With the
     * window hidden, the tray's colour was all that changed.
     */
    @Test
    void recoveryThatStopsSaysWhy() {
        AppSettings settings = new AppSettings();
        settings.setHealthCheckAutoReconnect(true);
        settings.setHealthCheckDelaySeconds(1);
        ConfigRejectedException refusal =
                new ConfigRejectedException("unknown field \"download_detour\"");
        TunnelRecoveryService recovery = new TunnelRecoveryService(() -> settings, guard -> {
            throw refusal;
        }, () -> false);
        try {
            TrayIconService tray = trayOver(new StubConnections(tempDir) {
                @Override
                public TunnelRecoveryService getRecoveryService() {
                    return recovery;
                }
            });
            tray.followRecovery();

            recovery.connectionRequested();
            recovery.onConnectionState(ConnectionState.ERROR);

            assertThat(Await.untilValue("the notice",
                    () -> FxExecutor.get(() -> List.copyOf(notices)),
                    shown -> !shown.isEmpty(), Duration.ofSeconds(10)))
                    .containsExactly(List.of(I18n.get("tray.notify.stopped.title"),
                            I18n.get("tray.notify.stopped.body", refusal.getMessage())));
        } finally {
            recovery.close();
        }
    }

    /** A connection owner with no core behind it, whose connect a test decides. */
    private static class StubConnections extends ConnectionService {

        StubConnections(Path dir) {
            super(new ConfigStore(dir), new SingBoxConfigGenerator(), null,
                    SingBoxEngine.withoutCore());
        }

        @Override
        public boolean isRunning() {
            return false;
        }
    }
}
