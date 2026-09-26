package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.Subscription;
import com.vlessclient.service.Redact;
import com.vlessclient.service.SubscriptionService;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Scene;
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
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for the Subscriptions view. Lists configured subscriptions, adds
 * new ones, and refreshes them (individually or all at once) off the FX thread
 * so a slow fetch never blocks the UI.
 */
public class SubscriptionsViewController implements ViewShownAware {

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

    /** Ids of the subscriptions this view is refreshing; FX thread only. */
    private final Set<String> refreshing = new HashSet<>();

    /** Whether Refresh All is running; FX thread only. */
    private boolean refreshingAll;

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
     * Redraws the rows. A refresh records its outcome in the subscription
     * itself, and the hourly one tells no view, so rows this cached view drew
     * earlier went on showing an old timestamp and no error.
     */
    @Override
    public void onViewShown() {
        subscriptionListView.refresh();
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
        openAddSubscriptionDialog("");
    }

    /**
     * Opens the form that adds a subscription, holding {@code url}: empty from
     * this page's button, or a URL pasted on the Servers page.
     *
     * @param url what the URL field starts with
     */
    public void openAddSubscriptionDialog(String url) {
        showSubscriptionDialog(I18n.get("button.add.subscription"),
                I18n.get("subscriptions.add.header"), "", url)
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
        runOffFxThread(action, failureHeaderKey, () -> { });
    }

    /**
     * Runs a service call off the FX thread and reports a failure in a dialog.
     * Either way {@code whenDone} then runs on the FX thread, and the rows are
     * redrawn.
     */
    private void runOffFxThread(Runnable action, String failureHeaderKey, Runnable whenDone) {
        // Taken while the view is on screen: the failure can land after the
        // user has gone to another page, which takes this view out of the window.
        Window owner = ownerWindow();
        Thread.startVirtualThread(() -> {
            try {
                action.run();
                Platform.runLater(() -> {
                    whenDone.run();
                    subscriptionListView.refresh();
                });
            } catch (Exception e) {
                log.error("Subscription change failed", e);
                Platform.runLater(() -> {
                    whenDone.run();
                    subscriptionListView.refresh();
                    Alert alert = Dialogs.alert(Alert.AlertType.ERROR);
                    alert.setTitle(I18n.get("dialog.error"));
                    alert.setHeaderText(I18n.get(failureHeaderKey));
                    alert.setContentText(e.getMessage());
                    alert.initOwner(owner);
                    alert.showAndWait();
                });
            }
        });
    }

    /** The failure to show, worded as the MCP tool words it too. */
    private static String failureText(Subscription sub) {
        return SubscriptionService.failureText(sub);
    }

    /**
     * The add/edit form, prefilled with {@code name} and {@code url}.
     *
     * @return what the user entered, or empty when cancelled
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
        httpWarning.getStyleClass().add("subscription-http-warning");
        httpWarning.setWrapText(true);
        httpWarning.setMaxWidth(350);
        // Never shorter than its wrapped lines: a window sized to its scene
        // measures the dialog without a width, which makes this label one line.
        httpWarning.setMinHeight(Region.USE_PREF_SIZE);
        httpWarning.setVisible(false);
        httpWarning.setManaged(false);
        urlField.textProperty().addListener((obs, oldVal, newVal) -> {
            boolean insecure = SubscriptionService.isInsecureHttpUrl(newVal);
            if (insecure == httpWarning.isVisible()) {
                return;
            }
            httpWarning.setVisible(insecure);
            httpWarning.setManaged(insecure);
            // A shown dialog keeps the size it opened with: a warning typed in
            // got one line and pushed the buttons out, and one that went left
            // a blank band.
            Window window = dialog.getDialogPane().getScene().getWindow();
            if (window.isShowing()) {
                window.sizeToScene();
            }
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
        Dialogs.localizeButtons(dialog.getDialogPane());
        // OK waits for the URL. The fields used to be checked after the dialog
        // closed: OK on an incomplete form put up a warning and dropped what
        // had been typed. A name left empty is the provider's own
        // (profile-title), or the host's, from the first refresh.
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(
                Bindings.createBooleanBinding(
                        () -> urlField.getText().isBlank(), urlField.textProperty()));
        dialog.initOwner(ownerWindow());

        Platform.runLater(nameField::requestFocus);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return Optional.empty();
        }
        return Optional.of(new Entry(nameField.getText().trim(), urlField.getText().trim()));
    }

    // Both refreshes ran on a bare thread: an exception ended it without a
    // word, and every click while one ran started another.
    @FXML
    private void onRefreshAllClicked() {
        if (refreshingAll) {
            return;
        }
        refreshingAll = true;
        refreshAllButton.setDisable(true);
        subscriptionListView.refresh();
        runOffFxThread(subscriptionService::refreshAll, "subscriptions.refresh.failed", () -> {
            refreshingAll = false;
            refreshAllButton.setDisable(false);
        });
    }

    private void refreshSubscription(Subscription sub) {
        if (refreshingAll || !refreshing.add(sub.getId())) {
            return;
        }
        subscriptionListView.refresh();
        runOffFxThread(() -> subscriptionService.refreshSubscription(sub.getId()),
                "subscriptions.refresh.failed", () -> refreshing.remove(sub.getId()));
    }

    private void deleteSubscription(Subscription sub) {
        int servers = sub.getServerIds().size();
        // Enter keeps the subscription: the stock confirmation's OK took it,
        // with all its servers. Nothing to add for a subscription without
        // servers: "all 0 servers".
        if (Confirmations.confirmIrreversible(ownerWindow(),
                I18n.get("subscriptions.delete.title"),
                I18n.get("subscriptions.delete.confirm", shownName(sub)),
                servers == 0 ? null : I18n.plural("subscriptions.delete.content", servers),
                I18n.get("button.delete"))) {
            // Off the FX thread like add and edit: removing waits for the lock a
            // refresh holds while that refresh waits for the FX thread.
            runOffFxThread(() -> {
                subscriptionService.removeSubscription(sub.getId());
                log.info("Deleted subscription: {}", sub.getName());
            }, "subscriptions.delete.failed");
        }
    }

    /** The window the dialogs belong to, or null before the view is shown. */
    private Window ownerWindow() {
        Scene scene = subscriptionListView.getScene();
        return scene == null ? null : scene.getWindow();
    }

    /**
     * The name a row shows: the subscription's, or its host's until the
     * first refresh names a subscription that was added without one.
     *
     * @param sub the subscription
     * @return the name to show; empty when there is neither
     */
    public static String shownName(Subscription sub) {
        String name = sub.getName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        try {
            String host = sub.getUrl() == null ? null : URI.create(sub.getUrl().strip()).getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /**
     * The line under a subscription's URL: its servers, what its last list
     * held that this client cannot run, when it was refreshed, and the
     * provider's quota when it sent one.
     */
    static String statusLine(Subscription sub) {
        List<String> parts = new ArrayList<>();
        parts.add(I18n.plural("subscriptions.servers", sub.getServerIds().size()));
        if (sub.getLeftOutLinks() > 0) {
            parts.add(I18n.plural("subscriptions.left.out", sub.getLeftOutLinks())
                    + " (" + sub.getLeftOutSummary() + ")");
        }
        parts.add(sub.getLastRefreshedAt() > 0
                ? I18n.get("subscriptions.refreshed", TIME_FORMAT.format(
                        Instant.ofEpochMilli(sub.getLastRefreshedAt())))
                : I18n.get("subscriptions.never.refreshed"));
        String quota = quotaLine(sub);
        if (quota != null) {
            parts.add(quota);
        }
        return String.join(" · ", parts);
    }

    /**
     * The provider's quota when the response carried one: traffic used of
     * the plan's total, and the expiry. Null when the provider said nothing.
     */
    static String quotaLine(Subscription sub) {
        List<String> parts = new ArrayList<>();
        if (sub.getTotalBytes() > 0) {
            parts.add(I18n.get("subscriptions.traffic",
                    TrafficText.bytes(sub.getUploadBytes() + sub.getDownloadBytes()),
                    TrafficText.bytes(sub.getTotalBytes())));
        }
        sub.expiry().ifPresent(expiry -> parts.add(I18n.get(
                expiry.isBefore(Instant.now()) ? "subscriptions.expired" : "subscriptions.expires",
                DATE_FORMAT.format(expiry))));
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

            Label nameLabel = new Label(shownName(sub));
            nameLabel.getStyleClass().add("server-name");

            // Scheme and host only: the path and query carry the account
            // token, and the file this row is read from seals the URL for
            // exactly that reason. The first 47 characters showed it anyway.
            Label urlLabel = new Label(sub.getUrl() == null ? "" : Redact.url(sub.getUrl()));
            urlLabel.getStyleClass().add("server-address");

            Label statusLabel = new Label(statusLine(sub));
            statusLabel.getStyleClass().add("server-address");

            VBox info = new VBox(2);
            info.getChildren().addAll(nameLabel, urlLabel, statusLabel);

            // What the provider tells its users, as its last answer said it.
            if (!sub.getAnnounce().isEmpty()) {
                Label announceLabel = new Label(
                        I18n.get("subscriptions.announce", sub.getAnnounce()));
                announceLabel.getStyleClass().add("server-address");
                announceLabel.setWrapText(true);
                info.getChildren().add(announceLabel);
            }

            // A failed refresh is otherwise invisible: the row keeps showing an
            // old timestamp and looks the same as a healthy subscription.
            if (sub.hasLastError()) {
                Label errorLabel = new Label(
                        I18n.get("subscriptions.last.error", failureText(sub)));
                errorLabel.getStyleClass().add("subscription-error");
                errorLabel.setWrapText(true);
                info.getChildren().add(errorLabel);
            }

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Button refreshBtn = new Button(I18n.get("button.refresh"));
            refreshBtn.getStyleClass().add("secondary-button");
            refreshBtn.setOnAction(e -> refreshSubscription(sub));
            refreshBtn.setDisable(refreshingAll || refreshing.contains(sub.getId()));

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
