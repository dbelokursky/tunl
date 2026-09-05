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
 * Smoke test for the redesigned SubscriptionsView — verifies the SplitPane-free
 * layout still wires up to the controller.
 */
@UiTest
public class SubscriptionsViewTest extends ApplicationTest {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/SubscriptionsView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 1100, 720));
        stage.show();
    }

    @Test
    void controlsExist() {
        assertThat(lookup("#subscriptionListView").tryQuery()).isPresent();
        assertThat(lookup("#refreshAllButton").tryQuery()).isPresent();
        assertThat(lookup("#addSubscriptionButton").tryQuery()).isPresent();
        assertThat(lookup("#emptyState").tryQuery()).isPresent();
    }
}
