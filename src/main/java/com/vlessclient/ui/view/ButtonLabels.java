package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * <p>A width can also be shared across buttons rather than fitted to each —
 * see {@link #bindSharingWidth}, for pairs that are read side by side and so
 * have to agree. What no method here covers is a pair that needs both at
 * once, which is why {@code testLatencyButton} and {@code recheckButton} keep
 * their {@code prefWidth="140"} in the FXML: those two swap their labels
 * <em>and</em> line their edges up across the gap between two cards.</p>
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
     * Binds each button in the group to its label and pins the whole group to
     * one width — what the widest of those labels needs.
     *
     * <p>For buttons that read as a pair without sharing a row. Sized to its
     * own label each one comes out a different width, and two instances of
     * the same control a row apart look like two different controls; the
     * Updates block in Settings had "Check for updates" at 161px above
     * "Download" at 107.</p>
     *
     * <p>Measured rather than written into the FXML for the reason
     * {@link #bind} gives: a number picked for English is the wrong number in
     * Russian, where the same pair runs 201px and 98. This asks the two
     * labels what they need in the language actually on screen, and asks
     * again when that changes.</p>
     */
    static void bindSharingWidth(Map<Button, String> group) {
        group.forEach((button, key) -> button.textProperty().bind(I18n.binding(key)));

        // Same timing as bind(): the width depends on the font, which only
        // arrives with the stylesheet, so it cannot be measured until the
        // buttons are in a scene.
        Button any = group.keySet().iterator().next();
        whenInScene(any, () -> pinToSharedWidest(group));
        I18n.localeProperty().addListener((obs, old, current) -> {
            if (any.getScene() != null) {
                pinToSharedWidest(group);
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
     * Measures every button against its own label and pins them all to the
     * widest answer. Both bounds again, for the same reason as
     * {@link #pinToWidest}: a preferred width alone still lets a cramped row
     * squeeze a button below it and clip the text.
     */
    private static void pinToSharedWidest(Map<Button, String> group) {
        double widest = 0;
        for (Map.Entry<Button, String> entry : group.entrySet()) {
            widest = Math.max(widest, naturalWidth(entry.getKey(), entry.getValue()));
        }
        for (Button button : group.keySet()) {
            button.setMinWidth(widest);
            button.setPrefWidth(widest);
        }
    }

    /**
     * What the button needs to show the label behind {@code key}, with any
     * earlier pin released first — {@code prefWidth(-1)} reports the pinned
     * value when one is set, so measuring through it would keep answering
     * with the previous language's width and never grow.
     *
     * <p>The string comes from {@link I18n#get} rather than the button's own
     * bound text because this also runs from a locale listener: the bundle is
     * swapped before the locale property fires, so {@code get()} is already
     * on the new language whatever order the listeners run in.</p>
     */
    private static double naturalWidth(Button button, String key) {
        button.textProperty().unbind();
        button.setText(I18n.get(key));
        button.setMinWidth(Region.USE_COMPUTED_SIZE);
        button.setPrefWidth(Region.USE_COMPUTED_SIZE);
        button.applyCss();
        double width = button.prefWidth(-1);
        button.textProperty().bind(I18n.binding(key));
        return width;
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
