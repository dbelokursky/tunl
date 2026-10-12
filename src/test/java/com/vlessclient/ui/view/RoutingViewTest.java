package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.RoutingRule;
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
