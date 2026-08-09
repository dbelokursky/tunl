package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import java.util.List;
import java.util.Locale;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The toolbar actions each view opens with. Their translations existed in the
 * bundle from the start and simply were not connected to anything, so these
 * pin the wiring rather than the wording.
 */
public class ToolbarLocalizationTest extends ApplicationTest {

    /** view, control id, bundle key, and whether the add marker fronts it. */
    private record Action(String view, String id, String key, boolean added) { }

    private static final List<Action> ACTIONS = List.of(
            new Action("ServersView", "importLinkButton", "button.import.link", false),
            new Action("ServersView", "addServerButton", "button.add.server", true),
            new Action("RoutingView", "addRuleButton", "button.add.rule", true),
            new Action("SubscriptionsView", "refreshAllButton", "subscriptions.refresh.all", false),
            new Action("SubscriptionsView", "addSubscriptionButton",
                    "button.add.subscription", true),
            new Action("LogsView", "autoScrollCheckBox", "logs.auto.scroll", false),
            new Action("DashboardView", "uploadCardTitle", "dashboard.upload.speed", false),
            new Action("DashboardView", "downloadCardTitle",
                    "dashboard.download.speed", false));

    private Stage stage;

    @BeforeAll
    static void setupHeadless() {
        System.setProperty("testfx.robot", "glass");
        System.setProperty("testfx.headless", "true");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        System.setProperty("java.awt.headless", "true");
        ServiceLocator.initialize();
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
    void toolbarActionsComeFromTheBundleInEveryLanguage() {
        for (Locale locale : List.of(Locale.ENGLISH, Locale.of("ru"))) {
            interact(() -> I18n.setLocale(locale));
            for (Action action : ACTIONS) {
                assertAction(action, locale);
            }
        }
    }

    private void assertAction(Action action, Locale locale) {
        Labeled node = load(action.view(), action.id());
        String expected = (action.added() ? "+ " : "") + I18n.get(action.key());
        assertThat(node.getText())
                .withFailMessage("%s#%s shows \"%s\" in %s but the bundle says \"%s\" — "
                                + "the label is hardcoded again",
                        action.view(), action.id(), node.getText(), locale, expected)
                .isEqualTo(expected);
    }

    /** Loads a view fresh, so each assertion sees the locale set just now. */
    private Labeled load(String view, String id) {
        final Labeled[] found = new Labeled[1];
        interact(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                        getClass().getResource("/fxml/" + view + ".fxml"));
                Parent root = loader.load();
                Scene scene = new Scene(root, 900, 640);
                scene.getStylesheets().add(
                        getClass().getResource("/css/light.css").toExternalForm());
                stage.setScene(scene);
                stage.show();
                found[0] = (Labeled) scene.lookup("#" + id);
            } catch (Exception e) {
                throw new IllegalStateException("could not load " + view, e);
            }
        });
        assertThat(found[0])
                .withFailMessage("%s#%s is gone; the binding points at nothing", view, id)
                .isNotNull();
        return found[0];
    }
}
