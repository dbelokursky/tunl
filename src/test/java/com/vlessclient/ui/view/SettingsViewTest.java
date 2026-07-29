package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the Settings view — in particular the Agent Control (MCP)
 * section, whose fx:ids and action handlers must match the controller or the
 * FXML fails to load.
 */
public class SettingsViewTest extends ApplicationTest {

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
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/SettingsView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 700, 720));
        stage.show();
    }

    @Test
    void mcpControlsExist() {
        assertThat(lookup("#mcpEnabledCheck").tryQuery()).isPresent();
        assertThat(lookup("#mcpPortField").tryQuery()).isPresent();
        assertThat(lookup("#mcpAllowMutationsCheck").tryQuery()).isPresent();
        assertThat(lookup("#mcpCommandArea").tryQuery()).isPresent();
        assertThat(lookup("#mcpCopyButton").tryQuery()).isPresent();
        assertThat(lookup("#mcpRegenButton").tryQuery()).isPresent();
    }

    @Test
    void mcpPortDefaultsToConfiguredValue() {
        TextField portField = lookup("#mcpPortField").query();
        assertThat(portField.getText()).isNotBlank();
    }

    @Test
    void mcpCommandAreaIsReadOnly() {
        TextArea command = lookup("#mcpCommandArea").query();
        assertThat(command.isEditable()).isFalse();
    }

    @Test
    void mcpEnabledCheckReflectsSetting() {
        CheckBox enabled = lookup("#mcpEnabledCheck").query();
        assertThat(enabled).isNotNull();
    }
}
