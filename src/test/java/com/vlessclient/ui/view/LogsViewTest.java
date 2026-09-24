package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.testing.Await;
import java.nio.file.Path;
import com.vlessclient.testing.UiTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Callback;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for LogsView — verifies the FXML wires up to the controller,
 * including the Download button and its {@code #onDownloadClicked} handler.
 * The Download action itself opens a native save dialog and is not triggered.
 */
@UiTest
public class LogsViewTest extends ApplicationTest {

    private Stage stage;

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/LogsView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 800, 600));
        stage.show();
        // The primary stage is shared by every test class in the fork. A class
        // that sized it explicitly (DashboardLayoutTest does) leaves those bounds
        // to be reapplied on the next show(), under a scroll this class asserts
        // on. sizeToScene() on the shown window fits it to this scene and drops
        // the explicit bounds, so this class inherits none and passes none on:
        // setting a size here instead broke the 500 px fit tests that follow.
        stage.sizeToScene();
    }

    @Test
    void toolbarControlsExist() {
        assertThat(lookup("#logLevelFilter").tryQuery()).isPresent();
        assertThat(lookup("#searchField").tryQuery()).isPresent();
        assertThat(lookup("#autoScrollCheckBox").tryQuery()).isPresent();
        assertThat(lookup("#downloadButton").tryQuery()).isPresent();
        assertThat(lookup("#clearButton").tryQuery()).isPresent();
        assertThat(lookup("#logListView").tryQuery()).isPresent();
    }

    @Test
    void autoScrollIsOnByDefault() {
        CheckBox autoScroll = lookup("#autoScrollCheckBox").query();
        assertThat(autoScroll.isSelected()).isTrue();
    }

    @Test
    void disablingAutoScrollKeepsTheViewportAnchoredWhenLinesArrive() {
        ListView<String> list = lookup("#logListView").query();
        CheckBox autoScroll = lookup("#autoScrollCheckBox").query();
        ObservableList<String> source = sourceOf(list);

        interact(() -> source.setAll(IntStream.range(0, 100)
                .mapToObj(i -> logLine(i))
                .toList()));
        WaitForAsyncUtils.waitForFxEvents();

        interact(() -> {
            list.scrollTo(source.size() - 1);
            autoScroll.setSelected(false);
        });
        WaitForAsyncUtils.waitForFxEvents();
        ViewportAnchor before = firstVisibleAnchor(list);

        // A real LogReader can enqueue many lines before the FX thread gets a
        // layout pulse. Keep the whole burst in one event to exercise that
        // ordering instead of draining runLater callbacks after every line.
        interact(() -> {
            for (int line = 0; line < 10; line++) {
                source.add(logLine(100 + line));
                source.removeFirst();
            }
        });
        WaitForAsyncUtils.waitForFxEvents();
        ViewportAnchor after = firstVisibleAnchor(list);

        assertThat(after.item()).isSameAs(before.item());
        assertThat(after.offset()).isCloseTo(
                before.offset(), org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void disablingAutoScrollKeepsTheViewportAnchoredWhenABatchArrives() {
        ListView<String> list = lookup("#logListView").query();
        CheckBox autoScroll = lookup("#autoScrollCheckBox").query();
        ObservableList<String> source = sourceOf(list);

        interact(() -> source.setAll(IntStream.range(0, 100)
                .mapToObj(i -> logLine(i))
                .toList()));
        WaitForAsyncUtils.waitForFxEvents();

        interact(() -> {
            list.scrollTo(source.size() - 1);
            autoScroll.setSelected(false);
        });
        WaitForAsyncUtils.waitForFxEvents();
        ViewportAnchor before = firstVisibleAnchor(list);

        // LogReader hands a burst over in one task: one addition, then one
        // removal from the front for the lines past the buffer's size.
        interact(() -> {
            source.addAll(IntStream.range(100, 110).mapToObj(i -> logLine(i)).toList());
            source.remove(0, 10);
        });
        WaitForAsyncUtils.waitForFxEvents();
        ViewportAnchor after = firstVisibleAnchor(list);

        assertThat(after.item()).isSameAs(before.item());
        assertThat(after.offset()).isCloseTo(
                before.offset(), org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void refilteringDoesNotMisclassifyTheChangeAsARingBufferTrim() {
        ListView<String> list = lookup("#logListView").query();
        CheckBox autoScroll = lookup("#autoScrollCheckBox").query();
        TextField search = lookup("#searchField").query();
        ObservableList<String> source = sourceOf(list);

        interact(() -> source.setAll(IntStream.range(0, 100)
                .mapToObj(i -> logLine(i))
                .toList()));
        WaitForAsyncUtils.waitForFxEvents();

        interact(() -> {
            list.scrollTo(source.size() - 1);
            autoScroll.setSelected(false);
        });
        WaitForAsyncUtils.waitForFxEvents();
        ViewportAnchor before = firstVisibleAnchor(list);

        // Every row matches, but FilteredList still reports setPredicate() as
        // a replace-from-zero change. That must not reset the viewport.
        interact(() -> search.setText("outbound"));
        WaitForAsyncUtils.waitForFxEvents();
        ViewportAnchor after = firstVisibleAnchor(list);

        assertThat(after.item()).isSameAs(before.item());
        assertThat(after.offset()).isCloseTo(
                before.offset(), org.assertj.core.data.Offset.offset(0.5));
    }

    /**
     * MainViewController caches views and swaps them out of the scene. With the
     * ring buffer full, every line shifts every row, so a cached list that kept
     * its rows rebuilt all of them on each line for as long as the app ran.
     */
    @Test
    void rowsAreNotRebuiltWhileTheViewIsNavigatedAway() {
        ListView<String> list = lookup("#logListView").query();
        ObservableList<String> source = sourceOf(list);
        fillRingBuffer(source);
        AtomicInteger rowUpdates = countRowUpdates(list);
        String selected = source.get(990);
        interact(() -> list.getSelectionModel().select(990));

        Scene scene = list.getScene();
        Parent view = scene.getRoot();
        interact(() -> scene.setRoot(new StackPane()));
        WaitForAsyncUtils.waitForFxEvents();
        rowUpdates.set(0);

        appendTrimming(source, 20);
        assertThat(rowUpdates).hasValue(0);

        // Back on screen it follows the tail again, as auto-scroll promises,
        // and the row the user had selected is still selected.
        interact(() -> scene.setRoot(view));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(lastVisibleIndex(list)).isEqualTo(list.getItems().size() - 1);
        assertThat(list.getSelectionModel().getSelectedItems()).containsExactly(selected);
    }

    /**
     * Installing the core from the dashboard registers a new engine after this
     * view was built, and the view went on showing the log of the engine that
     * had no binary: empty, for the rest of the run.
     */
    @Test
    void theLogOfAnEngineRegisteredLaterIsShownWhenTheViewComesBack() {
        ListView<String> list = lookup("#logListView").query();
        SingBoxEngine previous = ServiceLocator.find(SingBoxEngine.class).orElse(null);
        SingBoxEngine installed = new SingBoxEngine(Path.of("target", "no-such-sing-box"));
        try {
            interact(() -> stage.hide());
            ServiceLocator.register(SingBoxEngine.class, installed);
            interact(() -> installed.getLogLines().add("INFO sing-box started (0.10s)"));

            interact(() -> stage.show());
            WaitForAsyncUtils.waitForFxEvents();

            assertThat(list.getItems()).containsExactly("INFO sing-box started (0.10s)");
            interact(() -> installed.getLogLines().add("INFO inbound/socks[socks-in]: ready"));
            assertThat(list.getItems()).as("and it keeps following it").hasSize(2);
        } finally {
            if (previous != null) {
                ServiceLocator.register(SingBoxEngine.class, previous);
            }
        }
    }

    /** Closing the window to the tray hides the stage and leaves the scene on it. */
    @Test
    void rowsAreNotRebuiltWhileTheWindowIsHidden() {
        ListView<String> list = lookup("#logListView").query();
        ObservableList<String> source = sourceOf(list);
        fillRingBuffer(source);
        AtomicInteger rowUpdates = countRowUpdates(list);

        interact(() -> stage.hide());
        WaitForAsyncUtils.waitForFxEvents();
        rowUpdates.set(0);

        appendTrimming(source, 20);
        assertThat(rowUpdates).hasValue(0);

        interact(() -> stage.show());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(lastVisibleIndex(list)).isEqualTo(list.getItems().size() - 1);
    }

    @Test
    void aReaderWithAutoScrollOffComesBackToTheSameLine() {
        ListView<String> list = lookup("#logListView").query();
        CheckBox autoScroll = lookup("#autoScrollCheckBox").query();
        ObservableList<String> source = sourceOf(list);
        fillRingBuffer(source);
        interact(() -> {
            list.scrollTo(500);
            autoScroll.setSelected(false);
        });
        WaitForAsyncUtils.waitForFxEvents();
        ViewportAnchor before = firstVisibleAnchor(list);

        Scene scene = list.getScene();
        Parent view = scene.getRoot();
        interact(() -> scene.setRoot(new StackPane()));
        WaitForAsyncUtils.waitForFxEvents();
        appendTrimming(source, 20);
        interact(() -> scene.setRoot(view));
        WaitForAsyncUtils.waitForFxEvents();

        ViewportAnchor after = firstVisibleAnchor(list);
        assertThat(after.item()).isSameAs(before.item());
        assertThat(after.offset()).isCloseTo(
                before.offset(), org.assertj.core.data.Offset.offset(0.5));
    }

    /**
     * A window can come back with other bounds than it left with; on the
     * Windows runner the list landed seven rows short of the tail. A scroll
     * issued against the viewport it had before showing must still end there.
     */
    @Test
    void theTailIsShownWhenTheWindowComesBackAtAnotherSize() {
        ListView<String> list = lookup("#logListView").query();
        ObservableList<String> source = sourceOf(list);
        fillRingBuffer(source);

        interact(() -> stage.hide());
        WaitForAsyncUtils.waitForFxEvents();
        appendTrimming(source, 20);
        try {
            interact(() -> {
                stage.setHeight(stage.getHeight() - 150);
                stage.show();
            });
            WaitForAsyncUtils.waitForFxEvents();

            assertThat(lastVisibleIndex(list)).isEqualTo(list.getItems().size() - 1);
        } finally {
            // An explicit height would follow the shared stage into the next class.
            interact(() -> stage.sizeToScene());
        }
    }

    /**
     * Window.show() lays the scene out before it tells anyone the window is
     * showing, so the list is measured against the empty stand-in first and a
     * restore queued right after would scroll a flow that holds no rows.
     */
    @Test
    void aReaderWithAutoScrollOffComesBackToTheSameLineAfterTheWindowWasHidden() {
        ListView<String> list = lookup("#logListView").query();
        CheckBox autoScroll = lookup("#autoScrollCheckBox").query();
        ObservableList<String> source = sourceOf(list);
        fillRingBuffer(source);
        interact(() -> {
            list.scrollTo(500);
            autoScroll.setSelected(false);
        });
        WaitForAsyncUtils.waitForFxEvents();
        ViewportAnchor before = firstVisibleAnchor(list);

        interact(() -> stage.hide());
        WaitForAsyncUtils.waitForFxEvents();
        appendTrimming(source, 20);
        interact(() -> stage.show());
        WaitForAsyncUtils.waitForFxEvents();

        ViewportAnchor after = firstVisibleAnchor(list);
        assertThat(after.item()).isSameAs(before.item());
        assertThat(after.offset()).isCloseTo(
                before.offset(), org.assertj.core.data.Offset.offset(0.5));
    }

    /** Away long enough for the line to be trimmed: the oldest lines are where it was. */
    @Test
    void aReaderWhoseLineWasTrimmedAwayComesBackToTheOldestLine() {
        ListView<String> list = lookup("#logListView").query();
        CheckBox autoScroll = lookup("#autoScrollCheckBox").query();
        ObservableList<String> source = sourceOf(list);
        fillRingBuffer(source);
        interact(() -> {
            list.scrollTo(500);
            autoScroll.setSelected(false);
        });
        WaitForAsyncUtils.waitForFxEvents();

        Scene scene = list.getScene();
        Parent view = scene.getRoot();
        interact(() -> scene.setRoot(new StackPane()));
        WaitForAsyncUtils.waitForFxEvents();
        appendTrimming(source, 1000);
        interact(() -> scene.setRoot(view));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(firstVisibleIndex(list)).isZero();
    }

    /** The engine keeps 1000 lines; a long-running instance always has them. */
    /**
     * Clear emptied the log on one click, and the log is the only copy of the
     * core's output the app keeps. It asks first, with Cancel as the button
     * Enter presses, and only the button named Clear clears.
     */
    @Test
    void clearingTheLogAsksFirstAndOnlyItsOwnButtonClearsIt() {
        ListView<String> list = lookup("#logListView").query();
        ObservableList<String> source = sourceOf(list);
        interact(() -> source.setAll(logLine(0), logLine(1)));
        Button clear = lookup("#clearButton").query();
        String question = I18n.get("logs.clear.confirm");

        Platform.runLater(clear::fire);
        DialogPane confirm = awaitDialogAsking(question);
        Button cancel = buttonFor(confirm, ButtonBar.ButtonData.CANCEL_CLOSE);
        assertThat(cancel.isDefaultButton()).as("Enter keeps the log").isTrue();
        assertThat(buttonFor(confirm, ButtonBar.ButtonData.OK_DONE).isDefaultButton()).isFalse();
        interact(cancel::fire);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(source).hasSize(2);

        Platform.runLater(clear::fire);
        DialogPane again = awaitDialogAsking(question);
        interact(buttonFor(again, ButtonBar.ButtonData.OK_DONE)::fire);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(source).isEmpty();
    }

    /** The showing dialog that asks {@code question}, once one does. */
    private DialogPane awaitDialogAsking(String question) {
        return Await.untilValue("a dialog asking: " + question, () -> {
            AtomicReference<DialogPane> found = new AtomicReference<>();
            interact(() -> {
                for (Window window : Window.getWindows()) {
                    if (window.isShowing() && window.getScene() != null
                            && window.getScene().getRoot() instanceof DialogPane pane
                            && question.equals(pane.getHeaderText())) {
                        found.set(pane);
                    }
                }
            });
            return found.get();
        }, Objects::nonNull, Duration.ofSeconds(10));
    }

    private static Button buttonFor(DialogPane dialog, ButtonBar.ButtonData data) {
        return dialog.getButtonTypes().stream()
                .filter(type -> type.getButtonData() == data)
                .map(type -> (Button) dialog.lookupButton(type))
                .findFirst()
                .orElseThrow();
    }

    private void fillRingBuffer(ObservableList<String> source) {
        interact(() -> source.setAll(IntStream.range(0, 1000)
                .mapToObj(i -> logLine(i))
                .toList()));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Lines arriving at a full buffer: each one added drops the oldest. */
    private void appendTrimming(ObservableList<String> source, int count) {
        interact(() -> {
            for (int line = 0; line < count; line++) {
                source.add(logLine(1000 + line));
                source.removeFirst();
            }
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Counts every row that is handed a different line, through the real cells. */
    private AtomicInteger countRowUpdates(ListView<String> list) {
        AtomicInteger updates = new AtomicInteger();
        interact(() -> {
            Callback<ListView<String>, ListCell<String>> rows = list.getCellFactory();
            list.setCellFactory(view -> {
                ListCell<String> cell = rows.call(view);
                cell.itemProperty().addListener((obs, was, is) -> updates.incrementAndGet());
                return cell;
            });
            list.layout();
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(updates.get()).as("rows rendered while on screen").isPositive();
        return updates;
    }

    @SuppressWarnings("unchecked")
    private int firstVisibleIndex(ListView<String> list) {
        AtomicInteger index = new AtomicInteger(-1);
        interact(() -> {
            list.layout();
            VirtualFlow<ListCell<String>> flow =
                    (VirtualFlow<ListCell<String>>) list.lookup(".virtual-flow");
            ListCell<String> cell = flow == null ? null : flow.getFirstVisibleCell();
            index.set(cell == null ? -1 : cell.getIndex());
        });
        return index.get();
    }

    @SuppressWarnings("unchecked")
    private int lastVisibleIndex(ListView<String> list) {
        AtomicInteger index = new AtomicInteger(-1);
        interact(() -> {
            list.layout();
            VirtualFlow<ListCell<String>> flow =
                    (VirtualFlow<ListCell<String>>) list.lookup(".virtual-flow");
            ListCell<String> cell = flow == null ? null : flow.getLastVisibleCell();
            index.set(cell == null ? -1 : cell.getIndex());
        });
        return index.get();
    }

    @SuppressWarnings("unchecked")
    private static ObservableList<String> sourceOf(ListView<String> list) {
        FilteredList<String> filtered = (FilteredList<String>) list.getItems();
        return (ObservableList<String>) filtered.getSource();
    }

    private static String logLine(int index) {
        return "+0200 2026-08-26 17:15:42 INFO [663785960 168ms] "
                + "outbound/vless[srv-08c49d80-a91c-4a75-b4d8-e446b7b714cd]: "
                + "outbound connection to 172.64.155.209:443 line " + index;
    }

    @SuppressWarnings("unchecked")
    private ViewportAnchor firstVisibleAnchor(ListView<String> list) {
        AtomicReference<ViewportAnchor> anchor = new AtomicReference<>();
        interact(() -> {
            VirtualFlow<ListCell<String>> flow =
                    (VirtualFlow<ListCell<String>>) list.lookup(".virtual-flow");
            ListCell<String> cell = flow.getFirstVisibleCell();
            Bounds flowBounds = flow.localToScene(flow.getBoundsInLocal());
            Bounds cellBounds = cell.localToScene(cell.getBoundsInLocal());
            anchor.set(new ViewportAnchor(
                    cell.getItem(), cellBounds.getMinY() - flowBounds.getMinY()));
        });
        return anchor.get();
    }

    private record ViewportAnchor(String item, double offset) {
    }

    /**
     * Download, Save diagnostics and Clear show only an icon, and a screen
     * reader read out nothing for them. Each names its action, in the current
     * language.
     */
    @Test
    void theIconButtonsCarryTheirNamesInTheCurrentLanguage() {
        try {
            for (Locale locale : List.of(Locale.ENGLISH, Locale.of("ru"))) {
                interact(() -> I18n.setLocale(locale));
                assertThat(iconButtonNames())
                        .as("the names of Download, Save diagnostics and Clear in %s", locale)
                        .containsExactly(I18n.get("logs.download.tooltip"),
                                I18n.get("logs.diagnostics.tooltip"),
                                I18n.get("logs.clear.tooltip"));
            }
        } finally {
            interact(() -> I18n.setLocale(Locale.ENGLISH));
        }
    }

    private List<String> iconButtonNames() {
        List<String> names = new ArrayList<>();
        for (String id : List.of("#downloadButton", "#diagnosticsButton", "#clearButton")) {
            Button button = lookup(id).query();
            names.add(button.getAccessibleText());
        }
        return names;
    }
}
