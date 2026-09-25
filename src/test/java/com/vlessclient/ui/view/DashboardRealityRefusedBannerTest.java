package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Dashboard says why a REALITY server's tunnel carries nothing.
 *
 * <p>Current Xray REALITY servers (26.7 and later, by default) turn away a
 * client that is not a recent Xray, and sing-box, the core, is not one. The
 * user saw "All services unreachable" and a reconnect loop, as for a dead
 * server or a blocked network (item 1.1 of the 2026-09-25 review). The line
 * goes through the engine's own log, as the core's reader delivers it.</p>
 */
@UiTest
public class DashboardRealityRefusedBannerTest extends ApplicationTest {

    private static final SingBoxEngine ENGINE = new SingBoxEngine(Path.of("sing-box"));

    @BeforeAll
    static void registerTheEngine() {
        ServiceLocator.register(SingBoxEngine.class, ENGINE);
    }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/DashboardView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 640, 480));
        stage.show();
    }

    @Test
    void theDashboardSaysWhyWhenAServerTurnsTheCoreAway() {
        HBox banner = lookup("#realityRefusedBanner").query();
        Label label = lookup("#realityRefusedLabel").query();
        assertThat(banner.isVisible()).isFalse();
        assertThat(banner.isManaged()).isFalse();

        interact(() -> ENGINE.getLogLines().add("+0200 2026-09-25 23:05:44 ERROR "
                + "[2809146892 683ms] connection: open connection to example.org:443 using "
                + "outbound/selector[proxy]: reality verification failed"));

        assertThat(banner.isVisible()).isTrue();
        assertThat(banner.isManaged()).isTrue();
        assertThat(label.getText()).isEqualTo(I18n.get("dashboard.reality.refused"));

        ENGINE.forgetRealityRefusal();
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(banner.isVisible()).isFalse();
    }
}
