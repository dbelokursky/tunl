package com.vlessclient.ui.view.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.service.TrafficMonitor;
import com.vlessclient.testing.UiTest;
import com.vlessclient.ui.view.TrafficText;
import java.lang.reflect.Method;
import java.util.Locale;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * With no tunnel up, the total line reads this month's figure: the same
 * label as "N this session", which it leaves when the tunnel goes down.
 *
 * <p>It kept the session's split tooltip, which read "Uploaded 0 B ·
 * downloaded 0 B" over the month figure. And a language switch rendered the
 * session line whatever the state, so switching it while disconnected (an
 * agent's set_setting applies at once) put "0 B за сессию" where the month
 * figure had been, until the next state change.</p>
 */
@UiTest
public class TrafficIdleLineTest extends ApplicationTest {

    private final TrafficMonitor monitor = new TrafficMonitor() {
        @Override
        public void start(int clashApiPort, String secret) {
            // Samples are fed by the test.
        }

        @Override
        public void stop() {
            // Nothing was started.
        }
    };
    private Label sessionTotal;
    private TrafficDisplayBinder binder;

    @Override
    public void start(Stage stage) {
        ServiceLocator.register(AppSettings.class, new AppSettings());
        sessionTotal = new Label();
        VBox summary = new VBox(sessionTotal);
        binder = new TrafficDisplayBinder(monitor,
                new TrafficDisplayBinder.Readout(new Label(), new Label()),
                new TrafficDisplayBinder.Readout(new Label(), new Label()),
                sessionTotal, summary, new VBox(), TrafficIdleLineTest::month);
        binder.bindLabels();
        stage.setScene(new Scene(summary, 300, 200));
        stage.show();
    }

    @AfterEach
    void backToEnglish() {
        interact(() -> I18n.setLocale(Locale.ENGLISH));
    }

    @Test
    void theMonthLineCarriesNoSplitOfTheSessionBefore() throws Exception {
        interact(() -> binder.onConnectionStateChanged(ConnectionState.CONNECTED));
        sample(4_000, 9_000);
        assertThat(sessionTotal.getTooltip()).as("the split of a live session").isNotNull();

        interact(() -> binder.onConnectionStateChanged(ConnectionState.DISCONNECTED));

        assertThat(sessionTotal.getText()).isEqualTo(month());
        assertThat(sessionTotal.getTooltip()).as("a split behind the month figure").isNull();

        interact(() -> binder.onConnectionStateChanged(ConnectionState.CONNECTED));
        assertThat(sessionTotal.getText())
                .as("a new session opens on its own count")
                .isEqualTo(I18n.get("dashboard.traffic.session", TrafficText.bytes(0)));
        assertThat(sessionTotal.getTooltip()).as("the split, back with the session").isNotNull();
    }

    @Test
    void aLanguageSwitchWhileIdleKeepsTheMonthLine() {
        interact(() -> binder.onConnectionStateChanged(ConnectionState.CONNECTED));
        interact(() -> binder.onConnectionStateChanged(ConnectionState.DISCONNECTED));

        interact(() -> I18n.setLocale(Locale.of("ru")));

        assertThat(sessionTotal.getText()).isEqualTo(month());
    }

    /** What the history holds for this month, in the language now in use. */
    private static String month() {
        return I18n.get("dashboard.traffic.history.month", "3.4 GB");
    }

    /** One {@code /traffic} line, as the core streams it once a second. */
    private void sample(long up, long down) throws Exception {
        Method process = TrafficMonitor.class.getDeclaredMethod("processTrafficLine", String.class);
        process.setAccessible(true);
        process.invoke(monitor, "{\"up\":" + up + ",\"down\":" + down + "}");
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.waitForFxEvents();   // the queued render of the total
    }
}
