package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Starts sing-box with the elevated privileges TUN mode needs — root on
 * macOS, administrator on Windows — while keeping it controllable from the
 * unprivileged app.
 *
 * <p>Both platforms share the same control contract: the launcher returns a
 * process whose lifetime mirrors the core's and whose stdout carries the
 * core's log lines, plus a <em>stop-signal file</em>. Creating that file asks
 * the privileged side to terminate sing-box — necessary because an
 * unprivileged parent cannot kill an elevated child directly on either OS.</p>
 */
public interface TunLauncher {

    /**
     * Handle to a privileged core launch.
     *
     * @param process        unprivileged observer process: its stdout streams
     *                       the core's log output and it exits when the core
     *                       dies
     * @param stopSignalFile creating this file makes the privileged side stop
     *                       sing-box; the privileged side deletes it afterwards
     */
    record Launched(Process process, Path stopSignalFile) {
    }

    /**
     * Launches sing-box elevated with the given config.
     *
     * @param binary     the sing-box executable
     * @param configFile the generated config to run
     * @return the launch handle
     * @throws IOException if the launch could not even be attempted; a user
     *                     declining the elevation prompt is reported through
     *                     the process exiting instead
     */
    Launched launch(Path binary, Path configFile) throws IOException;

    /**
     * Removes whatever {@link #launch} had to publish outside the caller's
     * temp file for the privileged side to read — on macOS a copy of the
     * config at the fixed path the sudoers rule pins, which carries the
     * server's credentials.
     *
     * <p>Call once the launched process has exited, or after a launch that
     * threw. Unlike the caller's temp config, the published path is
     * <em>fixed</em> rather than unique per session, so the caller must be
     * sure no newer session has taken over before calling this.</p>
     *
     * <p>Best effort: failures are logged by the implementation, never thrown.
     * The default is a no-op, for launchers that read the config in place.</p>
     */
    default void cleanupSession() {
    }

    /**
     * Returns the launcher for the host platform.
     *
     * @return the launcher for the host platform
     */
    static TunLauncher current() {
        return switch (Platform.current()) {
            case WINDOWS -> new WindowsTunLauncher();
            case LINUX -> new LinuxTunLauncher();
            // OTHER keeps the mac fallback: unix-like defaults beat crashing.
            case MAC, OTHER -> new MacTunLauncher();
        };
    }
}
