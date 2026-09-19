package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.testing.FxToolkitExtension;
import java.io.IOException;
import java.nio.file.Path;
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
        TrayIconService tray = new TrayIconService(() -> null, null, connections, null, null);
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
                return new ConnectAttempt(Outcome.NO_ENGINE, null);
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

    /** A connection owner with no core behind it, whose connect a test decides. */
    private static class StubConnections extends ConnectionService {

        StubConnections(Path dir) {
            super(new ConfigStore(dir), new SingBoxConfigGenerator(), null, null);
        }

        @Override
        public boolean isRunning() {
            return false;
        }
    }
}
