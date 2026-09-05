package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.UiServicesExtension;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TunnelHealth;
import com.vlessclient.service.CountryResolver;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.testing.FxToolkitExtension;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.vlessclient.testing.FxTestSupport.flushFxEvents;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hero card's wording, colours and button, state by state, without the
 * rest of the Dashboard around them: what the process state and the probe
 * verdict together turn into, and which of the two the button follows.
 */
@ExtendWith({FxToolkitExtension.class, UiServicesExtension.class})
class StatusPresenterTest {

    /** Engine whose reported state the test controls. */
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

    /**
     * Knows one address outright; for another it holds the answer until the
     * test releases it, which is what a "late" lookup is.
     */
    private static final class KnownCountries extends CountryResolver {
        private Consumer<String> pending;

        KnownCountries() {
            super(null);
        }

        @Override
        public Optional<String> countryOf(ServerConfig server) {
            return server != null && "203.0.113.7".equals(server.getAddress())
                    ? Optional.of("NL")
                    : Optional.empty();
        }

        @Override
        public void resolveAsync(ServerConfig server, Consumer<String> onResolved) {
            if (server != null && "203.0.113.8".equals(server.getAddress())) {
                pending = onResolved;
            }
        }

        void answerLate(String code) {
            assertThat(pending).as("a lookup is outstanding").isNotNull();
            Consumer<String> callback = pending;
            pending = null;
            callback.accept(code);
        }
    }

    private final Circle circle = new Circle();
    private final StackPane halo = new StackPane();
    private final StackPane flag = new StackPane();
    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final Label serverName = new Label();
    private final Button connect = new Button();

    private final AtomicReference<ServerConfig> active = new AtomicReference<>();
    private final AtomicReference<ServerConfig> routed = new AtomicReference<>();
    private final AtomicReference<TunnelHealth> health =
            new AtomicReference<>(TunnelHealth.UNMONITORED);
    private final AtomicReference<SingBoxEngine> engine = new AtomicReference<>();
    private final AtomicInteger refreshes = new AtomicInteger();
    private final KnownCountries countries = new KnownCountries();

    private StatusPresenter presenter;

    @BeforeEach
    void buildPresenter() {
        ServiceLocator.register(CountryResolver.class, countries);
        presenter = new StatusPresenter(
                new StatusPresenter.Controls(
                        circle, halo, flag, title, subtitle, serverName, connect),
                active::get, routed::get, health::get, engine::get,
                refreshes::incrementAndGet);
    }

    private static ServerConfig server(String name, String address) {
        ServerConfig server = new ServerConfig();
        server.setName(name);
        server.setAddress(address);
        return server;
    }

    @Test
    void connectedAndHealthyReadsConnectedAndOffersDisconnect() {
        active.set(server("Pinned", "203.0.113.1"));
        routed.set(server("Winner", "203.0.113.2"));
        health.set(TunnelHealth.HEALTHY);

        presenter.update(ConnectionState.CONNECTED);

        assertThat(title.getText()).isEqualTo(I18n.get("state.connected"));
        assertThat(title.getStyleClass()).containsExactly("status-title", "status-title-connected");
        assertThat(subtitle.getText())
                .as("the card names the server traffic goes through, not the pinned one")
                .isEqualTo(I18n.get("dashboard.status.routing.through", "Winner"));
        assertThat(subtitle.getStyleClass()).containsExactly("status-subtitle");
        assertThat(circle.getStyleClass()).containsExactly("status-circle-connected");
        assertThat(halo.getStyleClass())
                .contains("status-halo-connected")
                .doesNotContain("status-halo-connecting", "status-halo-error",
                        "status-halo-disconnected");
        assertThat(connect.getText()).isEqualTo(I18n.get("button.disconnect"));
        assertThat(connect.getStyleClass())
                .contains("disconnect-button").doesNotContain("connect-button");
        assertThat(connect.isDisabled()).isFalse();
        assertThat(serverName.getText()).isEqualTo("Pinned");
        assertThat(refreshes.get())
                .as("a live tunnel does not re-ask whether Connect may be enabled")
                .isZero();
    }

    @Test
    void connectedButBrokenReadsNoTrafficAndStillOffersDisconnect() {
        active.set(server("Pinned", "203.0.113.1"));
        health.set(TunnelHealth.BROKEN);

        presenter.update(ConnectionState.CONNECTED);

        assertThat(title.getText()).isEqualTo(I18n.get("state.no.traffic"));
        assertThat(title.getStyleClass()).containsExactly("status-title", "status-title-error");
        assertThat(subtitle.getText()).isEqualTo(I18n.get("dashboard.status.no.traffic"));
        assertThat(subtitle.getStyleClass())
                .containsExactly("status-subtitle", "status-subtitle-error");
        assertThat(circle.getStyleClass()).containsExactly("status-circle-error");
        assertThat(halo.getStyleClass()).contains("status-halo-error");
        assertThat(connect.getText())
                .as("a dead tunnel is still one the user turns off, not one they retry")
                .isEqualTo(I18n.get("button.disconnect"));
        assertThat(connect.getStyleClass())
                .contains("disconnect-button").doesNotContain("connect-button");
    }

    @Test
    void errorShowsTheEngineMessageUntilTheNextStateAndOffersRetry() {
        active.set(server("Pinned", "203.0.113.1"));
        presenter.update(ConnectionState.CONNECTED);
        presenter.showEngineError("sing-box exited with code 9");

        presenter.update(ConnectionState.ERROR);

        assertThat(title.getText()).isEqualTo(I18n.get("state.error"));
        assertThat(subtitle.getText()).isEqualTo("sing-box exited with code 9");
        assertThat(subtitle.getStyleClass())
                .containsExactly("status-subtitle", "status-subtitle-error");
        assertThat(circle.getStyleClass()).containsExactly("status-circle-error");
        assertThat(connect.getText()).isEqualTo(I18n.get("button.retry"));
        assertThat(connect.getStyleClass())
                .contains("connect-button").doesNotContain("disconnect-button");
        assertThat(refreshes.get()).isEqualTo(1);

        presenter.update(ConnectionState.ERROR);
        assertThat(subtitle.getText())
                .as("a repaint in the same state must not wipe the engine's message")
                .isEqualTo("sing-box exited with code 9");

        presenter.update(ConnectionState.DISCONNECTED);
        assertThat(subtitle.getText()).isEqualTo(I18n.get("dashboard.status.ready", "Pinned"));

        presenter.update(ConnectionState.ERROR);
        assertThat(subtitle.getText())
                .as("once another state has shown, an old message is not resurrected")
                .isEqualTo(I18n.get("dashboard.status.check.logs"));
    }

    @Test
    void disconnectedNamesThePinnedServerOrAsksForOne() {
        active.set(server("Pinned", "203.0.113.1"));

        presenter.update(ConnectionState.DISCONNECTED);

        assertThat(title.getText()).isEqualTo(I18n.get("state.disconnected"));
        assertThat(title.getStyleClass())
                .containsExactly("status-title", "status-title-disconnected");
        assertThat(subtitle.getText()).isEqualTo(I18n.get("dashboard.status.ready", "Pinned"));
        assertThat(subtitle.getStyleClass()).containsExactly("status-subtitle");
        assertThat(circle.getStyleClass()).containsExactly("status-circle-disconnected");
        assertThat(halo.getStyleClass()).contains("status-halo-disconnected");
        assertThat(connect.getText()).isEqualTo(I18n.get("button.connect"));
        assertThat(connect.getStyleClass())
                .contains("connect-button").doesNotContain("disconnect-button");
        assertThat(flag.isVisible()).isFalse();
        assertThat(flag.isManaged()).isFalse();
        assertThat(refreshes.get()).isEqualTo(1);

        active.set(null);
        presenter.update(ConnectionState.DISCONNECTED);

        assertThat(subtitle.getText()).isEqualTo(I18n.get("dashboard.status.add.server"));
        assertThat(serverName.getText()).isEmpty();
        assertThat(refreshes.get()).isEqualTo(2);
    }

    @Test
    void connectingOffersCancelAndDoesNotTouchConnectAvailability() {
        presenter.update(ConnectionState.CONNECTING);

        assertThat(title.getText()).isEqualTo(I18n.get("state.connecting"));
        assertThat(subtitle.getText()).isEqualTo(I18n.get("dashboard.status.establishing"));
        assertThat(circle.getStyleClass()).containsExactly("status-circle-connecting");
        assertThat(connect.getText()).isEqualTo(I18n.get("button.cancel"));
        assertThat(connect.isDisabled()).isFalse();
        assertThat(refreshes.get()).isZero();
    }

    @Test
    void showActiveServerNameFallsBackToTheNoServerWording() {
        presenter.showActiveServerName(server("Pinned", "203.0.113.1"));
        assertThat(serverName.getText()).isEqualTo("Pinned");

        presenter.showActiveServerName(null);
        assertThat(serverName.getText()).isEqualTo(I18n.get("dashboard.no.server"));
    }

    @Test
    void theExitFlagFollowsTheRoutedServerWhileConnected() {
        active.set(server("Pinned", "203.0.113.1"));
        routed.set(server("Exit", "203.0.113.7"));
        health.set(TunnelHealth.HEALTHY);

        presenter.update(ConnectionState.CONNECTED);

        assertThat(flag.isVisible()).isTrue();
        assertThat(flag.isManaged()).isTrue();
        assertThat(flag.getChildren()).hasSize(1);

        routed.set(null);
        presenter.update(ConnectionState.CONNECTED);

        assertThat(flag.isVisible())
                .as("an unknown country hides the slot rather than showing an empty one")
                .isFalse();
        assertThat(flag.isManaged()).isFalse();
        assertThat(flag.getChildren()).isEmpty();
    }

    @Test
    void aLateCountryAnswerIsPaintedOnlyWhileStillConnectedToThatServer() throws Exception {
        FakeEngine core = new FakeEngine();
        engine.set(core);
        ServerConfig exit = server("Exit", "203.0.113.8");
        active.set(exit);
        routed.set(exit);
        health.set(TunnelHealth.HEALTHY);

        core.state.set(ConnectionState.CONNECTED);
        presenter.update(ConnectionState.CONNECTED);
        assertThat(flag.isVisible()).as("nothing to show until the lookup answers").isFalse();
        countries.answerLate("DE");
        flushFxEvents();
        assertThat(flag.isVisible()).isTrue();
        assertThat(flag.getChildren()).hasSize(1);

        // The answer arriving once the core is down must not put a flag next
        // to "Disconnected".
        presenter.update(ConnectionState.CONNECTED);
        core.state.set(ConnectionState.DISCONNECTED);
        countries.answerLate("DE");
        flushFxEvents();
        assertThat(flag.isVisible()).isFalse();

        // Nor may it decorate a different server than the one it was asked about.
        core.state.set(ConnectionState.CONNECTED);
        presenter.update(ConnectionState.CONNECTED);
        routed.set(server("Other", "203.0.113.9"));
        countries.answerLate("DE");
        flushFxEvents();
        assertThat(flag.isVisible()).isFalse();
    }
}
