package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.testing.UiTest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextInputControl;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * Every input field and drop-down has a name a screen reader can speak.
 *
 * <p>The labels beside the fields were only drawn there: {@code labelFor} was
 * set nowhere, so Narrator and NVDA announced "edit" and "combo box" with no
 * name, forty times over. A field is named by the label that says it is for
 * it, or by accessible text of its own where no label stands beside it.</p>
 */
@UiTest
public class FieldAccessibleNamesTest extends ApplicationTest {

    private static final List<String> VIEWS = List.of(
            "/fxml/DashboardView.fxml", "/fxml/ServersView.fxml",
            "/fxml/SubscriptionsView.fxml", "/fxml/RoutingView.fxml", "/fxml/LogsView.fxml",
            "/fxml/SettingsView.fxml", "/fxml/ServerFormView.fxml");

    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.show();
    }

    @Test
    void everyFieldHasANameAScreenReaderCanSpeak() {
        List<String> unnamed = new ArrayList<>();
        for (String view : VIEWS) {
            interact(() -> {
                Parent root;
                try {
                    root = new FXMLLoader(getClass().getResource(view)).load();
                } catch (IOException e) {
                    throw new IllegalStateException("could not load " + view, e);
                }
                stage.setScene(new Scene(root, 900, 700));
                List<Node> nodes = new ArrayList<>(root.lookupAll("*"));
                for (Node node : nodes) {
                    if (isField(node) && !named((Control) node, nodes)) {
                        unnamed.add(view.substring(view.lastIndexOf('/') + 1) + ": " + node.getId());
                    }
                }
            });
        }
        assertThat(unnamed)
                .as("fields a screen reader reads out without a name; set labelFor on the "
                        + "label beside them, or accessibleText where there is none")
                .isEmpty();
    }

    /**
     * An input control the view declares; the text fields inside a spinner's
     * or an editable combo box's skin have no id and are the control's own.
     */
    private static boolean isField(Node node) {
        return node.getId() != null
                && (node instanceof TextInputControl || node instanceof ComboBoxBase<?>
                        || node instanceof Spinner<?>);
    }

    private static boolean named(Control field, List<Node> nodes) {
        String own = field.getAccessibleText();
        if (own != null && !own.isBlank()) {
            return true;
        }
        return nodes.stream().anyMatch(node -> node instanceof Label label
                && label.getLabelFor() == field
                && label.getText() != null && !label.getText().isBlank());
    }
}
