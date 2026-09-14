package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.service.ThemeManager;
import java.util.Optional;
import javafx.scene.Scene;
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
     * now: a snapshot, as ThemeManager hands every scene it does not own.
     */
    void setThemedScene(Scene scene) {
        Optional<ThemeManager> themes = ServiceLocator.find(ThemeManager.class);
        if (themes.isPresent()) {
            scene.getStylesheets().addAll(themes.get().currentStylesheets());
        } else {
            log.debug("ThemeManager unavailable; '{}' keeps the default styling", getTitle());
        }
        setScene(scene);
    }

    /**
     * Sets the stage down over its owner where JavaFX sets a Dialog down: the
     * middles of the two scenes together, half the owner's title bar lower.
     * A window calls this as it is first shown, once its scene is sized and
     * before it is on screen. With no owner on screen, the middle of the
     * screen.
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
        setX(owner.getX() + (ownerScene.getWidth() - scene.getWidth()) / 2);
        setY(owner.getY() + ownerScene.getY() / 2
                + (ownerScene.getHeight() - scene.getHeight()) / 2);
    }
}
