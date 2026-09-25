package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The tray follows a change of the UI's language.
 *
 * <p>Its items were labeled once, when the menu was built. A change of state
 * labeled the connect item and the status again, but nothing followed the
 * language: "Show", the servers submenu and "Quit", and the submenu's "no
 * servers" and "N more", stayed in the old one until a restart.</p>
 */
class TrayLanguageTest {

    @AfterEach
    void backToEnglish() {
        I18n.setLocale(Locale.ENGLISH);
    }

    @Test
    void aLanguageSwitchQueuesARefreshOfTheMenu() {
        List<Runnable> awtQueue = new ArrayList<>();
        // Headless: the constructor creates no AWT object, and install() is never called.
        TrayIconService tray = new TrayIconService(() -> null, null, null, null, null, null);
        tray.setAwtInvoker(awtQueue::add);

        I18n.setLocale(Locale.of("ru"));

        assertThat(awtQueue).as("a refresh queued for the new language").hasSize(1);
        // The locale property reaches the tray through a weak listener.
        Reference.reachabilityFence(tray);
    }
}
