package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for DashboardView using TestFX with Monocle headless rendering.
 */
public class DashboardViewTest extends ApplicationTest {

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
    }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/DashboardView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 640, 480));
        stage.show();
    }

    @Test
    void connectButtonExists() {
        assertThat(lookup("#connectButton").tryQuery()).isPresent();
        Button connect = lookup("#connectButton").query();
        assertThat(connect.getText()).isNotBlank();
    }

    @Test
    void statusLabelExists() {
        assertThat(lookup("#statusLabel").tryQuery()).isPresent();
        Label status = lookup("#statusLabel").query();
        assertThat(status).isNotNull();
    }

    @Test
    void clickingConnectWithNoServerDoesNotCrash() {
        Button connect = lookup("#connectButton").query();
        // Either disabled or clickable but produces an error — both are valid;
        // we just assert the app survives the interaction.
        if (!connect.isDisabled()) {
            clickOn("#connectButton");
        }
        assertThat(connect).isNotNull();
    }

    @Test
    void serviceAvailabilityNodesExist() {
        assertThat(lookup("#healthCard").tryQuery()).isPresent();
        assertThat(lookup("#serviceStatusList").tryQuery()).isPresent();
        assertThat(lookup("#recheckButton").tryQuery()).isPresent();
        assertThat(lookup("#addTargetButton").tryQuery()).isPresent();
        assertThat(lookup("#reconnectBanner").tryQuery()).isPresent();
        assertThat(lookup("#healthSummaryLabel").tryQuery()).isPresent();
    }

    @Test
    void healthCardAndBannerHiddenWhileDisconnected() {
        // Disconnected on load: the availability card and the reconnect banner
        // start hidden until the tunnel comes up.
        Node healthCard = lookup("#healthCard").query();
        Node reconnectBanner = lookup("#reconnectBanner").query();
        assertThat(healthCard.isVisible()).isFalse();
        assertThat(reconnectBanner.isVisible()).isFalse();
    }
}
