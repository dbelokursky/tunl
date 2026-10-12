package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import java.util.Locale;
import java.util.function.Function;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;

/**
 * A combo box whose items are shown in the language of the UI, and shown
 * again when it changes.
 *
 * <p>A combo draws an item's text when the item is put in a cell and not
 * again, so after a language switch the closed combos and their lists stayed
 * in the language they were drawn in. Settings forced a redraw by setting
 * each combo's value to null and back, which its listeners took for the user
 * choosing: every switch wrote the settings again, and wrote back a value an
 * agent had changed meanwhile.</p>
 */
final class ComboLabels {

    /** Where a combo keeps its locale listener, which the locale holds only weakly. */
    private static final Object LISTENER = new Object();

    /** Marks a combo whose language changed while it was off screen. */
    private static final Object STALE = new Object();

    /** Where a combo keeps the binding that says whether it is on screen. */
    private static final Object ON_SCREEN = new Object();

    private ComboLabels() {
    }

    /**
     * Shows each item of {@code combo} as {@code label} words it, now and
     * after every change of language.
     *
     * @param combo the combo box
     * @param label the item's text in the language of the UI at the time
     * @param <T>   the type of its items
     */
    static <T> void follow(ComboBox<T> combo, Function<T, String> label) {
        Runnable draw = () -> {
            // New cells, not a new value: the skin redraws the closed combo
            // and its list for them, and the value stays as it is.
            combo.setCellFactory(list -> new Labelled<>(label));
            combo.setButtonCell(new Labelled<>(label));
        };
        draw.run();
        // Redrawn only while on screen (OnScreen). A combo of a view cached
        // off-screen, of a window hidden to the tray, or of one discarded and
        // not yet collected got new cells styled away from the stylesheets
        // that define their colours, and JavaFX logged a CSS warning for each
        // (modena's -fx-text-base-color, base.css's -c-text). Such a combo
        // is redrawn when it comes on screen again.
        ObservableValue<Boolean> onScreen = OnScreen.of(combo);
        ChangeListener<Locale> redraw = (obs, oldLocale, newLocale) -> {
            if (Boolean.TRUE.equals(onScreen.getValue())) {
                draw.run();
            } else {
                combo.getProperties().put(STALE, Boolean.TRUE);
            }
        };
        onScreen.addListener((obs, was, is) -> {
            if (Boolean.TRUE.equals(is) && combo.getProperties().remove(STALE) != null) {
                draw.run();
            }
        });
        combo.getProperties().put(ON_SCREEN, onScreen);
        // The locale outlives every view, and must not keep a discarded one.
        combo.getProperties().put(LISTENER, redraw);
        I18n.localeProperty().addListener(new WeakChangeListener<>(redraw));
    }

    /** A cell that shows its item as worded by a function. */
    private static final class Labelled<T> extends ListCell<T> {

        private final Function<T, String> label;

        Labelled(Function<T, String> label) {
            this.label = label;
        }

        @Override
        protected void updateItem(T item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : label.apply(item));
        }
    }
}
