package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.UiTestServices;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same guard {@link DashboardFitTest} puts on the Dashboard, for the
 * views beside it. Every page is mounted in {@link ContentScrollPane}, which
 * sets {@code hbarPolicy=NEVER} — so a control wider than the viewport is not
 * merely ugly, it is clipped with no way to scroll to it.
 *
 * <p>Runs in Russian as well as English: the translations here run wider than
 * their originals, and a row that fits in one language can overflow in the
 * other.</p>
 */
public class ViewFitTest extends ApplicationTest {

    /** Window min 760 - sidebar 200 - content padding 48 = 512; a hair tighter. */
    private static final double MIN_CONTENT_WIDTH = 500;

    private static final List<String> VIEWS = List.of(
            "SettingsView", "ServersView", "RoutingView", "SubscriptionsView", "LogsView");

    private Stage stage;

    @BeforeAll
    static void setupHeadless() {
        System.setProperty("testfx.robot", "glass");
        System.setProperty("testfx.headless", "true");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        System.setProperty("java.awt.headless", "true");
        UiTestServices.initialize();
    }

    @AfterEach
    void resetLocale() {
        interact(() -> I18n.setLocale(Locale.ENGLISH));
    }

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
    }

    @Test
    void actionableControlsStayWithinTheViewportInEveryLanguage() {
        for (Locale locale : List.of(Locale.ENGLISH, Locale.of("ru"))) {
            interact(() -> I18n.setLocale(locale));
            for (String view : VIEWS) {
                assertFits(view, locale);
            }
        }
    }

    private void assertFits(String view, Locale locale) {
        Scene scene = load(view);
        List<String> overflowing = new ArrayList<>();
        // Collected inside the FX thread, asserted outside it: an assertion
        // thrown in here comes back wrapped as an Error rather than a test
        // failure, and stops at the first offender instead of listing them.
        interact(() -> {
            for (Node node : scene.getRoot().lookupAll("*")) {
                if (!isActionable(node) || !node.isVisible() || !node.isManaged()) {
                    continue;
                }
                Bounds inScene = node.localToScene(node.getBoundsInLocal());
                if (inScene.getWidth() <= 0 || inScene.getMaxX() <= MIN_CONTENT_WIDTH + 0.5) {
                    continue;
                }
                overflowing.add("%s: %s reaches x=%.1f".formatted(
                        view, describe(node), inScene.getMaxX()));
            }
        });

        assertThat(overflowing)
                .withFailMessage("past the %.0fpx viewport in %s — ContentScrollPane has no "
                                + "horizontal scrollbar, so these are clipped and "
                                + "unreachable:%n  %s",
                        MIN_CONTENT_WIDTH, locale, String.join("\n  ", overflowing))
                .isEmpty();
    }

    /**
     * Only controls the user has to reach. Containers are skipped on purpose:
     * a card's drop shadow puts its bounds a couple of pixels past the edge
     * without anything being clipped.
     */
    private static boolean isActionable(Node node) {
        return node instanceof Button || node instanceof ComboBox<?>
                || node instanceof TextField || node instanceof CheckBox
                || node instanceof MenuButton;
    }

    private static String describe(Node node) {
        String id = node.getId() != null ? "#" + node.getId() : node.getClass().getSimpleName();
        return node instanceof javafx.scene.control.Labeled labeled && labeled.getText() != null
                ? id + " (\"" + labeled.getText() + "\")"
                : id;
    }

    private Scene load(String view) {
        final Scene[] holder = new Scene[1];
        interact(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                        getClass().getResource("/fxml/" + view + ".fxml"));
                Parent root = loader.load();
                Scene scene = new Scene(root, MIN_CONTENT_WIDTH, 640);
                scene.getStylesheets().add(
                        getClass().getResource("/css/light.css").toExternalForm());
                stage.setScene(scene);
                stage.show();
                root.applyCss();
                root.layout();
                holder[0] = scene;
            } catch (Exception e) {
                throw new IllegalStateException("could not load " + view, e);
            }
        });
        return holder[0];
    }
}
