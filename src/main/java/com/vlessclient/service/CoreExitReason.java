package com.vlessclient.service;

import com.vlessclient.app.I18n;
import com.vlessclient.platform.WindowsTunLauncher;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts a core's unexpected exit into words, in the language of the UI, and
 * picks the lines of its output that say why for the app log.
 *
 * <p>The card and the notification showed the exit code and the last line of
 * the core's log as it came: English inside a Russian window, cut off where
 * the card ran out of room. A cause the core states in words known here is
 * named; anything else is said to be an exit, with its code. The line itself
 * stays in the log, and agents get it through
 * {@link SingBoxEngine#errorDetailProperty()}.</p>
 */
final class CoreExitReason {

    /**
     * How many lines of an exited core's output go into the app log: room for
     * a FATAL line and what led up to it, or for a crash's first line and the
     * frames that failed.
     */
    static final int LAST_LINES = 20;

    /**
     * A listening port the core could not open. Only the core's words up to
     * the system's error are matched: that error differs between systems and,
     * on Windows, with the system's language and with whether the other
     * program holds the port exclusively.
     */
    private static final Pattern PORT = Pattern.compile("listen tcp \\S*:(\\d+): bind: ");

    /** A remote rule set whose first download failed; the core does not start without it. */
    private static final Pattern RULE_SET = Pattern.compile(
            "initialize rule-set\\[\\d+]: initial rule-set: ([^:\\s]+): ");

    /**
     * Administrator rights not granted: AppleScript's error -128 from the
     * macOS prompt, worded in the system's language, so only the number is
     * matched; the Windows launcher's own line for a declined UAC prompt; or
     * pkexec's line for a dismissed PolicyKit dialog, which it exits 126 after.
     */
    private static final Pattern RIGHTS = Pattern.compile("execution error: .*\\(-128\\)\\s*$|^"
            + Pattern.quote(WindowsTunLauncher.ELEVATION_DECLINED)
            + "|: Request dismissed\\s*$");

    private CoreExitReason() {
    }

    /**
     * Whether a core that exited never ran because the user dismissed the
     * administrator prompt its start raised: the user cancelling the connect,
     * which was reported as the tunnel failing.
     *
     * @param lastLine the last line of the launch's output, or null
     * @return true for a dismissed prompt
     */
    static boolean declined(String lastLine) {
        return lastLine != null && RIGHTS.matcher(lastLine).find();
    }

    /**
     * The sentence the card and the notification show for a core that exited.
     *
     * @param exitCode the core's exit code
     * @param lastLine the last line of its log, or null when it wrote none
     * @return the sentence, in the language of the UI
     */
    static String describe(int exitCode, String lastLine) {
        if (lastLine != null) {
            Matcher port = PORT.matcher(lastLine);
            if (port.find()) {
                return I18n.get("engine.exit.port", port.group(1));
            }
            Matcher ruleSet = RULE_SET.matcher(lastLine);
            if (ruleSet.find()) {
                return I18n.get("engine.exit.rule.set", ruleSet.group(1));
            }
            if (declined(lastLine)) {
                return I18n.get("engine.exit.rights");
            }
        }
        return I18n.get("engine.exited.unexpectedly", String.valueOf(exitCode));
    }

    /**
     * The lines of an exited core's output that say why it stopped: the last
     * {@value #LAST_LINES}, or, when a Go crash began further up, as many from
     * its first line on. A crash prints "panic: …" (or "fatal error: …" from
     * the runtime) and then the failing goroutine's stack, the frame that
     * failed first. The stack runs past twenty lines more often than not, and
     * its last lines are the bottom frames, which name nothing.
     *
     * @param output the core's output, oldest line first
     * @return at most {@value #LAST_LINES} lines, oldest first
     */
    static List<String> lastLines(List<String> output) {
        int from = Math.max(0, output.size() - LAST_LINES);
        for (int i = output.size() - 1; i >= 0; i--) {
            String line = output.get(i);
            if (line.startsWith("panic: ") || line.startsWith("fatal error: ")) {
                from = Math.min(from, i);
                break;
            }
        }
        return List.copyOf(output.subList(from, Math.min(output.size(), from + LAST_LINES)));
    }
}
