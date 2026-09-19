package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.service.TrafficMonitor;
import java.util.Locale;

/**
 * Byte counts and rates as the UI shows them: in the units and with the
 * decimal separator of its language, and with a no-break space between the
 * number and the unit.
 *
 * <p>The window showed {@link TrafficMonitor#formatBytes} as it is: "1.5 MB"
 * and "3.2 MB/s" in Latin letters next to "35 мс" in a Russian window, and a
 * line could break between a number and its unit. That English form stays
 * with MCP, where agents read it. The steps are the same: binary kilobytes,
 * one decimal up to megabytes and two for gigabytes.</p>
 */
public final class TrafficText {

    private static final long KB = 1024L;
    private static final long MB = KB * 1024L;
    private static final long GB = MB * 1024L;

    private TrafficText() {
    }

    /**
     * A byte count; a negative one reads as none.
     *
     * @param bytes the count
     * @return the count with its unit, in the UI's language
     */
    public static String bytes(long bytes) {
        return scaled(bytes, "unit.b", "unit.kb", "unit.mb", "unit.gb");
    }

    /**
     * A rate; a negative one reads as none.
     *
     * @param bytesPerSecond the rate
     * @return the rate with its unit, in the UI's language
     */
    public static String speed(long bytesPerSecond) {
        return scaled(bytesPerSecond, "unit.bps", "unit.kbps", "unit.mbps", "unit.gbps");
    }

    private static String scaled(long value, String b, String kb, String mb, String gb) {
        long count = Math.max(0, value);
        Locale locale = I18n.getLocale();
        if (count < KB) {
            return I18n.get(b, String.valueOf(count));
        }
        if (count < MB) {
            return I18n.get(kb, String.format(locale, "%.1f", count / (double) KB));
        }
        if (count < GB) {
            return I18n.get(mb, String.format(locale, "%.1f", count / (double) MB));
        }
        return I18n.get(gb, String.format(locale, "%.2f", count / (double) GB));
    }
}
