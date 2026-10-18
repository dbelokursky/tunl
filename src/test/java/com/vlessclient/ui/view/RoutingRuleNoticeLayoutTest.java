package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.RoutingRule;
import com.vlessclient.service.RoutingService;
import com.vlessclient.testing.UiTest;
import java.util.ArrayList;
import java.util.List;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListCell;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The line that says why a stored rule is not used fits its row.
 *
 * <p>Asked for its whole sentence, the line made the list as wide as the
 * sentence: every row widened with it, and each row's Delete button went out
 * of view, the unused rule's included.</p>
 */
@UiTest
public class RoutingRuleNoticeLayoutTest extends ApplicationTest {

    /** The size VlessClientApp opens the main window at. */
    private static final int WIDTH = 820;
    private static final int HEIGHT = 500;

    private List<RoutingRule> before;

    @Override
    public void start(Stage stage) {
        RoutingService routing = ServiceLocator.get(RoutingService.class);
        before = List.copyOf(routing.getConfig().getRules());
        // As an earlier version stored them: the add-rule check refuses the
        // first two now. These alone, so that every row is laid out, whatever
        // rules the tests before this one left.
        routing.getConfig().setRules(List.of(
                new RoutingRule(RoutingRule.RuleType.GEOSITE, "youtub",
                        RoutingRule.RuleAction.PROXY),
                new RoutingRule(RoutingRule.RuleType.DOMAIN, "*.example.net",
                        RoutingRule.RuleAction.DIRECT),
                new RoutingRule(RoutingRule.RuleType.DOMAIN_SUFFIX, "fine.example",
                        RoutingRule.RuleAction.DIRECT)));
        routing.saveConfig(routing.getConfig());
    }

    @AfterEach
    void restore() {
        interact(() -> {
            RoutingService routing = ServiceLocator.get(RoutingService.class);
            routing.getConfig().setRules(before);
            routing.saveConfig(routing.getConfig());
        });
    }

    @Test
    void everyRowKeepsItsDeleteButtonInTheList() {
        Scene scene = mainViewShowingRouting();
        List<String> wrong = new ArrayList<>();
        interact(() -> {
            Node list = scene.lookup("#rulesListView");
            Bounds listBounds = list.getParent().localToScene(list.getBoundsInParent());
            int rows = 0;
            for (Node node : list.lookupAll(".list-cell")) {
                if (!(node instanceof ListCell<?> cell) || cell.getItem() == null) {
                    continue;
                }
                rows++;
                RoutingRule rule = (RoutingRule) cell.getItem();
                Button delete = (Button) cell.getGraphic().lookupAll(".secondary-button")
                        .iterator().next();
                Bounds button = delete.getParent().localToScene(delete.getBoundsInParent());
                if (button.getMaxX() > listBounds.getMaxX() + 0.5) {
                    wrong.add("%s: Delete ends at x=%.1f, past the list's edge at %.1f"
                            .formatted(rule.getValue(), button.getMaxX(), listBounds.getMaxX()));
                }
                Bounds row = cell.getParent().localToScene(cell.getBoundsInParent());
                if (row.getMaxX() > listBounds.getMaxX() + 0.5) {
                    wrong.add("%s: the row ends at x=%.1f, past the list's edge at %.1f"
                            .formatted(rule.getValue(), row.getMaxX(), listBounds.getMaxX()));
                }
            }
            if (rows < 3) {
                wrong.add("only " + rows + " rows were laid out");
            }
        });

        assertThat(wrong).withFailMessage("at %dx%d:%n  %s", WIDTH, HEIGHT,
                String.join("\n  ", wrong)).isEmpty();
    }

    /** MainView at the default window size on the Routing page, never shown, as elsewhere. */
    private Scene mainViewShowingRouting() {
        final Scene[] holder = new Scene[1];
        interact(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainView.fxml"));
                Parent root = loader.load();
                Scene scene = new Scene(new Group(), WIDTH, HEIGHT);
                scene.getStylesheets().setAll(ThemeCss.light());
                scene.setRoot(root);
                ((MainViewController) loader.getController()).showRouting();
                root.resize(WIDTH, HEIGHT);
                root.applyCss();
                root.layout();
                holder[0] = scene;
            } catch (Exception e) {
                throw new IllegalStateException("could not load MainView", e);
            }
        });
        return holder[0];
    }
}
