package com.vlessclient.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;

/**
 * Lays out a scene a test built without a stage until its layout stops
 * changing, so what the test measures is what a window would show.
 *
 * <p>A shown scene is laid out on every pulse for as long as anything in it
 * asks for layout. A scene that is never shown has no pulse: JavaFX registers
 * a scene's pulse listener only once a window shows it, so the scene gets
 * exactly the passes the test runs. One pass is not a finished layout. A skin
 * decides some things from the size the pass just gave it and asks for
 * another pass, and TextAreaSkin decides whether its vertical bar shows in a
 * {@code Platform.runLater} it posts from its own layout. After the single
 * pass SettingsMcpCommandTest used to measure, 206 parents in MainView were
 * still waiting to be laid out.</p>
 *
 * <p>So each pass applies CSS, lays the root out, and then lets the FX thread
 * run what the pass posted. The scene counts as settled once no visible parent
 * in it waits for layout and a pass moved nothing. A scene that never gets there
 * fails the test, saying what kept changing, rather than being measured
 * half-done.</p>
 *
 * <p>Call it from the test thread, never from inside {@code interact}: what
 * the layout posts with {@code runLater} only runs between passes.</p>
 */
public final class SettledLayout {

    /** More than any view here has needed; a scene still moving after this is a loop. */
    private static final int MAX_PASSES = 10;

    private static final long FX_TIMEOUT_SECONDS = 10;

    private SettledLayout() {
    }

    /**
     * Lays {@code scene} out until it is settled.
     *
     * @param scene a scene whose root is already sized as the test wants it
     * @return how many passes it took
     * @throws AssertionError if the layout is still changing after
     *                        {@value #MAX_PASSES} passes
     */
    public static int settle(Scene scene) {
        if (Platform.isFxApplicationThread()) {
            throw new IllegalStateException("settle from the test thread: what a layout pass "
                    + "posts with runLater runs only between passes");
        }
        String before = null;
        List<String> waiting = List.of();
        for (int pass = 1; pass <= MAX_PASSES; pass++) {
            onFx(() -> {
                Parent root = scene.getRoot();
                root.applyCss();
                root.layout();
                return null;
            });
            flushFxEvents();
            Snapshot now = onFx(() -> Snapshot.of(scene.getRoot()));
            waiting = now.waiting();
            if (waiting.isEmpty() && now.geometry().equals(before)) {
                return pass;
            }
            before = now.geometry();
        }
        throw new AssertionError(("the scene's layout was still changing after %d passes; "
                + "parents still waiting for layout: %s").formatted(MAX_PASSES,
                waiting.isEmpty() ? "none, but the last pass moved something" : waiting));
    }

    /** Where everything in a scene is, and which parents still ask for layout. */
    private record Snapshot(String geometry, List<String> waiting) {

        static Snapshot of(Parent root) {
            StringBuilder geometry = new StringBuilder();
            List<String> waiting = new ArrayList<>();
            collect(root, geometry, waiting);
            return new Snapshot(geometry.toString(), List.copyOf(waiting));
        }

        private static void collect(Node node, StringBuilder geometry, List<String> waiting) {
            Bounds bounds = node.getLayoutBounds();
            geometry.append(String.format(Locale.ROOT, "%s %.3f %.3f %.3f %.3f %b%n",
                    node.getClass().getSimpleName(), node.getLayoutX(), node.getLayoutY(),
                    bounds.getWidth(), bounds.getHeight(), node.isVisible()));
            // A hidden subtree is not shown, and JavaFX may leave it waiting
            // for a layout it never needs: the server form's sections for
            // the transports and security modes not picked asked for one on
            // every pass. Whether it is hidden is recorded above.
            if (!node.isVisible()) {
                return;
            }
            if (node instanceof Parent parent) {
                if (parent.isNeedsLayout()) {
                    waiting.add(describe(parent));
                }
                for (Node child : parent.getChildrenUnmodifiable()) {
                    collect(child, geometry, waiting);
                }
            }
        }

        private static String describe(Node node) {
            String name = node.getClass().getSimpleName();
            if (node.getId() != null) {
                return name + "#" + node.getId();
            }
            return node.getStyleClass().isEmpty() ? name
                    : name + "." + String.join(".", node.getStyleClass());
        }
    }

    private static <T> T onFx(Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(action.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        try {
            return result.get(FX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while laying the scene out", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        } catch (TimeoutException e) {
            throw new AssertionError("the FX thread did not lay the scene out within "
                    + FX_TIMEOUT_SECONDS + "s", e);
        }
    }

    private static void flushFxEvents() {
        try {
            FxTestSupport.flushFxEvents();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while laying the scene out", e);
        }
    }
}
