package com.vlessclient.testing;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.stage.Stage;
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
 * <p>Each test is checked as it ends, not each class, because a leaked caret
 * does not outlive a GC: it stops once the closed dialog it blinks in is
 * collected. At the end of ViewDialogThemeTest the same leak read nine carets
 * in one run and five in another, so a class that leaked one could pass. As a
 * test ends it reads the same every time, and names the test.</p>
 *
 * <p>A caret blinks for as long as its field is focused, and Monocle keeps a
 * window focused after it hides. A dialog closed with a field focused, by its
 * own button or by a teardown hiding it, blinks on unless the test takes the
 * focus to the dialog's root. TestFX hides its stage after every test, and a
 * field focused in the view under test would blink on the same way; that stage
 * is TestFX's rather than the test's, so the focus is taken off it here, before
 * anything is counted.</p>
 */
public final class AnimationLeakGuardExtension implements BeforeEachCallback, AfterEachCallback {

    private static final Namespace NAMESPACE =
            Namespace.create(AnimationLeakGuardExtension.class);
    private static final String RUNNING_BEFORE = "runningBefore";
    /** Time for a finite animation to end: a button's confirmation flash plays 1.2 s. */
    private static final Duration SETTLE = Duration.ofSeconds(5);

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        // ApplicationTest starts the toolkit in its own @BeforeEach, after this.
        FxToolkit.registerPrimaryStage();
        context.getStore(NAMESPACE).put(RUNNING_BEFORE, FxPulses.running().stream()
                .map(FxPulses.Running::animation)
                .collect(Collectors.toSet()));
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        Set<?> before = context.getStore(NAMESPACE).remove(RUNNING_BEFORE, Set.class);
        if (before == null) {
            return;
        }
        FxToolkit.setupFixture(() -> {
            Stage stage = FxToolkit.toolkitContext().getRegisteredStage();
            if (stage != null && stage.getScene() != null) {
                stage.getScene().getRoot().requestFocus();
            }
        });
        // Only a finite animation is waited for. One that repeats forever will
        // not end by waiting, and a caret left in a closed dialog stops as soon
        // as the dialog is collected, so a wait would only give a GC the time to
        // hide it.
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
                + "\nA caret blinks while its field is focused, and Monocle keeps a window"
                + " focused after it hides: take the focus to the root of each window the"
                + " test closed. They are stopped now, so the tests after it start clean.";
    }
}
