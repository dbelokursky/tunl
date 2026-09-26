package com.vlessclient.app;

import com.vlessclient.model.AppSettings;
import com.vlessclient.service.ThemeManager;
import com.vlessclient.service.mcp.AppControlService;
import com.vlessclient.testing.FxToolkitExtension;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The window follows the language and theme the settings hold, whoever saves
 * them.
 *
 * <p>Only the Settings screen applied them. An agent that set the theme or
 * the language through MCP had them saved, and the window went on as it was
 * until the app was started again.</p>
 */
@ExtendWith(FxToolkitExtension.class)
class SettingsFollowTheSaveTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private Locale localeBefore;
    private String themeBefore;
    private String languageBefore;

    @BeforeEach
    void buildTheGraph() {
        UiTestServices.initialize();
        localeBefore = I18n.getLocale();
        AppSettings settings = ServiceLocator.get(AppSettings.class);
        themeBefore = settings.getTheme();
        languageBefore = settings.getLanguage();
    }

    @AfterEach
    void putThemAsTheyWere() throws Exception {
        AppControlService control = ServiceLocator.get(AppControlService.class);
        control.setSetting("theme", json.getNodeFactory().stringNode(
                ThemeManager.normalize(themeBefore)));
        control.setSetting("language", json.getNodeFactory().stringNode(languageBefore));
        control.setSetting("core_log_level", json.getNodeFactory().stringNode("info"));
        I18n.setLocale(localeBefore);
    }

    @Test
    void anAgentsThemeIsTheWindowsAtOnce() throws Exception {
        ThemeManager themes = ServiceLocator.get(ThemeManager.class);
        String other = "dark".equals(themes.getCurrentTheme()) ? "light" : "dark";

        ServiceLocator.get(AppControlService.class)
                .setSetting("theme", json.getNodeFactory().stringNode(other));

        assertThat(themes.getCurrentTheme()).isEqualTo(other);
    }

    @Test
    void anAgentsLanguageIsTheWindowsAtOnce() throws Exception {
        String other = "ru".equals(I18n.getLocale().getLanguage()) ? "en" : "ru";

        ServiceLocator.get(AppControlService.class)
                .setSetting("language", json.getNodeFactory().stringNode(other));

        assertThat(I18n.getLocale().getLanguage()).isEqualTo(other);
    }

    /**
     * A save of anything else leaves the language as it is shown, as the
     * localization tests show every view in Russian without storing it.
     */
    @Test
    void aSaveThatChangesNeitherLeavesThemAsShown() throws Exception {
        Locale shown = "ru".equals(I18n.getLocale().getLanguage()) ? Locale.ENGLISH
                : Locale.of("ru");
        I18n.setLocale(shown);

        ServiceLocator.get(AppControlService.class)
                .setSetting("core_log_level", json.getNodeFactory().stringNode("warn"));

        assertThat(I18n.getLocale()).isEqualTo(shown);
    }

    @Test
    void theLocaleOfAStoredLanguage() {
        assertThat(SettingsEffects.localeFor("ru")).isEqualTo(Locale.of("ru"));
        assertThat(SettingsEffects.localeFor("en")).isEqualTo(Locale.ENGLISH);
        assertThat(SettingsEffects.localeFor(null)).as("what the app falls back to")
                .isEqualTo(Locale.ENGLISH);
    }
}
