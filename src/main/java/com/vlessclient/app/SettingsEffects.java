package com.vlessclient.app;

import com.vlessclient.model.AppSettings;
import com.vlessclient.service.ThemeManager;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * What a saved change of the language or the theme does at once, whoever
 * saved it: the window speaks the new language and is drawn in the new theme.
 *
 * <p>Only the Settings screen used to apply them. The same settings set
 * through MCP were saved and then waited for the next start.</p>
 *
 * <p>It follows changes rather than the settings themselves: a save of
 * anything else leaves the language and the theme as they are shown, so a
 * view shown in another language for a moment, as the localization tests
 * do, is not switched back by an unrelated save.</p>
 */
public final class SettingsEffects implements Consumer<AppSettings> {

    private final ThemeManager themeManager;
    private String language;
    private String theme;

    /**
     * Follows the changes after {@code initial}, the settings the app was
     * started with.
     *
     * @param initial      the settings as the window shows them now
     * @param themeManager the window's theme, or null when there is none
     */
    public SettingsEffects(AppSettings initial, ThemeManager themeManager) {
        this.themeManager = themeManager;
        this.language = initial.getLanguage();
        this.theme = ThemeManager.normalize(initial.getTheme());
    }

    /**
     * The locale a stored language stands for: Russian for {@code ru}, English
     * for anything else, the language the app falls back to.
     *
     * @param language the stored language code
     * @return the locale to show the app in
     */
    public static Locale localeFor(String language) {
        return "ru".equals(language) ? Locale.of("ru") : Locale.ENGLISH;
    }

    /**
     * Shows the language and the theme {@code saved} holds, where the save
     * changed them. Runs on the JavaFX thread.
     *
     * @param saved the settings just saved
     */
    @Override
    public void accept(AppSettings saved) {
        if (!Objects.equals(saved.getLanguage(), language)) {
            language = saved.getLanguage();
            Locale locale = localeFor(language);
            if (!locale.equals(I18n.getLocale())) {
                I18n.setLocale(locale);
            }
        }
        String savedTheme = ThemeManager.normalize(saved.getTheme());
        if (!savedTheme.equals(theme)) {
            theme = savedTheme;
            if (themeManager != null && !savedTheme.equals(themeManager.getCurrentTheme())) {
                themeManager.setTheme(savedTheme);
                themeManager.reapply();
            }
        }
    }
}
