package com.vlessclient.testing;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Runs code under another default format locale, the one {@code %d} and
 * {@code String.formatted} write numbers in, and puts the old one back.
 *
 * <p>Locales such as fa-IR, ar-SA and bn-BD write digits of their own, which
 * no shell and no PowerShell reads as a number; a full JDK carries them, the
 * installers' runtime does not.</p>
 */
public final class FormatLocale {

    private FormatLocale() {
    }

    /**
     * What {@code build} returns with the default format locale set to {@code tag}.
     *
     * @param tag   a BCP 47 language tag, such as {@code "fa-IR"}
     * @param build what to run under it
     * @param <T>   what it returns
     * @return what {@code build} returned
     */
    public static <T> T in(String tag, Supplier<T> build) {
        Locale before = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag(tag));
        try {
            return build.get();
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, before);
        }
    }
}
