package com.vlessclient.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The texts keep one typography and one voice.
 *
 * <p>Both bundles mixed three dots with the ellipsis ("Подключение..." next to
 * "Проверка…") and wrote a hyphen for a dash, and the Russian one addressed
 * the user as «ты» twice ("добавь через +", "смотри проверки ниже") among
 * texts that say «вы».</p>
 */
class BundleTypographyTest {

    /** Imperatives addressed as «ты», of the verbs the texts use. */
    private static final Pattern TY_IMPERATIVE = Pattern.compile(
            "(?iu)(?<![\\p{L}-])(добавь|смотри|нажми|выбери|открой|введи|попробуй|проверь|укажи"
                    + "|сделай|удали|включи|выключи|скопируй|вставь|перезапусти|подключись)"
                    + "(?![\\p{L}-])");

    @Test
    void anEllipsisIsOneCharacter() {
        assertThat(valuesMatching("/i18n/messages_en.properties", "..."))
                .as("English texts with three dots").isEmpty();
        assertThat(valuesMatching("/i18n/messages_ru.properties", "..."))
                .as("Russian texts with three dots").isEmpty();
    }

    @Test
    void aDashIsNotAHyphen() {
        assertThat(valuesMatching("/i18n/messages_en.properties", " - "))
                .as("English texts with a hyphen for a dash").isEmpty();
        assertThat(valuesMatching("/i18n/messages_ru.properties", " - "))
                .as("Russian texts with a hyphen for a dash").isEmpty();
    }

    @Test
    void theRussianTextsSayVy() {
        Map<String, String> ty = new TreeMap<>();
        load("/i18n/messages_ru.properties").forEach((key, value) -> {
            if (TY_IMPERATIVE.matcher(value.toString()).find()) {
                ty.put(key.toString(), value.toString());
            }
        });
        assertThat(ty).as("Russian texts addressing the user as «ты»").isEmpty();
    }

    private static Map<String, String> valuesMatching(String bundle, String fragment) {
        Map<String, String> found = new TreeMap<>();
        load(bundle).forEach((key, value) -> {
            if (value.toString().contains(fragment)) {
                found.put(key.toString(), value.toString());
            }
        });
        return found;
    }

    private static Properties load(String bundle) {
        Properties properties = new Properties();
        try (InputStream in = BundleTypographyTest.class.getResourceAsStream(bundle)) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return properties;
    }
}
