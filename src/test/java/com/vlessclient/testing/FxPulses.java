package com.vlessclient.testing;

import com.sun.javafx.tk.TKPulseListener;
import com.sun.javafx.tk.Toolkit;
import com.sun.scenario.animation.AbstractPrimaryTimer;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyProperty;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Skin;
import javafx.scene.control.TextInputControl;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * What keeps JavaFX's pulse timer awake, read off the toolkit.
 *
 * <p>While an animation runs, every tick of the timer is a pulse handed to the
 * platform's main thread, window shown or not: a stage hidden to the tray draws
 * nothing and still wakes the process at the display refresh rate while a
 * single {@code PauseTransition} plays. Without one, a pulse happens only when
 * something asks for it, and the timer pauses after 250 ms of nobody asking
 * ({@code QuantumToolkit.postPulse}).</p>
 *
 * <p>None of this is public API. The timer's fields are read by name, so a
 * JavaFX upgrade that moves them fails here, naming the field, instead of
 * reading zero and letting a test that asserts zero pass. Only the
 * descriptions are read leniently: a detail that cannot be traced is left out
 * of one, and the entry still counts.</p>
 */
public final class FxPulses {

    private static final long FX_TIMEOUT_SECONDS = 5;

    private FxPulses() {
    }

    /**
     * The animations and animation timers the toolkit is driving right now.
     * While this is above zero the pulse timer cannot pause.
     *
     * @return the number of running animations, read on the FX thread
     */
    public static int runningAnimations() {
        return onFx(() -> {
            AbstractPrimaryTimer timer = Toolkit.getToolkit().getPrimaryTimer();
            return intField(timer, "receiversLength")
                    + intField(timer, "animationTimersLength");
        });
    }

    /**
     * The animations and animation timers the toolkit is driving right now,
     * each described well enough to find what left it running.
     *
     * @return one entry per animation or animation timer, read on the FX thread
     */
    public static List<Running> running() {
        return onFx(() -> {
            AbstractPrimaryTimer timer = Toolkit.getToolkit().getPrimaryTimer();
            List<Running> running = new ArrayList<>();
            addEntries(running, timer, "receivers", "receiversLength", Animation.class);
            addEntries(running, timer, "animationTimers", "animationTimersLength",
                    AnimationTimer.class);
            return running;
        });
    }

    /**
     * Starts counting the toolkit's pulses. Unlike a scene's pulse listeners
     * it goes on counting while no window is showing, which is exactly when a
     * pulse is pure cost.
     *
     * @return the running counter; close it to stop counting
     */
    public static Counter countPulses() {
        Counter counter = new Counter();
        onFx(() -> {
            Toolkit.getToolkit().addPostSceneTkPulseListener(counter);
            return null;
        });
        return counter;
    }

    /**
     * An animation or animation timer the toolkit drives; see {@link #running()}.
     *
     * @param animation the {@link Animation} or {@link AnimationTimer}
     * @param description what it is and, as far as can be told, whose it is
     * @param repeatsForever whether it runs until something stops it: an
     *     animation that cycles indefinitely, or any animation timer
     */
    public record Running(Object animation, String description, boolean repeatsForever) {

        /** Stops it, on the FX thread. */
        public void stop() {
            onFx(() -> {
                if (animation instanceof Animation playing) {
                    playing.stop();
                } else if (animation instanceof AnimationTimer timer) {
                    timer.stop();
                }
                return null;
            });
        }

        @Override
        public String toString() {
            return description;
        }
    }

    /** A pulse count in progress; see {@link #countPulses()}. */
    public static final class Counter implements TKPulseListener, AutoCloseable {

        private final AtomicLong pulses = new AtomicLong();

        private Counter() {
        }

        @Override
        public void pulse() {
            pulses.incrementAndGet();
        }

        /**
         * Pulses since the counter started.
         *
         * @return the count so far
         */
        public long pulses() {
            return pulses.get();
        }

        @Override
        public void close() {
            onFx(() -> {
                Toolkit.getToolkit().removePostSceneTkPulseListener(this);
                return null;
            });
        }
    }

    /**
     * Runs {@code work} on the FX thread, or right away when already on it,
     * and hands back what it returned. It waits five seconds at most, and
     * never goes through TestFX, so an exception TestFX holds for its next call
     * is left where it is.
     */
    static <T> T onFx(Supplier<T> work) {
        if (Platform.isFxApplicationThread()) {
            return work.get();
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.get());
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        try {
            return result.get(FX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while reading the JavaFX toolkit", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException(e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("the FX thread did not answer within "
                    + FX_TIMEOUT_SECONDS + "s", e);
        }
    }

    private static int intField(AbstractPrimaryTimer timer, String name) {
        return (Integer) field(timer, name);
    }

    private static Object field(AbstractPrimaryTimer timer, String name) {
        try {
            Field field = AbstractPrimaryTimer.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(timer);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("AbstractPrimaryTimer." + name
                    + " is gone in this JavaFX; update FxPulses", e);
        }
    }

    /**
     * Lists the entries of one of the timer's two arrays. An entry is a
     * ReceiverRecord around the receiver the timer calls, and the receiver an
     * inner object of the {@code kind} of thing it drives.
     */
    private static void addEntries(List<Running> running, AbstractPrimaryTimer timer,
                                   String array, String length, Class<?> kind) {
        Object[] records = (Object[]) field(timer, array);
        int count = intField(timer, length);
        try {
            Method receiver = Class.forName(AbstractPrimaryTimer.class.getName()
                    + "$ReceiverRecord").getDeclaredMethod("receiver");
            receiver.setAccessible(true);
            for (int i = 0; i < count; i++) {
                Object animation = outer(receiver.invoke(records[i]), kind);
                running.add(new Running(animation, describe(animation),
                        !(animation instanceof Animation playing)
                                || playing.getCycleCount() == Animation.INDEFINITE));
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("AbstractPrimaryTimer.ReceiverRecord.receiver()"
                    + " is gone in this JavaFX; update FxPulses", e);
        }
    }

    /**
     * The {@code kind} of object {@code receiver} is an inner object of. Not
     * lenient: an entry that leads to no animation could be neither told apart
     * nor stopped.
     */
    private static Object outer(Object receiver, Class<?> kind) {
        for (Object value : fieldValues(receiver)) {
            if (kind.isInstance(value)) {
                return value;
            }
        }
        throw new IllegalStateException(receiver.getClass().getName() + " holds no "
                + kind.getSimpleName() + " in this JavaFX; update FxPulses");
    }

    /**
     * What an entry is, and whose: the class its handler was written in and,
     * for a caret, the field it blinks in and whether that is on screen.
     */
    private static String describe(Object entry) {
        if (!(entry instanceof Animation animation)) {
            return entry.getClass().getName();
        }
        String what = animation.getClass().getSimpleName()
                + (animation.getCycleCount() == Animation.INDEFINITE
                        ? " repeating every " + millis(animation.getCycleDuration())
                        : " of " + millis(animation.getTotalDuration()));
        Object handler = handler(animation);
        if (handler == null) {
            return what;
        }
        String field = fieldBehind(handler);
        return what + ", handled in " + declaringClass(handler)
                + (field == null ? "" : " for " + field);
    }

    /** The code the animation calls: its first key frame's handler, else its own. */
    private static Object handler(Animation animation) {
        if (animation instanceof Timeline timeline) {
            for (KeyFrame frame : timeline.getKeyFrames()) {
                if (frame.getOnFinished() != null) {
                    return frame.getOnFinished();
                }
            }
        }
        return animation.getOnFinished();
    }

    /** The class a handler was written in, which a lambda's hidden class is named after. */
    private static String declaringClass(Object handler) {
        String name = handler.getClass().getName();
        int lambda = name.indexOf("$$Lambda");
        if (lambda >= 0) {
            name = name.substring(0, lambda);
        }
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /**
     * The text field a handler works for, when it can be traced: a caret's
     * blink handler holds its skin's blink property weakly, and the bean of
     * that property is the skin of the field.
     */
    private static String fieldBehind(Object handler) {
        for (Object captured : fieldValues(handler)) {
            if (captured == null) {
                continue;
            }
            for (Object held : fieldValues(captured)) {
                if (held instanceof Reference<?> reference
                        && reference.get() instanceof ReadOnlyProperty<?> property
                        && property.getBean() instanceof Skin<?> skin
                        && skin.getSkinnable() instanceof Node node) {
                    return describeField(node);
                }
            }
        }
        return null;
    }

    /** A node by class, id and prompt or text, and where it is. */
    private static String describeField(Node node) {
        StringBuilder text = new StringBuilder(node.getClass().getSimpleName());
        if (node.getId() != null) {
            text.append('#').append(node.getId());
        }
        if (node instanceof TextInputControl field) {
            String prompt = field.getPromptText();
            text.append(" \"").append(prompt == null || prompt.isEmpty() ? field.getText() : prompt)
                    .append('"');
        }
        Scene scene = node.getScene();
        Window window = scene == null ? null : scene.getWindow();
        return text.append(scene == null ? " in no scene"
                : window == null ? " in a scene with no window"
                : window.isShowing() ? " in a showing window" : " in a hidden window")
                .toString();
    }

    /** The values of an object's own instance fields, but for those Java will not open. */
    private static List<Object> fieldValues(Object object) {
        List<Object> values = new ArrayList<>();
        for (Field field : object.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                continue;
            }
            try {
                field.setAccessible(true);
                values.add(field.get(object));
            } catch (IllegalAccessException | InaccessibleObjectException closed) {
                // A field of a JDK class, which nothing here is traced through.
            }
        }
        return values;
    }

    private static String millis(Duration duration) {
        return String.format(Locale.ROOT, "%.0f ms", duration.toMillis());
    }
}
