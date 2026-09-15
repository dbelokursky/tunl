package com.vlessclient.platform;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Runs an OS command and captures its exit code and combined output. The seam
 * through which platform classes ({@link WindowsAutostart}, the
 * {@link SystemProxyGuard} implementations) shell out, so tests can exercise
 * them without touching the real registry or network settings.
 */
interface CommandRunner {

    /** Exit code and combined stdout/stderr of one invocation. */
    record Result(int exitCode, String output) {
    }

    Result run(List<String> command) throws IOException;

    /** The real implementation: ProcessBuilder with a 30-second timeout. */
    static CommandRunner system() {
        return system(Duration.ofSeconds(30));
    }

    /**
     * The real implementation with the given timeout. A command still running
     * when it expires is killed and reported as an {@link IOException}.
     */
    static CommandRunner system(Duration timeout) {
        return command -> {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            try {
                TimedProcess.Exit exit = TimedProcess.run(pb, null, timeout);
                return new Result(exit.code(), exit.output());
            } catch (TimeoutException e) {
                throw new IOException("Command timed out after " + timeout + ": " + command, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Command interrupted: " + command, e);
            }
        };
    }
}
