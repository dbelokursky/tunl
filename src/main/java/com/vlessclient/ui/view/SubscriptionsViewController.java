package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.Subscription;
import com.vlessclient.service.Redact;
import com.vlessclient.service.SubscriptionService;
import com.vlessclient.service.TrafficMonitor;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    @FXML private Label titleLabel;
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
        titleLabel.textProperty().bind(I18n.binding("subscriptions.title"));
        bindEmptyState();
        ButtonLabels.bindStatic(refreshAllButton, "subscriptions.refresh.all");
        ButtonLabels.bindAddAction(addSubscriptionButton, "button.add.subscription");
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
        showSubscriptionDialog(I18n.get("button.add.subscription"),
                I18n.get("subscriptions.add.header"), "", "")
                .ifPresent(entry -> runOffFxThread(
                        () -> subscriptionService.addSubscription(entry.name(), entry.url()),
                        "subscriptions.add.failed"));
    }

    /**
     * Renames a subscription or points it at a new URL. Changing a URL used
     * to mean deleting the subscription (with its servers) and adding it
     * again.
     */
    private void editSubscription(Subscription sub) {
        showSubscriptionDialog(I18n.get("button.edit"),
                I18n.get("subscriptions.edit.header"), sub.getName(), sub.getUrl())
                .ifPresent(entry -> runOffFxThread(
                        () -> subscriptionService.updateSubscription(
                                sub.getId(), entry.name(), entry.url()),
                        "subscriptions.edit.failed"));
    }

    private record Entry(String name, String url) {
    }

    /** Runs a service call off the FX thread and reports a failure in a dialog. */
    private void runOffFxThread(Runnable action, String failureHeaderKey) {
        Thread.startVirtualThread(() -> {
            try {
                action.run();
                Platform.runLater(() -> subscriptionListView.refresh());
            } catch (Exception e) {
                log.error("Subscription change failed", e);
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle(I18n.get("dialog.error"));
                    alert.setHeaderText(I18n.get(failureHeaderKey));
                    alert.setContentText(e.getMessage());
                    alert.showAndWait();
                });
            }
        });
    }

    /**
     * The add/edit form, prefilled with {@code name} and {@code url}.
     *
     * @return what the user entered, or empty when cancelled or incomplete
     */
    private Optional<Entry> showSubscriptionDialog(String title, String header,
                                                   String name, String url) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(header);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        TextField nameField = new TextField();
        nameField.setPromptText(I18n.get("subscriptions.name.prompt"));
        nameField.setPrefWidth(350);
        TextField urlField = new TextField();
        urlField.setPromptText("https://example.com/subscribe/...");
        urlField.setPrefWidth(350);

        // Non-blocking warning: a plaintext http subscription is
        // MITM-injectable, but some providers only offer http, so this shows
        // the risk while the URL is http and never stops the user.
        Label httpWarning = new Label(I18n.get("subscriptions.http.warning"));
        httpWarning.setWrapText(true);
        httpWarning.setMaxWidth(350);
        // Literal amber: the dialog is not inside the themed scene graph, so a
        // looked-up -c-warn colour could fail to resolve.
        httpWarning.setStyle("-fx-text-fill: #ef6c00; -fx-font-size: 11px;");
        httpWarning.setVisible(false);
        httpWarning.setManaged(false);
        urlField.textProperty().addListener((obs, oldVal, newVal) -> {
            boolean insecure = SubscriptionService.isInsecureHttpUrl(newVal);
            httpWarning.setVisible(insecure);
            httpWarning.setManaged(insecure);
        });
        // After the listener, so an http URL being edited shows its warning.
        nameField.setText(name == null ? "" : name);
        urlField.setText(url == null ? "" : url);

        grid.add(new Label(I18n.get("subscriptions.name.label")), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label(I18n.get("subscriptions.url.label")), 0, 1);
        grid.add(urlField, 1, 1);
        grid.add(httpWarning, 1, 2);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Platform.runLater(nameField::requestFocus);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return Optional.empty();
        }
        String enteredName = nameField.getText().trim();
        String enteredUrl = urlField.getText().trim();
        if (enteredName.isEmpty() || enteredUrl.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle(I18n.get("subscriptions.invalid.input"));
            alert.setHeaderText(I18n.get("subscriptions.name.url.required"));
            alert.showAndWait();
            return Optional.empty();
        }
        return Optional.of(new Entry(enteredName, enteredUrl));
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

    /**
     * The provider's quota when the response carried one: traffic used of
     * the plan's total, and the expiry. Null when the provider said nothing.
     */
    static String quotaLine(Subscription sub) {
        List<String> parts = new ArrayList<>();
        if (sub.getTotalBytes() > 0) {
            parts.add(I18n.get("subscriptions.traffic",
                    TrafficMonitor.formatBytes(sub.getUploadBytes() + sub.getDownloadBytes()),
                    TrafficMonitor.formatBytes(sub.getTotalBytes())));
        }
        if (sub.getExpiresAt() > 0) {
            String date = DATE_FORMAT.format(Instant.ofEpochSecond(sub.getExpiresAt()));
            boolean expired = sub.getExpiresAt() < Instant.now().getEpochSecond();
            parts.add(I18n.get(expired ? "subscriptions.expired" : "subscriptions.expires", date));
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
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

            // Scheme and host only: the path and query carry the account
            // token, and the file this row is read from seals the URL for
            // exactly that reason. The first 47 characters showed it anyway.
            Label urlLabel = new Label(sub.getUrl() == null ? "" : Redact.url(sub.getUrl()));
            urlLabel.getStyleClass().add("server-address");

            int serverCount = sub.getServerIds().size();
            String servers = serverCount == 1
                    ? I18n.get("subscriptions.servers.one", String.valueOf(serverCount))
                    : I18n.get("subscriptions.servers.many", String.valueOf(serverCount));
            String refresh = sub.getLastRefreshedAt() > 0
                    ? I18n.get("subscriptions.refreshed", TIME_FORMAT.format(
                            Instant.ofEpochMilli(sub.getLastRefreshedAt())))
                    : I18n.get("subscriptions.never.refreshed");
            String quota = quotaLine(sub);
            Label statusLabel = new Label(quota == null
                    ? servers + " · " + refresh : servers + " · " + refresh + " · " + quota);
            statusLabel.getStyleClass().add("server-address");

            VBox info = new VBox(2);
            info.getChildren().addAll(nameLabel, urlLabel, statusLabel);

            // A failed refresh is otherwise invisible: the row keeps showing an
            // old timestamp and looks the same as a healthy subscription.
            if (sub.getLastError() != null && !sub.getLastError().isBlank()) {
                Label errorLabel = new Label(
                        I18n.get("subscriptions.last.error", sub.getLastError()));
                errorLabel.getStyleClass().add("subscription-error");
                errorLabel.setWrapText(true);
                info.getChildren().add(errorLabel);
            }

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Button refreshBtn = new Button(I18n.get("button.refresh"));
            refreshBtn.getStyleClass().add("secondary-button");
            refreshBtn.setOnAction(e -> refreshSubscription(sub));

            Button editBtn = new Button(I18n.get("button.edit"));
            editBtn.getStyleClass().add("secondary-button");
            editBtn.setOnAction(e -> editSubscription(sub));

            Button deleteBtn = new Button(I18n.get("button.delete"));
            deleteBtn.getStyleClass().add("secondary-button");
            deleteBtn.setOnAction(e -> deleteSubscription(sub));

            // 12, like the row around them and like the server rows: 8 was
            // the only gap in a list row that was not.
            HBox buttons = new HBox(12, refreshBtn, editBtn, deleteBtn);
            buttons.setAlignment(Pos.CENTER_RIGHT);

            row.getChildren().addAll(info, spacer, buttons);
            setGraphic(row);
        }
    }

}
