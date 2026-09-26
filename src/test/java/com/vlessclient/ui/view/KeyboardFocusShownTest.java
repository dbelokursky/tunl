package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.ThemeCss;
import com.vlessclient.testing.UiTest;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Border;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * The app's button styles replace the background layers Modena draws keyboard
 * focus into, so a Tab through the window showed nothing: Connect, the
 * navigation and every other button looked the same focused or not. A ring
 * now marks the button the keyboard is on, and only then, so a click leaves
 * none behind.
 */
@UiTest
public class KeyboardFocusShownTest extends ApplicationTest {

    private static final List<String> STYLES = List.of("nav-button", "connect-button",
            "disconnect-button", "primary-button", "secondary-button", "icon-button");

    private Button start;
    private HBox row;
    private Scene scene;

    @Override
    public void start(Stage stage) {
        start = new Button("start");
        row = new HBox(8, start);
        for (String style : STYLES) {
            Button button = new Button(style);
            button.getStyleClass().add(style);
            row.getChildren().add(button);
        }
        scene = new Scene(new StackPane(row), 900, 200);
        scene.getStylesheets().setAll(ThemeCss.of("dark"));
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void theButtonTheKeyboardIsOnWearsARing() {
        for (int i = 1; i < row.getChildren().size(); i++) {
            Button button = (Button) row.getChildren().get(i);
            String style = button.getStyleClass().getLast();
            Border clicked = borderWhenFocusedBy(button, false);
            Border tabbed = borderWhenFocusedBy(button, true);

            assertThat(tabbed).as("%s reached by Tab", style).isNotEqualTo(clicked);
            assertThat(tabbed.getStrokes()).as("%s's ring", style)
                    .anySatisfy(stroke -> assertThat(stroke.getInsets().getTop())
                            .as("drawn outside the button").isNegative());
        }
    }

    /**
     * The server, subscription and rule lists act on their selection with
     * Delete and Enter, and the selection was transparent: the keys acted on
     * a row nobody could see.
     */
    @Test
    void theSelectedRowOfAListIsMarked() {
        ListView<String> list = new ListView<>();
        interact(() -> {
            list.getStyleClass().add("server-list");
            list.getItems().setAll("Netherlands 01", "Germany 02");
            list.setCellFactory(view -> new ListCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setGraphic(null);
                        return;
                    }
                    HBox row = new HBox(new Label(item));
                    row.getStyleClass().add("server-list-item");
                    setGraphic(row);
                }
            });
            scene.setRoot(new StackPane(list));
            list.getSelectionModel().select(1);
        });
        interact(() -> list.applyCss());

        assertThat(rowBorderWidth(list, "Germany 02")).as("the selected row").isEqualTo(2);
        assertThat(rowBorderWidth(list, "Netherlands 01")).as("the other row").isEqualTo(1);
    }

    private double rowBorderWidth(ListView<String> list, String item) {
        return list.lookupAll(".server-list-item").stream()
                .map(HBox.class::cast)
                .filter(row -> ((Label) row.getChildren().getFirst()).getText().equals(item))
                .findFirst()
                .orElseThrow()
                .getBorder().getStrokes().getFirst().getWidths().getTop();
    }

    /**
     * The button's border once it has focus: given by a click (a request, as a
     * mouse press makes) or by Tab from the button before it.
     */
    private Border borderWhenFocusedBy(Button button, boolean keyboard) {
        interact(() -> {
            if (keyboard) {
                Button before = (Button) row.getChildren()
                        .get(row.getChildren().indexOf(button) - 1);
                before.requestFocus();
                before.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB,
                        false, false, false, false));
            } else {
                start.requestFocus();
                button.requestFocus();
            }
            button.applyCss();
        });
        assertThat(button.isFocused()).as("%s focused", button.getText()).isTrue();
        assertThat(button.isFocusVisible())
                .as("%s shows focus only when the keyboard moved it", button.getText())
                .isEqualTo(keyboard);
        Border border = button.getBorder();
        return border == null ? Border.EMPTY : border;
    }
}
