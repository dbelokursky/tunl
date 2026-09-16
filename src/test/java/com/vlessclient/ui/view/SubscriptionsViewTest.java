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
import java.util.concurrent.atomic.AtomicInteger;
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
import javafx.scene.control.Label;
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

    private TestSubscriptionServices.Scripted service;

    /** The view's controller, as FXMLLoader built it. */
    private Object controller;

    /** Whether the service was asked to remove a subscription on the FX thread. */
    private final AtomicReference<Boolean> removedOnFxThread = new AtomicReference<>();
    private final CountDownLatch removed = new CountDownLatch(1);

    @Override
    public void start(Stage stage) throws Exception {
        // The graph's own service saves into the shared test data dir and
        // seals through the platform keychain; this one writes to a temp dir,
        // seals nothing and never fetches. TestFX restarts per method, so
        // each start gets a directory of its own.
        service = TestSubscriptionServices.scripted(tempDir.resolve("subs-" + System.nanoTime()));
        service.beforeRemove(() -> {
            removedOnFxThread.set(Platform.isFxApplicationThread());
            removed.countDown();
        });
        ServiceLocator.register(SubscriptionService.class, service);

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/SubscriptionsView.fxml"));
        Parent root = loader.load();
        controller = loader.getController();
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
        Button delete = rowButton("button.delete");

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

    /**
     * A refresh that threw ended its thread without a word: no dialog, no
     * redraw. Its button also stayed ready, so a second click started a
     * second refresh of the same subscription.
     */
    @Test
    void aRefreshThatFailsIsReportedAndItsButtonIsOfferedAgain() {
        service.addSubscription("Provider", "https://provider.example/sub");
        WaitForAsyncUtils.waitForFxEvents();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger refreshes = new AtomicInteger();
        service.onRefresh(id -> {
            refreshes.incrementAndGet();
            awaitQuietly(release);
            throw new IllegalStateException("could not apply the refreshed servers");
        });
        try {
            interact(rowRefreshButton()::fire);
            Await.until("the refresh to start", () -> refreshes.get() == 1, Duration.ofSeconds(10));
            interact(rowRefreshButton()::fire);
            WaitForAsyncUtils.waitForFxEvents();

            assertThat(rowRefreshButton().isDisabled()).as("the row's Refresh while it runs").isTrue();
        } finally {
            release.countDown();
        }
        DialogPane failure = awaitDialog("the refresh failure", pane ->
                I18n.get("subscriptions.refresh.failed").equals(pane.getHeaderText()));
        assertThat(failure.getContentText()).isEqualTo("could not apply the refreshed servers");
        interact(() -> ((Button) failure.lookupButton(ButtonType.OK)).fire());

        Await.until("the row's Refresh to be offered again",
                () -> !rowRefreshButton().isDisabled(), Duration.ofSeconds(10));
        assertThat(refreshes.get()).as("refreshes started").isEqualTo(1);
    }

    /** Refresh All started again on every click while the first run was still going. */
    @Test
    void refreshAllRunsOnceUntilItHasFinished() {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        service.onRefreshAll(() -> {
            runs.incrementAndGet();
            awaitQuietly(release);
        });
        Button refreshAll = lookup("#refreshAllButton").query();
        try {
            interact(refreshAll::fire);
            Await.until("Refresh All to start", () -> runs.get() == 1, Duration.ofSeconds(10));
            interact(refreshAll::fire);
            WaitForAsyncUtils.waitForFxEvents();

            assertThat(refreshAll.isDisabled()).as("Refresh All while it runs").isTrue();
        } finally {
            release.countDown();
        }
        Await.until("Refresh All to be offered again", () -> !refreshAll.isDisabled(),
                Duration.ofSeconds(10));
        assertThat(runs.get()).as("runs started").isEqualTo(1);
    }

    /**
     * A refresh records its outcome in the subscription itself, and the hourly
     * one tells no view. The cached list kept an old timestamp and no error,
     * even after a provider's token had expired overnight.
     */
    @Test
    void shownAgainTheRowsShowWhatARefreshRecordedMeanwhile() {
        service.addSubscription("Provider", "https://provider.example/sub");
        WaitForAsyncUtils.waitForFxEvents();
        String shown = I18n.get("subscriptions.last.error", "401 Unauthorized");
        // What the hourly refresh does: off the FX thread, and unannounced.
        service.getSubscriptions().getFirst().setLastError("401 Unauthorized");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(showsLabel(shown)).as("before the view is shown again").isFalse();

        interact(() -> ((ViewShownAware) controller).onViewShown());

        assertThat(showsLabel(shown)).isTrue();
    }

    /**
     * A keyed failure has to be rendered when the row is drawn: the point of
     * storing the key rather than the sentence is that the reader sees it in
     * the language the app is in now.
     */
    @Test
    void aKeyedFailureIsRenderedInTheCurrentLanguage() {
        service.addSubscription("Provider", "https://provider.example/sub");
        WaitForAsyncUtils.waitForFxEvents();
        service.getSubscriptions().getFirst()
                .recordFailure("subscriptions.error.no.links", java.util.List.of());

        interact(() -> ((ViewShownAware) controller).onViewShown());

        assertThat(showsLabel(I18n.get("subscriptions.last.error",
                I18n.get("subscriptions.error.no.links")))).isTrue();
    }

    private Button rowRefreshButton() {
        return rowButton("button.refresh");
    }

    /**
     * The row's button labelled with {@code key}, waited for: a ListView builds
     * its cells during layout, a pulse after the subscription lands, so a query
     * that runs straight after the change can find nothing.
     */
    private Button rowButton(String key) {
        String text = I18n.get(key);
        return Await.untilValue("the row's \"" + text + "\" button",
                () -> lookup((Node node) -> node instanceof Button button
                        && text.equals(button.getText())).tryQueryAs(Button.class).orElse(null),
                Objects::nonNull, Duration.ofSeconds(10));
    }

    private boolean showsLabel(String text) {
        return lookup((Node node) -> node instanceof Label label
                && text.equals(label.getText())).tryQuery().isPresent();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
