package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ConnectionService;
import com.vlessclient.service.ConnectionService.ConnectAttempt;
import com.vlessclient.service.ConnectionService.Outcome;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Connect keeps the keyboard, and says why it cannot be pressed.
 *
 * <p>A start or a stop disables the button while it runs, and disabling the
 * focused button moved focus on to the next control, the health card's "+":
 * a second Space opened "Add service". And the reason a disabled Connect
 * gave in a tooltip never showed, since a disabled node gets no mouse
 * events.</p>
 *
 * <p>Each repaint also appended the button's colour class again, so its
 * style classes grew by one per update.</p>
 */
@UiTest
public class DashboardConnectFocusTest extends ApplicationTest {

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
    private static final CountDownLatch CONNECT_ASKED = new CountDownLatch(1);
    private static final CountDownLatch CONNECT_RELEASED = new CountDownLatch(1);
    private static ServerConfig server;

    private Parent root;

    @BeforeAll
    static void registerTheServices() {
        AppSettings settings = new AppSettings();
        settings.setHealthCheckEnabled(true);
        ServiceLocator.register(AppSettings.class, settings);
        ServiceLocator.register(SingBoxEngine.class, ENGINE);
        ServiceLocator.register(ConnectionService.class,
                new ConnectionService(null, null, null, null) {
                    @Override
                    public ConnectAttempt connect(ProxyMode modeOverride) {
                        CONNECT_ASKED.countDown();
                        try {
                            CONNECT_RELEASED.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return new ConnectAttempt(Outcome.ALREADY_RUNNING, null);
                    }
                });
        server = new ServerConfig();
        server.setName("Netherlands 01");
        server.setAddress("185.107.56.12");
        server.setPort(443);
        server.setProtocol(Protocol.VLESS);
        ServiceLocator.get(ConfigStore.class).addServer(server);
    }

    @AfterAll
    static void removeTheServer() {
        ServiceLocator.get(ConfigStore.class).applyServerBatch(List.of(), List.of(server.getId()));
    }

    @Override
    public void start(Stage stage) throws Exception {
        root = new FXMLLoader(getClass().getResource("/fxml/DashboardView.fxml")).load();
        stage.setScene(new Scene(root, 640, 700));
        stage.show();
    }

    @Test
    void aStartedConnectKeepsTheKeyboardOnConnect() throws InterruptedException {
        Button connect = lookup("#connectButton").queryAs(Button.class);
        Button addTarget = lookup("#addTargetButton").queryAs(Button.class);
        interact(connect::requestFocus);

        interact(connect::fire);
        assertThat(CONNECT_ASKED.await(10, TimeUnit.SECONDS)).as("connect asked").isTrue();
        Node owner = connect.getScene().getFocusOwner();
        assertThat(owner).as("focus while Connect is disabled").isNotSameAs(addTarget);

        CONNECT_RELEASED.countDown();
        interact(() -> ENGINE.state.set(ConnectionState.CONNECTED));
        Await.until("Connect to be enabled", () -> !connect.isDisabled(), Duration.ofSeconds(10));
        assertThat(connect.isFocused()).as("focus back on the button once enabled").isTrue();
        interact(() -> ENGINE.state.set(ConnectionState.DISCONNECTED));
    }

    @Test
    void aDisabledConnectSaysWhyWhereItCanBeRead() {
        Button connect = lookup("#connectButton").queryAs(Button.class);
        ConfigStore store = ServiceLocator.get(ConfigStore.class);
        interact(() -> store.applyServerBatch(List.of(), List.of(server.getId())));
        try {
            Await.until("Connect to be disabled with no server", connect::isDisabled,
                    Duration.ofSeconds(10));
            assertThat(connect.getAccessibleHelp())
                    .as("what a screen reader says of the disabled button")
                    .isEqualTo(I18n.get("dashboard.no.servers"));
            Node holder = lookup("#connectButtonHolder").query();
            assertThat(holder.getProperties().get("javafx.scene.control.Tooltip"))
                    .as("the tooltip, on a holder the pointer reaches")
                    .isInstanceOf(Tooltip.class);
        } finally {
            interact(() -> store.addServer(server));
        }
    }

    @Test
    void aRepaintLeavesConnectOneColourClass() {
        Button connect = lookup("#connectButton").queryAs(Button.class);
        ConfigStore store = ServiceLocator.get(ConfigStore.class);
        for (int i = 0; i < 3; i++) {
            interact(() -> store.applyServerBatch(List.of(), List.of(server.getId())));
            interact(() -> store.addServer(server));
        }
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(connect.getStyleClass())
                .as("the colour class, once however often the button was repainted")
                .containsOnlyOnce("connect-button")
                .doesNotContain("disconnect-button");
    }
}
