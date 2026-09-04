package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.service.DiagnosticsBundle;
import com.vlessclient.service.LogLineFormatter;
import com.vlessclient.service.SingBoxEngine;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for the Logs view.
 * Displays sing-box log output with level filtering and search.
 */
public class LogsViewController {

    private static final Logger log = LoggerFactory.getLogger(LogsViewController.class);

    @FXML private Label titleLabel;
    @FXML private ComboBox<String> logLevelFilter;
    @FXML private TextField searchField;
    @FXML private CheckBox autoScrollCheckBox;
    @FXML private Button downloadButton;
    @FXML private Button diagnosticsButton;
    @FXML private Button clearButton;
    @FXML private ListView<String> logListView;

    private ObservableList<String> sourceLogLines;
    private FilteredList<String> filteredLogLines;
    private ViewportAnchor pendingViewportAnchor;
    private boolean viewportRestoreScheduled;
    private boolean filterChangeInProgress;

    /**
     * Builds the log toolbar (level filter, search, icon buttons), binds the
     * engine's log buffer to the list view, and wires copy/clear shortcuts,
     * the context menu, and the tail-following auto-scroll behaviour.
     */
    @FXML
    public void initialize() {
        titleLabel.textProperty().bind(I18n.binding("logs.title"));
        searchField.promptTextProperty().bind(I18n.binding("logs.search.prompt"));
        ButtonLabels.bindStatic(autoScrollCheckBox, "logs.auto.scroll");
        // Items are the filter codes buildLevelPredicate() switches on; the
        // converter renders the localized names, so translating the UI can
        // never break the filtering logic.
        logLevelFilter.setItems(FXCollections.observableArrayList(
                "all", "info", "warn", "error", "debug"));
        logLevelFilter.setConverter(new StringConverter<>() {
            @Override
            public String toString(String level) {
                if (level == null) {
                    return "";
                }
                // Full key literals keep I18nBundleConsistencyTest able to
                // verify every reference statically.
                String key = switch (level) {
                    case "info" -> "logs.level.info";
                    case "warn" -> "logs.level.warn";
                    case "error" -> "logs.level.error";
                    case "debug" -> "logs.level.debug";
                    default -> "logs.level.all";
                };
                return I18n.get(key);
            }

            @Override
            public String fromString(String string) {
                return string;
            }
        });
        logLevelFilter.getSelectionModel().select("all");
        logLevelFilter.setTooltip(new Tooltip(I18n.get("logs.filter.tooltip")));

        // Compact icon buttons keep the toolbar from overflowing on a narrow
        // window; tooltips preserve discoverability without the text labels.
        downloadButton.setGraphic(Icons.download(16));
        downloadButton.setTooltip(new Tooltip(I18n.get("logs.download.tooltip")));
        diagnosticsButton.setGraphic(Icons.diagnostics(16));
        diagnosticsButton.setTooltip(new Tooltip(I18n.get("logs.diagnostics.tooltip")));
        clearButton.setGraphic(Icons.clear(16));
        clearButton.setTooltip(new Tooltip(I18n.get("logs.clear.tooltip")));

        SingBoxEngine engine = null;
        try {
            engine = ServiceLocator.get(SingBoxEngine.class);
        } catch (IllegalArgumentException e) {
            log.warn("SingBoxEngine not available; logs view will be empty");
        }

        if (engine != null) {
            sourceLogLines = engine.getLogLines();
        } else {
            sourceLogLines = FXCollections.observableArrayList();
        }

        filteredLogLines = new FilteredList<>(sourceLogLines, p -> true);
        logListView.setItems(filteredLogLines);

        logListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        logListView.setCellFactory(lv -> new LogLineCell(lv));

        // A queued restore belongs to the viewport position that existed when
        // a log line arrived. If the user navigates before it runs, their new
        // position wins and the stale restore must be discarded.
        logListView.addEventFilter(
                ScrollEvent.SCROLL, event -> discardPendingViewportRestore());
        logListView.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (isScrollBarTarget(event.getTarget())) {
                discardPendingViewportRestore();
            }
        });
        logListView.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (isViewportNavigationKey(event.getCode())) {
                discardPendingViewportRestore();
            }
        });

        // Keyboard copy: Cmd+C / Ctrl+C copies selected rows
        KeyCombination copyCombo = new KeyCodeCombination(
                KeyCode.C, KeyCombination.SHORTCUT_DOWN);
        logListView.setOnKeyPressed(event -> {
            if (copyCombo.match(event)) {
                copySelection();
                event.consume();
            } else if (event.getCode() == KeyCode.A && event.isShortcutDown()) {
                logListView.getSelectionModel().selectAll();
                event.consume();
            }
        });

        // Right-click context menu with Copy / Copy All / Clear
        MenuItem copyItem = new MenuItem(I18n.get("logs.copy"));
        copyItem.setAccelerator(copyCombo);
        copyItem.setOnAction(e -> copySelection());
        MenuItem copyAllItem = new MenuItem(I18n.get("logs.copy.all"));
        copyAllItem.setOnAction(e -> copyAll());
        MenuItem selectAllItem = new MenuItem(I18n.get("logs.select.all"));
        selectAllItem.setOnAction(e -> logListView.getSelectionModel().selectAll());
        MenuItem clearItem = new MenuItem(I18n.get("button.clear"));
        clearItem.setOnAction(e -> sourceLogLines.clear());
        ContextMenu contextMenu = new ContextMenu();
        contextMenu.getItems().addAll(copyItem, copyAllItem, selectAllItem, clearItem);
        logListView.setContextMenu(contextMenu);

        logLevelFilter.valueProperty().addListener((obs, oldVal, newVal) -> applyFilter());
        searchField.textProperty().addListener((obs, oldVal, newVal) -> applyFilter());

        // Re-enabling auto-scroll should immediately snap to the tail.
        autoScrollCheckBox.selectedProperty().addListener((obs, was, isOn) -> {
            discardPendingViewportRestore();
            if (isOn && !filteredLogLines.isEmpty()) {
                logListView.scrollTo(filteredLogLines.size() - 1);
            }
        });

        filteredLogLines.addListener((ListChangeListener<String>) change -> {
            // setPredicate() reports the whole FilteredList as a replacement
            // from index zero. It is a user-requested refilter, not a ring-
            // buffer trim, and applyFilter() applies its own viewport policy.
            if (filterChangeInProgress) {
                return;
            }
            if (filteredLogLines.isEmpty()) {
                discardPendingViewportRestore();
                return;
            }
            if (autoScrollCheckBox.isSelected()) {
                discardPendingViewportRestore();
                logListView.scrollTo(filteredLogLines.size() - 1);
                return;
            }
            // Auto-scroll is off: hold the lines the user is reading in place.
            // ListView.scrollTo(index) only makes a row visible. If that row
            // is already on screen, VirtualFlow remains pinned to the tail and
            // shifts it as new rows arrive. Capture both the first row and its
            // exact pixel offset, then restore them after the list mutation.
            queueViewportRestore(change);
        });
    }

    /**
     * Coalesces all list mutations waiting in the FX queue into one restore.
     * LogReader appends and trims in separate operations, so a burst can emit
     * many changes before JavaFX lays the list out again.
     */
    private void queueViewportRestore(ListChangeListener.Change<? extends String> change) {
        if (pendingViewportAnchor == null) {
            pendingViewportAnchor = firstVisibleAnchor();
        }
        if (pendingViewportAnchor == null) {
            return;
        }

        int removed = removedFromFront(change);
        if (removed > 0) {
            pendingViewportAnchor = pendingViewportAnchor.shiftedBy(-removed);
        }
        scheduleViewportRestore();
    }

    private void queueViewportRestore(ViewportAnchor anchor) {
        pendingViewportAnchor = anchor;
        scheduleViewportRestore();
    }

    private void scheduleViewportRestore() {
        if (viewportRestoreScheduled) {
            return;
        }
        viewportRestoreScheduled = true;
        Platform.runLater(() -> {
            viewportRestoreScheduled = false;
            ViewportAnchor anchor = pendingViewportAnchor;
            pendingViewportAnchor = null;
            if (anchor != null) {
                restoreViewport(anchor);
            }
        });
    }

    private void discardPendingViewportRestore() {
        pendingViewportAnchor = null;
    }

    /**
     * Captures the first rendered row and its vertical offset from the flow.
     * Called from a list-change notification, before the next layout pulse.
     */
    private ViewportAnchor firstVisibleAnchor() {
        VirtualFlow<ListCell<String>> flow = virtualFlow();
        if (flow == null) {
            return null;
        }
        ListCell<String> cell = flow.getFirstVisibleCell();
        if (cell == null || cell.isEmpty()) {
            return null;
        }
        return new ViewportAnchor(cell.getItem(), cell.getIndex(), offsetFromFlow(flow, cell));
    }

    /**
     * Restores an exact row-and-pixel viewport anchor after JavaFX has observed
     * an item-list change. The selected-state check discards a queued restore
     * if the user has already switched tail following back on.
     */
    private void restoreViewport(ViewportAnchor anchor) {
        if (autoScrollCheckBox.isSelected() || filteredLogLines.isEmpty()) {
            return;
        }
        VirtualFlow<ListCell<String>> flow = virtualFlow();
        if (flow == null) {
            return;
        }

        int index = indexOfIdentity(anchor.item());
        if (index < 0) {
            index = Math.min(anchor.index(), filteredLogLines.size() - 1);
        }
        flow.scrollToTop(index);
        flow.layout();

        ListCell<String> cell = flow.getFirstVisibleCell();
        if (cell != null && cell.getIndex() == index) {
            double currentOffset = offsetFromFlow(flow, cell);
            flow.scrollPixels(currentOffset - anchor.offset());
        }
    }

    @SuppressWarnings("unchecked")
    private VirtualFlow<ListCell<String>> virtualFlow() {
        Node node = logListView.lookup(".virtual-flow");
        if (node instanceof VirtualFlow<?> flow) {
            return (VirtualFlow<ListCell<String>>) flow;
        }
        return null;
    }

    private int indexOfIdentity(String item) {
        if (item == null) {
            return -1;
        }
        for (int i = 0; i < filteredLogLines.size(); i++) {
            if (sameReference(filteredLogLines.get(i), item)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean sameReference(Object left, Object right) {
        return left == right;
    }

    private static double offsetFromFlow(
            VirtualFlow<ListCell<String>> flow, ListCell<String> cell) {
        Bounds flowBounds = flow.localToScene(flow.getBoundsInLocal());
        Bounds cellBounds = cell.localToScene(cell.getBoundsInLocal());
        return cellBounds.getMinY() - flowBounds.getMinY();
    }

    /**
     * Count of rows this change removed from the front of the list. The ring
     * buffer drops the oldest line, so the anchor index must shift down by the
     * same amount to keep tracking the same content.
     */
    private static int removedFromFront(ListChangeListener.Change<? extends String> change) {
        int removed = 0;
        change.reset();
        while (change.next()) {
            if (change.wasRemoved() && change.getFrom() == 0) {
                removed += change.getRemovedSize();
            }
        }
        change.reset();
        return removed;
    }

    private static boolean isScrollBarTarget(Object target) {
        Node node = target instanceof Node targetNode ? targetNode : null;
        while (node != null) {
            if (node instanceof ScrollBar) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    private static boolean isViewportNavigationKey(KeyCode code) {
        return code == KeyCode.UP
                || code == KeyCode.DOWN
                || code == KeyCode.PAGE_UP
                || code == KeyCode.PAGE_DOWN
                || code == KeyCode.HOME
                || code == KeyCode.END;
    }

    private record ViewportAnchor(String item, int index, double offset) {

        private ViewportAnchor shiftedBy(int delta) {
            return new ViewportAnchor(item, Math.max(0, index + delta), offset);
        }
    }

    @FXML
    private void onClearClicked() {
        sourceLogLines.clear();
    }

    @FXML
    private void onDownloadClicked() {
        if (sourceLogLines == null || sourceLogLines.isEmpty()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("logs.save.title"));
        String stamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        chooser.setInitialFileName("tunl-log-" + stamp + ".txt");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(I18n.get("logs.save.filter"), "*.log", "*.txt"),
                new FileChooser.ExtensionFilter(I18n.get("logs.save.filter.all"), "*.*"));

        Window owner = logListView.getScene() == null
                ? null : logListView.getScene().getWindow();
        File file = chooser.showSaveDialog(owner);
        if (file == null) {
            return;
        }

        // Snapshot here is safe: appends run on the FX thread too, so the list
        // cannot mutate mid-iteration. Saves the full buffer, not the filtered
        // view — the level/search filters are a transient reading aid.
        String content = sourceLogLines.stream()
                .filter(line -> line != null)
                .collect(Collectors.joining(
                        System.lineSeparator(), "", System.lineSeparator()));
        try {
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
            log.info("Saved {} log lines to {}", sourceLogLines.size(), file);
        } catch (IOException e) {
            log.error("Failed to save log to {}", file, e);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(I18n.get("dialog.error"));
            alert.setHeaderText(I18n.get("logs.save.failed"));
            alert.setContentText(e.getMessage());
            alert.initOwner(owner);
            alert.showAndWait();
        }
    }

    /**
     * Saves a diagnostics bundle: the log tail, the versions, and the
     * configuration this build would generate, with credentials removed.
     *
     * <p>Downloading the raw log has never been enough for a bug report — it
     * says nothing about which app or core version produced it, which proxy
     * mode was active, or what the config looked like — and assembling the
     * rest by hand is what most reports skipped.</p>
     */
    @FXML
    private void onSaveDiagnosticsClicked() {
        DiagnosticsBundle bundle;
        try {
            bundle = ServiceLocator.get(DiagnosticsBundle.class);
        } catch (IllegalArgumentException e) {
            log.warn("DiagnosticsBundle not available; cannot save diagnostics");
            return;
        }

        final Window owner = logListView.getScene() == null
                ? null : logListView.getScene().getWindow();
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("logs.diagnostics.title"));
        String stamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"));
        chooser.setInitialFileName("tunl-diagnostics-" + stamp + ".zip");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(I18n.get("logs.diagnostics.filter"), "*.zip"),
                new FileChooser.ExtensionFilter(I18n.get("logs.diagnostics.filter.all"), "*.*"));
        File file = chooser.showSaveDialog(owner);
        if (file == null) {
            return;
        }

        try {
            bundle.writeTo(file.toPath());
        } catch (IOException | RuntimeException e) {
            log.error("Failed to write the diagnostics bundle to {}", file, e);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(I18n.get("dialog.error"));
            alert.setHeaderText(I18n.get("logs.diagnostics.failed"));
            alert.setContentText(e.getMessage());
            alert.initOwner(owner);
            alert.showAndWait();
            return;
        }
        Alert done = new Alert(Alert.AlertType.INFORMATION);
        done.setTitle(I18n.get("logs.diagnostics.done.title"));
        done.setHeaderText(I18n.get("logs.diagnostics.done.header"));
        done.setContentText(I18n.get("logs.diagnostics.done.content", file.getName()));
        done.initOwner(owner);
        done.showAndWait();
    }

    private void applyFilter() {
        String level = logLevelFilter.getValue();
        String searchText = searchField.getText();

        Predicate<String> levelPredicate = buildLevelPredicate(level);
        Predicate<String> searchPredicate = buildSearchPredicate(searchText);

        ViewportAnchor visible = autoScrollCheckBox.isSelected()
                ? null : firstVisibleAnchor();
        final int sourceIndex = sourceIndexOf(visible);
        discardPendingViewportRestore();

        filterChangeInProgress = true;
        try {
            filteredLogLines.setPredicate(levelPredicate.and(searchPredicate));
        } finally {
            filterChangeInProgress = false;
        }

        if (filteredLogLines.isEmpty()) {
            return;
        }
        if (autoScrollCheckBox.isSelected()) {
            logListView.scrollTo(filteredLogLines.size() - 1);
            return;
        }
        if (visible != null) {
            int index = indexOfIdentity(visible.item());
            if (index < 0) {
                index = nearestFilteredIndex(sourceIndex);
            }
            queueViewportRestore(new ViewportAnchor(
                    filteredLogLines.get(index), index, visible.offset()));
        }
    }

    private int sourceIndexOf(ViewportAnchor anchor) {
        if (anchor == null || filteredLogLines.isEmpty()) {
            return -1;
        }
        int index = indexOfIdentity(anchor.item());
        if (index < 0) {
            index = Math.min(anchor.index(), filteredLogLines.size() - 1);
        }
        return filteredLogLines.getSourceIndex(index);
    }

    private int nearestFilteredIndex(int sourceIndex) {
        if (sourceIndex < 0) {
            return 0;
        }
        for (int i = 0; i < filteredLogLines.size(); i++) {
            if (filteredLogLines.getSourceIndex(i) >= sourceIndex) {
                return i;
            }
        }
        return filteredLogLines.size() - 1;
    }

    private Predicate<String> buildLevelPredicate(String level) {
        if (level == null || "all".equals(level)) {
            return line -> true;
        }
        return line -> matchesLevel(LogLineFormatter.levelOf(line), level);
    }

    /**
     * Whether a line of the given level belongs under a filter.
     *
     * <p>The filter used to look for the level's name anywhere in the line, so
     * an INFO line saying "no error" showed up under Error and a DEBUG line
     * mentioning "info" under Info. The level is now the parsed one the
     * formatter already colours. A line with no recognizable level (a panic
     * trace, a continuation) passes every filter: hiding it would cut the
     * lines that explain the error above them.</p>
     */
    static boolean matchesLevel(LogLineFormatter.Kind lineLevel, String filter) {
        if (lineLevel == null || lineLevel == LogLineFormatter.Kind.PLAIN) {
            return true;
        }
        return switch (filter) {
            case "error" -> lineLevel == LogLineFormatter.Kind.LEVEL_ERROR;
            case "warn" -> lineLevel == LogLineFormatter.Kind.LEVEL_ERROR
                    || lineLevel == LogLineFormatter.Kind.LEVEL_WARN;
            case "info" -> lineLevel != LogLineFormatter.Kind.LEVEL_DEBUG;
            default -> true;
        };
    }

    private void copySelection() {
        var selected = logListView.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) {
            return;
        }
        String joined = selected.stream()
                .filter(line -> line != null)
                .collect(Collectors.joining("\n"));
        putStringOnClipboard(joined);
    }

    private void copyAll() {
        String joined = filteredLogLines.stream()
                .filter(line -> line != null)
                .collect(Collectors.joining("\n"));
        putStringOnClipboard(joined);
    }

    private void putStringOnClipboard(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private Predicate<String> buildSearchPredicate(String searchText) {
        if (searchText == null || searchText.isBlank()) {
            return line -> true;
        }
        String searchLower = searchText.toLowerCase();
        return line -> line.toLowerCase().contains(searchLower);
    }

    /**
     * Custom list cell that renders a log line as a {@link TextFlow} of
     * styled {@link Text} nodes — timestamp / level / context / module /
     * message each get their own CSS class, so the result reads like a
     * syntax-highlighted terminal. Long lines wrap inside the viewport.
     */
    private static class LogLineCell extends ListCell<String> {

        private final TextFlow flow = new TextFlow();

        LogLineCell(ListView<String> parent) {
            flow.getStyleClass().add("log-line-flow");
            // Bind the TextFlow width to the viewport so long lines wrap
            // instead of growing horizontally.
            flow.prefWidthProperty().bind(parent.widthProperty().subtract(28));
            flow.maxWidthProperty().bind(parent.widthProperty().subtract(28));
            setMinHeight(Region.USE_PREF_SIZE);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(null);
            flow.getChildren().clear();
            for (LogLineFormatter.Segment seg : LogLineFormatter.format(item)) {
                Text text = new Text(seg.text());
                text.getStyleClass().add(seg.kind().styleClass());
                flow.getChildren().add(text);
            }
            setGraphic(flow);
        }
    }
}
