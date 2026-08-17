package com.vlessclient.app;

import ch.qos.logback.core.PropertyDefinerBase;
import com.vlessclient.platform.PlatformPaths;

/**
 * Answers logback's {@code ${DEFAULT_LOG_DIR}} with the per-OS logs directory.
 *
 * <p>Replaces a literal path in {@code logback.xml} that named the macOS
 * location outright, on the assumption that anything reaching logging without
 * going through {@link Launcher} was a test and would not mind. Two things were
 * wrong with that. The path is macOS-shaped, so a Linux or Windows process
 * starting logging any other way wrote into a {@code ~/Library} directory that
 * has no business existing there. And "would not mind" was the opposite of
 * true: the file appender purges its own history on start, so a test run
 * pointed at the developer's real logs deleted the records of what their app
 * had actually been doing.</p>
 *
 * <p>Resolved when logback first configures itself, so it sees the same
 * {@code vless.data.dir} redirect the rest of the app resolves through — which
 * is what puts a test run's logging in {@code target/} without anything having
 * to remember to redirect it. That also retires the ordering constraint the
 * launcher used to carry: nothing has to set a property before the first class
 * with a static logger loads, because there is no property to set.</p>
 *
 * <p>Public and no-arg by necessity: logback instantiates it reflectively from
 * the {@code <define>} in {@code logback.xml}.</p>
 */
public final class LogDirPropertyDefiner extends PropertyDefinerBase {

    @Override
    public String getPropertyValue() {
        return PlatformPaths.current().logsDir().toString();
    }
}
