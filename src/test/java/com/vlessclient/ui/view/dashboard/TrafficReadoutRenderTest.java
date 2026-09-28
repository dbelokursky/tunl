package com.vlessclient.ui.view.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.service.TrafficMonitor;
import com.vlessclient.testing.UiTest;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * What one traffic sample costs the readout on screen: every sample re-applied
 * the CSS of all four readout nodes, whose colours change only when a
 * direction goes idle or wakes, and rendered the session total twice.
 */
@UiTest
public class TrafficReadoutRenderTest extends ApplicationTest {

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
    private final Label uploadIcon = new Label();
    private final Label uploadSpeed = new Label();
    private final Label downloadIcon = new Label();
    private final Label downloadSpeed = new Label();
    private final Label sessionTotal = new Label();
    private TrafficDisplayBinder binder;

    @Override
    public void start(Stage stage) {
        ServiceLocator.register(AppSettings.class, new AppSettings());
        VBox summary = new VBox(uploadIcon, uploadSpeed, downloadIcon, downloadSpeed,
                sessionTotal);
        binder = new TrafficDisplayBinder(monitor,
                new TrafficDisplayBinder.Readout(uploadIcon, uploadSpeed),
                new TrafficDisplayBinder.Readout(downloadIcon, downloadSpeed),
                sessionTotal, summary, new VBox(), () -> null);
        binder.bindLabels();
        stage.setScene(new Scene(summary, 300, 200));
        stage.show();
        binder.onConnectionStateChanged(ConnectionState.CONNECTED);
    }

    @Test
    void aSampleThatChangesNoColourLeavesTheStyleClassesAlone() throws Exception {
        sample(4_000, 9_000);
        AtomicInteger restyles = new AtomicInteger();
        ListChangeListener<String> counter = change -> restyles.incrementAndGet();
        interact(() -> {
            uploadIcon.getStyleClass().addListener(counter);
            uploadSpeed.getStyleClass().addListener(counter);
            downloadIcon.getStyleClass().addListener(counter);
            downloadSpeed.getStyleClass().addListener(counter);
        });

        sample(5_000, 7_000);
        sample(3_000, 8_000);
        assertThat(restyles.get()).as("style class changes over two busy samples").isZero();

        sample(0, 8_000);
        assertThat(restyles.get()).as("upload going idle recolours its two nodes").isEqualTo(2);
        assertThat(uploadSpeed.getStyleClass()).containsExactly("speed-value", "speed-value-idle");
    }

    @Test
    void aSampleRendersTheSessionTotalOnce() throws Exception {
        sample(1_000, 1_000);
        AtomicInteger renders = new AtomicInteger();
        interact(() -> sessionTotal.textProperty()
                .addListener((obs, was, is) -> renders.incrementAndGet()));

        sample(2_000, 3_000);

        assertThat(renders.get()).as("session total text changes for one sample").isEqualTo(1);
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
