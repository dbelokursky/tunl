package com.vlessclient.testing;

import static com.vlessclient.testing.FxTestSupport.flushFxEvents;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Objects;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The toolkit readings the pulse tests rest on. A reflective read that quietly
 * returned zero would turn every "no animation is running" assertion into one
 * that cannot fail, so both readings are shown to move when an animation plays,
 * and what is listed as running is shown to lead back to what left it.
 *
 * <p>Each test waits for what it started inside the try that stops it. The
 * wait used to come first, so a stalled FX thread failed the wait and skipped
 * the cleanup: a caret left blinking in a hidden window then failed
 * {@code DashboardHiddenWindowTest}, later in the same fork.</p>
 */
@ExtendWith(FxToolkitExtension.class)
class FxPulsesTest {

    private static final Duration PATIENCE = Duration.ofSeconds(5);

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

    @Test
    void namesAPlayingTransitionByTheClassItWasWrittenInAndStopsIt() throws Exception {
        PauseTransition pause = new PauseTransition(javafx.util.Duration.minutes(1));
        pause.setOnFinished(event -> {
        });
        Platform.runLater(pause::play);
        try {
            flushFxEvents();
            FxPulses.Running running = FxPulses.running().stream()
                    .filter(entry -> entry.animation() == pause)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the playing transition is not listed"));
            assertThat(running.description())
                    .isEqualTo("PauseTransition of 60000 ms, handled in FxPulsesTest");
            assertThat(running.repeatsForever())
                    .as("whether a transition of one cycle runs until something stops it")
                    .isFalse();

            running.stop();

            assertThat(FxPulses.running())
                    .as("what runs once the transition was stopped through its entry")
                    .noneMatch(entry -> entry.animation() == pause);
        } finally {
            Platform.runLater(pause::stop);
            flushFxEvents();
        }
    }

    @Test
    void listsARunningAnimationTimerAndStopsIt() throws Exception {
        javafx.animation.AnimationTimer timer = new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
            }
        };
        Platform.runLater(timer::start);
        try {
            flushFxEvents();
            FxPulses.Running running = FxPulses.running().stream()
                    .filter(entry -> entry.animation() == timer)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the running timer is not listed"));
            assertThat(running.description()).isEqualTo(timer.getClass().getName());
            assertThat(running.repeatsForever())
                    .as("whether an animation timer runs until something stops it")
                    .isTrue();

            running.stop();

            assertThat(FxPulses.running())
                    .as("what runs once the timer was stopped through its entry")
                    .noneMatch(entry -> entry.animation() == timer);
        } finally {
            Platform.runLater(timer::stop);
            flushFxEvents();
        }
    }

    /**
     * The leak the descriptions were written for: a caret blinks while its
     * field is focused, and Monocle keeps a window focused after it hides, so
     * the caret blinks on in a hidden window. Its entry names the field and
     * says where the field is.
     */
    @Test
    void tracesACaretToItsFieldAndWhereTheFieldIs() throws Exception {
        TextField field = new TextField();
        field.setPromptText("Provider");
        Stage[] window = new Stage[1];
        Platform.runLater(() -> {
            window[0] = new Stage();
            window[0].setScene(new Scene(new StackPane(field), 300, 100));
            window[0].show();
            field.requestFocus();
        });
        try {
            flushFxEvents();
            FxPulses.Running caret = caret();
            assertThat(caret.description())
                    .isEqualTo("Timeline repeating every 1000 ms, handled in"
                            + " TextInputControlSkin$CaretBlinking for TextField \"Provider\""
                            + " in a showing window");
            assertThat(caret.repeatsForever())
                    .as("whether a caret blinks until something stops it")
                    .isTrue();

            Platform.runLater(window[0]::hide);
            flushFxEvents();

            assertThat(caret().description())
                    .as("the caret of a field in a window Monocle hid and still counts focused")
                    .endsWith("for TextField \"Provider\" in a hidden window");
        } finally {
            Platform.runLater(() -> {
                window[0].hide();
                window[0].getScene().getRoot().requestFocus();
            });
            flushFxEvents();
        }
        assertThat(FxPulses.running())
                .as("what runs once the focus has left the field")
                .noneMatch(entry -> entry.description().contains("\"Provider\""));
    }

    /** What is running for the field prompting "Provider", once its caret blinks. */
    private static FxPulses.Running caret() {
        return Await.untilValue("the caret of the field to blink",
                () -> FxPulses.running().stream()
                        .filter(entry -> entry.description().contains("\"Provider\""))
                        .findFirst()
                        .orElse(null),
                Objects::nonNull, PATIENCE);
    }
}
