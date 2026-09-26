package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.Subscription;
import com.vlessclient.service.SubscriptionService;
import com.vlessclient.service.TestSubscriptionServices;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Dashboard shows the notice of a subscription near its end, as the
 * service lists it; {@code SubscriptionEndSectionTest} covers the wording.
 */
@UiTest
public class DashboardSubscriptionEndBannerTest extends ApplicationTest {

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void registerASubscriptionEndingSoon() {
        SubscriptionService service = TestSubscriptionServices.quiet(tempDir);
        Subscription sub = new Subscription();
        sub.setName("Nordic Nodes");
        sub.setExpiresAt(Instant.now().plus(Duration.ofDays(2)).getEpochSecond());
        service.getSubscriptions().add(sub);
        ServiceLocator.register(SubscriptionService.class, service);
    }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/DashboardView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 640, 480));
        stage.show();
    }

    @Test
    void aSubscriptionEndingSoonIsNamedOnTheDashboard() {
        HBox banner = lookup("#subscriptionEndBanner").query();
        Label label = lookup("#subscriptionEndLabel").query();

        assertThat(banner.isVisible()).isTrue();
        assertThat(banner.isManaged()).isTrue();
        assertThat(label.getText()).contains("Nordic Nodes");
    }
}
