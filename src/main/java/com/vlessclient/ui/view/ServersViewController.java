package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.CountryResolver;
import com.vlessclient.service.LatencyTester;
import com.vlessclient.service.ServerBackupService;
import com.vlessclient.service.ShareLinkExporter;
import com.vlessclient.service.ShareLinkParser;
import com.vlessclient.service.ThemeManager;
import com.vlessclient.service.WireguardConfigParser;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for the Servers view. Lists the configured servers, tracks the
 * active selection, and opens the add/edit form as well as share-link import
 * and per-server actions (edit, duplicate, delete, copy link).
 */
public class ServersViewController {

    private static final Logger log = LoggerFactory.getLogger(ServersViewController.class);

    @FXML private Label titleLabel;
    @FXML private ListView<ServerConfig> serverListView;
    @FXML private Button addServerButton;
    @FXML private Button importLinkButton;
    @FXML private MenuButton backupMenuButton;
    @FXML private VBox emptyState;
    @FXML private Label emptyStateTitle;
    @FXML private Label emptyStateHint;
    @FXML private HBox filterBar;
    @FXML private TextField searchField;
    @FXML private ComboBox<ServerSort> sortCombo;
    @FXML private Button measureButton;

    private ConfigStore configStore;
    private FilteredList<ServerConfig> filtered;
    private SortedList<ServerConfig> sorted;
    private LatencyTester latencyTester;

    /**
     * How the list is ordered. {@link #CONFIGURED} is the stored order, kept as
     * the default because it is the one the user arranged and the only one they
     * can predict.
     */
    private enum ServerSort {
        CONFIGURED("servers.sort.configured"),
        NAME("servers.sort.name"),
        LATENCY("servers.sort.latency"),
        PROTOCOL("servers.sort.protocol");

        private final String key;

        ServerSort(String key) {
            this.key = key;
        }

        String label() {
            return I18n.get(key);
        }
    }

    /**
     * Binds the server list to the config store, keeps the empty-state
     * placeholder in sync, and wires search, sort and selection.
     */
    @FXML
    public void initialize() {
        titleLabel.textProperty().bind(I18n.binding("servers.title"));
        bindEmptyState();
        configStore = ServiceLocator.get(ConfigStore.class);
        latencyTester = optionalService(LatencyTester.class);
        ObservableList<ServerConfig> servers = configStore.getServers();

        // Store -> filter -> sort -> view. Both wrappers are views over the
        // store's list, so adds, edits and deletes still flow through
        // untouched; only what the user sees is narrowed and reordered.
        filtered = new FilteredList<>(servers, server -> true);
        sorted = new SortedList<>(filtered);
        serverListView.setItems(sorted);
        serverListView.setCellFactory(list -> new ServerListCell());
        serverListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        serverListView.setOnKeyPressed(this::onListKeyPressed);

        setUpSearch();
        setUpSort();
        setUpMeasureButton();
        setUpBackupMenu();
        ButtonLabels.bindStatic(importLinkButton, "button.import.link");
        ButtonLabels.bindAddAction(addServerButton, "button.add.server");

        servers.addListener((javafx.collections.ListChangeListener<ServerConfig>) change -> {
            updateEmptyState(servers);
        });

        updateEmptyState(servers);
    }

    /** Returns the service, or null when it is not registered. */
    private <T> T optionalService(Class<T> type) {
        try {
            return ServiceLocator.get(type);
        } catch (IllegalArgumentException e) {
            log.debug("{} unavailable in this context", type.getSimpleName());
            return null;
        }
    }

    private void setUpSearch() {
        searchField.promptTextProperty().bind(I18n.binding("servers.search.prompt"));
        searchField.textProperty().addListener(
                (obs, old, text) -> filtered.setPredicate(matching(text)));
        Label noMatches = new Label();
        noMatches.textProperty().bind(I18n.binding("servers.no.matches"));
        serverListView.setPlaceholder(noMatches);
    }

    /**
     * A server matches if the query appears anywhere in what the row shows:
     * name, address, port or protocol. One field-agnostic box rather than four
     * — the user typing "de-3" or "vless" or "443" wants the same thing, and
     * asking them which field it was is a question they should not have to
     * answer.
     */
    private static Predicate<ServerConfig> matching(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return server -> true;
        }
        return server -> searchableText(server).contains(needle);
    }

    private static String searchableText(ServerConfig server) {
        StringBuilder text = new StringBuilder();
        if (server.getName() != null) {
            text.append(server.getName()).append(' ');
        }
        if (server.getAddress() != null) {
            text.append(server.getAddress()).append(' ');
        }
        text.append(server.getPort()).append(' ');
        if (server.getProtocol() != null) {
            text.append(server.getProtocol().getValue());
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private void setUpSort() {
        sortCombo.getItems().setAll(ServerSort.values());
        sortCombo.setValue(ServerSort.CONFIGURED);
        sortCombo.setCellFactory(list -> sortCell());
        sortCombo.setButtonCell(sortCell());
        sortCombo.valueProperty().addListener((obs, old, sort) -> applySort(sort));
    }

    /** A sort label that follows the locale without replacing the cell. */
    private ListCell<ServerSort> sortCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(ServerSort sort, boolean empty) {
                super.updateItem(sort, empty);
                textProperty().unbind();
                if (empty || sort == null) {
                    setText(null);
                } else {
                    textProperty().bind(Bindings.createStringBinding(
                            sort::label, I18n.localeProperty()));
                }
            }
        };
    }

    private void applySort(ServerSort sort) {
        sorted.setComparator(comparatorFor(sort));
    }

    /** Null keeps the stored order, which is what CONFIGURED means. */
    private Comparator<ServerConfig> comparatorFor(ServerSort sort) {
        if (sort == null || sort == ServerSort.CONFIGURED) {
            return null;
        }
        Comparator<ServerConfig> byName = Comparator.comparing(
                server -> server.getName() == null ? "" : server.getName(),
                String.CASE_INSENSITIVE_ORDER);
        return switch (sort) {
            case NAME -> byName;
            case PROTOCOL -> Comparator.comparing(
                    (ServerConfig server) -> server.getProtocol() == null
                            ? "" : server.getProtocol().getValue()).thenComparing(byName);
            // Unmeasured and unreachable both sort last: neither is a number
            // the user can act on, and floating them to the top under a
            // "fastest first" order would be a lie.
            case LATENCY -> Comparator.comparingLong(this::sortableLatency).thenComparing(byName);
            default -> null;
        };
    }

    private long sortableLatency(ServerConfig server) {
        if (latencyTester == null) {
            return Long.MAX_VALUE;
        }
        return latencyTester.lastResult(server.getId())
                .filter(LatencyTester.Result::reachable)
                .map(LatencyTester.Result::millis)
                .orElse(Long.MAX_VALUE);
    }

    /**
     * Binds the empty-state text to the bundle. The FXML carried English
     * literals while servers.empty.* sat translated and unused, so a Russian
     * user met an English screen at exactly the moment they had nothing yet.
     */
    private void bindEmptyState() {
        if (emptyStateTitle != null) {
            emptyStateTitle.textProperty().bind(I18n.binding("servers.empty.title"));
        }
        if (emptyStateHint != null) {
            emptyStateHint.textProperty().bind(I18n.binding("servers.empty.hint"));
        }
    }

    private void updateEmptyState(ObservableList<ServerConfig> servers) {
        boolean empty = servers.isEmpty();
        emptyState.setVisible(empty);
        emptyState.setManaged(empty);
        serverListView.setVisible(!empty);
        serverListView.setManaged(!empty);
        // Nothing to narrow or reorder yet; the controls would only be noise
        // above the "add your first server" prompt.
        filterBar.setVisible(!empty);
        filterBar.setManaged(!empty);
    }

    private void setUpMeasureButton() {
        ButtonLabels.bind(measureButton, "servers.measure", "servers.measuring");
        measureButton.setDisable(latencyTester == null);
    }

    /**
     * Measures the servers currently visible, so the latency sort has numbers
     * to work with without a trip to the Dashboard. Filtered-out servers are
     * skipped on purpose: measuring forty when the user narrowed to three is
     * work they did not ask for.
     */
    @FXML
    private void onMeasureClicked() {
        List<ServerConfig> targets = List.copyOf(sorted);
        if (latencyTester == null || targets.isEmpty()) {
            return;
        }
        ButtonLabels.show(measureButton, "servers.measuring");
        measureButton.setDisable(true);
        latencyTester.testAll(targets).whenComplete((results, err) -> Platform.runLater(() -> {
            measureButton.setDisable(false);
            ButtonLabels.reset(measureButton);
            if (err != null) {
                log.warn("Latency measurement failed", err);
                return;
            }
            refreshAfterMeasurement();
        }));
    }

    /**
     * A {@link SortedList} re-sorts when the list changes or the comparator
     * does. New latencies are neither — they live outside the elements — so
     * the comparator is re-applied to force the pass.
     */
    private void refreshAfterMeasurement() {
        serverListView.refresh();
        if (sortCombo.getValue() == ServerSort.LATENCY) {
            sorted.setComparator(null);
            applySort(ServerSort.LATENCY);
        }
    }

    private void onListKeyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER) {
            ServerConfig focused = serverListView.getSelectionModel().getSelectedItem();
            if (focused != null) {
                setActiveServer(focused);
                event.consume();
            }
        } else if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
            deleteSelected();
            event.consume();
        }
    }

    /**
     * Deletes every selected server, confirming once for the whole batch. A
     * mis-imported forty-server subscription used to need forty confirmations.
     */
    private void deleteSelected() {
        List<ServerConfig> targets =
                List.copyOf(serverListView.getSelectionModel().getSelectedItems());
        if (targets.isEmpty()) {
            return;
        }
        if (targets.size() == 1) {
            deleteServer(targets.get(0));
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("dialog.delete.server"));
        confirm.setHeaderText(I18n.get("servers.delete.many.header", targets.size()));
        confirm.setContentText(I18n.get("servers.delete.warning"));
        if (confirm.showAndWait().filter(button -> button == ButtonType.OK).isPresent()) {
            // Clear first: otherwise the selection model reshuffles onto
            // surviving rows as each removal lands.
            serverListView.getSelectionModel().clearSelection();
            // One save and one keychain sweep for the batch. Deleting through
            // removeServer wrote servers.json — and re-sealed every stored
            // credential — once per row, the quadratic cost applyServerBatch
            // exists to avoid.
            configStore.applyServerBatch(List.of(),
                    targets.stream().map(ServerConfig::getId).toList());
            log.info("Deleted {} servers", targets.size());
        }
    }

    @FXML
    private void onAddServerClicked() {
        openServerForm(null);
    }

    /**
     * Opens the add-server dialog. Used by keyboard shortcuts.
     */
    public void openAddServerDialog() {
        openServerForm(null);
    }

    /**
     * Imports a server from a share link or a WireGuard {@code .conf}.
     *
     * <p>One input for both: WireGuard has no share-link format, so its users
     * hold an INI config instead. Asking them to pick a format first would be
     * a choice the text itself already answers — a {@code .conf} always has an
     * {@code [Interface]} section, and a share link never does.</p>
     */
    @FXML
    private void onImportLinkClicked() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("dialog.import.link"));
        dialog.setHeaderText(I18n.get("servers.import.header"));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setPrefWidth(560);

        // Multi-line: a .conf is a whole file, not a one-liner.
        TextArea input = new TextArea();
        input.setPromptText(I18n.get("servers.import.prompt"));
        input.setPrefRowCount(8);
        input.setWrapText(true);
        dialog.getDialogPane().setContent(input);
        dialog.setResultConverter(button -> button == ButtonType.OK ? input.getText() : null);

        applyTheme(dialog);

        dialog.showAndWait().ifPresent(text -> {
            if (text == null || text.isBlank()) {
                return;
            }
            try {
                ServerConfig server = parseImport(text.trim());
                configStore.addServer(server);
                log.info("Imported server: {}", server.getName());
            } catch (Exception e) {
                log.error("Failed to import server", e);
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle(I18n.get("servers.import.error.title"));
                alert.setHeaderText(I18n.get("servers.import.error.header"));
                alert.setContentText(e.getMessage());
                alert.showAndWait();
            }
        });
    }

    /**
     * Builds the backup menu. The items are created here rather than in the
     * FXML so their labels can be bound to the bundle — a {@code text="…"}
     * written into the FXML never follows a language switch.
     */
    private void setUpBackupMenu() {
        ButtonLabels.bindStatic(backupMenuButton, "servers.backup");
        MenuItem exportItem = new MenuItem();
        exportItem.setId("exportServersItem");
        exportItem.textProperty().bind(I18n.binding("servers.backup.export"));
        exportItem.setOnAction(event -> exportServers());
        MenuItem importItem = new MenuItem();
        importItem.setId("importServersItem");
        importItem.textProperty().bind(I18n.binding("servers.backup.import"));
        importItem.setOnAction(event -> importServers());
        backupMenuButton.getItems().setAll(exportItem, importItem);
    }

    /**
     * Writes the whole list to a file the user picks, after warning what the
     * file will contain.
     *
     * <p>The warning is not boilerplate: the backup has to hold plaintext
     * credentials to be restorable on another machine, so the file is exactly
     * as sensitive as the servers themselves. Asking first is the only point
     * at which the user can decide where it may land.</p>
     */
    private void exportServers() {
        ServerBackupService backup = optionalService(ServerBackupService.class);
        if (backup == null) {
            return;
        }
        Alert warning = new Alert(Alert.AlertType.CONFIRMATION);
        warning.setTitle(I18n.get("servers.backup.export.title"));
        warning.setHeaderText(I18n.get("servers.backup.export.warning.header"));
        warning.setContentText(I18n.get("servers.backup.export.warning.content"));
        warning.initOwner(ownerWindow());
        if (warning.showAndWait().filter(button -> button == ButtonType.OK).isEmpty()) {
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("servers.backup.export.title"));
        chooser.setInitialFileName("tunl-servers-"
                + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".json");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(I18n.get("servers.backup.filter.json"), "*.json"),
                new FileChooser.ExtensionFilter(I18n.get("servers.backup.filter.all"), "*.*"));
        File file = chooser.showSaveDialog(ownerWindow());
        if (file == null) {
            return;
        }
        try {
            log.info("Exported {} servers", backup.exportAll(file.toPath()));
        } catch (IOException | RuntimeException e) {
            log.error("Failed to export servers", e);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(I18n.get("servers.export.error.title"));
            alert.setHeaderText(I18n.get("servers.backup.export.failed"));
            alert.setContentText(e.getMessage());
            alert.initOwner(ownerWindow());
            alert.showAndWait();
        }
    }

    /** Restores servers from a backup file or a share-link list. */
    private void importServers() {
        ServerBackupService backup = optionalService(ServerBackupService.class);
        if (backup == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("servers.backup.import.title"));
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(I18n.get("servers.backup.filter.json"), "*.json"),
                new FileChooser.ExtensionFilter(I18n.get("servers.backup.filter.links"), "*.txt"),
                new FileChooser.ExtensionFilter(I18n.get("servers.backup.filter.all"), "*.*"));
        File file = chooser.showOpenDialog(ownerWindow());
        if (file == null) {
            return;
        }
        try {
            ServerBackupService.ImportResult result = backup.importFile(file.toPath());
            Alert done = new Alert(Alert.AlertType.INFORMATION);
            done.setTitle(I18n.get("servers.backup.import.done.title"));
            done.setHeaderText(I18n.get("servers.backup.import.done.header",
                    result.added(), result.updated(), result.skipped().size()));
            done.setContentText(importSummary(file.getName(), result));
            done.initOwner(ownerWindow());
            done.showAndWait();
        } catch (IOException | RuntimeException e) {
            log.error("Failed to import servers", e);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(I18n.get("servers.import.error.title"));
            alert.setHeaderText(I18n.get("servers.backup.import.failed"));
            alert.setContentText(e.getMessage());
            alert.initOwner(ownerWindow());
            alert.showAndWait();
        }
    }

    /**
     * The body of the import report: where it came from, and — when entries
     * were dropped — which ones and why. Capped at five, because a stale
     * thirty-line link list would otherwise fill the screen with a dialog the
     * user cannot scroll.
     */
    private static String importSummary(String fileName, ServerBackupService.ImportResult result) {
        StringBuilder text = new StringBuilder(
                I18n.get("servers.backup.import.done.content", fileName));
        if (!result.skipped().isEmpty()) {
            text.append("\n\n").append(I18n.get("servers.backup.import.skipped.list"));
            result.skipped().stream().limit(5).forEach(skip -> text.append('\n')
                    .append(skip.entry()).append(" — ").append(skip.reason()));
        }
        return text.toString();
    }

    /** The window the dialogs belong to, or null before the view is shown. */
    private Window ownerWindow() {
        Scene scene = serverListView.getScene();
        return scene == null ? null : scene.getWindow();
    }

    /**
     * Fills the flag slot for a row: instantly when the country is already
     * known, otherwise once the background lookup answers. An unresolved or
     * unknown country leaves the slot empty rather than showing a placeholder
     * — a row without a flag reads as "no information", which is the truth.
     */
    private void showFlag(ListCell<ServerConfig> cell, StackPane slot, ServerConfig server) {
        // The slot is reused across items: clear what the previous one left.
        slot.getChildren().clear();
        CountryResolver resolver = optionalService(CountryResolver.class);
        if (resolver == null) {
            return;
        }
        resolver.countryOf(server)
                .ifPresent(code -> slot.getChildren().setAll(Flags.of(code, 15)));
        resolver.resolveAsync(server, code -> Platform.runLater(() -> {
            // The cell may have been recycled onto another server by now. The
            // slot being in a scene says nothing about which server it shows
            // — only the cell's current item does — so a late answer used to
            // paint the previous row's flag onto whichever server now sat
            // there.
            if (cell.getItem() == server) {
                slot.getChildren().setAll(Flags.of(code, 15));
            }
        }));
    }

    /**
     * Paints the last measured latency into a row's chip, when one exists.
     * Absent rather than a dash: a row with no chip reads as "not measured",
     * which is the truth, while a placeholder reads as a measurement that
     * came back empty.
     *
     * @return whether the chip carries a measurement and should be shown
     */
    private boolean updateLatencyChip(Label chip, ServerConfig server) {
        Optional<LatencyTester.Result> measured = latencyTester == null
                ? Optional.empty() : latencyTester.lastResult(server.getId());
        if (measured.isEmpty()) {
            return false;
        }
        LatencyTester.Result result = measured.get();
        chip.setText(result.reachable()
                ? result.millis() + " ms" : I18n.get("dashboard.latency.timeout"));
        chip.getStyleClass().setAll("latency-chip",
                result.reachable() ? "latency-chip-ok" : "latency-chip-fail");
        chip.setTooltip(new Tooltip(I18n.get(result.throughProxy()
                ? "dashboard.latency.via.proxy" : "dashboard.latency.via.tcp")));
        return true;
    }

    /** Picks the parser from the text's own shape rather than asking the user. */
    private ServerConfig parseImport(String text) {
        if (text.toLowerCase(java.util.Locale.ROOT).contains("[interface]")) {
            return new WireguardConfigParser().parse(text);
        }
        return ServiceLocator.get(ShareLinkParser.class).parse(text);
    }

    private void applyTheme(Dialog<?> dialog) {
        try {
            dialog.getDialogPane().getStylesheets()
                    .addAll(ServiceLocator.get(ThemeManager.class).currentStylesheets());
        } catch (IllegalArgumentException e) {
            log.debug("ThemeManager unavailable; import dialog uses default styling");
        }
    }

    private void openServerForm(ServerConfig existingServer) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ServerFormView.fxml"));
            VBox formRoot = loader.load();
            final ServerFormController controller = loader.getController();

            Stage dialog = new Stage();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle(existingServer == null
                    ? I18n.get("dialog.add.server")
                    : I18n.get("dialog.edit.server"));
            dialog.setMinWidth(500);
            dialog.setMinHeight(600);

            Scene scene = new Scene(formRoot, 520, 650);
            // Follow the app's theme instead of forcing light: a dark-mode user
            // got a white flash on every add/edit.
            try {
                scene.getStylesheets().addAll(
                        ServiceLocator.get(ThemeManager.class).currentStylesheets());
            } catch (IllegalArgumentException e) {
                log.debug("ThemeManager unavailable; server form uses default styling");
            }
            dialog.setScene(scene);

            if (existingServer != null) {
                controller.setServerConfig(existingServer);
            }

            controller.setOnSave(server -> {
                if (existingServer != null) {
                    configStore.updateServer(server);
                } else {
                    configStore.addServer(server);
                }
                dialog.close();
            });

            controller.setOnCancel(dialog::close);

            dialog.showAndWait();
        } catch (IOException e) {
            log.error("Failed to open server form", e);
        }
    }

    private void editServer(ServerConfig server) {
        openServerForm(server);
    }

    private void deleteServer(ServerConfig server) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("dialog.delete.server"));
        confirm.setHeaderText(I18n.get("servers.delete.header", server.getName()));
        confirm.setContentText(I18n.get("servers.delete.warning"));

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            configStore.removeServer(server.getId());
            log.info("Deleted server: {}", server.getName());
        }
    }

    private void duplicateServer(ServerConfig server) {
        configStore.duplicateServer(server.getId());
        log.info("Duplicated server: {}", server.getName());
    }

    private void copyShareLink(ServerConfig server) {
        try {
            ShareLinkExporter exporter = ServiceLocator.get(ShareLinkExporter.class);
            String link = exporter.export(server);
            ClipboardContent content = new ClipboardContent();
            content.putString(link);
            Clipboard.getSystemClipboard().setContent(content);
            log.info("Copied share link for: {}", server.getName());
        } catch (Exception e) {
            log.error("Failed to export share link", e);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(I18n.get("servers.export.error.title"));
            alert.setHeaderText(I18n.get("servers.export.error.header"));
            alert.setContentText(e.getMessage());
            alert.showAndWait();
        }
    }

    private void setActiveServer(ServerConfig server) {
        // Delegates so the choice is persisted; flipping the flags here left it
        // in memory only and it was lost on restart.
        configStore.setActiveServer(server.getId());
        serverListView.refresh();
        log.info("Active server set to: {}", server.getName());
    }

    /**
     * Custom list cell that renders server info with name, address, active badge,
     * and a right-click context menu.
     *
     * <p>The row, its labels and the context menu are built once per cell and
     * only re-filled in {@link #updateItem}: a ListView recycles a handful of
     * cells across the whole list, so rebuilding the graphic on every update
     * cost an HBox, five labels, a four-item menu and a country lookup per
     * scroll tick.</p>
     */
    private class ServerListCell extends ListCell<ServerConfig> {

        private final HBox row = new HBox(12);
        private final StackPane flagSlot = new StackPane();
        private final VBox info;
        private final Region spacer = new Region();
        private final Label nameLabel = new Label();
        private final Label addressLabel = new Label();
        private final Label latencyChip = new Label();
        private final Label protocolBadge = new Label();
        private final Label insecureBadge = new Label();
        private final Label activeBadge = new Label();
        private final ContextMenu contextMenu = new ContextMenu();

        /**
         * Activation is a click, not a selection change.
         *
         * <p>It used to be bound to the selection model, which broke as soon as
         * the list became a filtered view: rebuilding it as the user types
         * moves the selection on its own, so searching silently switched the
         * active server. It was already costing an arrow-key user a config
         * write and a live-tunnel restart per keypress, and it made a
         * multi-select impossible — every row touched while building one would
         * activate.</p>
         */
        ServerListCell() {
            setOnMouseClicked(event -> {
                boolean extendingSelection = event.isShortcutDown() || event.isShiftDown();
                if (isEmpty() || getItem() == null
                        || event.getButton() != MouseButton.PRIMARY || extendingSelection) {
                    return;
                }
                setActiveServer(getItem());
            });

            row.getStyleClass().add("server-list-item");
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

            // Fixed-width slot so rows stay aligned whether or not a country
            // is known — a flag appearing later must not shift the layout.
            flagSlot.setMinWidth(24);
            flagSlot.setPrefWidth(24);

            nameLabel.getStyleClass().add("server-name");
            addressLabel.getStyleClass().add("server-address");
            info = new VBox(2, nameLabel, addressLabel);

            HBox.setHgrow(spacer, Priority.ALWAYS);

            protocolBadge.getStyleClass().add("protocol-badge");
            // A link that turns certificate verification off is one a network
            // attacker on the subscription's fetch path could have written;
            // the list says so rather than applying it silently.
            insecureBadge.getStyleClass().add("insecure-badge");
            insecureBadge.textProperty().bind(I18n.binding("servers.badge.insecure"));
            Tooltip insecureTooltip = new Tooltip();
            insecureTooltip.textProperty().bind(I18n.binding("servers.badge.insecure.tooltip"));
            insecureBadge.setTooltip(insecureTooltip);
            activeBadge.getStyleClass().add("active-badge");
            activeBadge.textProperty().bind(I18n.binding("servers.active.badge"));

            // Context menu for right-click. Every item reads getItem() when it
            // fires, so the one menu serves whichever server the cell shows.
            MenuItem editItem = menuItem("servers.menu.edit", () -> editServer(getItem()));
            MenuItem duplicateItem = menuItem("button.duplicate",
                    () -> duplicateServer(getItem()));
            MenuItem copyLinkItem = menuItem("button.copy.share.link",
                    () -> copyShareLink(getItem()));
            // Acts on the whole selection when there is one, so right-clicking
            // inside a multi-select does what it looks like it will.
            MenuItem deleteItem = menuItem("button.delete", () -> {
                if (serverListView.getSelectionModel().getSelectedItems().contains(getItem())) {
                    deleteSelected();
                } else {
                    deleteServer(getItem());
                }
            });
            contextMenu.getItems().addAll(editItem, duplicateItem, copyLinkItem, deleteItem);
        }

        private MenuItem menuItem(String key, Runnable action) {
            MenuItem item = new MenuItem();
            item.textProperty().bind(I18n.binding(key));
            item.setOnAction(e -> {
                if (getItem() != null) {
                    action.run();
                }
            });
            return item;
        }

        @Override
        protected void updateItem(ServerConfig server, boolean empty) {
            super.updateItem(server, empty);
            if (empty || server == null) {
                setGraphic(null);
                setText(null);
                setContextMenu(null);
                return;
            }

            nameLabel.setText(
                    server.getName() != null ? server.getName() : I18n.get("servers.unnamed"));
            addressLabel.setText(server.getAddress() + ":" + server.getPort());
            protocolBadge.setText(server.getProtocol() != null
                    ? server.getProtocol().getValue().toUpperCase()
                    : "VLESS");
            showFlag(this, flagSlot, server);

            // The same nodes every time; only which of the optional ones
            // appear changes with the item.
            row.getChildren().setAll(flagSlot, info, spacer);
            if (updateLatencyChip(latencyChip, server)) {
                row.getChildren().add(latencyChip);
            }
            row.getChildren().add(protocolBadge);
            if (server.getTls() != null && server.getTls().isAllowInsecure()) {
                row.getChildren().add(insecureBadge);
            }
            if (server.isActive()) {
                row.getChildren().add(activeBadge);
            }

            setContextMenu(contextMenu);
            setGraphic(row);
        }
    }
}
