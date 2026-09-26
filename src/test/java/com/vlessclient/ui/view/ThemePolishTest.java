package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.ThemeCss;
import com.vlessclient.testing.UiTest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.RadioButton;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * The controls the themes left to Modena take the theme, and the leftovers
 * that never drew are fixed or gone.
 *
 * <p>Modena paints a check box, a radio button and an editable combo box's
 * arrow from its own light base: in the dark theme they were light grey
 * (#E5E5E5) on the dark cards. The active navigation item's bar had its
 * colour on the wrong side and never showed. And Settings painted the window
 * colour where every other page shows the page colour.</p>
 */
@UiTest
public class ThemePolishTest extends ApplicationTest {

    private static final List<String> THEMES = List.of("light", "dark");

    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.show();
    }

    @Test
    void checkBoxesAndRadioButtonsTakeTheFieldColours() throws IOException {
        CheckBox check = new CheckBox("Launch at login");
        RadioButton radio = new RadioButton("All traffic");
        CheckBox checked = new CheckBox("Auto-connect");
        checked.setSelected(true);
        RadioButton chosen = new RadioButton("Only what is blocked");
        chosen.setSelected(true);
        Scene scene = onACard(check, radio, checked, chosen);
        for (String theme : THEMES) {
            Map<String, Color> tokens = tokens(theme);
            interact(() -> {
                scene.getStylesheets().setAll(ThemeCss.of(theme));
                // Focused by a click: Modena's :focused layers brought its
                // light fill back.
                check.requestFocus();
                scene.getRoot().applyCss();
            });
            for (Region box : List.of(part(check, ".box"), part(radio, ".radio"))) {
                assertThat(fills(box)).as("an empty box in the %s theme", theme)
                        .containsExactly(tokens.get("-c-field-border"),
                                tokens.get("-c-field-bg"));
            }
            for (Region box : List.of(part(checked, ".box"), part(chosen, ".radio"))) {
                assertThat(fills(box)).as("a chosen box in the %s theme", theme)
                        .containsOnly(tokens.get("-c-accent"));
            }
            assertThat(fills(part(checked, ".mark"))).as("the tick in the %s theme", theme)
                    .containsExactly(tokens.get("-c-on-accent"));
            assertThat(fills(part(chosen, ".dot"))).as("the dot in the %s theme", theme)
                    .containsExactly(tokens.get("-c-on-accent"));
        }
    }

    @Test
    void anEditableCombosArrowSitsOnTheField() throws IOException {
        ComboBox<String> editable = new ComboBox<>();
        editable.setEditable(true);
        ComboBox<String> plain = new ComboBox<>();
        Scene scene = onACard(editable, plain);
        for (String theme : THEMES) {
            Map<String, Color> tokens = tokens(theme);
            interact(() -> {
                scene.getStylesheets().setAll(ThemeCss.of(theme));
                scene.getRoot().applyCss();
            });
            assertThat(fills(part(editable, ".arrow-button")))
                    .as("the editable combo's arrow button in the %s theme", theme)
                    .allSatisfy(fill -> assertThat(fill).isInstanceOfSatisfying(Color.class,
                            color -> assertThat(color.getOpacity()).as("its opacity").isZero()));
            for (ComboBox<String> combo : List.of(editable, plain)) {
                assertThat(fills(part(combo, ".arrow")))
                        .as("a combo's arrow in the %s theme", theme)
                        .containsExactly(tokens.get("-c-text-secondary"));
            }
        }
    }

    @Test
    void theActiveNavigationItemShowsItsBar() throws IOException {
        Button active = new Button("Servers");
        active.getStyleClass().addAll("nav-button", "nav-button-active");
        Scene scene = onACard(active);
        for (String theme : THEMES) {
            interact(() -> {
                scene.getStylesheets().setAll(ThemeCss.of(theme));
                scene.getRoot().applyCss();
            });
            var stroke = active.getBorder().getStrokes().getFirst();
            assertThat(stroke.getLeftStroke()).as("the bar's colour in the %s theme", theme)
                    .isEqualTo(tokens(theme).get("-c-accent-fg"));
            assertThat(stroke.getWidths().getLeft()).as("the bar's width").isEqualTo(3);
        }
    }

    /** MainView shows every page on the content area, which paints the page colour. */
    @Test
    void noPagePaintsABackgroundOfItsOwn() {
        for (String view : List.of("DashboardView", "ServersView", "SubscriptionsView",
                "RoutingView", "LogsView", "SettingsView")) {
            List<Paint> own = new ArrayList<>();
            interact(() -> {
                Parent root;
                try {
                    root = new FXMLLoader(getClass().getResource("/fxml/" + view + ".fxml"))
                            .load();
                } catch (IOException e) {
                    throw new IllegalStateException(view, e);
                }
                Scene scene = new Scene(new StackPane(root), 900, 700);
                scene.getStylesheets().setAll(ThemeCss.of("dark"));
                stage.setScene(scene);
                root.applyCss();
                own.addAll(fills((Region) root));
            });
            assertThat(own).as("%s's own background", view).isEmpty();
        }
    }

    private Scene onACard(Node... controls) {
        VBox card = new VBox(12, controls);
        card.getStyleClass().add("card");
        Scene scene = new Scene(new StackPane(card), 400, 300);
        interact(() -> stage.setScene(scene));
        return scene;
    }

    private static Region part(Node control, String selector) {
        Node part = control.lookup(selector);
        assertThat(part).as("%s of a %s", selector, control.getClass().getSimpleName())
                .isInstanceOf(Region.class);
        return (Region) part;
    }

    /** The fills a region paints; none for a region with no background. */
    private static List<Paint> fills(Region region) {
        Background background = region.getBackground();
        return background == null ? List.of()
                : background.getFills().stream().map(BackgroundFill::getFill).toList();
    }

    private static Map<String, Color> tokens(String theme) throws IOException {
        return ThemeTokenContrastTest.tokens("/css/" + theme + ".css");
    }
}
