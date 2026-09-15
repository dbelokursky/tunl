package com.vlessclient.platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a command and waits for it no longer than a timeout. Both obvious ways
 * of waiting hang: reading the output to the end before a timed wait holds the
 * caller for as long as the command keeps its output open, which is forever
 * for a command stuck on a prompt nobody answers; waiting before reading
 * deadlocks once the output outgrows the pipe buffer. So the input is written
 * and the output read on threads of their own while the caller waits, and a
 * command that runs out of time is killed.
 *
 * <p>The output stays in memory rather than going to a temporary file the way
 * {@code SingBoxInstaller} sends it: what a secret backend prints is the
 * secret.</p>
 */
final class TimedProcess {

    /**
     * How long the input and output may take to finish once the command has
     * exited. Its own ends of the pipes close as it exits, so this normally
     * takes no time at all, but a child it left running can hold them open.
     */
    private static final Duration DRAIN_GRACE = Duration.ofSeconds(2);

    /** How a command that finished in time ended: its exit code and its output. */
    record Exit(int code, String output) {
    }

    private TimedProcess() {
    }

    /**
     * Starts {@code builder}, writes {@code stdin} to the command, closes its
     * input, and collects its output, decoded as UTF-8.
     *
     * @param builder the command. Its standard error must be merged into the
     *     output or redirected: nothing here reads a separate error pipe, and a
     *     full one stalls the command.
     * @param stdin what to write to the command's input, or null for nothing
     * @param timeout how long the command may run
     * @return how the command ended
     * @throws TimeoutException when the command did not finish in time; it has
     *     been killed
     * @throws IOException when the command could not start, writing its input
     *     or reading its output failed, or its output stayed open after it
     *     exited
     * @throws InterruptedException when the waiting thread was interrupted; the
     *     command has been killed
     */
    static Exit run(ProcessBuilder builder, String stdin, Duration timeout)
            throws IOException, InterruptedException, TimeoutException {
        Process process = builder.start();
        try {
            FutureTask<Void> input = new FutureTask<>(() -> {
                try (OutputStream in = process.getOutputStream()) {
                    if (stdin != null) {
                        in.write(stdin.getBytes(StandardCharsets.UTF_8));
                    }
                }
                return null;
            });
            FutureTask<byte[]> output = new FutureTask<>(() -> {
                try (InputStream out = process.getInputStream()) {
                    return out.readAllBytes();
                }
            });
            Thread.startVirtualThread(input);
            Thread.startVirtualThread(output);
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("did not finish within " + timeout);
            }
            long drainDeadline = System.nanoTime() + DRAIN_GRACE.toNanos();
            try {
                input.get(nanosUntil(drainDeadline), TimeUnit.NANOSECONDS);
                byte[] bytes = output.get(nanosUntil(drainDeadline), TimeUnit.NANOSECONDS);
                return new Exit(process.exitValue(), new String(bytes, StandardCharsets.UTF_8));
            } catch (TimeoutException e) {
                // Not a timeout of the command, which has exited: something it
                // started is still holding the output open.
                throw new IOException("output still open " + DRAIN_GRACE
                        + " after the command exited", e);
            }
        } catch (ExecutionException e) {
            throw new IOException(e.getCause());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static long nanosUntil(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }
}
