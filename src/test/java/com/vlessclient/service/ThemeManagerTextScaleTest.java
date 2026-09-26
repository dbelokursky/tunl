package com.vlessclient.service;

import com.vlessclient.testing.FxToolkitExtension;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The app's text follows the OS's text size.
 *
 * <p>base.css sets its font sizes in pixels, so a user who made the system's
 * text larger got it everywhere but in this window.</p>
 */
@ExtendWith(FxToolkitExtension.class)
class ThemeManagerTextScaleTest {

    @Test
    void aLargerTextSizeScalesTheFontsBaseCssSets() throws Exception {
        assertThat(titleSizeAt(1.5)).isEqualTo(33.0);
    }

    @Test
    void theUsualTextSizeLeavesBaseCssAsItIs() throws Exception {
        assertThat(titleSizeAt(1.0)).isEqualTo(22.0);
    }

    @Test
    void dialogsTakeTheScaledFontsToo() {
        ThemeManager themes = new ThemeManager(1.5);

        assertThat(themes.currentStylesheets()).last().asString().startsWith("data:text/css");
        assertThat(new ThemeManager(1.0).currentStylesheets()).hasSize(2);
    }

    /** The size a view title is drawn at under a manager at {@code scale}. */
    private static double titleSizeAt(double scale) throws Exception {
        AtomicReference<Double> size = new AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Platform.runLater(() -> {
            ThemeManager themes = new ThemeManager(scale);
            try {
                Label title = new Label("Servers");
                title.getStyleClass().add("view-title");
                StackPane root = new StackPane(title);
                root.getStyleClass().add("root-pane");
                Scene scene = new Scene(root, 200, 100);
                themes.applyTheme(scene);
                root.applyCss();
                size.set(title.getFont().getSize());
            } finally {
                themes.stopWatching();
                done.countDown();
            }
        });
        assertThat(done.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        return size.get();
    }
}
