package com.vlessclient.testing;

import java.lang.ref.Reference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.collections.ListChangeListener;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.testfx.api.FxToolkit;

/**
 * Fails a UI test that leaves an animation running, naming the test and what
 * it left, and stops what it left so that the tests after it start clean.
 *
 * <p>While any animation runs, JavaFX pulses at the display refresh rate,
 * window shown or not, and a surefire fork has one toolkit for all of its
 * classes. DashboardHiddenWindowTest counts the pulses of a dashboard in the
 * tray and allows two. Run after ViewDialogThemeTest, which had left carets
 * blinking, it counted over two hundred and failed while the class that leaked
 * passed, and only in a fork that happened to run the two in that order.</p>
 *
 * <p>A caret blinks for as long as its field is focused, and the headless
 * platform keeps a window focused after it hides. A dialog closed with a
 * field focused, by its own button or by a teardown hiding it, blinks on
 * unless the test takes the focus to the dialog's root. TestFX hides its
 * stage after every test, and a field focused in the view under test would
 * blink on the same way; that stage is TestFX's rather than the test's, so
 * the focus is taken off it here, before anything is counted.</p>
 *
 * <p>Such a caret also stops by itself once the dialog it blinks in is
 * collected, so what is found would depend on when a GC ran: at the end of
 * ViewDialogThemeTest the same leak read nine carets in one run and five in
 * another. So each test is checked as it ends, and every window shown during
 * the test is held until then, which keeps a form the test closed and kept no
 * reference to from being collected before it is counted.</p>
 *
 * <p>The guard's work on the FX thread goes past TestFX, which would first
 * rethrow an exception an earlier FX task left behind and skip the check. That
 * exception still reaches the next TestFX call, as it would without the
 * guard.</p>
 */
public final class AnimationLeakGuardExtension implements BeforeEachCallback, AfterEachCallback {

    private static final Namespace NAMESPACE =
            Namespace.create(AnimationLeakGuardExtension.class);
    private static final String RUNNING_BEFORE = "runningBefore";
    private static final String SHOWN = "shown";
    /** Time for a finite animation to end: a button's confirmation flash plays 1.2 s. */
    private static final Duration SETTLE = Duration.ofSeconds(5);

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        // ApplicationTest starts the toolkit in its own @BeforeEach, after this.
        FxToolkit.registerPrimaryStage();
        ExtensionContext.Store store = context.getStore(NAMESPACE);
        store.put(RUNNING_BEFORE, FxPulses.running().stream()
                .map(FxPulses.Running::animation)
                .collect(Collectors.toSet()));
        store.put(SHOWN, FxPulses.onFx(() -> {
            ShownWindows shown = new ShownWindows();
            Window.getWindows().addListener(shown);
            return shown;
        }));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(NAMESPACE);
        Set<?> before = store.remove(RUNNING_BEFORE, Set.class);
        ShownWindows shown = store.remove(SHOWN, ShownWindows.class);
        if (before == null || shown == null) {
            return;
        }
        try {
            FxPulses.onFx(() -> {
                Window.getWindows().removeListener(shown);
                Stage stage = FxToolkit.toolkitContext().getRegisteredStage();
                if (stage != null && stage.getScene() != null) {
                    stage.getScene().getRoot().requestFocus();
                }
                return null;
            });
            // Only a finite animation is waited for: one that repeats forever
            // does not end by waiting.
            try {
                Await.until("the finite animations the test started to end",
                        () -> startedSince(before).stream()
                                .allMatch(FxPulses.Running::repeatsForever),
                        SETTLE);
            } catch (AssertionError stillPlaying) {
                // A finite animation longer than SETTLE is reported with the rest.
            }
            List<FxPulses.Running> left = startedSince(before);
            if (!left.isEmpty()) {
                left.forEach(FxPulses.Running::stop);
                throw new AssertionError(report(context, left));
            }
        } finally {
            // The windows are what keep a caret in them from stopping through a
            // GC before it is counted, so they stay reachable up to here.
            Reference.reachabilityFence(shown);
        }
    }

    private static List<FxPulses.Running> startedSince(Set<?> before) {
        return FxPulses.running().stream()
                .filter(running -> !before.contains(running.animation()))
                .toList();
    }

    private static String report(ExtensionContext context, List<FxPulses.Running> left) {
        String method = context.getRequiredTestMethod().getName();
        String test = context.getRequiredTestClass().getSimpleName() + "." + method
                + (context.getDisplayName().startsWith(method)
                        ? "" : " " + context.getDisplayName());
        return test + " left " + left.size()
                + (left.size() == 1 ? " animation" : " animations")
                + " running, and JavaFX pulses at the display refresh rate while one runs,"
                + " through every test after it in the fork:\n  "
                + left.stream().map(FxPulses.Running::description)
                        .collect(Collectors.joining("\n  "))
                + "\nA caret blinks while its field is focused, and the headless platform"
                + " keeps a window focused after it hides: take the focus to the root of"
                + " each window the test closed. They are stopped now, so the tests after"
                + " it start clean.";
    }

    /** Every window shown while it listens, held until the test has been checked. */
    private static final class ShownWindows implements ListChangeListener<Window> {

        private final List<Window> windows = new ArrayList<>();

        @Override
        public void onChanged(Change<? extends Window> change) {
            while (change.next()) {
                windows.addAll(change.getAddedSubList());
            }
        }
    }
}
