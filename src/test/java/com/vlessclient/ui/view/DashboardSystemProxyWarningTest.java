package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.UiTestServices;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ProxyMode;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SYSTEM_PROXY on a host that has no OS proxy store.
 *
 * <p>This is the app's default mode. On a Linux box without the GNOME schema
 * the generator drops {@code set_system_proxy} so sing-box does not FATAL —
 * correct, but until now entirely silent: the tunnel connected, the health
 * card went green (it probes through the local inbound), and nothing was
 * proxied OS-wide. The dashboard now says so, and names the address to
 * configure by hand.</p>
 */
public class DashboardSystemProxyWarningTest extends ApplicationTest {

    private DashboardViewController controller;

    @BeforeAll
    static void setupHeadless() {
        System.setProperty("testfx.robot", "glass");
        System.setProperty("testfx.headless", "true");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        System.setProperty("java.awt.headless", "true");
        try {
            UiTestServices.initialize();
        } catch (Exception e) {
            // Tolerate service initialization failures in headless CI.
        }
    }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/DashboardView.fxml"));
        Parent root = loader.load();
        controller = loader.getController();
        stage.setScene(new Scene(root, 640, 480));
        stage.show();
    }

    @Test
    void aHostWithoutAProxyStoreIsToldWhereToPointItsApps() {
        Label warning = lookup("#proxyModeWarning").query();
        AppSettings settings = ServiceLocator.get(AppSettings.class);

        interact(() -> {
            settings.setProxyMode(ProxyMode.SYSTEM_PROXY);
            controller.setSystemProxySupport(() -> false);
            controller.onViewShown();
        });

        assertThat(warning.isVisible())
                .as("silently not proxying is the failure this app exists to prevent")
                .isTrue();
        assertThat(warning.getText())
                .contains("127.0.0.1:" + settings.getHttpPort())
                .as("MessageFormat renders a bare int through NumberFormat")
                .doesNotContain(",");
    }

    @Test
    void aHostWithAProxyStoreShowsNothing() {
        Label warning = lookup("#proxyModeWarning").query();
        AppSettings settings = ServiceLocator.get(AppSettings.class);

        interact(() -> {
            settings.setProxyMode(ProxyMode.SYSTEM_PROXY);
            controller.setSystemProxySupport(() -> true);
            controller.onViewShown();
        });

        assertThat(warning.isVisible()).isFalse();
    }
}
