package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.TunnelHealth;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.TunnelHealthState;
import java.nio.file.Path;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * The hero card must not call a tunnel connected before the reachability
 * probes back that up, and must go on offering Disconnect when they don't —
 * a dead tunnel is still one the user turns off, not one they retry.
 */
public class DashboardTunnelStatusTest extends ApplicationTest {

    /** Engine whose connection state the test drives directly. */
    private static final class FakeEngine extends SingBoxEngine {
        private final SimpleObjectProperty<ConnectionState> state =
                new SimpleObjectProperty<>(ConnectionState.DISCONNECTED);

        FakeEngine() {
            super(Path.of("sing-box-not-used-in-tests"));
        }

        @Override
        public ReadOnlyObjectProperty<ConnectionState> connectionStateProperty() {
            return state;
        }
    }

    private static FakeEngine engine;
    private static TunnelHealthState health;
    private static Object priorEngine;
    private static Object priorHealth;
    private static Object priorSettings;

    @BeforeAll
    static void setupHeadless() {
        System.setProperty("testfx.robot", "glass");
        System.setProperty("testfx.headless", "true");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        System.setProperty("java.awt.headless", "true");
        try {
            ServiceLocator.initialize();
        } catch (Exception e) {
            // Tolerate service initialization failures in headless CI
        }
        priorEngine = tryGet(SingBoxEngine.class);
        priorHealth = tryGet(TunnelHealthState.class);
        priorSettings = tryGet(AppSettings.class);

        // The real probe loop would otherwise race this test for the same
        // health state — and on a developer machine with a tunnel actually up,
        // win. Switch the checks off so the only writer is the test.
        AppSettings settings = new AppSettings();
        settings.setHealthCheckEnabled(false);
        ServiceLocator.register(AppSettings.class, settings);

        engine = new FakeEngine();
        health = new TunnelHealthState();
        ServiceLocator.register(SingBoxEngine.class, engine);
        ServiceLocator.register(TunnelHealthState.class, health);
    }

    private static <T> T tryGet(Class<T> type) {
        try {
            return ServiceLocator.get(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @AfterAll
    static void restoreServices() {
        if (priorEngine instanceof SingBoxEngine restored) {
            ServiceLocator.register(SingBoxEngine.class, restored);
        }
        if (priorHealth instanceof TunnelHealthState restored) {
            ServiceLocator.register(TunnelHealthState.class, restored);
        }
        ServiceLocator.register(AppSettings.class,
                priorSettings instanceof AppSettings restored ? restored : new AppSettings());
    }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/DashboardView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 640, 480));
        stage.show();
    }

    /**
     * Puts the view in one (state, verdict) combination. The engine change
     * goes first because the coordinator resets the verdict as it reacts to
     * it; the test's own verdict is written last and stands.
     */
    private void showTunnel(ConnectionState state, TunnelHealth verdict) {
        interact(() -> {
            engine.state.set(state);
            health.set(verdict);
        });
    }

    private String titleText() {
        Label title = lookup("#statusTitle").query();
        return title.getText();
    }

    private Circle statusCircle() {
        return lookup("#statusCircle").query();
    }

    @Test
    void aProvenTunnelReadsAsConnected() {
        showTunnel(ConnectionState.CONNECTED, TunnelHealth.HEALTHY);

        assertThat(titleText()).isEqualTo(I18n.get("state.connected"));
        assertThat(statusCircle().getStyleClass()).containsExactly("status-circle-connected");
    }

    @Test
    void anUnprovenTunnelSaysSoInsteadOfClaimingConnected() {
        showTunnel(ConnectionState.CONNECTED, TunnelHealth.CHECKING);

        assertThat(titleText()).isEqualTo(I18n.get("state.verifying"));
        assertThat(statusCircle().getStyleClass()).containsExactly("status-circle-connecting");
    }

    @Test
    void partialReachabilityReadsAsDegraded() {
        showTunnel(ConnectionState.CONNECTED, TunnelHealth.DEGRADED);

        assertThat(titleText()).isEqualTo(I18n.get("state.degraded"));
        assertThat(statusCircle().getStyleClass()).containsExactly("status-circle-connecting");
    }

    @Test
    void aTunnelCarryingNothingReadsAsFailedButStillOffersDisconnect() {
        showTunnel(ConnectionState.CONNECTED, TunnelHealth.BROKEN);

        assertThat(titleText()).isEqualTo(I18n.get("state.no.traffic"));
        assertThat(statusCircle().getStyleClass()).containsExactly("status-circle-error");

        // The process is still running, so the action on offer is Disconnect —
        // the red dot must not turn the button into Retry.
        Button connect = lookup("#connectButton").query();
        assertThat(connect.getText()).isEqualTo(I18n.get("button.disconnect"));
        assertThat(connect.getStyleClass()).contains("disconnect-button");
    }

    @Test
    void switchingTheChecksOffLeavesAPlainConnectedTunnel() {
        showTunnel(ConnectionState.CONNECTED, TunnelHealth.UNMONITORED);

        assertThat(titleText()).isEqualTo(I18n.get("state.connected"));
        assertThat(statusCircle().getStyleClass()).containsExactly("status-circle-connected");
    }

    @Test
    void aStaleVerdictNeverSurvivesTheTunnelItDescribed() {
        showTunnel(ConnectionState.CONNECTED, TunnelHealth.BROKEN);
        assertThat(titleText()).isEqualTo(I18n.get("state.no.traffic"));

        showTunnel(ConnectionState.DISCONNECTED, TunnelHealth.BROKEN);

        assertThat(titleText()).isEqualTo(I18n.get("state.disconnected"));
        assertThat(statusCircle().getStyleClass()).containsExactly("status-circle-disconnected");
        Button connect = lookup("#connectButton").query();
        assertThat(connect.getText()).isEqualTo(I18n.get("button.connect"));
    }
}
