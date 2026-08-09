package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import java.util.ArrayList;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.Scene;
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
 * <p>Note this deliberately does not cover {@code testLatencyButton} and
 * {@code recheckButton}, whose shared {@code prefWidth="140"} in the FXML
 * means something this class does not express: those two live in separate
 * cards and are pinned to a <em>common</em> width so their edges line up
 * across the gap. Sizing each to its own longest label would let them
 * disagree again.</p>
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

    /** Runs the action once the button has a scene, immediately if it has one. */
    private static void whenInScene(Button button, Runnable action) {
        if (button.getScene() != null) {
            action.run();
            return;
        }
        button.sceneProperty().addListener(new ChangeListener<Scene>() {
            @Override
            public void changed(ObservableValue<? extends Scene> obs, Scene old, Scene scene) {
                if (scene != null) {
                    button.sceneProperty().removeListener(this);
                    action.run();
                }
            }
        });
    }
}
