package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the dashboard against being squashed when the window is shorter than
 * the page needs.
 *
 * <p>A JavaFX {@code Region} does not clip its children: hand a card less
 * height than its content needs and the content is not hidden, it is painted
 * on top of the next sibling. That turns a too-short window into visibly
 * overlapping widgets rather than a scrollbar, so these assertions check the
 * cards keep at least their content height.
 *
 * <p>The ScrollPane here mirrors the wrapper MainViewController puts around
 * every view, so the layout under test is the one the app actually renders.
 */
public class DashboardLayoutTest extends ApplicationTest {

    /** Shorter than the dashboard's natural height, so the page must scroll. */
    private static final int SHORT_WINDOW_HEIGHT = 420;

    /** Roughly the window the TUN warning was reported spilling out of. */
    private static final int TALLER_WINDOW_HEIGHT = 640;

    private static final int WINDOW_WIDTH = 820;

    /**
     * What the dashboard actually gets at the narrowest window the app allows:
     * VlessClientApp pins minWidth to 760 and MainView spends 200 of it on the
     * sidebar. Sizing the test window to the whole 760 would hand the page
     * width it never has in the app, and the row would fit for the wrong
     * reason.
     */
    private static final int NARROW_CONTENT_WIDTH = 760 - 200;

    private ScrollPane wrapper;
    private Stage stage;

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
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/DashboardView.fxml"));
        Parent dashboard = loader.load();

        wrapper = new ContentScrollPane(dashboard);

        Scene scene = new Scene(wrapper, WINDOW_WIDTH, SHORT_WINDOW_HEIGHT);
        // The min-heights that allow the squash live in the stylesheet, so the
        // page has to be styled for this test to exercise the real layout.
        scene.getStylesheets().add(
                getClass().getResource("/css/dark.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
        this.stage = stage;
    }

    /**
     * TestFX reuses one stage for every method in the class, so a test that
     * resizes the window or reveals the warning would otherwise hand the next
     * one a window it did not ask for.
     */
    @BeforeEach
    void resetWindow() {
        interact(() -> {
            stage.setWidth(WINDOW_WIDTH);
            stage.setHeight(SHORT_WINDOW_HEIGHT);
            Region health = lookup("#healthCard").query();
            health.setVisible(false);
            health.setManaged(false);
            Label warning = lookup("#proxyModeWarning").query();
            warning.setText("");
            warning.setVisible(false);
            warning.setManaged(false);
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * TUN mode reveals a warning under the mode row, which grows the page. It
     * was reported spilling out of the card bottom after a resize, so it is
     * asserted at more than one window height.
     */
    @Test
    void tunWarningStaysInsideTheHeroCard() {
        Label warning = lookup("#proxyModeWarning").query();
        interact(() -> {
            warning.setText("TUN mode requires administrator privileges");
            warning.setManaged(true);
            warning.setVisible(true);
        });

        for (int height : new int[] {SHORT_WINDOW_HEIGHT, TALLER_WINDOW_HEIGHT}) {
            interact(() -> stage.setHeight(height));
            WaitForAsyncUtils.waitForFxEvents();

            Region hero = lookup(".hero-card").query();
            assertThat(toScene(warning).getMaxY())
                    .withFailMessage("at a %dpx window the TUN warning spills %.1fpx"
                            + " below the hero card", height,
                            toScene(warning).getMaxY() - toScene(hero).getMaxY())
                    .isLessThanOrEqualTo(toScene(hero).getMaxY() + 0.5);
        }
    }

    /**
     * At the narrowest window the app allows, the mode row runs out of width
     * first. The combos are the ones meant to give it up; the labels naming
     * them must stay whole rather than collapsing to an ellipsis.
     */
    @Test
    void narrowWindowShrinksTheCombosNotTheirLabels() {
        interact(() -> stage.setWidth(NARROW_CONTENT_WIDTH));
        WaitForAsyncUtils.waitForFxEvents();

        Label serverLabel = lookup("#serverSelectionLabel").query();
        assertThat(serverLabel.getWidth())
                .withFailMessage("the server label was ellipsized to %.1fpx, below the"
                        + " %.1fpx its text needs", serverLabel.getWidth(),
                        serverLabel.prefWidth(-1))
                .isGreaterThanOrEqualTo(serverLabel.prefWidth(-1) - 0.5);

        Region hero = lookup(".hero-card").query();
        assertThat(toScene(lookup("#connectButton").query()).getMaxY())
                .withFailMessage("connect button spills below the hero card at %dpx wide",
                        NARROW_CONTENT_WIDTH)
                .isLessThanOrEqualTo(toScene(hero).getMaxY() + 0.5);
    }

    /**
     * Controls that share a row should share a height. The remove button in a
     * service row is a glyph-only .icon-button while its neighbours are
     * .secondary-buttons, so the two style classes have to agree on one
     * control height or the row reads as ragged.
     */
    @Test
    void rowControlsShareOneHeight() {
        Button remove = new Button("\u2715");
        remove.getStyleClass().setAll("icon-button", "destructive");
        interact(() -> {
            Region health = lookup("#healthCard").query();
            health.setVisible(true);
            health.setManaged(true);
            ((Pane) lookup("#serviceStatusList").query()).getChildren().add(remove);
        });
        WaitForAsyncUtils.waitForFxEvents();

        double recheck = ((Region) lookup("#recheckButton").query()).getHeight();
        double testLatency = ((Region) lookup("#testLatencyButton").query()).getHeight();
        double addTarget = ((Region) lookup("#addTargetButton").query()).getHeight();

        assertThat(testLatency)
                .withFailMessage("Test Latency is %.1fpx tall, Re-check %.1fpx",
                        testLatency, recheck)
                .isEqualTo(recheck);
        assertThat(addTarget)
                .withFailMessage("the + button is %.1fpx tall, Re-check %.1fpx",
                        addTarget, recheck)
                .isEqualTo(recheck);
        assertThat(remove.getHeight())
                .withFailMessage("the row remove button is %.1fpx tall, Re-check %.1fpx",
                        remove.getHeight(), recheck)
                .isEqualTo(recheck);
    }

    @Test
    void heroCardKeepsAtLeastItsContentHeight() {
        Region hero = lookup(".hero-card").query();
        double needed = hero.prefHeight(hero.getWidth());

        assertThat(hero.getHeight())
                .withFailMessage("hero card squashed to %.1f but its content needs %.1f;"
                        + " the overflow paints over the section below",
                        hero.getHeight(), needed)
                .isGreaterThanOrEqualTo(needed - 0.5);
    }

    @Test
    void connectButtonStaysInsideTheHeroCard() {
        Button connect = lookup("#connectButton").query();
        Region hero = lookup(".hero-card").query();

        // layoutBounds, not boundsInLocal: a Region's layoutBounds is exactly
        // its own width x height, while boundsInLocal grows to swallow children
        // that spill past the edge -- which is the very thing under test.
        Bounds button = toScene(connect);
        Bounds card = toScene(hero);

        assertThat(button.getMaxY())
                .withFailMessage("connect button spills %.1fpx below the hero card",
                        button.getMaxY() - card.getMaxY())
                .isLessThanOrEqualTo(card.getMaxY() + 0.5);
    }

    @Test
    void shortWindowScrollsInsteadOfSquashingThePage() {
        Region page = (Region) wrapper.getContent();
        double viewport = wrapper.getViewportBounds().getHeight();

        assertThat(page.getHeight())
                .withFailMessage("page was resized down to the %.1fpx viewport instead"
                        + " of keeping its %.1fpx natural height and scrolling",
                        viewport, page.prefHeight(page.getWidth()))
                .isGreaterThan(viewport);
    }

    private static Bounds toScene(Node node) {
        return node.localToScene(node.getLayoutBounds());
    }
}
