package com.vlessclient.app;

import com.sun.glass.ui.Application;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.TrayIconService;
import com.vlessclient.testing.UiTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * macOS's Quit quits the app when JavaFX rather than AWT answers it.
 *
 * <p>The app menu's Quit, Cmd+Q and the Dock's Quit reached the AWT quit
 * handler only while AWT's delegate answered them. With a JavaFX window in
 * front, JavaFX's own quit ran instead, and all it does is ask every window
 * to close: the main window hid in the tray, as for its close button, and the
 * app kept running after the user had quit it. Here that quit is JavaFX's
 * own, the {@code handleQuitAction} its macOS delegate calls.</p>
 */
@UiTest
public class SystemQuitTest extends ApplicationTest {

    /** Keeps every other window open through the quit's close requests. */
    private final EventHandler<WindowEvent> keepOpen = WindowEvent::consume;

    private final List<Window> others = new ArrayList<>();

    private Stage window;

    @Override
    public void start(Stage primary) {
        // JavaFX's quit asks every window shown to close, the suite's own too.
        for (Window shown : List.copyOf(Window.getWindows())) {
            shown.addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, keepOpen);
            others.add(shown);
        }
        window = new Stage();
        window.setScene(new Scene(new StackPane(), 300, 200));
        window.show();
    }

    @AfterEach
    void putTheWindowsBack() {
        interact(() -> {
            window.hide();
            for (Window other : others) {
                other.removeEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, keepOpen);
            }
        });
    }

    /** JavaFX's quit, as its macOS delegate runs it for the app menu's Quit. */
    private void quitThroughJavaFx() {
        interact(() -> {
            Application app = Application.GetApplication();
            app.getEventHandler().handleQuitAction(app, System.nanoTime());
        });
    }

    /** The close button's request: the window's own, not every window's. */
    private void closeTheWindow() {
        interact(() -> window.fireEvent(
                new WindowEvent(window, WindowEvent.WINDOW_CLOSE_REQUEST)));
    }

    @Test
    void aQuitIsToldApartFromAWindowsClose() {
        List<Boolean> quitting = new ArrayList<>();
        interact(() -> window.setOnCloseRequest(event -> {
            quitting.add(SystemQuit.isClosingEveryWindow());
            event.consume();
        }));

        quitThroughJavaFx();
        closeTheWindow();

        assertThat(quitting)
                .as("the quit's close request, then the close button's")
                .containsExactly(true, false);
    }

    @Test
    void theMainWindowQuitsOnTheSystemsQuitThoughTheTrayCouldHideIt() {
        TrayIconService showing = new TrayIconService(SingBoxEngine.withoutCore(), null,
                null, null, null, null) {
            @Override
            public boolean isShowing() {
                return true;
            }
        };
        AtomicInteger quits = new AtomicInteger();
        interact(() -> window.setOnCloseRequest(
                VlessClientApp.closeHandler(window, () -> showing, quits::incrementAndGet)));

        closeTheWindow();
        assertThat(window.isShowing()).as("the close button hides it in the tray").isFalse();
        assertThat(quits).as("and the app runs on").hasValue(0);

        interact(window::show);
        quitThroughJavaFx();
        assertThat(quits).as("Quit quits").hasValue(1);
    }
}
