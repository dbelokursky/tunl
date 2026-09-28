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
     * @param start the command that runs the core, already shell-quoted
     * @return the wrapper's script, for {@code /bin/sh -c}
     */
    static String around(String start) {
        return "trap 'kill ${SBPID:-$!} 2>/dev/null; exit 0' EXIT INT TERM; "
                + start + " & SBPID=$!; "
                + "exec 3<&0; "
                + "( while read -r _ <&3; do :; done; kill $SBPID 2>/dev/null ) & WATCHER=$!; "
                + "wait $SBPID; "
                + "kill $WATCHER 2>/dev/null";
    }
}
