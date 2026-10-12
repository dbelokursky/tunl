package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.service.RoutingService;
import com.vlessclient.service.TestRoutingServices;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import java.util.List;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * The bypass list is saved only when the core can read every address in it.
 *
 * <p>Every non-blank line was stored as typed, and a mistyped address such as
 * {@code 10.0.0.0/33} reached the core as {@code ip_cidr}: it refused the whole
 * configuration at the next connect, so no server could connect until the
 * user found the line, with nothing pointing at the list.</p>
 */
@UiTest
public class RoutingBypassListTest extends ApplicationTest {

    @TempDir
    static Path tempDir;

    private RoutingService routing;

    @Override
    public void start(Stage stage) throws Exception {
        routing = TestRoutingServices.at(tempDir.resolve("routing-" + System.nanoTime()));
        ServiceLocator.register(RoutingService.class, routing);
        stage.setScene(new Scene(new FXMLLoader(
                getClass().getResource("/fxml/RoutingView.fxml")).load(), 1000, 720));
        stage.show();
    }

    @Test
    void aListWithAnAddressTheCoreCannotReadIsNotSavedAndItsLinesAreNamed() {
        TextArea area = lookup("#bypassListArea").queryAs(TextArea.class);
        Label problem = lookup("#bypassProblemLabel").queryAs(Label.class);
        Button save = lookup("#saveBypassButton").queryAs(Button.class);

        interact(() -> area.setText("example.com\n10.0.0.0/33\n# a comment\n192.168.1.300"));
        interact(save::fire);

        assertThat(routing.getConfig().getBypassList()).as("what is stored").isEmpty();
        assertThat(problem.isVisible()).as("the reason, under the list").isTrue();
        assertThat(problem.getText())
                .contains(I18n.get("routing.bypass.problem.line", "2", "10.0.0.0/33"))
                .contains(I18n.get("routing.bypass.problem.line", "4", "192.168.1.300"))
                .doesNotContain("example.com");
        assertThat(area.getStyleClass()).contains("field-error");

        interact(() -> area.setText("example.com\n10.0.0.0/8\n192.168.1.30"));
        assertThat(problem.isVisible()).as("the reason, once the text has changed").isFalse();
        interact(save::fire);

        assertThat(routing.getConfig().getBypassList())
                .isEqualTo(List.of("example.com", "10.0.0.0/8", "192.168.1.30"));
        assertThat(problem.isVisible()).isFalse();
        assertThat(area.getStyleClass()).doesNotContain("field-error");
    }
}
