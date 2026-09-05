package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.testing.UiTest;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
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

    /**
     * The badge counts entries as the user types, skipping blank and comment
     * lines, and switches between the singular and plural wording.
     */
    @Test
    void bypassCountBadgeFollowsTheEditedList() {
        TextArea area = lookup("#bypassListArea").query();
        Label badge = lookup("#bypassCountLabel").query();

        interact(() -> area.setText("example.com\n# a comment\n\n   10.0.0.0/8  \n"));
        assertThat(badge.getText()).isEqualTo(I18n.get("routing.bypass.count.many", "2"));

        interact(() -> area.setText("example.com"));
        assertThat(badge.getText()).isEqualTo(I18n.get("routing.bypass.count.one"));

        interact(() -> area.setText("# only a comment\n\n"));
        assertThat(badge.getText()).isEqualTo(I18n.get("routing.bypass.count.many", "0"));

        interact(area::clear);
        assertThat(badge.getText()).isEqualTo(I18n.get("routing.bypass.count.many", "0"));
    }
}
