package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.service.ConnectionService;
import com.vlessclient.service.ConnectionService.ConnectAttempt;
import com.vlessclient.service.ConnectionService.Outcome;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * The Dashboard offers a reconnect while the running core was started from
 * settings that changed since.
 *
 * <p>DNS, ports, TUN options, routing rules and the mode are read when the core
 * starts. Changed while connected, they waited for the next reconnect, and
 * nothing on screen said so: not even the mode picked on this very card.</p>
 */
@UiTest
public class DashboardPendingChangesTest extends ApplicationTest {

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

    private static final FakeEngine ENGINE = new FakeEngine();
    /** What the stub service answers; read on the FX thread. */
    private static volatile boolean runsCurrentSettings = true;
    /** The mode each reconnect asked for, and whether it ran on the FX thread. */
    private static final List<String> RECONNECTS = new CopyOnWriteArrayList<>();
    private static final CountDownLatch RECONNECTED = new CountDownLatch(1);

    private ViewShownAware controller;

    @BeforeAll
    static void registerTheServices() {
        AppSettings settings = new AppSettings();
        settings.setHealthCheckEnabled(false);
        ServiceLocator.register(AppSettings.class, settings);
        ServiceLocator.register(SingBoxEngine.class, ENGINE);
        ServiceLocator.register(ConnectionService.class,
                new ConnectionService(null, null, null, null) {
                    @Override
                    public boolean runsCurrentSettings() {
                        return runsCurrentSettings;
                    }

                    @Override
                    public ConnectAttempt reconnect(ProxyMode modeOverride) {
                        RECONNECTS.add(modeOverride + (Platform.isFxApplicationThread()
                                ? " on the FX thread" : ""));
                        RECONNECTED.countDown();
                        return new ConnectAttempt(Outcome.CANCELLED, null);
                    }
                });
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
    void theOfferFollowsWhetherTheRunningCoreHasTheSettings() {
        assertThat(bannerShown()).as("nothing runs").isFalse();

        runsCurrentSettings = false;
        interact(() -> ENGINE.state.set(ConnectionState.CONNECTED));
        assertThat(bannerShown()).as("connected on settings changed since").isTrue();

        runsCurrentSettings = true;
        interact(controller::onViewShown);
        assertThat(bannerShown()).as("shown again, the core running the settings").isFalse();

        runsCurrentSettings = false;
        interact(controller::onViewShown);
        assertThat(bannerShown()).as("back from a page that changed a setting").isTrue();

        interact(() -> ENGINE.state.set(ConnectionState.DISCONNECTED));
        assertThat(bannerShown()).as("disconnected").isFalse();
    }

    /**
     * The offer is a reconnect with no mode of its own, so the saved one
     * applies: that is what {@link ConnectionService#runsCurrentSettings}
     * compares with.
     */
    @Test
    void theOfferReconnectsInTheSavedMode() throws InterruptedException {
        runsCurrentSettings = false;
        interact(() -> ENGINE.state.set(ConnectionState.CONNECTED));

        interact(() -> lookup("#pendingChangesButton").queryAs(Button.class).fire());

        assertThat(RECONNECTED.await(5, TimeUnit.SECONDS)).as("reconnected").isTrue();
        assertThat(RECONNECTS).containsExactly("null");
        interact(() -> ENGINE.state.set(ConnectionState.DISCONNECTED));
    }

    private boolean bannerShown() {
        HBox banner = lookup("#pendingChangesBanner").queryAs(HBox.class);
        return banner.isVisible() && banner.isManaged();
    }
}
