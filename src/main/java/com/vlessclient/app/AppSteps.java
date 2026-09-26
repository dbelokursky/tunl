package com.vlessclient.app;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the app's startup and shutdown as named steps in a fixed order.
 *
 * <p>Both orders matter, and they lived inline in {@link VlessClientApp},
 * which no test could run:</p>
 * <ul>
 *   <li>a core an earlier run left running is ended before the service graph
 *       exists, since its MCP server would race that core for its ports;</li>
 *   <li>the system proxy that core pointed at is cleared once the graph
 *       exists, before the window can connect;</li>
 *   <li>on the way out the MCP port is freed before the tray's AWT teardown,
 *       which can take its whole timeout while an update waits to start.</li>
 * </ul>
 *
 * <p>A step that fails is logged, and the steps after it run. A cleanup that
 * threw used to leave {@code init()} with it, and the app did not start at
 * all. The one step the app cannot go on without, building the service
 * graph, is marked required, and its failure ends the run.</p>
 */
final class AppSteps {

    private static final Logger log = LoggerFactory.getLogger(AppSteps.class);

    /**
     * One step.
     *
     * @param name     what the log calls it
     * @param required whether the app cannot go on without it
     * @param action   the step
     */
    record Step(String name, boolean required, Runnable action) {

        Step {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(action, "action");
        }
    }

    private AppSteps() {
    }

    /**
     * A step whose failure is logged, after which the next one runs.
     *
     * @param name   what the log calls it
     * @param action the step
     * @return the step
     */
    static Step step(String name, Runnable action) {
        return new Step(name, false, action);
    }

    /**
     * A step the app cannot go on without: its failure ends the run.
     *
     * @param name   what the log calls it
     * @param action the step
     * @return the step
     */
    static Step required(String name, Runnable action) {
        return new Step(name, true, action);
    }

    /**
     * Runs {@code steps} in order.
     *
     * <p>A {@link LinkageError} counts as a failure too: the first touch of
     * AWT on a host without a display fails its static initializer with
     * one.</p>
     *
     * @param phase what the log calls the whole, such as "startup"
     * @param steps the steps, in the order they depend on
     * @throws RuntimeException what a required step threw
     * @throws LinkageError     what a required step threw
     */
    static void run(String phase, List<Step> steps) {
        for (Step step : steps) {
            try {
                step.action().run();
            } catch (RuntimeException | LinkageError e) {
                if (step.required()) {
                    throw e;
                }
                log.warn("{}: {} failed; going on without it", phase, step.name(), e);
            }
        }
    }
}
