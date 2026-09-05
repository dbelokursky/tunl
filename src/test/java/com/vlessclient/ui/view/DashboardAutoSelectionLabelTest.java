package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.ServerSelection;
import com.vlessclient.model.TunnelHealth;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ProxyGroupMonitor;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.TunnelHealthState;
import com.vlessclient.service.outbound.OutboundTags;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import java.util.List;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * In the automatic selection mode the hero card used to name the pinned
 * server while the urltest group routed through whichever member won the
 * last probe. The card now follows the core's own answer.
 */
@UiTest
public class DashboardAutoSelectionLabelTest extends ApplicationTest {

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

    /** Monitor whose published pick the test sets by hand; never polls. */
    private static final class FakeGroupMonitor extends ProxyGroupMonitor {
        private final SimpleStringProperty tag = new SimpleStringProperty();

        @Override
        public ReadOnlyStringProperty currentMemberTagProperty() {
            return tag;
        }

        @Override
        public void start(int port, String secret) {
            // The test is the only writer.
        }

        @Override
        public void stop() {
            // Keep the test's value: the real stop() would clear it.
        }
    }

    private static FakeEngine engine;
    private static TunnelHealthState health;
    private static FakeGroupMonitor groupMonitor;
    private static ServerConfig pinned;
    private static ServerConfig winner;
    private static Object priorEngine;
    private static Object priorHealth;
    private static Object priorSettings;
    private static Object priorMonitor;

    @BeforeAll
    static void setupHeadless() {
        priorEngine = tryGet(SingBoxEngine.class);
        priorHealth = tryGet(TunnelHealthState.class);
        priorSettings = tryGet(AppSettings.class);
        priorMonitor = tryGet(ProxyGroupMonitor.class);

        AppSettings settings = new AppSettings();
        settings.setHealthCheckEnabled(false);
        settings.setServerSelection(ServerSelection.AUTO_BEST);
        ServiceLocator.register(AppSettings.class, settings);

        engine = new FakeEngine();
        health = new TunnelHealthState();
        groupMonitor = new FakeGroupMonitor();
        ServiceLocator.register(SingBoxEngine.class, engine);
        ServiceLocator.register(TunnelHealthState.class, health);
        ServiceLocator.register(ProxyGroupMonitor.class, groupMonitor);

        ConfigStore store = ServiceLocator.get(ConfigStore.class);
        pinned = server("Pinned", "203.0.113.1");
        winner = server("Winner", "203.0.113.2");
        store.addServer(pinned);
        store.addServer(winner);
        store.setActiveServer(pinned.getId());
    }

    private static ServerConfig server(String name, String address) {
        ServerConfig server = new ServerConfig();
        server.setName(name);
        server.setProtocol(Protocol.VLESS);
        server.setAddress(address);
        server.setPort(443);
        server.setUuid("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        return server;
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
        ConfigStore store = tryGet(ConfigStore.class);
        if (store != null) {
            store.applyServerBatch(List.of(), List.of(pinned.getId(), winner.getId()));
        }
        if (priorEngine instanceof SingBoxEngine restored) {
            ServiceLocator.register(SingBoxEngine.class, restored);
        }
        if (priorHealth instanceof TunnelHealthState restored) {
            ServiceLocator.register(TunnelHealthState.class, restored);
        }
        if (priorMonitor instanceof ProxyGroupMonitor restored) {
            ServiceLocator.register(ProxyGroupMonitor.class, restored);
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

    private String subtitle() {
        Label label = lookup("#statusLabel").query();
        return label.getText();
    }

    @Test
    void whileConnectedTheCardNamesTheServerTheGroupPicked() {
        interact(() -> {
            engine.state.set(ConnectionState.CONNECTED);
            health.set(TunnelHealth.HEALTHY);
        });
        // No pick reported yet, and this view never ran a connect of its own,
        // so there is no name to claim.
        assertThat(subtitle()).isEqualTo(I18n.get("dashboard.status.routing"));

        interact(() -> groupMonitor.tag.set(OutboundTags.server(winner)));
        assertThat(subtitle())
                .isEqualTo(I18n.get("dashboard.status.routing.through", "Winner"));

        // A tag the store cannot resolve (a member since deleted) falls back
        // rather than naming nobody's server.
        interact(() -> groupMonitor.tag.set("srv-no-such-server"));
        assertThat(subtitle()).isEqualTo(I18n.get("dashboard.status.routing"));

        interact(() -> {
            groupMonitor.tag.set(null);
            engine.state.set(ConnectionState.DISCONNECTED);
        });
    }
}
