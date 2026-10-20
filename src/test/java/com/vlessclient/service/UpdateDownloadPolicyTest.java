package com.vlessclient.service;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What any schedule of retries has to keep to, whatever its numbers: the
 * shape is a choice, these are not.
 */
class UpdateDownloadPolicyTest {

    private static final Duration CHECK_INTERVAL =
            Duration.ofHours(UpdateManager.CHECK_INTERVAL_HOURS);

    /**
     * The commonest failure is the tunnel switching over at startup, which is
     * over in seconds; a first retry hours later is the wait this replaced.
     */
    @Test
    void theFirstRetryComesWithinMinutes() {
        Duration first = UpdateDownloadPolicy.retryDelay(1);

        assertThat(first).isPositive();
        assertThat(first).isLessThanOrEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void theWaitsNeverShrinkAsFailuresPileUp() {
        for (int failed = 1; failed < 500; failed++) {
            assertThat(UpdateDownloadPolicy.retryDelay(failed + 1))
                    .as("after %d failures", failed + 1)
                    .isGreaterThanOrEqualTo(UpdateDownloadPolicy.retryDelay(failed));
        }
    }

    /**
     * A network that blocks GitHub fails every try, and each can cost most of
     * an installer: the waits have to grow, so a long run of failures does
     * not keep the first, short wait.
     */
    @Test
    void theWaitsGrow() {
        assertThat(UpdateDownloadPolicy.retryDelay(10))
                .isGreaterThan(UpdateDownloadPolicy.retryDelay(1));
    }

    /** Past the six-hour check a retry would only follow the check anyway. */
    @Test
    void theWaitsStopGrowingBelowTheCheckInterval() {
        for (int failed = 1; failed < 500; failed++) {
            assertThat(UpdateDownloadPolicy.retryDelay(failed))
                    .as("after %d failures", failed)
                    .isPositive()
                    .isLessThan(CHECK_INTERVAL);
        }
    }

    /** A doubling written as a shift turns negative past 31 or 63 failures. */
    @Test
    void anyNumberOfFailuresGivesAPositiveWaitWithinTheCap() {
        for (int failed : new int[] {31, 32, 33, 63, 64, 65, 1_000, Integer.MAX_VALUE}) {
            assertThat(UpdateDownloadPolicy.retryDelay(failed))
                    .as("after %d failures", failed)
                    .isPositive()
                    .isLessThan(CHECK_INTERVAL);
        }
    }
}
