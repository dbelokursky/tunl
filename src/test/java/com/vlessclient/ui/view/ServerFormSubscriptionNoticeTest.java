package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.SubscriptionService;
import com.vlessclient.service.TestSubscriptionServices;
import com.vlessclient.testing.TestServers;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * The server form says when a subscription owns the server it edits.
 *
 * <p>A refresh stores the server the provider sends in place of the one in
 * the list, keeping only its id and whether it is picked. Every edit made in
 * the form, a new name included, was gone after the next refresh, hourly by
 * default, whether or not the provider had changed anything, and nothing had
 * said so.</p>
 */
@UiTest
public class ServerFormSubscriptionNoticeTest extends ApplicationTest {

    @TempDir
    static Path tempDir;

    private ServerFormController controller;

    @Override
    public void start(Stage stage) throws Exception {
        SubscriptionService subscriptions =
                TestSubscriptionServices.quiet(tempDir.resolve("subs-" + System.nanoTime()));
        subscriptions.addSubscription("Provider", "https://provider.example/sub");
        subscriptions.getSubscriptions().getFirst().getServerIds().add("from-provider");
        ServiceLocator.register(SubscriptionService.class, subscriptions);

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ServerFormView.fxml"));
        Parent root = loader.load();
        controller = loader.getController();
        Scene scene = new Scene(root, 520, 650);
        scene.getStylesheets().setAll(ThemeCss.light());
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void aServerFromASubscriptionSaysItsNextRefreshReplacesTheEdits() {
        interact(() -> controller.setServerConfig(server("from-provider")));

        Label notice = lookup("#subscriptionNoticeLabel").queryAs(Label.class);
        assertThat(notice.isVisible()).isTrue();
        assertThat(notice.isManaged()).isTrue();
        assertThat(notice.getText())
                .isEqualTo(I18n.get("form.subscription.notice", "Provider"));
    }

    @Test
    void aServerOfTheUsersOwnSaysNothing() {
        interact(() -> controller.setServerConfig(server("added-by-hand")));

        Label notice = lookup("#subscriptionNoticeLabel").queryAs(Label.class);
        assertThat(notice.isVisible()).isFalse();
        assertThat(notice.isManaged()).isFalse();
    }

    @Test
    void aNewServerSaysNothing() {
        Label notice = lookup("#subscriptionNoticeLabel").queryAs(Label.class);
        assertThat(notice.isVisible()).isFalse();
        assertThat(notice.isManaged()).isFalse();
    }

    private static ServerConfig server(String id) {
        return TestServers.server()
                .id(id)
                .name("Tokyo")
                .protocol(Protocol.VLESS)
                .address("198.51.100.7")
                .port(443)
                .uuid("11111111-2222-3333-4444-555555555555")
                .build();
    }
}
