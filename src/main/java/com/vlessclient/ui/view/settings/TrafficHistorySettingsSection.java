package com.vlessclient.ui.view.settings;

import com.vlessclient.app.I18n;
import com.vlessclient.service.TrafficHistoryStore;
import com.vlessclient.service.TrafficMonitor;
import com.vlessclient.ui.view.Confirmations;
import com.vlessclient.ui.view.Icons;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Window;

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
    private final Predicate<Window> confirm;

    /**
     * Creates the section; nothing is wired until {@link #init()} runs.
     *
     * @param store the history to report and clear, or null when unavailable
     * @param controls the injected nodes
     * @param confirm asks the user before clearing, which happens only on
     *     true, and is handed the window the Clear button is in for its dialog
     *     to belong to; a parameter so a test can answer without a modal dialog
     */
    public TrafficHistorySettingsSection(TrafficHistoryStore store, Controls controls,
                                         Predicate<Window> confirm) {
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
        if (store == null || !confirm.test(ownerWindow())) {
            return;
        }
        store.reset();
        refresh();
    }

    /** The window the Clear button is in, or null while the card is in none. */
    private Window ownerWindow() {
        Scene scene = controls.clearButton().getScene();
        return scene == null ? null : scene.getWindow();
    }

    /**
     * The confirmation the Settings view hands in: a modal dialog, which is
     * why it is a static method passed as a function rather than something the
     * button calls directly.
     *
     * @param owner the window the dialog belongs to. JavaFX gives a dialog the
     *     stylesheets of its owner's scene and no others, so without an owner
     *     it came up in stock light Modena over the dark theme
     * @return true when the user chose to clear the record
     */
    public static boolean confirmWithDialog(Window owner) {
        return Confirmations.confirmIrreversible(owner,
                I18n.get("settings.traffic.history.clear.title"),
                I18n.get("settings.traffic.history.clear.confirm"),
                I18n.get("settings.traffic.history.clear.content"),
                I18n.get("settings.traffic.history.clear.action"));
    }
}
