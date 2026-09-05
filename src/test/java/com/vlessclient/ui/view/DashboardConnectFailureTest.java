package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.ProxyMode;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the dashboard does when the connect flow fails in a way nobody declared.
 *
 * <p>{@code runConnect} used to catch only {@link java.io.IOException}. A
 * {@link RuntimeException} — {@code FxExecutor.get} unwrapping a failed task,
 * or the rethrow in {@code SingBoxEngine.startLocked} — escaped the virtual
 * thread instead, while the {@code finally} still re-enabled the button. The
 * user was left clicking a live Connect against a status pill frozen on
 * CONNECTING, with nothing written to {@code tunl.log}.</p>
 *
 * <p>The assertion is deliberately two-sided: the failure must reach the status
 * line, <em>and</em> it must not reach the thread's uncaught handler.</p>
 */
@UiTest
public class DashboardConnectFailureTest extends ApplicationTest {

    private static final String BOOM = "sing-box argv rejected";

    private static Thread.UncaughtExceptionHandler previousHandler;
    private static final AtomicReference<Throwable> escaped = new AtomicReference<>();

    private DashboardViewController controller;

    @BeforeAll
    static void setupHeadless() {
        // Present but never started: the throwing double replaces the flow.
        ServiceLocator.register(SingBoxEngine.class,
                new SingBoxEngine(Path.of("target", "no-such-sing-box")));
        ServiceLocator.register(ConnectionService.class, new ThrowingConnectionService());

        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> escaped.set(error));
    }

    @AfterAll
    static void restoreHandler() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
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
    void anUncheckedFailureIsReportedOnTheStatusLineAndNeverEscapes() throws Exception {
        Platform.runLater(controller::toggleConnection);

        Label status = lookup("#statusLabel").query();
        Await.until("the failure to reach the status line",
                () -> status.getText().contains(BOOM), Duration.ofSeconds(10));

        assertThat(status.getText())
                .as("without the catch the status line keeps its previous text "
                        + "and the user is told nothing at all")
                .isEqualTo("Failed to start: " + BOOM);
        assertThat(escaped.get())
                .as("the exception must be handled, not dumped to a stderr "
                        + "that the packaged app discards")
                .isNull();
    }

    /** Fails the way an internal defect does: unchecked, undeclared. */
    private static final class ThrowingConnectionService extends ConnectionService {

        ThrowingConnectionService() {
            super(null, null, null, null);
        }

        @Override
        public ConnectAttempt connect(ProxyMode modeOverride) {
            throw new IllegalStateException(BOOM);
        }
    }
}
