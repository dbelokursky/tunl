package com.vlessclient.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeManagerTest {

    /**
     * Dialogs need the stylesheet without handing their scene to the manager:
     * applyTheme() adopts whatever scene it is given as the tracked one, so
     * using it for a transient dialog would leave the main window unwatched
     * after the dialog closed and "auto" would stop following the OS there.
     */
    @Test
    void currentStylesheet_reflectsTheThemeWithoutCapturingAScene() {
        ThemeManager manager = new ThemeManager();

        manager.setTheme("dark");
        assertThat(manager.currentStylesheet()).endsWith("dark.css");

        manager.setTheme("light");
        assertThat(manager.currentStylesheet()).endsWith("light.css");

        // Resolvable URL, not a bare path — callers pass it straight to
        // Scene.getStylesheets().
        assertThat(manager.currentStylesheet()).startsWith("file:");
    }

    @Test
    void normalize_keepsSupportedThemes() {
        assertThat(ThemeManager.normalize("auto")).isEqualTo("auto");
        assertThat(ThemeManager.normalize("light")).isEqualTo("light");
        assertThat(ThemeManager.normalize("dark")).isEqualTo("dark");
    }

    @Test
    void normalize_mapsLegacySystemToAuto() {
        assertThat(ThemeManager.normalize("system")).isEqualTo("auto");
    }

    @Test
    void normalize_fallsBackToAutoForNullOrUnknown() {
        assertThat(ThemeManager.normalize(null)).isEqualTo("auto");
        assertThat(ThemeManager.normalize("")).isEqualTo("auto");
        assertThat(ThemeManager.normalize("solarized")).isEqualTo("auto");
    }

    @Test
    void setTheme_normalizesStoredValue() {
        ThemeManager manager = new ThemeManager();

        manager.setTheme("dark");
        assertThat(manager.getCurrentTheme()).isEqualTo("dark");

        manager.setTheme("system");
        assertThat(manager.getCurrentTheme()).isEqualTo("auto");

        manager.setTheme("nonsense");
        assertThat(manager.getCurrentTheme()).isEqualTo("auto");
    }

    @Test
    void defaultTheme_isAuto() {
        assertThat(new ThemeManager().getCurrentTheme()).isEqualTo("auto");
    }
}
