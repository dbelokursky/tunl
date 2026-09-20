package com.vlessclient.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A count in a message takes the form its number asks for.
 *
 * <p>Messages went around the plural with a label, "Серверов: 21" and
 * "Удалить серверов: 5?", or picked between one form and another, which in
 * English gave "remove all 1 servers" and in Russian cannot work at all:
 * 1 сервер, 2 сервера, 5 серверов, 21 сервер.</p>
 */
class I18nPluralTest {

    @AfterEach
    void backToEnglish() {
        I18n.setLocale(Locale.ENGLISH);
    }

    @Test
    void russianTakesOneFewOrManyByTheLastDigits() {
        assertForms(Locale.of("ru"),
                1, "one", 21, "one", 101, "one",
                2, "few", 3, "few", 4, "few", 22, "few", 104, "few",
                0, "many", 5, "many", 11, "many", 12, "many", 14, "many", 19, "many",
                25, "many", 111, "many", 112, "many");
    }

    @Test
    void englishTakesOneOrMany() {
        assertForms(Locale.ENGLISH, 1, "one", 0, "many", 2, "many", 21, "many");
    }

    @Test
    void theServerCountsReadRightInBothLanguages() {
        I18n.setLocale(Locale.of("ru"));
        assertThat(I18n.plural("subscriptions.servers", 1)).isEqualTo("1 сервер");
        assertThat(I18n.plural("subscriptions.servers", 3)).isEqualTo("3 сервера");
        assertThat(I18n.plural("subscriptions.servers", 21)).isEqualTo("21 сервер");
        assertThat(I18n.plural("subscriptions.servers", 25)).isEqualTo("25 серверов");
        assertThat(I18n.plural("servers.delete.count", 5)).isEqualTo("Удалить 5 серверов?");

        I18n.setLocale(Locale.ENGLISH);
        assertThat(I18n.plural("subscriptions.servers", 1)).isEqualTo("1 server");
        assertThat(I18n.plural("subscriptions.delete.content", 1))
                .doesNotContain("1 servers");
        assertThat(I18n.plural("routing.bypass.count", 2)).isEqualTo("2 entries");
    }

    /** A count of a thousand stays one number, not "1,000" or "1 000". */
    @Test
    void theCountIsWrittenAsItIs() {
        I18n.setLocale(Locale.ENGLISH);
        assertThat(I18n.plural("subscriptions.servers", 1000)).isEqualTo("1000 servers");
    }

    private static void assertForms(Locale locale, Object... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            long count = ((Number) pairs[i]).longValue();
            assertThat(I18n.pluralForm(locale, count)).as("%s %d", locale, count)
                    .isEqualTo(pairs[i + 1]);
        }
    }
}
