package com.vlessclient.ui.view;

import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.skin.VirtualFlow;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for LogsView — verifies the FXML wires up to the controller,
 * including the Download button and its {@code #onDownloadClicked} handler.
 * The Download action itself opens a native save dialog and is not triggered.
 */
public class LogsViewTest extends ApplicationTest {

    @BeforeAll
    static void setupHeadless() {
        System.setProperty("testfx.robot", "glass");
        System.setProperty("testfx.headless", "true");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        System.setProperty("java.awt.headless", "true");
    }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/LogsView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 800, 600));
        stage.show();
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
}
