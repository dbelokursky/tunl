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
