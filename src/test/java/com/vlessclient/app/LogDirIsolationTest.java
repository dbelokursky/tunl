package com.vlessclient.app;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.FileAppender;
import com.vlessclient.platform.PlatformPaths;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A test run must not write into — or delete from — the developer's real logs.
 *
 * <p>{@code logback.xml} used to name the macOS logs directory as its fallback
 * for anything that started logging without going through {@link Launcher},
 * tests included. The file appender purges its own history on start, so a test
 * run did not merely add noise to the records of what the user's app had been
 * doing: it removed them. On Linux and Windows the same literal pointed at a
 * {@code ~/Library} path that has no meaning there.</p>
 *
 * <p>Asserted against the appenders logback actually configured, not against
 * the definer alone — the property could resolve correctly and still not be
 * the one the appender uses.</p>
 */
class LogDirIsolationTest {

    private static Path overriddenDataDir() {
        String override = System.getProperty(PlatformPaths.DATA_DIR_PROPERTY);
        assertThat(override)
                .as("pom.xml must set %s for the test run", PlatformPaths.DATA_DIR_PROPERTY)
                .isNotBlank();
        return Path.of(override).toAbsolutePath().normalize();
    }

    @Test
    void everyFileAppenderWritesInsideTheRedirectedDataDir() {
        Path dataDir = overriddenDataDir();
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        // Both of them: the app log and the MCP audit trail sit in the same
        // directory, and only one of them being redirected is the bug with
        // half the blast radius rather than none of it.
        long checked = context.getLoggerList().stream()
                .flatMap(logger -> java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(
                                logger.iteratorForAppenders(), 0), false))
                .filter(FileAppender.class::isInstance)
                .map(FileAppender.class::cast)
                .peek(appender -> assertThat(Path.of(appender.getFile()).toAbsolutePath().normalize())
                        .as("%s writes outside the redirected data dir", appender.getName())
                        .startsWith(dataDir))
                .count();

        assertThat(checked)
                .as("no file appenders found — the check would pass on any configuration")
                .isGreaterThanOrEqualTo(2);
    }

    /**
     * The default the appenders resolve through, checked on its own so a
     * failure says which half broke: the definer, or the wiring to it.
     */
    @Test
    void theComputedDefaultFollowsTheDataDirRedirect() {
        assertThat(Path.of(new LogDirPropertyDefiner().getPropertyValue())
                .toAbsolutePath().normalize())
                .isEqualTo(overriddenDataDir().resolve("logs"));
    }
}
