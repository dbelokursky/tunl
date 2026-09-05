package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConnectionService;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.UiTest;
import java.time.Duration;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard's half of the connect flow, exercised through the real view.
 *
 * <p>The controller now only decides <em>when</em> to connect and how to render
 * the outcome; the flow itself belongs to {@link ConnectionService}. Two things
 * about that hand-off are worth pinning down here, because both failed silently
 * in review before: the controller must actually delegate, and it must do so
 * <strong>off the JavaFX thread</strong> — a connect blocks for the core's start
 * (a ~40 MB hash, and a modal admin prompt for TUN), which is exactly the freeze
 * the tray menu shipped with. A recording double stands in for the service so
 * this stays a headless test with no core, no dialogs and no real network.</p>
 */
@UiTest
public class DashboardConnectDelegationTest extends ApplicationTest {

    private static RecordingConnectionService recording;

    private DashboardViewController controller;

    @BeforeAll
    static void setupHeadless() {
        // An engine must be present or the controller reports "sing-box not
        // found" with a modal dialog, which would hang a headless run. It is
        // never started: the recording service replaces the whole flow.
        ServiceLocator.register(SingBoxEngine.class,
                new SingBoxEngine(Path.of("target", "no-such-sing-box")));
        recording = new RecordingConnectionService();
        ServiceLocator.register(ConnectionService.class, recording);
    }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/DashboardView.fxml"));
        Parent root = loader.load();
        controller = loader.getController();
        stage.setScene(new Scene(root, 640, 480));
        stage.show();
    }

    @Test
    void connectingDelegatesToTheConnectionServiceOffTheFxThread() throws Exception {
        Platform.runLater(controller::toggleConnection);

        assertThat(recording.called.await(10, TimeUnit.SECONDS))
                .as("the controller must delegate the connect to ConnectionService")
                .isTrue();
        assertThat(recording.connects.get()).isEqualTo(1);
        assertThat(recording.sawFxThread.get())
                .as("a connect blocks for the core's start; running it on the FX "
                        + "thread is the freeze the tray menu shipped with")
                .isFalse();
    }

    @Test
    void aStartedConnectPublishesTheServerToTheUiThread() throws Exception {
        Platform.runLater(controller::toggleConnection);
        assertThat(recording.called.await(10, TimeUnit.SECONDS)).isTrue();

        // The STARTED branch hops back to the FX thread to name the server; give
        // that runLater a chance to land before sampling the label.
        Label serverName = lookup("#serverNameLabel").query();
        Await.until("the server name to reach the label",
                () -> "Tokyo".equals(serverName.getText()), Duration.ofSeconds(5));
        assertThat(serverName.getText()).isEqualTo("Tokyo");
    }

    /** Stands in for the real flow: records the call and reports success. */
    private static final class RecordingConnectionService extends ConnectionService {

        final AtomicInteger connects = new AtomicInteger();
        final AtomicBoolean sawFxThread = new AtomicBoolean();
        final CountDownLatch called = new CountDownLatch(1);

        RecordingConnectionService() {
            super(null, null, null, null);
        }

        @Override
        public ConnectAttempt connect(ProxyMode modeOverride) {
            connects.incrementAndGet();
            sawFxThread.set(Platform.isFxApplicationThread());
            called.countDown();
            ServerConfig target = new ServerConfig();
            target.setId("srv-1");
            target.setName("Tokyo");
            return new ConnectAttempt(Outcome.STARTED, target);
        }
    }
}
