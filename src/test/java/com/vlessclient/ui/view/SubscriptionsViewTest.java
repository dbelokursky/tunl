package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.Subscription;
import com.vlessclient.service.SubscriptionService;
import com.vlessclient.service.TestSubscriptionServices;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the redesigned SubscriptionsView — verifies the SplitPane-free
 * layout still wires up to the controller, and that the empty state and the
 * list are two views of one list.
 */
@UiTest
public class SubscriptionsViewTest extends ApplicationTest {

    @TempDir
    static Path tempDir;

    private SubscriptionService service;

    @Override
    public void start(Stage stage) throws Exception {
        // The graph's own service saves into the shared test data dir and
        // seals through the platform keychain; this one writes to a temp dir,
        // seals nothing and never fetches. TestFX restarts per method, so
        // each start gets a directory of its own.
        service = TestSubscriptionServices.quiet(tempDir.resolve("subs-" + System.nanoTime()));
        ServiceLocator.register(SubscriptionService.class, service);

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/SubscriptionsView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 1100, 720));
        stage.show();
    }

    @Test
    void controlsExist() {
        assertThat(lookup("#subscriptionListView").tryQuery()).isPresent();
        assertThat(lookup("#refreshAllButton").tryQuery()).isPresent();
        assertThat(lookup("#addSubscriptionButton").tryQuery()).isPresent();
        assertThat(lookup("#emptyState").tryQuery()).isPresent();
    }

    /**
     * Adding the first subscription through the service must swap the empty
     * state for the list, and removing the last must swap them back. Refresh
     * All is offered throughout: it is a no-op on an empty list, not an error.
     */
    @Test
    void theFirstSubscriptionSwapsTheEmptyStateForTheListAndTheLastRemovalSwapsBack() {
        VBox emptyState = lookup("#emptyState").query();
        ListView<Subscription> list = lookup("#subscriptionListView").query();
        Button refreshAll = lookup("#refreshAllButton").query();

        assertThat(service.getSubscriptions()).isEmpty();
        assertThat(emptyState.isVisible()).isTrue();
        assertThat(emptyState.isManaged()).isTrue();
        assertThat(list.isVisible()).isFalse();
        assertThat(list.isManaged()).isFalse();
        assertThat(refreshAll.isDisabled()).isFalse();

        service.addSubscription("Provider", "https://provider.example/sub");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(list.isVisible()).isTrue();
        assertThat(list.isManaged()).isTrue();
        assertThat(list.getItems()).extracting(Subscription::getName).containsExactly("Provider");
        assertThat(emptyState.isVisible()).isFalse();
        assertThat(emptyState.isManaged()).isFalse();
        assertThat(refreshAll.isDisabled()).isFalse();

        service.removeSubscription(service.getSubscriptions().getFirst().getId());
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(list.getItems()).isEmpty();
        assertThat(list.isVisible()).isFalse();
        assertThat(list.isManaged()).isFalse();
        assertThat(emptyState.isVisible()).isTrue();
        assertThat(emptyState.isManaged()).isTrue();
    }
}
