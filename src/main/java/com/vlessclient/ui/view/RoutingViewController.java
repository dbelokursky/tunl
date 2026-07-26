package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.RoutingRule;
import com.vlessclient.service.RoutingService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for the Routing view. The three sections — bypass countries,
 * the editable bypass list, and custom per-domain/IP rules — are always
 * active and compose; routing everything through the VPN is simply all of
 * them empty.
 */
public class RoutingViewController {

    private static final Logger log = LoggerFactory.getLogger(RoutingViewController.class);

    @FXML private javafx.scene.layout.FlowPane bypassCountryChips;
    @FXML private ComboBox<String> bypassCountryCombo;
    @FXML private Label bypassCountryHint;
    @FXML private VBox customRulesSection;
    @FXML private ListView<RoutingRule> rulesListView;
    @FXML private VBox emptyState;
    @FXML private Button addRuleButton;
    @FXML private TextArea bypassListArea;
    @FXML private Label bypassCountLabel;
    @FXML private Button saveBypassButton;

    private RoutingService routingService;
    private final ObservableList<RoutingRule> rulesList = FXCollections.observableArrayList();

    /**
     * Loads the routing config, populates the country chips and their ISO
     * catalog, and binds the rules list and bypass textarea.
     */
    @FXML
    public void initialize() {
        routingService = ServiceLocator.get(RoutingService.class);

        RoutingConfig config = routingService.getConfig();
        initBypassCountryChips(config);

        rulesListView.setItems(rulesList);
        rulesListView.setCellFactory(list -> new RuleListCell());

        loadRules();
        loadBypassList();
        if (bypassListArea != null) {
            bypassListArea.textProperty().addListener(
                    (obs, oldText, newText) -> updateBypassCount());
        }
        updateBypassCount();
        updateEmptyState();
    }

    /**
     * Maps "code — display name" catalog entries back to the bare code. The
     * catalog answers "which countries CAN I pick": every ISO-3166 country
     * the JDK knows, with a human-readable name, so nobody has to guess
     * two-letter codes. Committed input is validated against the same
     * catalog — an invalid code would otherwise become a remote rule-set
     * URL that 404s and keeps sing-box from starting.
     */
    private static String countryCodeOf(String catalogEntry) {
        int sep = catalogEntry.indexOf(" — ");
        return (sep > 0 ? catalogEntry.substring(0, sep) : catalogEntry)
                .trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String catalogEntryFor(String code) {
        String upper = code.toUpperCase(java.util.Locale.ROOT);
        String name = java.util.Locale.of("", upper)
                .getDisplayCountry(java.util.Locale.ENGLISH);
        return code + (name.isEmpty() || name.equals(upper) ? "" : " — " + name);
    }

    /**
     * Renders the selected bypass countries as removable chips in front of
     * an "add" ComboBox listing the full ISO catalog. Committing a value
     * (Enter, item pick, or focus loss) appends a chip; input that resolves
     * to no ISO code is dropped by the model setter and simply doesn't
     * stick. Empty selection is valid — it means no country bypass.
     */
    private void initBypassCountryChips(RoutingConfig config) {
        if (bypassCountryCombo == null || bypassCountryChips == null) {
            return;
        }
        List<String> catalog = new ArrayList<>();
        for (String iso : java.util.Locale.getISOCountries()) {
            catalog.add(catalogEntryFor(iso.toLowerCase(java.util.Locale.ROOT)));
        }
        catalog.sort(String.CASE_INSENSITIVE_ORDER);
        bypassCountryCombo.getItems().addAll(catalog);
        bypassCountryCombo.setVisibleRowCount(12);

        bypassCountryCombo.setOnAction(e -> commitCountryFromCombo());
        if (bypassCountryCombo.getEditor() != null) {
            bypassCountryCombo.getEditor().focusedProperty().addListener((obs, was, isNow) -> {
                if (!isNow) {
                    commitCountryFromCombo();
                }
            });
        }

        renderCountryChips(config.getBypassCountries());
    }

    /** Adds the combo's current selection/text to the country list. */
    private void commitCountryFromCombo() {
        String raw = bypassCountryCombo.getEditor() != null
                ? bypassCountryCombo.getEditor().getText()
                : bypassCountryCombo.getValue();
        if (raw == null || raw.isBlank()) {
            return;
        }
        RoutingConfig current = routingService.getConfig();
        List<String> countries = new ArrayList<>(current.getBypassCountries());
        countries.add(countryCodeOf(raw));
        current.setBypassCountries(countries);
        routingService.saveConfig(current);

        // Clearing value fires onAction again; the isBlank guard above makes
        // that re-entry a no-op.
        bypassCountryCombo.setValue(null);
        if (bypassCountryCombo.getEditor() != null) {
            bypassCountryCombo.getEditor().clear();
        }
        renderCountryChips(current.getBypassCountries());
    }

    private void removeCountry(String code) {
        RoutingConfig current = routingService.getConfig();
        List<String> countries = new ArrayList<>(current.getBypassCountries());
        countries.remove(code);
        current.setBypassCountries(countries);
        routingService.saveConfig(current);
        renderCountryChips(current.getBypassCountries());
    }

    /** Rebuilds the chip nodes, keeping the add-combo as the last child. */
    private void renderCountryChips(List<String> countries) {
        List<javafx.scene.Node> chips = new ArrayList<>();
        for (String code : countries) {
            HBox chip = new HBox(6);
            chip.getStyleClass().add("country-chip");
            chip.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

            Label codeLabel = new Label(code);
            codeLabel.getStyleClass().add("country-chip-label");
            javafx.scene.control.Tooltip.install(chip,
                    new javafx.scene.control.Tooltip(catalogEntryFor(code)));
            chip.getChildren().add(codeLabel);

            Label remove = new Label("✕");
            remove.getStyleClass().add("country-chip-remove");
            remove.setOnMouseClicked(e -> removeCountry(code));
            chip.getChildren().add(remove);
            chips.add(chip);
        }
        chips.add(bypassCountryCombo);
        bypassCountryChips.getChildren().setAll(chips);
    }

    private void loadBypassList() {
        if (bypassListArea == null) {
            return;
        }
        List<String> list = routingService.getConfig().getBypassList();
        if (list == null || list.isEmpty()) {
            bypassListArea.clear();
            return;
        }
        bypassListArea.setText(String.join("\n", list));
    }

    @FXML
    private void onSaveBypassClicked() {
        if (bypassListArea == null) {
            return;
        }
        String text = bypassListArea.getText();
        List<String> parsed = new ArrayList<>();
        if (text != null) {
            for (String line : text.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    parsed.add(trimmed);
                }
            }
        }
        RoutingConfig config = routingService.getConfig();
        config.setBypassList(parsed);
        routingService.saveConfig(config);
        log.info("Bypass list saved: {} entries", parsed.size());

        // Brief button feedback
        String originalText = saveBypassButton.getText();
        saveBypassButton.setText(I18n.get("routing.bypass.saved"));
        saveBypassButton.setDisable(true);
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            Platform.runLater(() -> {
                saveBypassButton.setText(originalText);
                saveBypassButton.setDisable(false);
            });
        });
    }

    @FXML
    private void onAddRuleClicked() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("routing.rule.add.title"));
        dialog.setHeaderText(I18n.get("routing.rule.add.header"));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        ComboBox<RoutingRule.RuleType> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll(RoutingRule.RuleType.values());
        typeCombo.setValue(RoutingRule.RuleType.DOMAIN_SUFFIX);
        typeCombo.setPrefWidth(200);
        typeCombo.setCellFactory(cb -> new ListCell<>() {
            @Override
            protected void updateItem(RoutingRule.RuleType item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getValue());
            }
        });
        typeCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(RoutingRule.RuleType item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getValue());
            }
        });

        TextField valueField = new TextField();
        valueField.setPromptText(I18n.get("routing.rule.value.prompt"));
        valueField.setPrefWidth(300);

        ComboBox<RoutingRule.RuleAction> actionCombo = new ComboBox<>();
        actionCombo.getItems().addAll(RoutingRule.RuleAction.values());
        actionCombo.setValue(RoutingRule.RuleAction.PROXY);
        actionCombo.setCellFactory(cb -> new ListCell<>() {
            @Override
            protected void updateItem(RoutingRule.RuleAction item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatAction(item));
            }
        });
        actionCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(RoutingRule.RuleAction item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatAction(item));
            }
        });

        grid.add(new Label(I18n.get("form.type") + ":"), 0, 0);
        grid.add(typeCombo, 1, 0);
        grid.add(new Label(I18n.get("routing.rule.value") + ":"), 0, 1);
        grid.add(valueField, 1, 1);
        grid.add(new Label(I18n.get("routing.rule.action") + ":"), 0, 2);
        grid.add(actionCombo, 1, 2);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Platform.runLater(valueField::requestFocus);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            String value = valueField.getText().trim();
            if (value.isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle(I18n.get("routing.rule.invalid.title"));
                alert.setHeaderText(
                        I18n.get("error.field.required", I18n.get("routing.rule.value")));
                alert.showAndWait();
                return;
            }

            RoutingRule rule = new RoutingRule(
                    typeCombo.getValue(), value, actionCombo.getValue());
            routingService.addRule(rule);
            loadRules();
        }
    }

    /**
     * Deletes a rule after confirming. Deleting used to happen on a single
     * click of the in-row button, with no confirmation and no undo — unlike
     * servers and subscriptions, which both ask first.
     */
    private void deleteRule(RoutingRule rule) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("routing.rule.delete.title"));
        confirm.setHeaderText(I18n.get("routing.rule.delete.header",
                rule.getType() + " " + rule.getValue()));
        confirm.setContentText(I18n.get("servers.delete.warning"));

        java.util.Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            routingService.removeRule(rule.getId());
            loadRules();
        }
    }

    private void loadRules() {
        RoutingConfig config = routingService.getConfig();
        rulesList.setAll(config.getRules());
        updateEmptyState();
    }

    private void updateEmptyState() {
        boolean empty = rulesList.isEmpty();
        emptyState.setVisible(empty);
        emptyState.setManaged(empty);
        rulesListView.setVisible(!empty);
        rulesListView.setManaged(!empty);
    }

    /**
     * Refreshes the "N entries" badge from the textarea, counting only
     * non-blank, non-comment lines (matching what {@link #onSaveBypassClicked()}
     * actually persists).
     */
    private void updateBypassCount() {
        if (bypassCountLabel == null) {
            return;
        }
        int count = 0;
        if (bypassListArea != null && bypassListArea.getText() != null) {
            for (String line : bypassListArea.getText().split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    count++;
                }
            }
        }
        bypassCountLabel.setText(count == 1
                ? I18n.get("routing.bypass.count.one")
                : I18n.get("routing.bypass.count.many", String.valueOf(count)));
    }

    private String formatAction(RoutingRule.RuleAction action) {
        return switch (action) {
            case PROXY -> "Proxy";
            case DIRECT -> "Direct";
            case BLOCK -> "Block";
        };
    }

    private class RuleListCell extends ListCell<RoutingRule> {

        @Override
        protected void updateItem(RoutingRule rule, boolean empty) {
            super.updateItem(rule, empty);
            if (empty || rule == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            HBox row = new HBox(12);
            row.getStyleClass().add("server-list-item");
            row.setAlignment(Pos.CENTER_LEFT);

            Label typeLabel = new Label(rule.getType().getValue());
            typeLabel.getStyleClass().add("protocol-badge");
            typeLabel.setMinWidth(110);

            Label valueLabel = new Label(rule.getValue());
            valueLabel.getStyleClass().add("server-name");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label actionBadge = new Label(formatAction(rule.getAction()));
            actionBadge.getStyleClass().add("protocol-badge");

            Button deleteBtn = new Button(I18n.get("button.delete"));
            deleteBtn.getStyleClass().add("secondary-button");
            deleteBtn.setOnAction(e -> deleteRule(rule));

            row.getChildren().addAll(typeLabel, valueLabel, spacer, actionBadge, deleteBtn);
            setGraphic(row);
        }
    }
}
