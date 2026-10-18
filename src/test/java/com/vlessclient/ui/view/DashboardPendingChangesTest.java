package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.service.ConnectionService;
import com.vlessclient.service.ConnectionService.ConnectAttempt;
import com.vlessclient.service.ConnectionService.NetworkChange;
import com.vlessclient.service.ConnectionService.Outcome;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
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
    /** What the stub service answers. */
    private static volatile boolean runsCurrentSettings = true;
    /** How long the stub takes to answer, as a long server list makes it. */
    private static volatile long comparisonMillis;
    /** The comparisons made, and how many of them ran on the FX thread. */
    private static final AtomicInteger COMPARISONS = new AtomicInteger();
    private static final AtomicInteger COMPARISONS_ON_FX = new AtomicInteger();
    /** Comparisons the stub has started and not answered yet. */
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    /** The mode each reconnect asked for, and whether it ran on the FX thread. */
    private static final List<String> RECONNECTS = new CopyOnWriteArrayList<>();
    private static final CountDownLatch RECONNECTED = new CountDownLatch(1);
    /** How the network changed under the tunnel, as the stub service reports it. */
    private static final SimpleObjectProperty<NetworkChange> NETWORK =
            new SimpleObjectProperty<>(NetworkChange.NONE);

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private ViewShownAware controller;

    @BeforeAll
    static void registerTheServices() {
        AppSettings settings = new AppSettings();
        settings.setHealthCheckEnabled(false);
        ServiceLocator.register(AppSettings.class, settings);
        ServiceLocator.register(SingBoxEngine.class, ENGINE);
        ServiceLocator.register(ConnectionService.class,
                new ConnectionService(null, null, null, SingBoxEngine.withoutCore()) {
                    @Override
                    public boolean runsCurrentSettings() {
                        IN_FLIGHT.incrementAndGet();
                        COMPARISONS.incrementAndGet();
                        if (Platform.isFxApplicationThread()) {
                            COMPARISONS_ON_FX.incrementAndGet();
                        }
                        try {
                            Thread.sleep(comparisonMillis);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            IN_FLIGHT.decrementAndGet();
                        }
                        return runsCurrentSettings;
                    }

                    @Override
                    public ReadOnlyObjectProperty<NetworkChange> networkChangeProperty() {
                        return NETWORK;
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

    @AfterEach
    void disconnect() {
        comparisonMillis = 0;
        interact(() -> ENGINE.state.set(ConnectionState.DISCONNECTED));
    }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/DashboardView.fxml"));
        Parent root = loader.load();
        controller = loader.getController();
        stage.setScene(new Scene(root, 640, 480));
        stage.show();
    }

    /**
     * The comparison is made off the FX thread, so whether the offer shows is
     * waited for; that it goes once the tunnel is down is not.
     */
    @Test
    void theOfferFollowsWhetherTheRunningCoreHasTheSettings() {
        assertThat(bannerShown()).as("nothing runs").isFalse();

        runsCurrentSettings = false;
        interact(() -> ENGINE.state.set(ConnectionState.CONNECTED));
        awaitBanner(true, "connected on settings changed since");

        runsCurrentSettings = true;
        interact(controller::onViewShown);
        awaitBanner(false, "shown again, the core running the settings");

        runsCurrentSettings = false;
        interact(controller::onViewShown);
        awaitBanner(true, "back from a page that changed a setting");

        interact(() -> ENGINE.state.set(ConnectionState.DISCONNECTED));
        assertThat(bannerShown()).as("disconnected").isFalse();
    }

    /**
     * The comparison regenerates the whole configuration from every server
     * and compares it with the running one: at 10,000 servers that took 84 ms,
     * and up to 225, on every repaint while connected, and froze the window
     * each time.
     */
    @Test
    void theComparisonIsMadeOffTheFxThread() {
        runsCurrentSettings = false;
        COMPARISONS.set(0);
        COMPARISONS_ON_FX.set(0);

        interact(() -> ENGINE.state.set(ConnectionState.CONNECTED));
        interact(controller::onViewShown);
        awaitBanner(true, "connected on settings changed since");

        assertThat(COMPARISONS).as("comparisons made").hasPositiveValue();
        assertThat(COMPARISONS_ON_FX).as("comparisons made on the FX thread").hasValue(0);
    }

    @Test
    void aSlowComparisonLeavesTheWindowFree() {
        runsCurrentSettings = false;
        comparisonMillis = 1500;

        long started = System.nanoTime();
        interact(() -> ENGINE.state.set(ConnectionState.CONNECTED));
        long tookMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(tookMillis).as("the state change's repaint, in ms").isLessThan(750);
        awaitBanner(true, "connected on settings changed since");
    }

    /**
     * Repaints come in bursts: a state change, a health verdict, the group
     * monitor. Those asked for while a comparison runs are answered by one
     * more, made once it ends, rather than by one each.
     */
    @Test
    void repaintsDuringAComparisonAreAnsweredByOneMore() {
        runsCurrentSettings = false;
        comparisonMillis = 600;
        interact(() -> ENGINE.state.set(ConnectionState.CONNECTED));
        awaitBanner(true, "connected on settings changed since");
        awaitComparisonsAnswered();
        COMPARISONS.set(0);

        for (int i = 0; i < 10; i++) {
            interact(controller::onViewShown);
        }
        Await.until("the one more comparison", () -> COMPARISONS.get() >= 2, PATIENCE);
        awaitComparisonsAnswered();

        assertThat(COMPARISONS).as("comparisons for ten repaints").hasValue(2);
    }

    /** An answer the tunnel's going down overtook is not shown. */
    @Test
    void aComparisonOvertakenByADisconnectIsNotShown() throws InterruptedException {
        runsCurrentSettings = false;
        comparisonMillis = 600;
        COMPARISONS.set(0);

        interact(() -> ENGINE.state.set(ConnectionState.CONNECTED));
        Await.until("a comparison to start", () -> COMPARISONS.get() > 0, PATIENCE);
        interact(() -> ENGINE.state.set(ConnectionState.DISCONNECTED));
        awaitComparisonsAnswered();

        assertThat(bannerShown()).as("disconnected before the answer came").isFalse();
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

    /**
     * The TUN device takes IPv6 or not by the network it starts on. A laptop
     * that moved to a network with IPv6 sent its IPv6 around the tunnel, and
     * one that moved off it drew apps to IPv6 that could not leave, until a
     * reconnect nothing offered. The same banner offers it now, and says why.
     */
    @Test
    void aNetworkThatChangedUnderTheTunnelIsOfferedTheReconnect() {
        runsCurrentSettings = true;
        interact(() -> ENGINE.state.set(ConnectionState.CONNECTED));
        assertThat(bannerShown()).as("the network as it was").isFalse();

        interact(() -> NETWORK.set(NetworkChange.IPV6_GAINED));
        assertThat(bannerShown()).as("the network gained IPv6").isTrue();
        assertThat(lookup("#pendingChangesLabel").queryAs(Label.class).getText())
                .isEqualTo(I18n.get("dashboard.network.ipv6.gained"));

        interact(() -> NETWORK.set(NetworkChange.IPV6_LOST));
        assertThat(lookup("#pendingChangesLabel").queryAs(Label.class).getText())
                .isEqualTo(I18n.get("dashboard.network.ipv6.lost"));

        interact(() -> NETWORK.set(NetworkChange.NONE));
        awaitBanner(false, "reconnected, or the network changed back");
        interact(() -> ENGINE.state.set(ConnectionState.DISCONNECTED));
    }

    private boolean bannerShown() {
        HBox banner = lookup("#pendingChangesBanner").queryAs(HBox.class);
        return banner.isVisible() && banner.isManaged();
    }

    private void awaitBanner(boolean shown, String when) {
        Await.until((shown ? "the offer to show: " : "the offer to go: ") + when,
                () -> onFx(this::bannerShown) == shown, PATIENCE);
    }

    /**
     * Waits for the comparisons under way to answer, and for the answer each
     * posts to the FX thread as it ends to land.
     */
    private void awaitComparisonsAnswered() {
        Await.until("the comparisons under way to answer", () -> IN_FLIGHT.get() == 0, PATIENCE);
        interact(() -> { });
    }

    private <T> T onFx(java.util.function.Supplier<T> work) {
        List<T> result = new java.util.ArrayList<>(1);
        interact(() -> result.add(work.get()));
        return result.get(0);
    }
}
