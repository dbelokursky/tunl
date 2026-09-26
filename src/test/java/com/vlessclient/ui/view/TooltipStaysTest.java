package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.ThemeCss;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.UiTest;
import java.util.List;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * A tooltip stays while the pointer rests on its node. JavaFX hides one after
 * five seconds, too soon to read the longer ones, such as why a link's
 * certificate check is off.
 */
@UiTest
public class TooltipStaysTest extends ApplicationTest {

    private Scene scene;
    private Label label;

    @Override
    public void start(Stage stage) {
        label = new Label("insecure");
        scene = new Scene(new StackPane(label), 320, 200);
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void aTooltipStaysInEitherTheme() {
        for (String theme : List.of("light", "dark")) {
            Tooltip tooltip = new Tooltip("The link turns certificate verification off.");
            interact(() -> {
                scene.getStylesheets().setAll(ThemeCss.of(theme));
                label.setTooltip(tooltip);
                Bounds bounds = label.localToScreen(label.getBoundsInLocal());
                tooltip.show(label, bounds.getMinX(), bounds.getMaxY());
            });
            try {
                Await.until("the " + theme + " theme's tooltip to be styled",
                        () -> tooltip.getShowDuration().isIndefinite(),
                        java.time.Duration.ofSeconds(5));
            } finally {
                interact(tooltip::hide);
            }
            assertThat(tooltip.getShowDuration()).as("in the %s theme", theme)
                    .isEqualTo(Duration.INDEFINITE);
        }
    }
}
