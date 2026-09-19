package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.service.TrafficMonitor;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Traffic reads in the units and the decimal separator of the UI's language.
 *
 * <p>The window showed "1.5 MB" and "3.2 MB/s" in a Russian window, next to
 * "35 мс", and a line could break between a number and its unit.</p>
 */
class TrafficTextTest {

    private static final String NBSP = " ";

    @AfterEach
    void backToEnglish() {
        I18n.setLocale(Locale.ENGLISH);
    }

    @Test
    void russianTakesCyrillicUnitsAndADecimalComma() {
        I18n.setLocale(Locale.of("ru"));

        assertThat(TrafficText.bytes(500)).isEqualTo("500" + NBSP + "Б");
        assertThat(TrafficText.bytes(1536)).isEqualTo("1,5" + NBSP + "КБ");
        assertThat(TrafficText.bytes(5L * 1024 * 1024 * 1024 / 2)).isEqualTo("2,50" + NBSP + "ГБ");
        assertThat(TrafficText.speed(3L * 1024 * 1024 / 2)).isEqualTo("1,5" + NBSP + "МБ/с");
        assertThat(I18n.get("unit.ms", 35)).isEqualTo("35" + NBSP + "мс");
    }

    @Test
    void englishKeepsItsUnitsAndJoinsThemToTheNumber() {
        I18n.setLocale(Locale.ENGLISH);

        assertThat(TrafficText.bytes(1536)).isEqualTo("1.5" + NBSP + "KB");
        assertThat(TrafficText.speed(0)).isEqualTo("0" + NBSP + "B/s");
        assertThat(TrafficText.speed(3L * 1024 * 1024 / 2)).isEqualTo("1.5" + NBSP + "MB/s");
        assertThat(I18n.get("unit.ms", 35)).isEqualTo("35" + NBSP + "ms");
    }

    @Test
    void aNegativeCountReadsAsNothing() {
        assertThat(TrafficText.bytes(-1)).isEqualTo(TrafficText.bytes(0));
        assertThat(TrafficText.speed(-1)).isEqualTo(TrafficText.speed(0));
    }

    /** Agents read MCP's traffic in English whatever the window's language. */
    @Test
    void theCoresOwnFormatStaysEnglish() {
        I18n.setLocale(Locale.of("ru"));

        assertThat(TrafficMonitor.formatBytes(1536)).isEqualTo("1.5 KB");
        assertThat(TrafficMonitor.formatSpeed(3L * 1024 * 1024 / 2)).isEqualTo("1.5 MB/s");
    }
}
