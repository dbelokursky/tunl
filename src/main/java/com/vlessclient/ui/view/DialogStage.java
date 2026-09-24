package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.service.ThemeManager;
import java.util.List;
import java.util.Optional;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A window a view opens as a Stage of its own rather than a Dialog, which
 * belongs to the view's window all the same: owned by it, set down over it and
 * dressed in the app's theme.
 *
 * <p>A Dialog gets the last two from JavaFX. HeavyweightDialog binds the
 * dialog scene's stylesheets to its owner scene's, and the stage it builds
 * overrides {@link #centerOnScreen}, which a window calls as it is first shown
 * unless it was given a position, to sit over the owner. {@code initOwner} on
 * a plain Stage does neither: the server form and the sing-box installer came
 * up in the middle of the screen, and the installer, which took no stylesheet
 * either, in stock light Modena over the dark window.</p>
 */
final class DialogStage extends Stage {

    private static final Logger log = LoggerFactory.getLogger(DialogStage.class);

    /**
     * A stage that belongs to {@code owner}.
     *
     * @param owner the window of the view that opens the stage, or null when
     *     there is none (the view is in no window, or the main window does not
     *     exist yet), and the stage opens on its own in the middle of the screen
     */
    DialogStage(Window owner) {
        initOwner(owner);
    }

    /**
     * Puts {@code scene} in the stage, dressed in the stylesheets the app wears
     * now and a title bar to match: a snapshot, as ThemeManager hands every
     * scene it does not own.
     */
    void setThemedScene(Scene scene) {
        Optional<ThemeManager> themes = ServiceLocator.find(ThemeManager.class);
        if (themes.isPresent()) {
            scene.getStylesheets().addAll(themes.get().currentStylesheets());
            scene.getPreferences().setColorScheme(themes.get().currentColorScheme());
        } else {
            log.debug("ThemeManager unavailable; '{}' keeps the default styling", getTitle());
        }
        setScene(scene);
    }

    /**
     * Sets the stage down over its owner where JavaFX sets a Dialog down, the
     * middles of the two scenes together and half the owner's title bar lower,
     * but inside the screen the owner is on. A window calls this as it is first
     * shown, once its scene is sized and before it is on screen. With no owner
     * on screen, the middle of the screen.
     */
    @Override
    public void centerOnScreen() {
        Window owner = getOwner();
        Scene scene = getScene();
        if (owner == null || !owner.isShowing() || owner.getScene() == null || scene == null) {
            super.centerOnScreen();
            return;
        }
        Scene ownerScene = owner.getScene();
        double x = owner.getX() + (ownerScene.getWidth() - scene.getWidth()) / 2;
        double y = owner.getY() + ownerScene.getY() / 2
                + (ownerScene.getHeight() - scene.getHeight()) / 2;
        // JavaFX sets a Dialog down with no regard for the screen, which an
        // alert's size gets away with. The server form is taller than the main
        // window: over a window against the top or the bottom edge it would put
        // its title bar out of reach above the screen, or its Save button below
        // it. The owner's title bar stands in for this stage's, not drawn yet.
        Rectangle2D screen = screenOf(owner).getVisualBounds();
        setX(within(x, screen.getMinX(), screen.getMaxX() - scene.getWidth()));
        setY(within(y, screen.getMinY(),
                screen.getMaxY() - ownerScene.getY() - scene.getHeight()));
    }

    /** The screen the middle of {@code window} is on. */
    private static Screen screenOf(Window window) {
        List<Screen> screens = Screen.getScreensForRectangle(
                window.getX() + window.getWidth() / 2, window.getY() + window.getHeight() / 2,
                1, 1);
        return screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
    }

    /**
     * {@code value} kept between {@code min} and {@code max}, and at
     * {@code min} when the two cross: a stage larger than the screen keeps its
     * top-left corner, and so its title bar, on the screen.
     */
    private static double within(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }
}
