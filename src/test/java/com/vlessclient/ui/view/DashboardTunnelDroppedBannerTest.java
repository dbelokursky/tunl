package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.service.ConnectionService;
import com.vlessclient.testing.ThreadDump;
import com.vlessclient.testing.UiTest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * The Dashboard's notice for a tunnel that dropped where restarting it would
 * raise an elevation prompt again. Recovery leaves that reconnect to the user,
 * so the notice is the only place it can be made from the Dashboard.
 */
@UiTest
public class DashboardTunnelDroppedBannerTest extends ApplicationTest {

    private static final ReadOnlyBooleanWrapper NEEDED = new ReadOnlyBooleanWrapper();
    private static final AtomicInteger RECONNECTS = new AtomicInteger();
    private static final AtomicBoolean ON_FX_THREAD = new AtomicBoolean();
    private static final CountDownLatch RECONNECTED = new CountDownLatch(1);

    @BeforeAll
    static void registerTheService() {
        ServiceLocator.register(ConnectionService.class,
                new ConnectionService(null, null, null, null) {
                    @Override
                    public ReadOnlyBooleanProperty reconnectNeededProperty() {
                        return NEEDED.getReadOnlyProperty();
                    }

                    @Override
                    public ConnectAttempt reconnect(ProxyMode modeOverride) {
                        ON_FX_THREAD.set(Platform.isFxApplicationThread());
                        RECONNECTS.incrementAndGet();
                        RECONNECTED.countDown();
                        return new ConnectAttempt(Outcome.CANCELLED, null);
                    }
                });
    }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/DashboardView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 640, 480));
        stage.show();
    }

    @Test
    void theNoticeRestartsTheTunnelOnlyWhenTheUserAsks() throws Exception {
        HBox banner = lookup("#tunnelDroppedBanner").query();
        Label label = lookup("#tunnelDroppedLabel").query();
        Button button = lookup("#tunnelDroppedButton").query();
        assertThat(banner.isVisible()).isFalse();
        assertThat(banner.isManaged()).isFalse();

        interact(() -> NEEDED.set(true));

        assertThat(banner.isVisible()).isTrue();
        assertThat(banner.isManaged()).isTrue();
        assertThat(label.getText()).isNotBlank();
        assertThat(button.getText()).isNotBlank();

        interact(button::fire);
        assertThat(RECONNECTED.await(10, TimeUnit.SECONDS))
                .withFailMessage(() -> "the button did not restart the tunnel; registered: "
                        + ServiceLocator.find(ConnectionService.class)
                                .map(found -> found.getClass().getName()).orElse("none")
                        + "\n" + ThreadDump.forBackgroundWork())
                .isTrue();
        assertThat(RECONNECTS).hasValue(1);
        assertThat(ON_FX_THREAD)
                .as("a restart waits for the elevation prompt; on the FX thread it would "
                        + "freeze the window behind it")
                .isFalse();

        interact(() -> NEEDED.set(false));

        assertThat(banner.isVisible()).isFalse();
        assertThat(banner.isManaged()).isFalse();
    }
}
