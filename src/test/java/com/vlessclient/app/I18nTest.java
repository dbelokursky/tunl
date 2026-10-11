package com.vlessclient.app;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class I18nTest {

    @Test
    void englishLocale_returnsEnglishStrings() {
        I18n.setLocale(Locale.ENGLISH);

        assertThat(I18n.get("sidebar.dashboard")).isEqualTo("Dashboard");
        assertThat(I18n.get("button.connect")).isEqualTo("Connect");
        assertThat(I18n.get("state.connected")).isEqualTo("Connected");
        assertThat(I18n.get("settings.title")).isEqualTo("Settings");
    }

    @Test
    void russianLocale_returnsRussianStrings() {
        I18n.setLocale(Locale.of("ru"));

        assertThat(I18n.get("sidebar.dashboard")).isEqualTo("\u0413\u043b\u0430\u0432\u043d\u0430\u044f");
        assertThat(I18n.get("button.connect")).isEqualTo("\u041f\u043e\u0434\u043a\u043b\u044e\u0447\u0438\u0442\u044c");
        assertThat(I18n.get("state.connected")).isEqualTo("\u041f\u043e\u0434\u043a\u043b\u044e\u0447\u0435\u043d\u043e");
        assertThat(I18n.get("settings.title")).isEqualTo("\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438");
    }

    @Test
    void unknownKey_returnsKeyItself() {
        I18n.setLocale(Locale.ENGLISH);

        assertThat(I18n.get("nonexistent.key")).isEqualTo("nonexistent.key");
    }

    @Test
    void getWithArgs_formatsMessage() {
        I18n.setLocale(Locale.ENGLISH);

        String result = I18n.get("error.connection.failed", "timeout");
        assertThat(result).isEqualTo("Failed to start: timeout");
    }

    @Test
    void switchingLocale_updatesSubsequentCalls() {
        I18n.setLocale(Locale.ENGLISH);
        assertThat(I18n.get("sidebar.servers")).isEqualTo("Servers");

        I18n.setLocale(Locale.of("ru"));
        assertThat(I18n.get("sidebar.servers")).isEqualTo("\u0421\u0435\u0440\u0432\u0435\u0440\u044b");

        I18n.setLocale(Locale.ENGLISH);
        assertThat(I18n.get("sidebar.servers")).isEqualTo("Servers");
    }

    @Test
    void localeProperty_reflectsCurrentLocale() {
        I18n.setLocale(Locale.ENGLISH);
        assertThat(I18n.localeProperty().get()).isEqualTo(Locale.ENGLISH);

        Locale russian = Locale.of("ru");
        I18n.setLocale(russian);
        assertThat(I18n.localeProperty().get()).isEqualTo(russian);
    }

    /**
     * A number in a message is written the way the UI's language writes it.
     * MessageFormat.format used the host's format locale: on a host set to
     * Persian with the app in English, the health card wrote its latencies in
     * Persian digits, and on a German one "1.234 ms", beside Latin digits
     * everywhere else.
     */
    @Test
    void numbersInAMessageFollowTheUiLanguageNotTheHost() {
        Locale host = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("fa-IR"));
            I18n.setLocale(Locale.ENGLISH);
            assertThat(I18n.get("unit.ms", 1234L)).isEqualTo("1,234\u00a0ms");

            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
            I18n.setLocale(Locale.of("ru"));
            assertThat(I18n.get("unit.ms", 1234L))
                    .isEqualTo("1\u00a0234\u00a0\u043c\u0441");
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, host);
            I18n.setLocale(Locale.ENGLISH);
        }
    }
}
