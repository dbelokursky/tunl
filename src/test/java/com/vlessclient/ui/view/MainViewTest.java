package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Headless TestFX smoke tests for MainView: the FXML loads and sidebar
 * navigation marks the selected button active.
 */
public class MainViewTest extends ApplicationTest {

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
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 1024, 720));
        stage.show();
    }

    /**
     * The sidebar text must come from the bundle, not FXML literals. It used to
     * be hardcoded English, so the primary Russian audience saw an English
     * navigation while the sidebar.* translations sat unused — and a language
     * change did not move it.
     */
    @Test
    void sidebarLabelsComeFromTheMessageBundle() {
        // Every nav label must be bound to the bundle rather than carrying an
        // FXML literal — that is what left the navigation in English for the
        // primary Russian audience while sidebar.* sat translated and unused.
        // Asserting the binding (not a re-translated value) keeps this robust:
        // I18n's locale is process-wide static state shared by the whole
        // headless suite, so flipping it here would race the other classes.
        for (String id : new String[] {"#btnDashboard", "#btnServers", "#btnSubscriptions",
                                       "#btnRouting", "#btnLogs", "#btnSettings"}) {
            Button button = lookup(id).queryButton();
            assertThat(button.textProperty().isBound())
                    .as("%s must take its text from the message bundle", id)
                    .isTrue();
            assertThat(button.getText()).isNotBlank();
        }

        Button dashboard = lookup("#btnDashboard").queryButton();
        assertThat(dashboard.getText())
                .isEqualTo(com.vlessclient.app.I18n.get("sidebar.dashboard"));
    }

    @Test
    void sidebarButtonsExist() {
        assertThat(lookup("#btnDashboard").tryQuery()).isPresent();
        assertThat(lookup("#btnServers").tryQuery()).isPresent();
        assertThat(lookup("#btnSubscriptions").tryQuery()).isPresent();
        assertThat(lookup("#btnRouting").tryQuery()).isPresent();
        assertThat(lookup("#btnLogs").tryQuery()).isPresent();
        assertThat(lookup("#btnSettings").tryQuery()).isPresent();
    }

    @Test
    void clickingDashboardSwitchesView() {
        fireNav("#btnDashboard");
        Button dashboard = lookup("#btnDashboard").query();
        assertThat(dashboard.getStyleClass()).contains("nav-button-active");
    }

    @Test
    void clickingServersSwitchesView() {
        fireNav("#btnServers");
        Button servers = lookup("#btnServers").query();
        assertThat(servers.getStyleClass()).contains("nav-button-active");
    }

    /**
     * Fires the nav button's action directly instead of a robot clickOn. The
     * glass robot can miss its target under load when several TestFX suites
     * run together (headless focus contention), which made these assertions
     * flaky; firing the handler exercises the same navigation deterministically.
     */
    private void fireNav(String id) {
        Button button = lookup(id).query();
        interact(button::fire);
        WaitForAsyncUtils.waitForFxEvents();
    }
}
