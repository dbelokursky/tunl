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
}
