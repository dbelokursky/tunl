package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.Subscription;
import com.vlessclient.service.SubscriptionService;
import com.vlessclient.service.TestSubscriptionServices;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
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

    /** Whether the service was asked to remove a subscription on the FX thread. */
    private final AtomicReference<Boolean> removedOnFxThread = new AtomicReference<>();
    private final CountDownLatch removed = new CountDownLatch(1);

    @Override
    public void start(Stage stage) throws Exception {
        // The graph's own service saves into the shared test data dir and
        // seals through the platform keychain; this one writes to a temp dir,
        // seals nothing and never fetches. TestFX restarts per method, so
        // each start gets a directory of its own.
        service = TestSubscriptionServices.quiet(tempDir.resolve("subs-" + System.nanoTime()), () -> {
            removedOnFxThread.set(Platform.isFxApplicationThread());
            removed.countDown();
        });
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

    /**
     * Deleting asks first, then removes the subscription off the FX thread. The
     * removal waits for the lock a refresh holds while that refresh waits for
     * the FX thread: on the FX thread it froze the window, and the refresh's
     * timed-out change could still land after the delete.
     */
    @Test
    void deletingASubscriptionAsksThenRemovesItOffTheFxThread() throws Exception {
        service.addSubscription("Provider", "https://provider.example/sub");
        WaitForAsyncUtils.waitForFxEvents();
        Button delete = lookup((Node node) -> node instanceof Button button
                && I18n.get("button.delete").equals(button.getText())).queryButton();

        Platform.runLater(delete::fire);
        DialogPane confirm = awaitDialog("the delete confirmation", pane ->
                I18n.get("subscriptions.delete.confirm", "Provider").equals(pane.getHeaderText()));
        interact(() -> ((Button) confirm.lookupButton(ButtonType.OK)).fire());

        assertThat(removed.await(10, TimeUnit.SECONDS)).as("the subscription is removed").isTrue();
        assertThat(removedOnFxThread.get())
                .as("removing waits for the refresh lock, which froze the window on the FX thread")
                .isFalse();
        Await.until("the list to empty", () -> service.getSubscriptions().isEmpty(),
                Duration.ofSeconds(10));
    }

    private DialogPane awaitDialog(String what, Predicate<DialogPane> matches) {
        return Await.untilValue(what, () -> showingDialog(matches), Objects::nonNull,
                Duration.ofSeconds(10));
    }

    private DialogPane showingDialog(Predicate<DialogPane> matches) {
        AtomicReference<DialogPane> found = new AtomicReference<>();
        interact(() -> {
            for (Window window : Window.getWindows()) {
                if (window.isShowing() && window.getScene() != null
                        && window.getScene().getRoot().lookup(".dialog-pane")
                                instanceof DialogPane pane
                        && matches.test(pane)) {
                    found.set(pane);
                }
            }
        });
        return found.get();
    }
}
