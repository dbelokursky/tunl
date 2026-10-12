package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.testing.UiTest;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * A pressable label presses the way the Button it stands in for does: once
 * per press of Enter or Space, on the key's release, and not under a modifier.
 *
 * <p>It ran its action on every KEY_PRESSED, and a held key repeats that
 * event about thirty times a second: holding Enter on the dashboard's
 * session total flickered the traffic history panel open and shut and
 * rewrote settings.json on every repeat, and Shift+Enter or Ctrl+Space
 * toggled it too.</p>
 */
@UiTest
public class PressableLabelTest extends ApplicationTest {

    private final AtomicInteger presses = new AtomicInteger();
    private PressableLabel label;
    private TextField elsewhere;

    @Override
    public void start(Stage stage) {
        label = new PressableLabel();
        label.setText("Session 12 MB");
        label.setOnPress(presses::incrementAndGet);
        elsewhere = new TextField();
        stage.setScene(new Scene(new VBox(label, elsewhere), 240, 120));
        stage.show();
        label.requestFocus();
    }

    @Test
    void holdingEnterPressesOnceOnRelease() {
        for (int repeat = 0; repeat < 4; repeat++) {
            key(KeyEvent.KEY_PRESSED, KeyCode.ENTER, false, false);
        }
        assertThat(presses).as("while Enter is held").hasValue(0);

        key(KeyEvent.KEY_RELEASED, KeyCode.ENTER, false, false);
        assertThat(presses).as("once Enter is released").hasValue(1);
    }

    @Test
    void holdingSpacePressesOnceOnRelease() {
        for (int repeat = 0; repeat < 4; repeat++) {
            key(KeyEvent.KEY_PRESSED, KeyCode.SPACE, false, false);
        }
        key(KeyEvent.KEY_RELEASED, KeyCode.SPACE, false, false);

        assertThat(presses).hasValue(1);
    }

    @Test
    void aChordWithAModifierIsNotAPress() {
        key(KeyEvent.KEY_PRESSED, KeyCode.ENTER, true, false);
        key(KeyEvent.KEY_RELEASED, KeyCode.ENTER, true, false);
        key(KeyEvent.KEY_PRESSED, KeyCode.SPACE, false, true);
        key(KeyEvent.KEY_RELEASED, KeyCode.SPACE, false, true);

        assertThat(presses).hasValue(0);
    }

    @Test
    void theFocusLeavingMidPressCancelsIt() {
        key(KeyEvent.KEY_PRESSED, KeyCode.SPACE, false, false);
        interact(elsewhere::requestFocus);
        key(KeyEvent.KEY_RELEASED, KeyCode.SPACE, false, false);

        assertThat(presses).hasValue(0);
    }

    @Test
    void releasingAKeyThatWasNeverPressedHereIsNotAPress() {
        key(KeyEvent.KEY_PRESSED, KeyCode.SPACE, false, false);
        key(KeyEvent.KEY_RELEASED, KeyCode.ENTER, false, false);
        assertThat(presses).hasValue(0);

        key(KeyEvent.KEY_RELEASED, KeyCode.SPACE, false, false);
        assertThat(presses).hasValue(1);
    }

    private void key(EventType<KeyEvent> type, KeyCode code, boolean shift, boolean control) {
        interact(() -> Event.fireEvent(label,
                new KeyEvent(type, "", "", code, shift, control, false, false)));
    }
}
