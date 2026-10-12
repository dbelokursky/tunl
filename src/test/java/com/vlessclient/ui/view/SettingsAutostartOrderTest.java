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
import java.util.concurrent.atomic.AtomicInteger;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * "Launch at login" says what the system holds, in the order the user asked.
 *
 * <p>Every read and every write of the entry was a thread of its own, and on
 * Windows each is a {@code reg} process. A write could finish before an
 * earlier one, and a read that began before the user's click could answer
 * after it: either way the box contradicted the system until Settings was
 * opened again.</p>
 */
@UiTest
public class SettingsAutostartOrderTest extends ApplicationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @TempDir
    static Path tempDir;

    /** Turns the entry on slowly, and answers a read late when told to. */
    private static final class SlowEntry implements Autostart {
        private final CountDownLatch enablingMayEnd = new CountDownLatch(1);
        private final CountDownLatch readsMayEnd = new CountDownLatch(1);
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger writes = new AtomicInteger();
        private volatile boolean holdReads;
        private volatile boolean enabled;

        @Override
        public boolean isEnabled() {
            boolean seen = enabled;
            reads.incrementAndGet();
            if (holdReads) {
                await(readsMayEnd);
            }
            return seen;
        }

        @Override
        public void setEnabled(boolean value) {
            if (value) {
                await(enablingMayEnd);
            }
            enabled = value;
            writes.incrementAndGet();
        }

        @Override
        public void refresh() {
        }

        private static void await(CountDownLatch latch) {
            try {
                latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private final SlowEntry entry = new SlowEntry();
    private ViewShownAware controller;

    @Override
    public void start(Stage stage) throws Exception {
        ServiceLocator.register(ConfigStore.class, new ConfigStore(
                tempDir.resolve(UUID.randomUUID().toString()), SecretSealers.disabled()));
        ServiceLocator.register(Autostart.class, entry);
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/SettingsView.fxml"));
        Scene scene = new Scene(loader.load(), 700, 720);
        controller = loader.getController();
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void releaseTheEntry() {
        entry.enablingMayEnd.countDown();
        entry.readsMayEnd.countDown();
    }

    /** Ticked and unticked at once: the entry ends as the box does, off. */
    @Test
    void writesReachTheSystemInTheOrderTheyWereMade() {
        CheckBox box = lookup("#launchAtLoginCheck").queryAs(CheckBox.class);
        Await.until("the page to have read the entry", () -> entry.reads.get() > 0, PATIENCE);
        WaitForAsyncUtils.waitForFxEvents();

        interact(() -> box.setSelected(true));
        interact(() -> box.setSelected(false));
        entry.enablingMayEnd.countDown();
        Await.until("both writes", () -> entry.writes.get() == 2, PATIENCE);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(box.isSelected()).as("the box").isFalse();
        assertThat(entry.enabled).as("the entry").isFalse();
    }

    /**
     * A read that saw the entry on, answering after the user turned it off,
     * ticked the box again over an entry that was gone.
     */
    @Test
    void aReadOverTakenByTheUsersClickIsNotShown() {
        CheckBox box = lookup("#launchAtLoginCheck").queryAs(CheckBox.class);
        entry.enabled = true;
        interact(controller::onViewShown);
        Await.until("the box to show the entry on", box::isSelected, PATIENCE);

        entry.holdReads = true;
        int before = entry.reads.get();
        interact(controller::onViewShown);
        Await.until("the page to read again", () -> entry.reads.get() > before, PATIENCE);
        interact(() -> box.setSelected(false));
        entry.readsMayEnd.countDown();
        Await.until("the write", () -> entry.writes.get() == 1, PATIENCE);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(box.isSelected()).as("the box").isFalse();
        assertThat(entry.enabled).as("the entry").isFalse();
    }
}
