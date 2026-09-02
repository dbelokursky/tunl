package com.vlessclient.app;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The packaged app has nowhere to print to: logback is wired to CONSOLE and
 * FILE, and jpackage discards stderr. Without a default handler the JVM's own
 * one writes an uncaught failure to that discarded stream, which is how a
 * background-thread crash turns into "it just did nothing" in a bug report.
 */
class VlessClientAppUncaughtExceptionTest {

    @Test
    void anExceptionEscapingAThreadIsLoggedInsteadOfDiscarded() throws Exception {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Logger appLogger = (Logger) LoggerFactory.getLogger(VlessClientApp.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        appLogger.addAppender(appender);
        try {
            VlessClientApp.installUncaughtExceptionLogger();

            Thread failing = new Thread(() -> {
                throw new IllegalStateException("boom");
            }, "tunl-uncaught-probe");
            failing.start();
            failing.join(5_000);

            // Filtered rather than asserted on the whole list: the appender is
            // attached to a process-wide logger for the duration of this test.
            List<ILoggingEvent> ours = appender.list.stream()
                    .filter(e -> e.getFormattedMessage().contains("tunl-uncaught-probe"))
                    .toList();
            assertThat(ours)
                    .as("the handler must name the thread that died")
                    .hasSize(1);
            assertThat(ours.get(0).getThrowableProxy().getMessage()).isEqualTo("boom");
        } finally {
            appLogger.detachAppender(appender);
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }
}
