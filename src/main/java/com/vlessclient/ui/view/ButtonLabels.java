package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import java.util.ArrayList;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.beans.binding.Bindings;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.layout.Region;
import javafx.util.Duration;

/**
 * Button labels that come from the bundle: the ones that swap mid-action
 * without resizing the button, and the "add" labels that carry a marker.
 *
 * <p>Several buttons change text mid-action — "Copy" becomes "Copied!",
 * "Measure" becomes "Measuring…". Left alone the button resizes with its
 * label, and since these buttons sit at the end of a row, everything beside
 * them slides while the pointer is still over the button that moved.</p>
 *
 * <p>The width is measured from the translated strings rather than written
 * into the FXML as a number. A hand-picked number has to be re-derived for
 * every language — Russian labels here run up to 50px wider than their
 * English originals — and silently truncates the label when a translation
 * outgrows it. Measuring cannot drift out of date, because it reads the same
 * strings the button will actually show.</p>
 *
 * <p>Every width here is fitted to the button's own labels. A shared width
 * used to live alongside it, for a pair that read together while standing a
 * row apart — the Updates block's two buttons. They share a row now, where
 * natural widths read correctly, and the shared pin went with the layout that
 * needed it. {@code recheckButton} keeps its {@code prefWidth="140"} as the
 * last hand-picked number, and could be measured like everything else here.</p>
 */
final class ButtonLabels {

    /** How long a confirmation stays up before the idle label returns. */
    private static final Duration FLASH = Duration.millis(1200);

    /**
     * Fronts the label of a button that adds something. It lives here rather
     * than in the bundle because the same keys name dialogs too — putting the
     * marker in {@code button.add.subscription} would title that dialog
     * "+ Add Subscription".
     */
    private static final String ADD_MARKER = "+ ";

    /**
     * Keys into the button's own property map, so per-button state rides with
     * the button and dies with it — a static map here would outlive the views
     * and keep every button that ever used this class alive.
     */
    private static final Object IDLE_KEY = new Object();
    private static final Object PENDING_FLASH = new Object();

    private ButtonLabels() {
    }

    /**
     * Binds the button's label to {@code idleKey} and pins its width to the
     * widest label it can show, counting {@code alternateKeys} — the busy and
     * confirmation states it swaps in later.
     *
     * <p>Binding rather than leaving the FXML text in place is the point:
     * a label written into the FXML never follows a language switch, so a
     * button whose confirmation <em>is</em> translated ends up showing two
     * languages in turn.</p>
     */
    static void bind(Button button, String idleKey, String... alternateKeys) {
        button.getProperties().put(IDLE_KEY, idleKey);
        button.textProperty().bind(I18n.binding(idleKey));

        List<String> keys = new ArrayList<>();
        keys.add(idleKey);
        keys.addAll(List.of(alternateKeys));
        pinWidth(button, keys);
    }

    /**
     * Pins a button wide enough for every label it can show, without touching
     * its text — for buttons a controller drives with {@code setText} as its
     * own state changes, where binding the label here would fight it.
     *
     * <p>The width still has to come from the strings rather than from a
     * number in the FXML: {@code connectButton} asked for 170 and "Подключить"
     * needs 173.7, so the app's main action rendered as "Подключ…".</p>
     */
    static void pinWidth(Button button, String... keys) {
        pinWidth(button, List.of(keys));
    }

    private static void pinWidth(Button button, List<String> keys) {
        // Measuring needs the font, which arrives with the stylesheet, so it
        // cannot run here: initialize() is called while the view is still
        // detached. Wait for a scene, then re-measure on every locale change.
        whenInScene(button, () -> pinToWidest(button, keys));
        I18n.localeProperty().addListener((obs, old, current) -> {
            if (button.getScene() != null) {
                pinToWidest(button, keys);
            }
        });
    }

    /**
     * Binds a label that never changes, so it follows a language switch.
     * No width pinning: nothing swaps it, so nothing can make it jump.
     */
    static void bindStatic(Labeled node, String key) {
        node.textProperty().bind(I18n.binding(key));
    }

    /** Same, fronted by the marker an add-action carries. */
    static void bindAddAction(Labeled node, String key) {
        node.textProperty().bind(Bindings.concat(ADD_MARKER, I18n.binding(key)));
    }

    /**
     * Swaps in another label — a busy state that lasts as long as the work
     * does. Pairs with {@link #reset}.
     */
    static void show(Button button, String key) {
        button.textProperty().unbind();
        button.setText(I18n.get(key));
    }

    /** Returns to the idle label passed to {@link #bind}. */
    static void reset(Button button) {
        Object idleKey = button.getProperties().get(IDLE_KEY);
        if (idleKey != null) {
            button.textProperty().bind(I18n.binding((String) idleKey));
        }
    }

    /**
     * Shows a confirmation for a moment, then restores the idle label.
     *
     * <p>Repeated clicks restart the countdown rather than stacking timers,
     * so the confirmation always outlives the last click by the full delay.</p>
     */
    static void flash(Button button, String confirmationKey) {
        show(button, confirmationKey);
        PauseTransition pending = (PauseTransition) button.getProperties().get(PENDING_FLASH);
        if (pending != null) {
            pending.stop();
        }
        PauseTransition pause = new PauseTransition(FLASH);
        pause.setOnFinished(event -> reset(button));
        button.getProperties().put(PENDING_FLASH, pause);
        pause.play();
    }

    /**
     * Sets the button's width to the widest of its labels. Both bounds are
     * pinned: a preferred width alone still lets a cramped row squeeze the
     * button below it and clip the text.
     */
    private static void pinToWidest(Button button, List<String> keys) {
        final boolean wasBound = button.textProperty().isBound();
        final String current = button.getText();
        button.textProperty().unbind();

        // Release an earlier pin first. prefWidth(-1) reports the pinned value
        // when one is set, so measuring through it would keep answering with
        // the previous language's width and never grow.
        button.setMinWidth(Region.USE_COMPUTED_SIZE);
        button.setPrefWidth(Region.USE_COMPUTED_SIZE);

        double widest = 0;
        for (String key : keys) {
            button.setText(I18n.get(key));
            button.applyCss();
            widest = Math.max(widest, button.prefWidth(-1));
        }

        button.setText(current);
        if (wasBound) {
            reset(button);
        }
        button.applyCss();

        button.setMinWidth(widest);
        button.setPrefWidth(widest);
    }

    /**
     * Runs the action whenever the button is in a scene — now if it already
     * is, and again every time it returns to one.
     *
     * <p>Every time, not just the first: MainView shows a view by replacing
     * the content area's children, so all the views the user is not looking at
     * have left the scene graph. A language switch made while a view is away
     * skips its buttons — {@link #pinToWidest} needs a scene to measure in —
     * and without a second chance on the way back, the button keeps the width
     * of the language it was last measured in. That is how the Routing save
     * button came to render "Сох…".</p>
     */
    private static void whenInScene(Button button, Runnable action) {
        if (button.getScene() != null) {
            action.run();
        }
        button.sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) {
                action.run();
            }
        });
    }
}
