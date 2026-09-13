package com.vlessclient.ui.view.settings;

import com.vlessclient.app.I18n;
import com.vlessclient.service.TrafficHistoryStore;
import com.vlessclient.service.TrafficMonitor;
import com.vlessclient.ui.view.Icons;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;

/**
 * The Traffic history card in Settings: how much the record holds, since
 * when, and the only control that clears it.
 *
 * <p>Nothing in the history expires on its own, so the app has to offer a way
 * to remove it. That used to be a Clear link at the end of the dashboard's
 * history header, in the corner where the pointer already works: beside the
 * server line, above today's bar, under the total that opens the panel. A
 * stray click there and a reflexive Enter on the dialog wiped the whole
 * record. Settings is a page people open on purpose.</p>
 *
 * <p>Extracted from {@link com.vlessclient.ui.view.SettingsViewController} the
 * way {@link UpdatesSection} is: the controller stays the FXML endpoint and
 * hands its injected controls over via {@link Controls}.</p>
 */
public final class TrafficHistorySettingsSection {

    /**
     * The FXML-injected controls this section drives.
     *
     * @param summary the line saying how much is recorded and since when
     * @param clearButton the button that clears the record
     */
    public record Controls(Label summary, Button clearButton) { }

    private final TrafficHistoryStore store;
    private final Controls controls;
    private final BooleanSupplier confirm;

    /**
     * Creates the section; nothing is wired until {@link #init()} runs.
     *
     * @param store the history to report and clear, or null when unavailable
     * @param controls the injected nodes
     * @param confirm asks the user before clearing, which happens only on
     *     true; a parameter so a test can answer without a modal dialog
     */
    public TrafficHistorySettingsSection(TrafficHistoryStore store, Controls controls,
                                         BooleanSupplier confirm) {
        this.store = store;
        this.controls = controls;
        this.confirm = confirm;
    }

    /** Puts the trash glyph on the button, wires it and paints the card. */
    public void init() {
        controls.clearButton().setGraphic(Icons.clear(16));
        controls.clearButton().setOnAction(event -> clear());

        // The summary is formatted text rather than a binding: its date follows
        // the locale, so a language switch has to repaint it. The locale
        // property outlives this view, so it holds only a weak wrapper and the
        // label keeps the listener itself.
        ChangeListener<Locale> repaint = (obs, oldLocale, newLocale) -> refresh();
        controls.summary().getProperties().put(TrafficHistorySettingsSection.class, repaint);
        I18n.localeProperty().addListener(new WeakChangeListener<>(repaint));

        refresh();
    }

    /**
     * Re-reads the record. The Settings view is cached, and a connected tunnel
     * keeps adding to the total while the view is hidden.
     */
    public void refresh() {
        long total = store == null ? 0 : store.totalRecorded();
        Optional<LocalDate> since = store == null ? Optional.empty() : store.firstRecordedDate();
        if (total == 0 || since.isEmpty()) {
            controls.summary().setText(I18n.get("settings.traffic.history.empty"));
        } else {
            controls.summary().setText(I18n.get("settings.traffic.history.summary",
                    TrafficMonitor.formatBytes(total),
                    since.get().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                            .withLocale(I18n.getLocale()))));
        }
        // An empty record has nothing to confirm: the button is off, rather
        // than opening a dialog about deleting nothing.
        controls.clearButton().setDisable(total == 0);
    }

    private void clear() {
        if (store == null || !confirm.getAsBoolean()) {
            return;
        }
        store.reset();
        refresh();
    }

    /**
     * The confirmation the Settings view hands in: a modal dialog, which is
     * why it is a static method passed as a supplier rather than something the
     * button calls directly.
     *
     * @return true when the user chose to clear the record
     */
    public static boolean confirmWithDialog() {
        ButtonType clear = new ButtonType(I18n.get("settings.traffic.history.clear.action"),
                ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.get("button.cancel"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION,
                I18n.get("settings.traffic.history.clear.content"), cancel, clear);
        dialog.setTitle(I18n.get("settings.traffic.history.clear.title"));
        dialog.setHeaderText(I18n.get("settings.traffic.history.clear.confirm"));

        // Enter must not be the key that deletes. The stock dialog makes OK its
        // default button, and a reflexive Enter was half of how the old Clear
        // link wiped the record: here Cancel takes Enter as well as Escape, and
        // the button that deletes has to be clicked and says what it does.
        if (dialog.getDialogPane().lookupButton(clear) instanceof Button deleting) {
            deleting.setDefaultButton(false);
        }
        if (dialog.getDialogPane().lookupButton(cancel) instanceof Button keeping) {
            keeping.setDefaultButton(true);
        }
        return dialog.showAndWait().filter(button -> button == clear).isPresent();
    }
}
