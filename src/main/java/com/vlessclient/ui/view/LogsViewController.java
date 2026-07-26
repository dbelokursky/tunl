package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
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
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
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

    @FXML private ComboBox<String> logLevelFilter;
    @FXML private TextField searchField;
    @FXML private CheckBox autoScrollCheckBox;
    @FXML private Button downloadButton;
    @FXML private Button clearButton;
    @FXML private ListView<String> logListView;

    private ObservableList<String> sourceLogLines;
    private FilteredList<String> filteredLogLines;

    /**
     * Builds the log toolbar (level filter, search, icon buttons), binds the
     * engine's log buffer to the list view, and wires copy/clear shortcuts,
     * the context menu, and the tail-following auto-scroll behaviour.
     */
    @FXML
    public void initialize() {
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
            if (isOn && !filteredLogLines.isEmpty()) {
                logListView.scrollTo(filteredLogLines.size() - 1);
            }
        });

        filteredLogLines.addListener((ListChangeListener<String>) change -> {
            if (filteredLogLines.isEmpty()) {
                return;
            }
            if (autoScrollCheckBox.isSelected()) {
                logListView.scrollTo(filteredLogLines.size() - 1);
                return;
            }
            // Auto-scroll is off: hold the lines the user is reading in place.
            // VirtualFlow pins the viewport to the tail cell while it is
            // visible, and LogReader's ring buffer trims the oldest line off
            // the front, shifting every index down by one. A scrollbar-ratio
            // freeze survives neither — ratio 1.0 always maps to the new
            // bottom, which is why toggling the box used to keep following the
            // tail. Anchor on the first visible row index instead (captured
            // here, before the pulse re-lays-out the flow) and re-assert it on
            // the next pulse, compensating for any front-trimmed line.
            int firstVisible = firstVisibleIndex();
            if (firstVisible < 0) {
                return;
            }
            int anchor = Math.max(0, firstVisible - removedFromFront(change));
            Platform.runLater(() -> logListView.scrollTo(anchor));
        });
    }

    /**
     * Index of the first row intersecting the viewport, or {@code -1} when no
     * rendered cell is visible. Called from a list-change notification — before
     * the pulse re-lays-out the flow — it reports the pre-change (old) index
     * space, which is exactly what the anchor restore needs.
     */
    private int firstVisibleIndex() {
        Bounds viewport = logListView.localToScene(logListView.getBoundsInLocal());
        double top = viewport.getMinY();
        double bottom = viewport.getMaxY();
        int first = -1;
        for (Node node : logListView.lookupAll(".list-cell")) {
            if (!(node instanceof ListCell<?> cell) || cell.isEmpty()) {
                continue;
            }
            Bounds b = cell.localToScene(cell.getBoundsInLocal());
            if (b.getMaxY() > top + 1 && b.getMinY() < bottom - 1
                    && (first < 0 || cell.getIndex() < first)) {
                first = cell.getIndex();
            }
        }
        return first;
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
                new FileChooser.ExtensionFilter("Log files", "*.log", "*.txt"),
                new FileChooser.ExtensionFilter("All files", "*.*"));

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

    private void applyFilter() {
        String level = logLevelFilter.getValue();
        String searchText = searchField.getText();

        Predicate<String> levelPredicate = buildLevelPredicate(level);
        Predicate<String> searchPredicate = buildSearchPredicate(searchText);

        filteredLogLines.setPredicate(levelPredicate.and(searchPredicate));
    }

    private Predicate<String> buildLevelPredicate(String level) {
        if (level == null || "all".equals(level)) {
            return line -> true;
        }
        return line -> {
            String lower = line.toLowerCase();
            return switch (level) {
                case "error" -> lower.contains("error") || lower.contains("fatal");
                case "warn" -> lower.contains("warn") || lower.contains("error")
                        || lower.contains("fatal");
                case "info" -> lower.contains("info") || lower.contains("warn")
                        || lower.contains("error") || lower.contains("fatal");
                case "debug" -> true;
                default -> true;
            };
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
