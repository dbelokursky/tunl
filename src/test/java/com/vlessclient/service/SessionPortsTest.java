package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The run's HTTP port kept for the next start's cleanup of a stale proxy. */
class SessionPortsTest {

    @TempDir
    Path dataDir;

    @Test
    void aRecordedPortIsReadBackUntilItIsForgotten() {
        assertThat(SessionPorts.recorded(dataDir)).isEmpty();

        SessionPorts.record(dataDir, 1082);
        assertThat(SessionPorts.recorded(dataDir)).hasValue(1082);

        SessionPorts.forget(dataDir);
        assertThat(SessionPorts.recorded(dataDir)).isEmpty();
    }

    @Test
    void anUnreadableRecordIsNoPort() throws Exception {
        Files.writeString(dataDir.resolve(SessionPorts.FILE_NAME), "not a port");
        assertThat(SessionPorts.recorded(dataDir)).isEmpty();

        Files.writeString(dataDir.resolve(SessionPorts.FILE_NAME), "70000");
        assertThat(SessionPorts.recorded(dataDir)).isEmpty();
    }

    /**
     * A start used to look for a stale system proxy every time: on a Mac a
     * networksetup process per network service and proxy type, before the
     * window showed. A run that stopped its core had the proxy put back, and
     * leaves no record, so there is nothing to look for.
     */
    @Test
    void aStartLooksOnlyWhereARunLeftARecord() {
        SessionPorts.stalePorts(dataDir, true, 1081);

        assertThat(SessionPorts.stalePorts(dataDir, true, 1081))
                .as("the last run stopped its core").isEmpty();

        SessionPorts.record(dataDir, 1082);
        assertThat(SessionPorts.stalePorts(dataDir, true, 1081)).containsExactly(1082);
    }

    /**
     * An older build recorded a run's port only when it had moved, so the
     * first start of this one also looks at the chosen port, as every start
     * did before.
     */
    @Test
    void theFirstStartOfThisBuildAlsoLooksAtTheChosenPort() {
        assertThat(SessionPorts.stalePorts(dataDir, true, 1081)).containsExactly(1081);
    }

    @Test
    void theFirstStartOutsideSystemProxyModeHasNothingOfItsOwnToLookAt() {
        SessionPorts.record(dataDir, 1082);

        assertThat(SessionPorts.stalePorts(dataDir, false, 1081))
                .as("a record is looked at whatever the mode now")
                .containsExactly(1082);
    }
}
