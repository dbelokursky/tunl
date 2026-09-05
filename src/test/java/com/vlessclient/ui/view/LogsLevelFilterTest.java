package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.service.LogLineFormatter;
import org.junit.jupiter.api.Test;

/**
 * The Logs view's level filter. It used to look for the level's name anywhere
 * in the line, so an INFO line saying "no error" showed up under Error and a
 * DEBUG line mentioning "info" under Info; it now filters on the level the
 * formatter parses.
 */
class LogsLevelFilterTest {

    private static boolean shown(String line, String filter) {
        return LogsViewController.matchesLevel(LogLineFormatter.levelOf(line), filter);
    }

    @Test
    void errorFilterKeepsOnlyErrorLevels() {
        assertThat(shown("ERROR[0000] dial failed", "error")).isTrue();
        assertThat(shown("FATAL[0000] start service", "error")).isTrue();
        assertThat(shown("WARN[0000] retrying", "error")).isFalse();
        assertThat(shown("INFO[0000] there was no error at all", "error")).isFalse();
    }

    @Test
    void warnFilterKeepsWarningsAndWorse() {
        assertThat(shown("WARN[0000] retrying", "warn")).isTrue();
        assertThat(shown("ERROR[0000] dial failed", "warn")).isTrue();
        assertThat(shown("INFO[0000] warn is only a word here", "warn")).isFalse();
    }

    @Test
    void infoFilterHidesOnlyDebug() {
        assertThat(shown("INFO[0000] started", "info")).isTrue();
        assertThat(shown("DEBUG[0000] info-level chatter", "info")).isFalse();
        assertThat(shown("TRACE[0000] more", "info")).isFalse();
    }

    @Test
    void linesWithoutALevelPassEveryFilter() {
        // A panic trace or a continuation line explains the error above it.
        for (String filter : new String[] {"error", "warn", "info", "debug", "all"}) {
            assertThat(shown("goroutine 1 [running]:", filter)).as(filter).isTrue();
        }
    }
}
