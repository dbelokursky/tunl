package com.vlessclient.service;

import com.vlessclient.testing.FxToolkitExtension;
import java.util.List;
import javafx.application.ColorScheme;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Auto", the default theme, followed the macOS appearance alone: everywhere
 * else it read as light, so a Windows or Linux desktop in dark mode got the
 * light theme, and choosing "Auto" looked the same as choosing "Light".
 * Outside macOS it now follows the color scheme JavaFX reports for the OS,
 * live; on macOS, where that scheme misses the appearance, the poller stays.
 */
@ExtendWith(FxToolkitExtension.class)
class ThemeManagerFollowsTheOsTest {

    @Test
    void autoFollowsTheSchemeTheOsReportsWhileItRuns() {
        SimpleObjectProperty<ColorScheme> os = new SimpleObjectProperty<>(ColorScheme.DARK);
        ThemeManager themes = new ThemeManager(1.0, os);
        Scene scene = FxExecutor.get(() -> new Scene(new StackPane()));
        try {
            FxExecutor.run(() -> themes.applyTheme(scene));
            assertThat(themeSheet(scene)).as("a dark desktop").endsWith("dark.css");
            assertThat(FxExecutor.get(themes::currentColorScheme)).isEqualTo(ColorScheme.DARK);

            FxExecutor.run(() -> os.set(ColorScheme.LIGHT));
            assertThat(themeSheet(scene)).as("switched to light").endsWith("light.css");
            assertThat(FxExecutor.get(themes::currentStylesheets).get(1)).endsWith("light.css");

            themes.setTheme("light");
            FxExecutor.run(() -> themes.applyTheme(scene));
            FxExecutor.run(() -> os.set(ColorScheme.DARK));
            assertThat(themeSheet(scene)).as("a theme chosen outright").endsWith("light.css");
        } finally {
            themes.stopWatching();
        }
    }

    /** The theme's own stylesheet on {@code scene}, after base.css. */
    private static String themeSheet(Scene scene) {
        return FxExecutor.get(() -> List.copyOf(scene.getStylesheets())).get(1);
    }
}
