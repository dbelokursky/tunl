package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.TrafficHistoryStore;
import com.vlessclient.testing.UiTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The traffic history hangs off the session total in the hero card: closed
 * until asked for, and remembered afterwards.
 *
 * <p>The store is redirected into a directory of this test's own. The shared
 * one under {@code target/} is written by whatever else ran first, and a panel
 * asserted against someone else's leftovers is a panel asserted against
 * nothing.</p>
 */
@UiTest
public class DashboardTrafficHistoryTest extends ApplicationTest {

    private static Path historyDir;
    private static TrafficHistoryStore store;
    private static Object priorStore;
    private static Object priorSettings;

    @BeforeAll
    static void setupHeadless() throws IOException {
        priorStore = tryGet(TrafficHistoryStore.class);
        priorSettings = tryGet(AppSettings.class);

        ServiceLocator.register(AppSettings.class, new AppSettings());

        historyDir = Files.createTempDirectory("traffic-history-ui");
        store = new TrafficHistoryStore(historyDir, Clock.systemDefaultZone());
        // Recorded before the view loads on purpose: with an empty history the
        // total line has nothing to say while disconnected, so it is not on
        // screen and there is nothing to click. That is the intended
        // behaviour, and it is asserted separately below.
        store.record(server(), 1_000, 4_000);
        ServiceLocator.register(TrafficHistoryStore.class, store);
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
        if (priorStore instanceof TrafficHistoryStore restored) {
            ServiceLocator.register(TrafficHistoryStore.class, restored);
        }
        ServiceLocator.register(AppSettings.class,
                priorSettings instanceof AppSettings restored ? restored : new AppSettings());
    }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/DashboardView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 900, 700));
        stage.show();
    }

    /** TestFX reuses one stage per class, so each test starts it closed. */
    @BeforeEach
    void collapsePanel() {
        Region panel = lookup("#trafficHistoryPanel").query();
        if (panel.isVisible()) {
            clickSessionTotal();
        }
    }

    /**
     * A synthetic MOUSE_CLICKED delivered to the label, not a robot click.
     *
     * <p>The robot version passed on macOS and Linux x64 and failed on Windows
     * and Linux arm64 -- Monocle's pointer does not land on this label on every
     * platform. Firing the event still goes through the FXML {@code
     * onMouseClicked} wiring, which is the behaviour under test; what it no
     * longer covers is whether the label is reachable by an actual pointer, so
     * the tests below assert that it is on screen and laid out instead of
     * assuming a click proved it.</p>
     */
    private void clickSessionTotal() {
        Label total = lookup("#sessionTotalLabel").query();
        interact(() -> total.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED,
                0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, false, null)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void theHistoryStaysClosedUntilTheSessionTotalIsClicked() {
        Region panel = lookup("#trafficHistoryPanel").query();
        Label handle = lookup("#sessionTotalLabel").query();
        Region summary = lookup("#trafficSummary").query();
        // Both, and not just the label: isVisible() is a node's own flag and
        // stays true under a hidden ancestor, which is exactly how the handle
        // came to be unreachable in the first place. #trafficSummary is the
        // ancestor that actually gets toggled.
        assertThat(summary.isVisible() && handle.isVisible() && handle.getWidth() > 0)
                .as("the only way into the history is this line, so it has to be "
                        + "on screen and laid out, not merely wired up")
                .isTrue();
        assertThat(panel.isVisible())
                .as("the card must look exactly as it did before the panel existed")
                .isFalse();
        assertThat(panel.isManaged())
                .as("visible=false alone would still reserve the height")
                .isFalse();

        clickSessionTotal();

        assertThat(panel.isVisible()).isTrue();
        assertThat(panel.isManaged()).isTrue();
    }

    /**
     * The regression this whole arrangement exists to avoid: the total line is
     * the only way into the history, so hiding it with the speeds would mean
     * the record could be read only while connected -- which is precisely when
     * nobody is asking how much they used last week.
     */
    @Test
    void withNoTunnelTheLineStaysAndCountsTheMonthInstead() {
        Region summary = lookup("#trafficSummary").query();
        Region speeds = lookup("#trafficSpeeds").query();
        Label total = lookup("#sessionTotalLabel").query();

        assertThat(speeds.isManaged())
                .as("a speed with no tunnel behind it is not a number, it is a zero")
                .isFalse();
        assertThat(summary.isVisible())
                .as("but the history is still reachable")
                .isTrue();
        assertThat(total.getText())
                .as("and the line now counts the month, not a session that never ran")
                .contains("4.9 KB");
    }

    @Test
    void openingItDrawsOneBarPerDayOfTheWindow() {
        clickSessionTotal();

        HBox bars = lookup("#trafficHistoryBars").query();
        assertThat(bars.getChildren())
                .as("thirty days, including the quiet ones -- a missing bar "
                        + "reads as missing data rather than as an idle day")
                .hasSize(30);
        assertThat(bars.getChildren())
                .allSatisfy(bar -> assertThat(((Region) bar).getHeight()).isPositive());
    }

    @Test
    void theOpenStateIsRememberedForTheNextLaunch() {
        assertThat(ServiceLocator.get(AppSettings.class).isTrafficHistoryExpanded()).isFalse();

        clickSessionTotal();
        assertThat(ServiceLocator.get(AppSettings.class).isTrafficHistoryExpanded())
                .as("re-finding a click target on every launch is what makes a "
                        + "panel go unused")
                .isTrue();

        clickSessionTotal();
        assertThat(ServiceLocator.get(AppSettings.class).isTrafficHistoryExpanded()).isFalse();
    }

    private static ServerConfig server() {
        ServerConfig config = new ServerConfig();
        config.setId("test-server");
        config.setName("Amsterdam 01");
        return config;
    }
}
