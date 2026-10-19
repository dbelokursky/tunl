package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.RoutingRule;
import com.vlessclient.service.RoutingRuleCheck;
import com.vlessclient.service.RoutingService;
import com.vlessclient.testing.UiTest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for RoutingView — verifies the redesigned FXML wires up to the
 * controller, including the new bypass-list count badge and country hint.
 */
@UiTest
public class RoutingViewTest extends ApplicationTest {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/RoutingView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 1000, 720));
        stage.show();
    }

    /**
     * The country catalogue was built with Locale.ENGLISH, so a Russian UI
     * offered "RU — Russia" and every other name in English, in a list the
     * user reads to find their country.
     */
    @Test
    void theCountryCatalogueFollowsTheCurrentLanguage() {
        @SuppressWarnings("unchecked")
        javafx.scene.control.ComboBox<String> combo =
                (javafx.scene.control.ComboBox<String>) lookup("#bypassCountryCombo").query();
        interact(() -> com.vlessclient.app.I18n.setLocale(java.util.Locale.of("ru")));
        try {
            assertThat(combo.getItems())
                    .as("the catalogue is rebuilt in the language the app is in now")
                    .contains("ru \u2014 \u0420\u043e\u0441\u0441\u0438\u044f");
        } finally {
            interact(() -> com.vlessclient.app.I18n.setLocale(java.util.Locale.ENGLISH));
        }
    }

    /**
     * The page is cached, and it read the rules once, when it was built. A
     * rule an agent added through MCP was in effect but not on the page, and
     * one it removed was still listed, until the app was started again.
     */
    @Test
    void rulesAnAgentAddsOrRemovesShowOnThePage() throws Exception {
        RoutingService routing = ServiceLocator.get(RoutingService.class);
        ListView<RoutingRule> list = lookup("#rulesListView").query();
        RoutingRule rule = new RoutingRule(RoutingRule.RuleType.DOMAIN_SUFFIX, "agent.example",
                RoutingRule.RuleAction.DIRECT);
        try {
            // From another thread, as MCP's handlers change them.
            CompletableFuture.runAsync(() -> routing.addRule(rule)).get(10, TimeUnit.SECONDS);
            WaitForAsyncUtils.waitForFxEvents();
            assertThat(list.getItems()).extracting(RoutingRule::getValue)
                    .as("the rules after an agent added one").contains("agent.example");

            CompletableFuture.runAsync(() -> routing.removeRule(rule.getId()))
                    .get(10, TimeUnit.SECONDS);
            WaitForAsyncUtils.waitForFxEvents();
            assertThat(list.getItems()).extracting(RoutingRule::getValue)
                    .as("the rules after an agent removed it").doesNotContain("agent.example");
        } finally {
            routing.removeRule(rule.getId());
        }
    }

    /**
     * The rows were worded when they were drawn: after a language switch they
     * still said "Domain suffix", "Direct" and "Delete".
     */
    @Test
    void theRulesFollowALanguageSwitch() {
        RoutingService routing = ServiceLocator.get(RoutingService.class);
        RoutingRule rule = new RoutingRule(RoutingRule.RuleType.DOMAIN_SUFFIX, "words.example",
                RoutingRule.RuleAction.DIRECT);
        try {
            interact(() -> routing.addRule(rule));
            WaitForAsyncUtils.waitForFxEvents();
            interact(() -> I18n.setLocale(java.util.Locale.of("ru")));
            WaitForAsyncUtils.waitForFxEvents();

            javafx.scene.layout.HBox row = (javafx.scene.layout.HBox) lookup(".list-cell")
                    .queryAll().stream()
                    .map(node -> (javafx.scene.control.ListCell<?>) node)
                    .filter(cell -> cell.getItem() == rule)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no row shows the rule"))
                    .getGraphic();
            assertThat(row.getChildren().stream()
                    .filter(node -> node instanceof javafx.scene.control.Labeled)
                    .map(node -> ((javafx.scene.control.Labeled) node).getText()))
                    .contains(I18n.get("routing.rule.type.domain.suffix"),
                            I18n.get("routing.action.direct"), I18n.get("button.delete"));
        } finally {
            interact(() -> {
                I18n.setLocale(java.util.Locale.ENGLISH);
                routing.removeRule(rule.getId());
            });
        }
    }

    /**
     * A stored rule the core cannot use is left out of the configuration, so
     * that the core starts; the list showed it like any other rule, and
     * nothing told the user that it did nothing. A rule the core is given
     * but may not do what it says, a regex with a letter the core never sees
     * or a Geosite list this version does not know, says so as a hint: it is
     * not left out. The list was said to be "Not used", and was.
     */
    @Test
    void aStoredRuleTheCoreIsNotGivenSaysWhyInTheList() {
        RoutingService routing = ServiceLocator.get(RoutingService.class);
        RoutingRule unusable = new RoutingRule(RoutingRule.RuleType.GEOIP, "rus",
                RoutingRule.RuleAction.PROXY);
        RoutingRule usable = new RoutingRule(RoutingRule.RuleType.DOMAIN_SUFFIX, "fine.example",
                RoutingRule.RuleAction.DIRECT);
        RoutingRule hinted = new RoutingRule(RoutingRule.RuleType.DOMAIN_REGEX, "\\.(ru|рф)$",
                RoutingRule.RuleAction.DIRECT);
        RoutingRule unknownList = new RoutingRule(RoutingRule.RuleType.GEOSITE, "youtub",
                RoutingRule.RuleAction.PROXY);
        java.util.List<RoutingRule> before = java.util.List.copyOf(routing.getConfig().getRules());
        try {
            // Stored as an earlier version stored them: addRule refuses the
            // first now. These alone, whatever rules the tests before this
            // one left.
            interact(() -> {
                routing.getConfig().setRules(java.util.List.of(unusable, usable, hinted,
                        unknownList));
                routing.saveConfig(routing.getConfig());
            });
            WaitForAsyncUtils.waitForFxEvents();

            assertThat(textsIn(cellShowing(unusable)))
                    .anySatisfy(text -> assertThat(text).startsWith("Not used").contains("rus"));
            assertThat(textsIn(cellShowing(usable)))
                    .noneSatisfy(text -> assertThat(text).startsWith("Not used"));
            for (RoutingRule rule : java.util.List.of(hinted, unknownList)) {
                assertThat(textsIn(cellShowing(rule))).as(rule.getValue())
                        .noneSatisfy(text -> assertThat(text).startsWith("Not used"))
                        .contains(RoutingRuleCheck.hint(rule.getType(), rule.getValue())
                                .orElseThrow());
            }
        } finally {
            interact(() -> {
                routing.getConfig().setRules(before);
                routing.saveConfig(routing.getConfig());
            });
        }
    }

    /**
     * The row that shows {@code rule}, scrolled to first: the stage is shared
     * between test classes, and one before this may leave the window smaller
     * than the scene asks, with room in the list for a single row.
     */
    private javafx.scene.Node cellShowing(RoutingRule rule) {
        ListView<RoutingRule> list = lookup("#rulesListView").query();
        interact(() -> {
            list.scrollTo(rule);
            list.layout();
        });
        return lookup(".list-cell").queryAll().stream()
                .map(node -> (javafx.scene.control.ListCell<?>) node)
                .filter(cell -> cell.getItem() == rule)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row shows " + rule.getValue()))
                .getGraphic();
    }

    private static java.util.List<String> textsIn(javafx.scene.Node graphic) {
        java.util.List<String> texts = new java.util.ArrayList<>();
        java.util.ArrayDeque<javafx.scene.Node> nodes = new java.util.ArrayDeque<>();
        nodes.push(graphic);
        while (!nodes.isEmpty()) {
            javafx.scene.Node node = nodes.pop();
            if (node instanceof javafx.scene.control.Labeled labeled) {
                texts.add(labeled.getText());
            } else if (node instanceof javafx.scene.Parent parent) {
                parent.getChildrenUnmodifiable().forEach(nodes::push);
            }
        }
        return texts;
    }

    @Test
    void redesignedControlsExist() {
        assertThat(lookup("#bypassCountryCombo").tryQuery()).isPresent();
        assertThat(lookup("#bypassCountryChips").tryQuery()).isPresent();
        assertThat(lookup("#customRulesSection").tryQuery()).isPresent();
        assertThat(lookup("#bypassListArea").tryQuery()).isPresent();
        assertThat(lookup("#bypassCountLabel").tryQuery()).isPresent();
        assertThat(lookup("#saveBypassButton").tryQuery()).isPresent();
    }

    /**
     * The badge counts entries as the user types, skipping blank and comment
     * lines, and switches between the singular and plural wording.
     */
    @Test
    void bypassCountBadgeFollowsTheEditedList() {
        TextArea area = lookup("#bypassListArea").query();
        Label badge = lookup("#bypassCountLabel").query();

        interact(() -> area.setText("example.com\n# a comment\n\n   10.0.0.0/8  \n"));
        assertThat(badge.getText()).isEqualTo(I18n.plural("routing.bypass.count", 2));

        interact(() -> area.setText("example.com"));
        assertThat(badge.getText()).isEqualTo(I18n.plural("routing.bypass.count", 1));

        interact(() -> area.setText("# only a comment\n\n"));
        assertThat(badge.getText()).isEqualTo(I18n.plural("routing.bypass.count", 0));

        interact(area::clear);
        assertThat(badge.getText()).isEqualTo(I18n.plural("routing.bypass.count", 0));
    }
}
