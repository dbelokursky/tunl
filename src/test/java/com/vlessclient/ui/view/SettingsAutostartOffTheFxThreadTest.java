package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.platform.Autostart;
import com.vlessclient.platform.SecretSealers;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * The Settings page never waits on the autostart entry from the FX thread.
 *
 * <p>"Launch at login" reads the truth from the system rather than from the
 * settings file, and on Windows that truth comes from {@code reg query} — a
 * process, behind a two-second timeout. It was asked on the FX thread, and
 * every visit to Settings froze the window for as long as the query took. A
 * loaded Windows runner hit the timeout outright: {@code WindowsAutostart}
 * logged "output still open PT2S after the command exited" while the toolkit
 * stood still, and the tests waiting on it failed all over the suite.</p>
 *
 * <p>Writing it is the same call in the other direction: ticking the box ran
 * {@code reg add} on the FX thread.</p>
 */
@UiTest
public class SettingsAutostartOffTheFxThreadTest extends ApplicationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @TempDir
    static Path tempDir;

    /** Answers only when the test lets it, and remembers who asked. */
    private static final class BlockingAutostart implements Autostart {
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicBoolean askedOnFxThread = new AtomicBoolean();
        private final AtomicBoolean writtenOnFxThread = new AtomicBoolean();
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger writes = new AtomicInteger();
        private volatile boolean enabled = true;

        @Override
        public boolean isEnabled() {
            askedOnFxThread.compareAndSet(false, Platform.isFxApplicationThread());
            reads.incrementAndGet();
            await();
            return enabled;
        }

        @Override
        public void setEnabled(boolean value) {
            writtenOnFxThread.compareAndSet(false, Platform.isFxApplicationThread());
            writes.incrementAndGet();
            await();
            enabled = value;
        }

        @Override
        public void refresh() {
        }

        private void await() {
            try {
                released.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private final BlockingAutostart autostart = new BlockingAutostart();
    private ViewShownAware controller;

    @Override
    public void start(Stage stage) throws Exception {
        ServiceLocator.register(ConfigStore.class, new ConfigStore(
                tempDir.resolve(UUID.randomUUID().toString()), SecretSealers.disabled()));
        ServiceLocator.register(Autostart.class, autostart);
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/SettingsView.fxml"));
        Scene scene = new Scene(loader.load(), 700, 720);
        controller = loader.getController();
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void releaseTheEntry() {
        autostart.released.countDown();
    }

    /**
     * Showing the page hands back at once, with the answer still outstanding,
     * and the box catches up when it arrives.
     */
    @Test
    void showingThePageDoesNotWaitForTheEntry() {
        interact(controller::onViewShown);
        Await.until("the page to ask the system", () -> autostart.reads.get() > 0, PATIENCE);

        assertThat(autostart.askedOnFxThread).as("who asked the system").isFalse();

        autostart.released.countDown();
        CheckBox box = lookup("#launchAtLoginCheck").queryAs(CheckBox.class);
        Await.until("the box to show what the system says", box::isSelected, PATIENCE);
        assertThat(autostart.writes.get())
                .as("showing what the system says is not a change to write back")
                .isZero();
    }

    /** Ticking the box writes the entry off the FX thread as well. */
    @Test
    void tickingTheBoxDoesNotWaitForTheEntryEither() {
        CheckBox box = lookup("#launchAtLoginCheck").queryAs(CheckBox.class);
        interact(() -> box.setSelected(!box.isSelected()));
        Await.until("the entry to be written", () -> autostart.writes.get() > 0, PATIENCE);

        assertThat(autostart.writtenOnFxThread).as("who wrote the entry").isFalse();
    }
}
