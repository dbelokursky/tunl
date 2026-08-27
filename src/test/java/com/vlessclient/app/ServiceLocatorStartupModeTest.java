package com.vlessclient.app;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceLocatorStartupModeTest {

    @Test
    void testModeLeavesEveryBackgroundTaskDormant() {
        AtomicInteger starts = new AtomicInteger();

        ServiceLocator.runStartupTasks(ServiceLocator.StartupMode.TEST,
                starts::incrementAndGet,
                starts::incrementAndGet,
                starts::incrementAndGet,
                starts::incrementAndGet,
                starts::incrementAndGet);

        assertThat(starts).hasValue(0);
    }

    @Test
    void applicationModeRunsEveryBackgroundTask() {
        AtomicInteger starts = new AtomicInteger();

        ServiceLocator.runStartupTasks(ServiceLocator.StartupMode.APPLICATION,
                starts::incrementAndGet,
                starts::incrementAndGet,
                starts::incrementAndGet,
                starts::incrementAndGet,
                starts::incrementAndGet);

        assertThat(starts).hasValue(5);
    }
}
