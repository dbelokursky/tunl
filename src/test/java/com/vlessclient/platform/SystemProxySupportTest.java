package com.vlessclient.platform;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Linux capability probe, and — the point of this test — the fact that it
 * says so when it fails.
 *
 * <p>Returning a bare {@code false} is what made this the app's worst failure
 * shape: the generator drops {@code set_system_proxy}, the tunnel starts, the
 * health card is green because it probes through the local inbound, and no
 * traffic is proxied OS-wide. A report from such a host used to contain
 * nothing to diagnose.</p>
 */
class SystemProxySupportTest {

    private Logger probeLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        probeLogger = (Logger) LoggerFactory.getLogger(GnomeProxySchemaProbe.class);
        appender = new ListAppender<>();
        appender.start();
        probeLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        probeLogger.detachAppender(appender);
    }

    private List<String> warnings() {
        return appender.list.stream()
                .filter(e -> e.getLevel().toString().equals("WARN"))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void aMissingSchemaIsReportedAndLogged() {
        SystemProxySupport probe = SystemProxySupport.gnomeProxySchemaPresent(
                command -> new CommandRunner.Result(1, "No such schema org.gnome.system.proxy"));

        assertThat(probe.canAutoConfigure()).isFalse();
        assertThat(warnings())
                .as("a silent false is what hid this failure from every bug report")
                .hasSize(1);
        assertThat(warnings().get(0)).contains("No such schema");
    }

    @Test
    void aMissingGsettingsBinaryIsReportedAndLogged() {
        SystemProxySupport probe = SystemProxySupport.gnomeProxySchemaPresent(command -> {
            throw new IOException("Cannot run program \"gsettings\"");
        });

        assertThat(probe.canAutoConfigure()).isFalse();
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains("gsettings");
    }

    @Test
    void aWorkingSchemaStaysQuiet() {
        SystemProxySupport probe = SystemProxySupport.gnomeProxySchemaPresent(
                command -> new CommandRunner.Result(0, "'none'"));

        assertThat(probe.canAutoConfigure()).isTrue();
        assertThat(warnings())
                .as("the healthy path must not add noise to every connect")
                .isEmpty();
    }
}
