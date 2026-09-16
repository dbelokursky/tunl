package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.service.RoutingService;
import com.vlessclient.service.TestRoutingServices;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A bypass country's chip on the Routing page, for the keyboard and a screen
 * reader. Its "✕" was a label that answered the pointer only: Tab skipped it,
 * Enter and Space did nothing, and a screen reader read out a glyph.
 */
@UiTest
public class RoutingCountryChipTest extends ApplicationTest {

    @TempDir
    static Path tempDir;

    private RoutingService routing;

    @Override
    public void start(Stage stage) throws Exception {
        routing = TestRoutingServices.at(tempDir.resolve("routing-" + System.nanoTime()));
        RoutingConfig config = routing.getConfig();
        config.setBypassCountries(List.of("ru", "kz"));
        routing.saveConfig(config);
        ServiceLocator.register(RoutingService.class, routing);

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/RoutingView.fxml"));
        Parent root = loader.load();
        Scene scene = new Scene(root, 1000, 720);
        scene.getStylesheets().setAll(ThemeCss.of("light"));
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void aCountrysRemoveButtonTakesTheFocusNamesTheCountryAndRemovesIt() {
        Node remove = removeControlOf(chipOf("ru"));

        assertThat(remove).as("the chip's remove control").isInstanceOf(Button.class);
        Button button = (Button) remove;
        assertThat(button.isFocusTraversable()).as("whether Tab reaches it").isTrue();
        assertThat(button.getAccessibleText())
                .as("the name a screen reader reads for it")
                .isEqualTo(I18n.get("routing.bypass.countries.remove",
                        "ru — " + Locale.of("", "RU").getDisplayCountry(Locale.ENGLISH)));

        interact(button::fire);

        assertThat(routing.getConfig().getBypassCountries()).containsExactly("kz");
        assertThat(chipCodes()).as("the chips left").containsExactly("kz");
    }

    /**
     * The remove control is drawn as the bare glyph: whatever it is, it adds no
     * padding or insets around its text, so the chip does not grow.
     */
    @Test
    void theRemoveControlAddsNothingAroundItsGlyph() {
        Node remove = removeControlOf(chipOf("kz"));
        double[] heights = new double[2];
        interact(() -> {
            remove.getScene().getRoot().applyCss();
            remove.getScene().getRoot().layout();
            heights[0] = remove.getLayoutBounds().getHeight();
            heights[1] = remove.lookup(".text").getLayoutBounds().getHeight();
        });

        assertThat(heights[0])
                .as("the remove control's height, for a glyph %.1f px tall", heights[1])
                .isLessThanOrEqualTo(heights[1] + 0.5);
    }

    private HBox chipOf(String code) {
        return lookup(".country-chip").queryAll().stream()
                .map(HBox.class::cast)
                .filter(chip -> chip.getChildren().get(0) instanceof Label label
                        && code.equals(label.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no chip for " + code));
    }

    private static Node removeControlOf(HBox chip) {
        return chip.getChildren().get(chip.getChildren().size() - 1);
    }

    private List<String> chipCodes() {
        return lookup(".country-chip").queryAll().stream()
                .map(chip -> ((Label) ((HBox) chip).getChildren().get(0)).getText())
                .toList();
    }
}
