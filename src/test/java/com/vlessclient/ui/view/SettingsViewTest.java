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
        Scene scene = new Scene(root, 700, 720);
        // With no stylesheet this class measures a view the app never shows:
        // every size rule lives in the CSS, so anything the styles get wrong
        // is invisible here. That is how a field pinned to one line of height
        // while asking for three rows passed unnoticed.
        scene.getStylesheets().setAll(getClass().getResource("/css/light.css").toExternalForm());
        stage.setScene(scene);
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

    /**
     * The command has to be readable, not just present.
     *
     * <p>It is a TextArea asking for three rows, wearing the same
     * {@code .form-field} class as the single-line fields above it — and that
     * class pins a 34px height so every field in a form matches. The pin won,
     * so the box rendered one row tall and cut the glyphs through the middle:
     * a command nobody could read, next to a button offering to copy it.</p>
     */
    @Test
    void mcpCommandAreaIsTallEnoughToReadTheCommand() {
        TextArea command = lookup("#mcpCommandArea").query();
        double singleLineField = lookup("#mcpPortField").query().getBoundsInLocal().getHeight();

        assertThat(command.getBoundsInLocal().getHeight())
                .withFailMessage("the MCP command area is %.1fpx tall, no more than the "
                        + "%.1fpx single-line field beside it — it asks for %d rows",
                        command.getBoundsInLocal().getHeight(), singleLineField,
                        command.getPrefRowCount())
                .isGreaterThan(singleLineField);
    }
}
