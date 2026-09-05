package com.vlessclient.ui.view;

import com.vlessclient.testing.UiTest;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for RoutingView — verifies the redesigned FXML wires up to the
 * controller, including the new bypass-list count badge and country hint.
 */
@UiTest
public class RoutingViewTest extends ApplicationTest {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/RoutingView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 1000, 720));
        stage.show();
    }

    @Test
    void redesignedControlsExist() {
        assertThat(lookup("#bypassCountryCombo").tryQuery()).isPresent();
        assertThat(lookup("#bypassCountryChips").tryQuery()).isPresent();
        assertThat(lookup("#customRulesSection").tryQuery()).isPresent();
        assertThat(lookup("#bypassListArea").tryQuery()).isPresent();
        assertThat(lookup("#bypassCountLabel").tryQuery()).isPresent();
        assertThat(lookup("#saveBypassButton").tryQuery()).isPresent();
    }
}
