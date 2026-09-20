package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.RouteMode;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.service.RoutingService;
import com.vlessclient.service.TestRoutingServices;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.RadioButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The Routing page says what goes through the VPN: everything, or only what
 * is blocked in Russia. Everything went through it, and the only way around
 * was a list of countries or sites to send direct.
 */
@UiTest
public class RoutingModeTest extends ApplicationTest {

    @TempDir
    static Path tempDir;

    private RoutingService routing;

    @Override
    public void start(Stage stage) throws Exception {
        routing = TestRoutingServices.at(tempDir.resolve("routing-" + System.nanoTime()));
        ServiceLocator.register(RoutingService.class, routing);
        stage.setScene(new Scene(load(), 1000, 720));
        stage.show();
    }

    private static Parent load() throws Exception {
        return new FXMLLoader(RoutingModeTest.class.getResource("/fxml/RoutingView.fxml")).load();
    }

    @Test
    void choosingOnlyTheBlockedIsKept() {
        RadioButton all = lookup("#routeAllRadio").queryAs(RadioButton.class);
        RadioButton blocked = lookup("#routeBlockedRadio").queryAs(RadioButton.class);
        assertThat(all.isSelected()).as("everything, by default").isTrue();

        interact(blocked::fire);

        assertThat(routing.getConfig().getMode()).isEqualTo(RouteMode.BLOCKED_IN_RUSSIA);
        assertThat(all.isSelected()).isFalse();
    }

    @Test
    void theStoredModeIsShownChosen() throws Exception {
        RoutingConfig config = routing.getConfig();
        config.setMode(RouteMode.BLOCKED_IN_RUSSIA);
        routing.saveConfig(config);

        Parent page = WaitForAsyncUtils.asyncFx(RoutingModeTest::load).get();

        assertThat(((RadioButton) page.lookup("#routeBlockedRadio")).isSelected()).isTrue();
    }
}
