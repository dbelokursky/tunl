package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.Subscription;
import com.vlessclient.service.SubscriptionService;
import com.vlessclient.service.TestSubscriptionServices;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.ThreadDump;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
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

    /**
     * How long a wait for background work may take before it counts as work
     * that never ran.
     *
     * <p>Ten seconds was not enough on a loaded Windows runner: four runs in
     * one evening failed here and in {@code ViewDialogThemeTest}, each on a
     * ten-second wait, with the log showing ten seconds in which nothing at
     * all happened — the work started after them and every rerun passed. What
     * the waits guard is work that never runs, and that still fails; it now
     * takes longer to say so.</p>
     */
    private static final Duration PATIENCE = Duration.ofSeconds(30);

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

    /**
     * A subscription added without a name shows its host until the provider
     * names it, and what the provider tells its users shows on its row. The
     * row showed an empty name, and the provider's message only when it
     * declined a device.
     */
    @Test
    void aRowShowsTheHostOfAnUnnamedSubscriptionAndTheProvidersAnnouncement() {
        service.addSubscription("", "https://provider.example/sub?token=0123456789abcdef");
        WaitForAsyncUtils.waitForFxEvents();
        Subscription sub = service.getSubscriptions().getFirst();
        ListView<Subscription> list = lookup("#subscriptionListView").query();

        interact(() -> {
            sub.setAnnounce("Продлите подписку до 1 октября");
            list.refresh();
        });

        assertThat(lookup(".label").queryAllAs(Label.class)).extracting(Label::getText)
                .contains("provider.example",
                        I18n.get("subscriptions.announce", "Продлите подписку до 1 октября"));
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
        pressRowButtonWithoutWaiting("button.delete");
        DialogPane confirm = awaitDialog("the delete confirmation", pane ->
                I18n.get("subscriptions.delete.confirm", "Provider").equals(pane.getHeaderText()));
        ButtonType delete = confirm.getButtonTypes().stream()
                .filter(type -> type.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                .findFirst().orElseThrow();
        interact(() -> ((Button) confirm.lookupButton(delete)).fire());

        assertThat(removed.await(PATIENCE.toSeconds(), TimeUnit.SECONDS))
                .withFailMessage(() -> "the subscription was not removed; registered: "
                        + ServiceLocator.find(SubscriptionService.class)
                                .map(found -> found == service ? "this test's" : "another")
                                .orElse("none")
                        + "\n" + ThreadDump.forBackgroundWork())
                .isTrue();
        assertThat(removedOnFxThread.get())
                .as("removing waits for the refresh lock, which froze the window on the FX thread")
                .isFalse();
        Await.until("the list to empty", () -> service.getSubscriptions().isEmpty(),
                PATIENCE);
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
            pressRowButton("button.refresh");
            Await.until("the refresh to start", () -> refreshes.get() == 1, PATIENCE);
            pressRowButton("button.refresh");
            WaitForAsyncUtils.waitForFxEvents();

            assertThat(onFx(() -> findRowButton("button.refresh").isDisabled()))
                    .as("the row's Refresh while it runs").isTrue();
        } finally {
            release.countDown();
        }
        DialogPane failure = awaitDialog("the refresh failure", pane ->
                I18n.get("subscriptions.refresh.failed").equals(pane.getHeaderText()));
        assertThat(failure.getContentText()).isEqualTo("could not apply the refreshed servers");
        interact(() -> ((Button) failure.lookupButton(ButtonType.OK)).fire());

        Await.until("the row's Refresh to be offered again", () -> onFx(() -> {
            Button refresh = findRowButton("button.refresh");
            return refresh != null && !refresh.isDisabled();
        }), PATIENCE);
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
            Await.until("Refresh All to start", () -> runs.get() == 1, PATIENCE);
            interact(refreshAll::fire);
            WaitForAsyncUtils.waitForFxEvents();

            assertThat(refreshAll.isDisabled()).as("Refresh All while it runs").isTrue();
        } finally {
            release.countDown();
        }
        Await.until("Refresh All to be offered again", () -> !refreshAll.isDisabled(),
                PATIENCE);
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
        // The row has to be drawn before the refresh records anything: drawn
        // after, it shows the error and the check below fails, as it did on
        // #327's macOS runner. A ListView draws its rows in a pulse, and a
        // wait for FX events does not wait for one; with the pulse slowed to
        // 2 Hz (-Djavafx.animation.pulse=2) the row was not there yet after
        // such a wait in 33 runs of 40, and in 5 it came up with the error.
        // So the row's button, and then the end of the pulse that drew it:
        // the list draws its first row twice in that pulse.
        awaitRowButton("button.refresh");
        WaitForAsyncUtils.waitForFxEvents();
        String shown = I18n.get("subscriptions.last.error", "401 Unauthorized");
        // What the hourly refresh does: off the FX thread, and unannounced.
        service.getSubscriptions().getFirst().setLastError("401 Unauthorized");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(showsLabel(shown)).as("before the view is shown again").isFalse();

        interact(() -> ((ViewShownAware) controller).onViewShown());

        awaitLabel(shown);
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

        awaitLabel(I18n.get("subscriptions.last.error",
                I18n.get("subscriptions.error.no.links")));
    }

    /**
     * Presses the row's button labelled {@code key}, found and pressed in one
     * action on the FX thread.
     *
     * <p>Resolved on the test thread and fired afterwards, the press could
     * land on a button the list had recycled out of its cells meanwhile: the
     * handler did nothing, the work it was to start never started, and the
     * test waited for that work until it gave up. On the Windows runners it
     * cost one run in two, in five tests at once, and the wait's length was
     * the only thing the failure measured.</p>
     */
    private void pressRowButton(String key) {
        awaitRowButton(key);
        interact(() -> {
            Button button = findRowButton(key);
            assertThat(button).as("the row's \"%s\" button when it is pressed", I18n.get(key))
                    .isNotNull();
            button.fire();
        });
    }

    /**
     * The same press, without waiting for it to return: the dialog it opens
     * waits for the user, so an {@code interact} around it would not come
     * back.
     */
    private void pressRowButtonWithoutWaiting(String key) {
        awaitRowButton(key);
        Platform.runLater(() -> {
            Button button = findRowButton(key);
            if (button != null) {
                button.fire();
            }
        });
    }

    /** Waits for the row's button to be drawn, looking on the FX thread. */
    private void awaitRowButton(String key) {
        Await.until("the row's \"" + I18n.get(key) + "\" button",
                () -> onFx(() -> findRowButton(key)) != null, PATIENCE);
    }

    /** That button as the scene has it now; call it on the FX thread. */
    private Button findRowButton(String key) {
        String text = I18n.get(key);
        return lookup((Node node) -> node instanceof Button button
                && text.equals(button.getText())).tryQueryAs(Button.class).orElse(null);
    }

    /** Runs {@code work} on the FX thread and hands back what it returned. */
    private <T> T onFx(Supplier<T> work) {
        List<T> result = new ArrayList<>(1);
        interact(() -> result.add(work.get()));
        return result.get(0);
    }


    /**
     * Waits for a label with this text. Shown again, the view asks its list to
     * refresh, and a ListView redraws its cells in the next layout pass rather
     * than at once: checked straight after, the row still had its old text
     * on a loaded macOS runner (test-macos of #324), as #310 found for the
     * row's buttons.
     */
    private void awaitLabel(String text) {
        Await.until("a label reading \"" + text + "\"", () -> showsLabel(text),
                PATIENCE);
    }

    private boolean showsLabel(String text) {
        return lookup((Node node) -> node instanceof Label label
                && text.equals(label.getText())).tryQuery().isPresent();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(PATIENCE.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private DialogPane awaitDialog(String what, Predicate<DialogPane> matches) {
        return Await.untilValue(what, () -> showingDialog(matches), Objects::nonNull,
                PATIENCE);
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
