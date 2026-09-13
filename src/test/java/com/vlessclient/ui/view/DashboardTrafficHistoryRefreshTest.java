package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.TrafficHistoryStore;
import com.vlessclient.testing.UiTest;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The traffic history is cleared from Settings while the dashboard sits in
 * MainView's cache, built and hidden, so nothing on the dashboard hears about
 * it. Coming back to the view is the moment it has to catch up.
 *
 * <p>A class of its own because the test empties its store: sharing the one in
 * {@link DashboardTrafficHistoryTest} would hand the tests there a record that
 * depends on the order JUnit happened to run them in.</p>
 */
@UiTest
public class DashboardTrafficHistoryRefreshTest extends ApplicationTest {

    private static TrafficHistoryStore store;
    private static Object priorStore;
    private static Object priorSettings;

    private DashboardViewController controller;

    @BeforeAll
    static void registerHistory() throws IOException {
        priorStore = tryGet(TrafficHistoryStore.class);
        priorSettings = tryGet(AppSettings.class);

        ServiceLocator.register(AppSettings.class, new AppSettings());
        store = new TrafficHistoryStore(Files.createTempDirectory("traffic-history-refresh"),
                Clock.systemDefaultZone());
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
        controller = loader.getController();
        stage.setScene(new Scene(root, 900, 700));
        stage.show();
    }

    @Test
    void comingBackToTheDashboardShowsAHistoryClearedElsewhere() {
        // A synthetic click, as in DashboardTrafficHistoryTest: Monocle's
        // pointer does not land on this label on every CI platform.
        Label total = lookup("#sessionTotalLabel").query();
        interact(() -> total.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED,
                0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, false, null)));
        WaitForAsyncUtils.waitForFxEvents();

        Label servers = lookup("#trafficHistoryServers").query();
        Label range = lookup("#trafficHistoryRange").query();
        Region summary = lookup("#trafficSummary").query();
        assertThat(servers.getText())
                .as("precondition: the open panel names the server behind the record")
                .contains("Amsterdam 01");

        // What the Settings card does, followed by what MainView does when the
        // user navigates back.
        store.reset();
        interact(controller::onViewShown);

        assertThat(servers.getText())
                .as("a panel still naming servers would contradict the empty record")
                .isEmpty();
        assertThat(range.getText()).isEqualTo(I18n.get("dashboard.traffic.history.empty"));
        assertThat(summary.isVisible())
                .as("with no tunnel the line counts the month, and the month is now empty")
                .isFalse();
    }

    private static ServerConfig server() {
        ServerConfig config = new ServerConfig();
        config.setId("test-server");
        config.setName("Amsterdam 01");
        return config;
    }
}
