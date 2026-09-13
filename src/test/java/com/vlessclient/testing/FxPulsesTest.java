package com.vlessclient.testing;

import static com.vlessclient.testing.FxTestSupport.flushFxEvents;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The toolkit readings the pulse tests rest on. A reflective read that quietly
 * returned zero would turn every "no animation is running" assertion into one
 * that cannot fail, so both readings are shown to move when an animation plays.
 */
@ExtendWith(FxToolkitExtension.class)
class FxPulsesTest {

    @Test
    void seesAPlayingAnimationAndThePulsesItCauses() throws Exception {
        PauseTransition pause = new PauseTransition(javafx.util.Duration.minutes(1));
        int before = FxPulses.runningAnimations();
        try (FxPulses.Counter counter = FxPulses.countPulses()) {
            Platform.runLater(pause::play);
            flushFxEvents();

            assertThat(FxPulses.runningAnimations())
                    .as("a playing transition is registered with the primary timer")
                    .isEqualTo(before + 1);
            Await.until("the toolkit to pulse for the playing transition",
                    () -> counter.pulses() >= 5, Duration.ofSeconds(5));
        } finally {
            Platform.runLater(pause::stop);
            flushFxEvents();
        }
        assertThat(FxPulses.runningAnimations()).isEqualTo(before);
    }
}
