package com.vlessclient.app;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internationalization helper providing locale-aware string lookups
 * and JavaFX bindings that update automatically when the locale changes.
 */
public final class I18n {

    private static final Logger log = LoggerFactory.getLogger(I18n.class);
    private static final String BUNDLE_NAME = "i18n.messages";

    private static final ObjectProperty<Locale> locale = new SimpleObjectProperty<>(Locale.ENGLISH);
    private static ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE_NAME, Locale.ENGLISH);

    private I18n() {
    }

    /**
     * Changes the active locale and reloads the resource bundle.
     */
    public static void setLocale(Locale newLocale) {
        // Bundle first, property second. Setting the property is what wakes
        // every listener and binding, and anything that answers by calling
        // get() would otherwise be served the language we are leaving.
        bundle = ResourceBundle.getBundle(BUNDLE_NAME, newLocale);
        locale.set(newLocale);
        log.info("Locale set to {}", newLocale);
    }

    /**
     * Returns the translated string for the given key, or the key itself if not found.
     */
    public static String get(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            log.debug("Missing i18n key: {}", key);
            return key;
        }
    }

    /**
     * Returns the translated string with format arguments applied.
     */
    public static String get(String key, Object... args) {
        try {
            String pattern = bundle.getString(key);
            return MessageFormat.format(pattern, args);
        } catch (MissingResourceException e) {
            log.debug("Missing i18n key: {}", key);
            return key;
        }
    }

    /**
     * The form of a message for {@code count}, with the count as {@code {0}}:
     * the key {@code baseKey.one}, {@code .few} or {@code .many}, by the
     * plural rule of the UI's language. Messages went around the plural with a
     * label ("Серверов: 21") or picked between two forms, which gave "remove
     * all 1 servers" and cannot work in Russian at all.
     *
     * @param baseKey the key without its form
     * @param count   the number the message is about
     * @return the message in the form for {@code count}
     */
    public static String plural(String baseKey, long count) {
        // As a string: MessageFormat would write a thousand as "1,000".
        return get(baseKey + "." + pluralForm(getLocale(), count), String.valueOf(count));
    }

    /**
     * The plural form a language takes for a number: Russian's one (1, 21,
     * 101), few (2-4, 22-24) or many (0, 5-20, 25-30, 111-114); every other
     * language here has one for 1 and many for the rest.
     *
     * @param locale the language
     * @param count  the number
     * @return {@code one}, {@code few} or {@code many}
     */
    static String pluralForm(Locale locale, long count) {
        long n = Math.abs(count);
        if ("ru".equals(locale.getLanguage())) {
            long lastDigit = n % 10;
            long lastTwo = n % 100;
            if (lastDigit == 1 && lastTwo != 11) {
                return "one";
            }
            if (lastDigit >= 2 && lastDigit <= 4 && (lastTwo < 12 || lastTwo > 14)) {
                return "few";
            }
            return "many";
        }
        return n == 1 ? "one" : "many";
    }

    /**
     * Returns a JavaFX StringBinding that automatically updates when the locale changes.
     */
    public static StringBinding binding(String key) {
        return new StringBinding() {
            {
                bind(locale);
            }

            @Override
            protected String computeValue() {
                return I18n.get(key);
            }
        };
    }

    /**
     * Returns an observable property tracking the current locale.
     */
    public static ReadOnlyObjectProperty<Locale> localeProperty() {
        return locale;
    }

    /**
     * Returns the current locale.
     */
    public static Locale getLocale() {
        return locale.get();
    }
}
