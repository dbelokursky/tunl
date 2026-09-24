package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.service.ThemeManager;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.UiTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A window's native title bar wears the theme the app wears.
 *
 * <p>JavaFX paints the frame from the scene's color scheme: as the window is
 * first shown, in {@code Stage.doVisibleChanging}, and again whenever the
 * scheme changes. A scene nobody gives a scheme takes the platform's, which on
 * this runtime misses the macOS appearance (see ThemeManager), and nothing gave
 * one: in the dark theme the main window, the server form and every alert came
 * up under a white title bar. Monocle draws no frames, so what is checked is
 * the scheme the frame is painted from.</p>
 *
 * <p>Each check goes dark, then light, so that it fails without the fix
 * whichever scheme the headless platform reports.</p>
 */
@UiTest
public class TitleBarColorSchemeTest extends ApplicationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private final ThemeManager themes = new ThemeManager();
    private List<Window> windowsBefore;
    private Stage window;

    @Override
    public void start(Stage primary) {
        windowsBefore = List.copyOf(Window.getWindows());
        window = new Stage();
        window.setScene(new Scene(new StackPane(), 480, 320));
        window.show();
    }

    /** The dialogs find the manager where the app puts it; @UiTest restores the locator. */
    @BeforeEach
    void registerTheManager() {
        ServiceLocator.register(ThemeManager.class, themes);
    }

    /** Hiding a stage also ends the showAndWait of a confirmation a failure left open. */
    @AfterEach
    void closeTheWindows() {
        themes.stopWatching();
        interact(() -> {
            for (Window shown : List.copyOf(Window.getWindows())) {
                if (!windowsBefore.contains(shown)) {
                    shown.hide();
                }
            }
        });
    }

    @Test
    void theMainWindowFollowsTheTheme() {
        for (String theme : List.of("dark", "light")) {
            interact(() -> {
                themes.setTheme(theme);
                themes.applyTheme(window.getScene());
            });
            assertThat(schemeOf(window.getScene())).as(theme).isEqualTo(schemeFor(theme));
        }
    }

    /** The server form and the sing-box installer, which open as stages of their own. */
    @Test
    void aStageOfItsOwnOpensInTheTheme() {
        for (String theme : List.of("dark", "light")) {
            themes.setTheme(theme);
            Scene form = onFx(() -> {
                DialogStage stage = new DialogStage(window);
                stage.setThemedScene(new Scene(new StackPane(), 200, 100));
                return stage.getScene();
            });
            assertThat(schemeOf(form)).as(theme).isEqualTo(schemeFor(theme));
        }
    }

    @Test
    void anAlertOpensInTheTheme() {
        for (String theme : List.of("dark", "light")) {
            themes.setTheme(theme);
            Scene alert = onFx(() -> Dialogs.alert(Alert.AlertType.INFORMATION)
                    .getDialogPane().getScene());
            assertThat(schemeOf(alert)).as(theme).isEqualTo(schemeFor(theme));
        }
    }

    /** Deleting a server or a subscription asks through an alert of its own making. */
    @Test
    void aConfirmationOpensInTheTheme() {
        for (String theme : List.of("dark", "light")) {
            themes.setTheme(theme);
            Stage confirmation = open(() -> Confirmations.confirmIrreversible(window,
                    "Delete", "Delete the server?", "It cannot be undone.", "Delete"));
            assertThat(schemeOf(confirmation.getScene())).as(theme)
                    .isEqualTo(schemeFor(theme));
            interact(confirmation::hide);
        }
    }

    private static ColorScheme schemeFor(String theme) {
        return "dark".equals(theme) ? ColorScheme.DARK : ColorScheme.LIGHT;
    }

    private ColorScheme schemeOf(Scene scene) {
        return onFx(() -> scene.getPreferences().getColorScheme());
    }

    /**
     * Fires what opens a stage and returns the stage once it is on screen.
     * Through {@code runLater}: a confirmation is shown with {@code showAndWait},
     * which an {@code interact} would wait out.
     */
    private Stage open(Runnable opener) {
        List<Window> before = onFx(() -> List.copyOf(Window.getWindows()));
        Platform.runLater(opener);
        return Await.untilValue("a stage to open", () -> onFx(() -> {
            for (Window shown : Window.getWindows()) {
                if (!before.contains(shown) && shown.isShowing() && shown instanceof Stage stage) {
                    return stage;
                }
            }
            return null;
        }), Objects::nonNull, PATIENCE);
    }

    /** Runs {@code work} on the FX thread and hands back what it returned. */
    private <T> T onFx(Supplier<T> work) {
        List<T> result = new ArrayList<>(1);
        interact(() -> result.add(work.get()));
        return result.get(0);
    }
}
