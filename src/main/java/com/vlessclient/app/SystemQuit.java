package com.vlessclient.app;

/**
 * Tells a close request that is the system quitting the app apart from the
 * user closing a window.
 *
 * <p>On macOS the app menu's Quit, Cmd+Q and the Dock's Quit end the app
 * through the AWT quit handler only when AWT's application delegate answers
 * them. While a JavaFX window is in front, JavaFX's delegate and menu answer
 * instead, and JavaFX's quit does no more than ask every window to close
 * ({@code GlassStage.requestClosingAllWindows}, the whole of its
 * {@code handleQuitAction}). The main window answers a close by hiding in the
 * tray, so Quit hid the window and the app ran on, with nothing in the log.</p>
 *
 * <p>The two requests are the same {@code WINDOW_CLOSE_REQUEST}, so the code
 * that sent it is what tells them apart: JavaFX sends a quit's close requests
 * from inside {@code requestClosingAllWindows}, on the same thread and before
 * it returns, and a window's close button from that window's own event.</p>
 */
final class SystemQuit {

    /** The JavaFX class whose method sends a quit's close requests. */
    private static final String SENDER_CLASS = "com.sun.javafx.tk.quantum.GlassStage";

    /** That method. */
    private static final String SENDER_METHOD = "requestClosingAllWindows";

    private SystemQuit() {
    }

    /**
     * Whether the close request being handled on this thread is JavaFX
     * closing every window for a quit.
     *
     * @return true inside a quit's close requests, false for a window's own
     *         close
     */
    static boolean isClosingEveryWindow() {
        return StackWalker.getInstance().walk(frames -> frames.anyMatch(
                frame -> SENDER_METHOD.equals(frame.getMethodName())
                        && SENDER_CLASS.equals(frame.getClassName())));
    }
}
