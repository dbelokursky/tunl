package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.UiTestServices;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ProxyMode;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard's proxy-mode selector against writers it does not own.
 *
 * <p>{@code initialize()} runs once per app run, so the combo used to keep the
 * value it was born with while Settings and the MCP tools {@code set_proxy_mode}
 * and {@code connect} changed the same {@link AppSettings} instance underneath
 * it. Routing stayed correct — {@code ConnectionService.connect} reads the mode
 * live — but the UI reported the wrong one and, going to TUN, hid the warning
 * that the next Connect would raise an admin prompt and build a TUN device.</p>
 */
public class DashboardProxyModeSyncTest extends ApplicationTest {

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

    /**
     * Pins the host verdict so these cases test mode syncing and nothing else.
     *
     * <p>Without it the result depends on the machine: on macOS/Windows
     * {@code SystemProxySupport.current()} is always true, but on a Linux CI
     * runner there is no GNOME schema, so SYSTEM_PROXY legitimately shows the
     * "no OS proxy store" line and the assertion below flipped. Runs after
     * TestFX's own setup, which is where {@code controller} is assigned.</p>
     */
    @BeforeEach
    void pinHostProxyCapability() {
        interact(() -> controller.setSystemProxySupport(() -> true));
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
    void aModeChangedElsewhereIsPickedUpWithItsWarningWhenTheViewIsShown() {
        ComboBox<ProxyMode> combo = lookup("#proxyModeCombo").queryComboBox();
        Label warning = lookup("#proxyModeWarning").query();
        AppSettings settings = ServiceLocator.get(AppSettings.class);

        // Stand in for Settings or the MCP set_proxy_mode tool: the shared
        // settings instance changes without the dashboard being told.
        interact(() -> settings.setProxyMode(ProxyMode.TUN));
        interact(controller::onViewShown);

        assertThat(combo.getValue()).isEqualTo(ProxyMode.TUN);
        assertThat(warning.isVisible())
                .as("TUN captures all traffic; the dashboard must say so")
                .isTrue();

        interact(() -> settings.setProxyMode(ProxyMode.SYSTEM_PROXY));
        interact(controller::onViewShown);

        assertThat(combo.getValue()).isEqualTo(ProxyMode.SYSTEM_PROXY);
        assertThat(warning.isVisible()).isFalse();
    }

    @Test
    void theSelfSyncGuardIsReleasedSoAUserChangeStillPersists() {
        ComboBox<ProxyMode> combo = lookup("#proxyModeCombo").queryComboBox();
        AppSettings settings = ServiceLocator.get(AppSettings.class);

        interact(() -> settings.setProxyMode(ProxyMode.TUN));
        interact(controller::onViewShown);

        // The sync itself must not write back, but it must leave the listener
        // armed: a missing finally here would silently stop persisting every
        // later user change.
        interact(() -> combo.setValue(ProxyMode.SYSTEM_PROXY));

        assertThat(settings.getProxyMode())
                .as("a change the user makes after a sync must still be saved")
                .isEqualTo(ProxyMode.SYSTEM_PROXY);
    }
}
