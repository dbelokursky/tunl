package com.vlessclient.platform;

/**
 * The watch a TUN wrapper that runs as the user keeps over its core: it stops
 * the core when the app closes the wrapper's stdin or dies, and ends when the
 * core exits on its own, without polling.
 *
 * <p>The app holds the write end of the wrapper's stdin and never writes to
 * it. Closing it is the stop, and the system closes it when the app dies, even
 * from a SIGKILL. Either way the watcher reads end-of-file and stops the core,
 * and the shell, waiting on the core, ends with it. The wrappers used to check
 * the core, a stop file and the app's pid in a loop that ran {@code sleep 0.3},
 * an external command: three new processes a second for as long as the tunnel
 * was up, with the window in the tray as well.</p>
 *
 * <p>A wrapper that runs as root with no stdin from the app (the osascript and
 * pkexec fallbacks) cannot use this and keeps the loop.</p>
 */
final class StdinWatch {

    private StdinWatch() {
    }

    /**
     * Wraps {@code start}, a shell command that runs the core in the
     * foreground, in the watch.
     *
     * <p>The trap is set before the core starts, so no signal can land between
     * the two and leave the core orphaned; it covers TERM and INT as well as
     * EXIT, because a signal-killed shell skips an EXIT-only trap. The watcher
     * reads a copy of stdin on fd 3, taken after the core has started so the
     * core does not hold it: a background job of a non-interactive shell gets
     * /dev/null as its own stdin.</p>
     *
     * <p>The wrapper exits with the core's own status, and a signal to it
     * waits for the core to close. The trap used to end in {@code exit 0}:
     * every crash, panic and OOM kill reached the app, the log and agents as
     * "exited with code 0"; and on SIGTERM the wrapper left at once, while
     * the core it had just told to stop could take seconds more, so the app
     * took the tunnel for gone and could start another beside it.</p>
     *
     * @param start the command that runs the core, already shell-quoted
     * @return the wrapper's script, for {@code /bin/sh -c}
     */
    static String around(String start) {
        return SIGNAL_TRAP + "kill ${WATCHER:-} 2>/dev/null; exit 143' INT TERM; "
                + EXIT_TRAP
                + start + " & SBPID=$!; "
                + "exec 3<&0; "
                + "( while read -r _ <&3; do :; done; kill $SBPID 2>/dev/null ) & WATCHER=$!; "
                + "wait $SBPID; RC=$?; "
                + "kill $WATCHER 2>/dev/null; "
                + "exit $RC";
    }

    /**
     * The start of a wrapper's INT/TERM trap: stop the core and wait for it
     * to close. The caller adds its own clean-up and the {@code exit}, which
     * keeps a signal before the core starts from going on to start it.
     */
    static final String SIGNAL_TRAP =
            "trap 'kill ${SBPID:-$!} 2>/dev/null; wait ${SBPID:-$!} 2>/dev/null; ";

    /**
     * A wrapper's EXIT trap: the core goes with the shell, whatever ends it.
     * It sets no status, so the shell's own {@code exit} stands.
     */
    static final String EXIT_TRAP = "trap 'kill ${SBPID:-$!} 2>/dev/null' EXIT; ";
}
