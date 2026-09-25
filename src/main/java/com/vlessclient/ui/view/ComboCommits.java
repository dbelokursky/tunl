package com.vlessclient.ui.view;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Skin;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * A combo box's value as the user commits it, rather than as the keyboard
 * moves through it.
 *
 * <p>JavaFX changes a ComboBox's value with every arrow key: Up and Down on a
 * closed combo step to the neighbouring item, and in an open list each press
 * selects the row under it. A listener on the value ran its action at every
 * step: the Dashboard's server selection restarted a live tunnel per key
 * press, and Settings re-themed or re-translated the whole window. Here Up
 * and Down on a closed combo open its list instead, as a macOS pop-up button
 * does; a choice made in the list counts once the list closes, and Escape
 * puts back the value the list opened with. A value set by code while the
 * list is closed counts at once.</p>
 */
final class ComboCommits {

    private ComboCommits() {
    }

    /**
     * The committed value of {@code combo}, whose arrow keys change as above.
     *
     * @param combo the combo box
     * @param <T>   the type of its items
     * @return a property that follows the combo's value, changing on a commit only
     */
    static <T> ReadOnlyObjectProperty<T> committed(ComboBox<T> combo) {
        final ReadOnlyObjectWrapper<T> committed = new ReadOnlyObjectWrapper<>(combo.getValue());
        final ObjectProperty<T> openedWith = new SimpleObjectProperty<>(combo.getValue());
        final BooleanProperty escaped = new SimpleBooleanProperty();
        combo.addEventFilter(KeyEvent.KEY_PRESSED, key -> {
            boolean arrow = key.getCode() == KeyCode.UP || key.getCode() == KeyCode.DOWN;
            if (arrow && !combo.isShowing() && !key.isAltDown() && !key.isShortcutDown()
                    && !key.isShiftDown()) {
                combo.show();
                key.consume();
            }
        });
        // While the list is open the skin takes the keys before the combo's
        // own filters see them, so Escape is watched on the list itself.
        watchForEscape(combo.getSkin(), escaped);
        combo.skinProperty().addListener((obs, oldSkin, skin) -> watchForEscape(skin, escaped));
        combo.showingProperty().addListener((obs, wasShowing, showing) -> {
            if (showing) {
                openedWith.set(combo.getValue());
                escaped.set(false);
            } else if (escaped.get()) {
                // The arrows already moved the value while the list was open;
                // put back what it opened with, now that nothing moves it.
                escaped.set(false);
                combo.setValue(openedWith.get());
            } else {
                committed.set(combo.getValue());
            }
        });
        combo.valueProperty().addListener((obs, oldValue, value) -> {
            if (!combo.isShowing()) {
                committed.set(value);
            }
        });
        return committed.getReadOnlyProperty();
    }

    private static void watchForEscape(Skin<?> skin, BooleanProperty escaped) {
        if (skin instanceof ComboBoxListViewSkin<?> listSkin
                && listSkin.getPopupContent() != null) {
            listSkin.getPopupContent().addEventFilter(KeyEvent.KEY_PRESSED, key -> {
                if (key.getCode() == KeyCode.ESCAPE) {
                    escaped.set(true);
                }
            });
        }
    }
}
