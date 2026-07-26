package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ShareLinkExporter;
import com.vlessclient.service.ShareLinkParser;
import com.vlessclient.service.ThemeManager;
import java.io.IOException;
import java.util.Optional;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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

    private ConfigStore configStore;

    /**
     * Binds the server list to the config store, keeps the empty-state
     * placeholder in sync, and marks the selected server active.
     */
    @FXML
    public void initialize() {
        configStore = ServiceLocator.get(ConfigStore.class);
        ObservableList<ServerConfig> servers = configStore.getServers();

        serverListView.setItems(servers);
        serverListView.setCellFactory(list -> new ServerListCell());

        servers.addListener((javafx.collections.ListChangeListener<ServerConfig>) change -> {
            updateEmptyState(servers);
        });

        updateEmptyState(servers);

        serverListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal != null) {
                        setActiveServer(newVal);
                    }
                });
    }

    private void updateEmptyState(ObservableList<ServerConfig> servers) {
        boolean empty = servers.isEmpty();
        emptyState.setVisible(empty);
        emptyState.setManaged(empty);
        serverListView.setVisible(!empty);
        serverListView.setManaged(!empty);
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

    @FXML
    private void onImportLinkClicked() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle(I18n.get("dialog.import.link"));
        dialog.setHeaderText(I18n.get("servers.import.header"));
        dialog.setContentText(I18n.get("servers.import.label"));
        dialog.getDialogPane().setPrefWidth(500);

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(link -> {
            if (link.isBlank()) {
                return;
            }
            try {
                ShareLinkParser parser = ServiceLocator.get(ShareLinkParser.class);
                ServerConfig server = parser.parse(link.trim());
                configStore.addServer(server);
                log.info("Imported server from share link: {}", server.getName());
            } catch (Exception e) {
                log.error("Failed to parse share link", e);
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle(I18n.get("servers.import.error.title"));
                alert.setHeaderText(I18n.get("servers.import.error.header"));
                alert.setContentText(e.getMessage());
                alert.showAndWait();
            }
        });
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

            row.getChildren().addAll(info, spacer, protocolBadge);

            if (server.isActive()) {
                Label activeBadge = new Label(I18n.get("servers.active.badge"));
                activeBadge.getStyleClass().add("active-badge");
                row.getChildren().add(activeBadge);
            }

            // Context menu for right-click
            MenuItem editItem = new MenuItem(I18n.get("servers.menu.edit"));
            editItem.setOnAction(e -> editServer(server));

            MenuItem deleteItem = new MenuItem(I18n.get("button.delete"));
            deleteItem.setOnAction(e -> deleteServer(server));

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
