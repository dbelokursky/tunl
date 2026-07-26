package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.Subscription;
import com.vlessclient.service.SubscriptionService;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for the Subscriptions view. Lists configured subscriptions, adds
 * new ones, and refreshes them (individually or all at once) off the FX thread
 * so a slow fetch never blocks the UI.
 */
public class SubscriptionsViewController {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionsViewController.class);
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @FXML private ListView<Subscription> subscriptionListView;
    @FXML private VBox emptyState;
    @FXML private Label emptyStateTitle;
    @FXML private Label emptyStateHint;
    @FXML private Button addSubscriptionButton;
    @FXML private Button refreshAllButton;

    private SubscriptionService subscriptionService;

    /**
     * Binds the subscription list to the service and keeps the empty-state
     * placeholder in sync as subscriptions are added or removed.
     */
    @FXML
    public void initialize() {
        bindEmptyState();
        subscriptionService = ServiceLocator.get(SubscriptionService.class);

        ObservableList<Subscription> subs = subscriptionService.getSubscriptions();
        subscriptionListView.setItems(subs);
        subscriptionListView.setCellFactory(list -> new SubscriptionListCell());

        subs.addListener((javafx.collections.ListChangeListener<Subscription>) change ->
                updateEmptyState(subs));
        updateEmptyState(subs);
    }

    /**
     * Binds the empty-state text to the bundle; the FXML carried English
     * literals while subscriptions.empty.* sat translated and unused.
     */
    private void bindEmptyState() {
        if (emptyStateTitle != null) {
            emptyStateTitle.textProperty().bind(I18n.binding("subscriptions.empty.title"));
        }
        if (emptyStateHint != null) {
            emptyStateHint.textProperty().bind(I18n.binding("subscriptions.empty.hint"));
        }
    }

    private void updateEmptyState(ObservableList<Subscription> subs) {
        boolean empty = subs.isEmpty();
        emptyState.setVisible(empty);
        emptyState.setManaged(empty);
        subscriptionListView.setVisible(!empty);
        subscriptionListView.setManaged(!empty);
    }

    @FXML
    private void onAddSubscriptionClicked() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("button.add.subscription"));
        dialog.setHeaderText(I18n.get("subscriptions.add.header"));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        TextField nameField = new TextField();
        nameField.setPromptText(I18n.get("subscriptions.name.prompt"));
        nameField.setPrefWidth(350);
        TextField urlField = new TextField();
        urlField.setPromptText("https://example.com/subscribe/...");
        urlField.setPrefWidth(350);

        grid.add(new Label(I18n.get("subscriptions.name.label")), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label(I18n.get("subscriptions.url.label")), 0, 1);
        grid.add(urlField, 1, 1);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Platform.runLater(nameField::requestFocus);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            String name = nameField.getText().trim();
            String url = urlField.getText().trim();
            if (name.isEmpty() || url.isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle(I18n.get("subscriptions.invalid.input"));
                alert.setHeaderText(I18n.get("subscriptions.name.url.required"));
                alert.showAndWait();
                return;
            }
            Thread.startVirtualThread(() -> {
                try {
                    subscriptionService.addSubscription(name, url);
                    Platform.runLater(() -> subscriptionListView.refresh());
                } catch (Exception e) {
                    log.error("Failed to add subscription", e);
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle(I18n.get("dialog.error"));
                        alert.setHeaderText(I18n.get("subscriptions.add.failed"));
                        alert.setContentText(e.getMessage());
                        alert.showAndWait();
                    });
                }
            });
        }
    }

    @FXML
    private void onRefreshAllClicked() {
        Thread.startVirtualThread(() -> {
            subscriptionService.refreshAll();
            Platform.runLater(() -> subscriptionListView.refresh());
        });
    }

    private void refreshSubscription(Subscription sub) {
        Thread.startVirtualThread(() -> {
            subscriptionService.refreshSubscription(sub.getId());
            Platform.runLater(() -> subscriptionListView.refresh());
        });
    }

    private void deleteSubscription(Subscription sub) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("subscriptions.delete.title"));
        confirm.setHeaderText(I18n.get("subscriptions.delete.confirm", sub.getName()));
        confirm.setContentText(I18n.get("subscriptions.delete.content",
                String.valueOf(sub.getServerIds().size())));

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            subscriptionService.removeSubscription(sub.getId());
            log.info("Deleted subscription: {}", sub.getName());
        }
    }

    private class SubscriptionListCell extends ListCell<Subscription> {

        @Override
        protected void updateItem(Subscription sub, boolean empty) {
            super.updateItem(sub, empty);
            if (empty || sub == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            HBox row = new HBox(12);
            row.getStyleClass().add("server-list-item");
            row.setAlignment(Pos.CENTER_LEFT);

            Label nameLabel = new Label(sub.getName());
            nameLabel.getStyleClass().add("server-name");

            String urlDisplay = sub.getUrl();
            if (urlDisplay != null && urlDisplay.length() > 50) {
                urlDisplay = urlDisplay.substring(0, 47) + "...";
            }
            Label urlLabel = new Label(urlDisplay);
            urlLabel.getStyleClass().add("server-address");

            int serverCount = sub.getServerIds().size();
            String servers = serverCount == 1
                    ? I18n.get("subscriptions.servers.one", String.valueOf(serverCount))
                    : I18n.get("subscriptions.servers.many", String.valueOf(serverCount));
            String refresh = sub.getLastRefreshedAt() > 0
                    ? I18n.get("subscriptions.refreshed", TIME_FORMAT.format(
                            Instant.ofEpochMilli(sub.getLastRefreshedAt())))
                    : I18n.get("subscriptions.never.refreshed");
            Label statusLabel = new Label(servers + " · " + refresh);
            statusLabel.getStyleClass().add("server-address");

            VBox info = new VBox(2);
            info.getChildren().addAll(nameLabel, urlLabel, statusLabel);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Button refreshBtn = new Button(I18n.get("button.refresh"));
            refreshBtn.getStyleClass().add("secondary-button");
            refreshBtn.setOnAction(e -> refreshSubscription(sub));

            Button deleteBtn = new Button(I18n.get("button.delete"));
            deleteBtn.getStyleClass().add("secondary-button");
            deleteBtn.setOnAction(e -> deleteSubscription(sub));

            HBox buttons = new HBox(8, refreshBtn, deleteBtn);
            buttons.setAlignment(Pos.CENTER_RIGHT);

            row.getChildren().addAll(info, spacer, buttons);
            setGraphic(row);
        }
    }

}
