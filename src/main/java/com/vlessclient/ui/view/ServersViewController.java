package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.CountryResolver;
import com.vlessclient.service.LatencyTester;
import com.vlessclient.service.ShareLinkExporter;
import com.vlessclient.service.ShareLinkParser;
import com.vlessclient.service.ThemeManager;
import com.vlessclient.service.WireguardConfigParser;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import javafx.application.Platform;
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
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for the Servers view. Lists the configured servers, tracks the
 * active selection, and opens the add/edit form as well as share-link import
 * and per-server actions (edit, duplicate, delete, copy link).
 */
public class ServersViewController {

    private static final Logger log = LoggerFactory.getLogger(ServersViewController.class);

    @FXML private ListView<ServerConfig> serverListView;
    @FXML private Button addServerButton;
    @FXML private Button importLinkButton;
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
        serverListView.setPlaceholder(new Label(I18n.get("servers.no.matches")));
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
        sortCombo.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(ServerSort sort, boolean empty) {
                super.updateItem(sort, empty);
                setText(empty || sort == null ? null : sort.label());
            }
        });
        sortCombo.setButtonCell(sortCombo.getCellFactory().call(null));
        sortCombo.valueProperty().addListener((obs, old, sort) -> applySort(sort));
        // Re-render the labels when the language changes; a ComboBox caches
        // rendered cells and would otherwise keep the old locale's text.
        I18n.localeProperty().addListener((obs, old, locale) -> {
            sortCombo.setButtonCell(sortCombo.getCellFactory().call(null));
            serverListView.setPlaceholder(new Label(I18n.get("servers.no.matches")));
        });
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
            targets.forEach(server -> configStore.removeServer(server.getId()));
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
     * Fills the flag slot for a row: instantly when the country is already
     * known, otherwise once the background lookup answers. An unresolved or
     * unknown country leaves the slot empty rather than showing a placeholder
     * — a row without a flag reads as "no information", which is the truth.
     */
    private void showFlag(StackPane slot, ServerConfig server) {
        CountryResolver resolver;
        try {
            resolver = ServiceLocator.get(CountryResolver.class);
        } catch (IllegalArgumentException e) {
            return;
        }
        resolver.countryOf(server)
                .ifPresent(code -> slot.getChildren().setAll(Flags.of(code, 15)));
        resolver.resolveAsync(server, code -> Platform.runLater(() -> {
            // The cell may have been recycled onto another server by now.
            if (server.getAddress() != null && slot.getScene() != null) {
                slot.getChildren().setAll(Flags.of(code, 15));
            }
        }));
    }

    /**
     * The last measured latency for a row, when one exists. Absent rather than
     * a dash: a row with no chip reads as "not measured", which is the truth,
     * while a placeholder reads as a measurement that came back empty.
     */
    private Optional<Label> latencyChip(ServerConfig server) {
        if (latencyTester == null) {
            return Optional.empty();
        }
        return latencyTester.lastResult(server.getId()).map(result -> {
            Label chip = new Label(result.reachable()
                    ? result.millis() + " ms" : I18n.get("dashboard.latency.timeout"));
            chip.getStyleClass().addAll("latency-chip",
                    result.reachable() ? "latency-chip-ok" : "latency-chip-fail");
            Tooltip.install(chip, new Tooltip(I18n.get(result.throughProxy()
                    ? "dashboard.latency.via.proxy" : "dashboard.latency.via.tcp")));
            return chip;
        });
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
                    .add(ServiceLocator.get(ThemeManager.class).currentStylesheet());
        } catch (IllegalArgumentException e) {
            log.debug("ThemeManager unavailable; import dialog uses default styling");
        }
    }

    private void openServerForm(ServerConfig existingServer) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ServerFormView.fxml"));
            VBox formRoot = loader.load();
            ServerFormController controller = loader.getController();

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
                scene.getStylesheets().add(
                        ServiceLocator.get(ThemeManager.class).currentStylesheet());
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
     */
    private class ServerListCell extends ListCell<ServerConfig> {

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

            HBox row = new HBox(12);
            row.getStyleClass().add("server-list-item");
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

            // Fixed-width slot so rows stay aligned whether or not a country
            // is known — a flag appearing later must not shift the layout.
            StackPane flagSlot = new StackPane();
            flagSlot.setMinWidth(24);
            flagSlot.setPrefWidth(24);
            showFlag(flagSlot, server);

            VBox info = new VBox(2);
            Label nameLabel = new Label(
                    server.getName() != null ? server.getName() : I18n.get("servers.unnamed"));
            nameLabel.getStyleClass().add("server-name");

            String addressText = server.getAddress() + ":" + server.getPort();
            Label addressLabel = new Label(addressText);
            addressLabel.getStyleClass().add("server-address");

            info.getChildren().addAll(nameLabel, addressLabel);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label protocolBadge = new Label(server.getProtocol() != null
                    ? server.getProtocol().getValue().toUpperCase()
                    : "VLESS");
            protocolBadge.getStyleClass().add("protocol-badge");

            row.getChildren().addAll(flagSlot, info, spacer);
            latencyChip(server).ifPresent(chip -> row.getChildren().add(chip));
            row.getChildren().add(protocolBadge);

            if (server.isActive()) {
                Label activeBadge = new Label(I18n.get("servers.active.badge"));
                activeBadge.getStyleClass().add("active-badge");
                row.getChildren().add(activeBadge);
            }

            // Context menu for right-click
            MenuItem editItem = new MenuItem(I18n.get("servers.menu.edit"));
            editItem.setOnAction(e -> editServer(server));

            MenuItem deleteItem = new MenuItem(I18n.get("button.delete"));
            // Acts on the whole selection when there is one, so right-clicking
            // inside a multi-select does what it looks like it will.
            deleteItem.setOnAction(e -> {
                if (serverListView.getSelectionModel().getSelectedItems().contains(server)) {
                    deleteSelected();
                } else {
                    deleteServer(server);
                }
            });

            MenuItem duplicateItem = new MenuItem(I18n.get("button.duplicate"));
            duplicateItem.setOnAction(e -> duplicateServer(server));

            MenuItem copyLinkItem = new MenuItem(I18n.get("button.copy.share.link"));
            copyLinkItem.setOnAction(e -> copyShareLink(server));

            ContextMenu contextMenu = new ContextMenu();
            contextMenu.getItems().addAll(editItem, duplicateItem, copyLinkItem, deleteItem);
            setContextMenu(contextMenu);

            setGraphic(row);
        }
    }
}
