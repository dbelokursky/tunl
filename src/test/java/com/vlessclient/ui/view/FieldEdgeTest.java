package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.ThemeCss;
import com.vlessclient.testing.UiTest;
import java.io.IOException;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * A field that wears no form class, as in the dialogs, the Routing page and
 * the Dashboard's drop-downs, draws its edge in the field edge colour.
 *
 * <p>Those fields took the card hairline, {@code -c-border}, which stands at
 * about 1.2:1 against the surfaces they sit on: a field showed as a blank
 * patch of the card. {@code -c-field-border} reaches 3:1 on them
 * ({@link ThemeTokenContrastTest}).</p>
 */
@UiTest
public class FieldEdgeTest extends ApplicationTest {

    private Scene scene;
    private Button start;
    private List<Region> fields;

    @Override
    public void start(Stage stage) {
        start = new Button("start");
        ComboBox<String> editable = new ComboBox<>();
        editable.setEditable(true);
        fields = List.of(new TextField(), new TextArea(), new ComboBox<String>(), editable);
        VBox card = new VBox(8, start);
        card.getChildren().addAll(fields);
        card.getStyleClass().add("card");
        scene = new Scene(new StackPane(card), 400, 480);
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void aPlainFieldsEdgeIsTheFieldEdgeInEitherTheme() throws IOException {
        for (String theme : List.of("light", "dark")) {
            Color edge = ThemeTokenContrastTest.tokens("/css/" + theme + ".css")
                    .get("-c-field-border");
            interact(() -> {
                scene.getStylesheets().setAll(ThemeCss.of(theme));
                // Off the fields: a focused one draws the accent instead.
                start.requestFocus();
                scene.getRoot().applyCss();
            });
            for (Region field : fields) {
                assertThat(topEdge(field))
                        .as("the edge of a %s in the %s theme",
                                field.getClass().getSimpleName(), theme)
                        .isEqualTo(edge);
            }
        }
    }

    private static Paint topEdge(Region field) {
        return field.getBorder() == null || field.getBorder().getStrokes().isEmpty()
                ? null
                : field.getBorder().getStrokes().getFirst().getTopStroke();
    }
}
