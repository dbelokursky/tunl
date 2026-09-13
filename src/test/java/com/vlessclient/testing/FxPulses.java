package com.vlessclient.testing;

import com.sun.javafx.tk.TKPulseListener;
import com.sun.javafx.tk.Toolkit;
import com.sun.scenario.animation.AbstractPrimaryTimer;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javafx.application.Platform;

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
 * <p>Neither figure is public API. The timer's fields are read by name, so a
 * JavaFX upgrade that moves them fails here, naming the field, instead of
 * reading zero and letting a test that asserts zero pass.</p>
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

    private static int intField(AbstractPrimaryTimer timer, String name) {
        try {
            Field field = AbstractPrimaryTimer.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(timer);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("AbstractPrimaryTimer." + name
                    + " is gone in this JavaFX; update FxPulses", e);
        }
    }

    private static <T> T onFx(Supplier<T> work) {
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
}
