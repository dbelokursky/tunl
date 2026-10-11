package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.testing.UiTest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

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
@UiTest
public class DashboardSystemProxyWarningTest extends ApplicationTest {

    private DashboardViewController controller;

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
        awaitVerdict();

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
        awaitVerdict();

        assertThat(warning.isVisible()).isFalse();
    }

    /**
     * On Linux the answer is a {@code gsettings} process, and it was asked on
     * the FX thread: while the dashboard was built at startup, and again on
     * each return to it until it had answered. A slow or hung dconf froze the
     * window for as long as the process took.
     */
    @Test
    void theProxyStoreIsAskedWithoutHoldingTheWindow() throws Exception {
        Label warning = lookup("#proxyModeWarning").query();
        AppSettings settings = ServiceLocator.get(AppSettings.class);
        CountDownLatch asked = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        AtomicBoolean onFxThread = new AtomicBoolean();

        interact(() -> {
            settings.setProxyMode(ProxyMode.SYSTEM_PROXY);
            controller.setSystemProxySupport(() -> {
                onFxThread.set(Platform.isFxApplicationThread());
                asked.countDown();
                try {
                    answer.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return false;
            });
            controller.onViewShown();
        });

        assertThat(asked.await(5, TimeUnit.SECONDS)).as("the store was asked").isTrue();
        assertThat(onFxThread).as("gsettings must not run on the FX thread").isFalse();
        answer.countDown();
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, warning::isVisible);
    }

    /** Waits until the dashboard holds the answer and has shown it. */
    private void awaitVerdict() {
        try {
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () ->
                    WaitForAsyncUtils.asyncFx(controller::systemProxyVerdict).get() != null);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("the dashboard never received the probe's answer", e);
        }
        WaitForAsyncUtils.waitForFxEvents();
    }
}
