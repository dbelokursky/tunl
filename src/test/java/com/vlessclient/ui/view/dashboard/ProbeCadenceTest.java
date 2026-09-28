package com.vlessclient.ui.view.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The probes of a tunnel that keeps answering space out, and come back to the
 * configured interval the moment one does not.
 */
class ProbeCadenceTest {

    @Test
    void theFirstProbesKeepTheConfiguredInterval() {
        assertThat(ProbeCadence.nextDelaySeconds(5, 0)).isEqualTo(5);
        assertThat(ProbeCadence.nextDelaySeconds(15, 0)).isEqualTo(15);
        assertThat(ProbeCadence.nextDelaySeconds(5, ProbeCadence.HEALTHY_PER_DOUBLING - 1))
                .isEqualTo(5);
    }

    @Test
    void aTunnelThatKeepsAnsweringIsProbedLessOftenUpToTheCeiling() {
        assertThat(ProbeCadence.nextDelaySeconds(5, 3)).isEqualTo(10);
        assertThat(ProbeCadence.nextDelaySeconds(5, 6)).isEqualTo(20);
        assertThat(ProbeCadence.nextDelaySeconds(5, 9)).isEqualTo(40);
        assertThat(ProbeCadence.nextDelaySeconds(5, 12)).isEqualTo(ProbeCadence.MAX_SECONDS);
        assertThat(ProbeCadence.nextDelaySeconds(5, 10_000)).isEqualTo(ProbeCadence.MAX_SECONDS);
        assertThat(ProbeCadence.nextDelaySeconds(1, Integer.MAX_VALUE))
                .isEqualTo(ProbeCadence.MAX_SECONDS);
    }

    @Test
    void theWaitNeverShrinksAsHealthyVerdictsAddUpAndNeverUndercutsTheSettings() {
        for (int configured : new int[] {1, 2, 5, 7, 15, 30, 59}) {
            int previous = 0;
            for (int healthy = 0; healthy <= 60; healthy++) {
                int delay = ProbeCadence.nextDelaySeconds(configured, healthy);
                assertThat(delay).as("configured %d s, %d healthy", configured, healthy)
                        .isGreaterThanOrEqualTo(configured)
                        .isGreaterThanOrEqualTo(previous)
                        .isLessThanOrEqualTo(ProbeCadence.MAX_SECONDS);
                previous = delay;
            }
        }
    }

    @Test
    void aSlowerIntervalFromTheSettingsStandsAsItIs() {
        assertThat(ProbeCadence.nextDelaySeconds(120, 0)).isEqualTo(120);
        assertThat(ProbeCadence.nextDelaySeconds(120, 100)).isEqualTo(120);
        assertThat(ProbeCadence.nextDelaySeconds(3600, 7)).isEqualTo(3600);
    }

    /** At the default interval the ceiling is reached in about four minutes. */
    @Test
    void theDefaultIntervalReachesTheCeilingWithinFiveMinutes() {
        int elapsed = 0;
        int healthy = 0;
        while (ProbeCadence.nextDelaySeconds(5, healthy) < ProbeCadence.MAX_SECONDS) {
            elapsed += ProbeCadence.nextDelaySeconds(5, healthy);
            healthy++;
        }
        assertThat(elapsed).isLessThanOrEqualTo(300);
    }
}
