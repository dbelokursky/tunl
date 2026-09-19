package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.I18n;
import com.vlessclient.model.HealthCheckTarget;
import com.vlessclient.service.ServiceReachabilityChecker;
import com.vlessclient.ui.view.Dialogs;
import java.net.URI;
import java.net.URISyntaxException;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

/**
 * Dialog for adding a service-availability target: an optional display name
 * plus either an absolute http(s) URL or a bare host/IP with optional port.
 * OK stays disabled until the target parses; the result is a ready-to-store
 * {@link HealthCheckTarget}. Extracted from
 * {@link com.vlessclient.ui.view.DashboardViewController}.
 */
public final class AddHealthTargetDialog extends Dialog<HealthCheckTarget> {

    /**
     * Builds the dialog's fields, validation, and result converter; nothing
     * is shown until {@code showAndWait()}.
     */
    public AddHealthTargetDialog() {
        setTitle(I18n.get("health.target.add.title"));
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Dialogs.localizeButtons(getDialogPane());

        TextField nameField = new TextField();
        nameField.setPromptText(I18n.get("health.target.name"));
        TextField urlField = new TextField();
        urlField.setPromptText("https://example.com  ·  1.1.1.1  ·  1.1.1.1:53");

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        grid.add(new Label(I18n.get("health.target.name")), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label(I18n.get("health.target.target")), 0, 1);
        grid.add(urlField, 1, 1);
        getDialogPane().setContent(grid);

        var okButton = getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(true);
        urlField.textProperty().addListener((obs, o, n) ->
                okButton.setDisable(normalizeTarget(n) == null));

        setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return null;
            }
            ParsedTarget parsed = normalizeTarget(urlField.getText());
            if (parsed == null) {
                return null;
            }
            String name = nameField.getText() != null && !nameField.getText().isBlank()
                    ? nameField.getText().trim()
                    : parsed.displayHost();
            return new HealthCheckTarget(name, parsed.stored());
        });

        Platform.runLater(urlField::requestFocus);
    }

    /** A validated target: {@code stored} is persisted, {@code displayHost} names it. */
    record ParsedTarget(String stored, String displayHost) {
    }

    /**
     * Accepts either an absolute http(s) URL (probed over HTTP) or a bare
     * IP / host, optionally with {@code :port} (probed by a TCP connect
     * through the tunnel). Returns null for anything else.
     * Package-private for tests.
     */
    static ParsedTarget normalizeTarget(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            try {
                URI uri = new URI(trimmed);
                if (uri.getHost() == null || uri.getHost().isBlank()) {
                    return null;
                }
                return new ParsedTarget(uri.toString(), uri.getHost());
            } catch (URISyntaxException e) {
                return null;
            }
        }
        ServiceReachabilityChecker.HostPort hp =
                ServiceReachabilityChecker.parseHostPort(trimmed);
        return hp == null ? null : new ParsedTarget(trimmed, hp.host());
    }
}
