package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.service.ConnectionService;
import com.vlessclient.service.ConnectionService.SkippedServer;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.testing.UiTest;
import java.util.List;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * The Dashboard's notice for servers the core refused. Leaving them out keeps
 * one broken subscription entry from blocking every other server; saying so is
 * what keeps a server that never gets used from looking like a mystery.
 */
@UiTest
public class DashboardSkippedServersBannerTest extends ApplicationTest {

    private static final ReadOnlyObjectWrapper<List<SkippedServer>> SKIPPED =
            new ReadOnlyObjectWrapper<>(List.of());

    @BeforeAll
    static void registerTheService() {
        ServiceLocator.register(ConnectionService.class,
                new ConnectionService(null, null, null, SingBoxEngine.withoutCore()) {
                    @Override
                    public ReadOnlyObjectProperty<List<SkippedServer>> skippedServersProperty() {
                        return SKIPPED.getReadOnlyProperty();
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
    void theNoticeNamesTheRefusedServerAndGoesWhenTheListEmpties() {
        HBox banner = lookup("#skippedServersBanner").query();
        Label label = lookup("#skippedServersLabel").query();
        assertThat(banner.isVisible()).isFalse();

        interact(() -> SKIPPED.set(List.of(new SkippedServer(
                "srv-2", "Frankfurt", "unsupported flow: xtls-rprx-direct"))));

        assertThat(banner.isVisible()).isTrue();
        assertThat(banner.isManaged()).isTrue();
        assertThat(label.getText()).contains("Frankfurt", "unsupported flow: xtls-rprx-direct");

        interact(() -> SKIPPED.set(List.of()));

        assertThat(banner.isVisible()).isFalse();
        assertThat(banner.isManaged()).isFalse();
    }
}
