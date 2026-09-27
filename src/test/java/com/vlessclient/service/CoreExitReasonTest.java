package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A core that stopped is put into words, in the language of the UI.
 *
 * <p>The card and the notification showed the exit code and the last line of
 * the core's log as it came, English inside a Russian window:
 * "Процесс неожиданно завершился (код 1): FATAL[0000] start service: start
 * inbound/http[http-in]: listen tcp 127.0.0.1:1081: bind: address already in
 * use". The lines below are what the pinned core 1.14.1, osascript and the
 * Windows TUN launcher print.</p>
 */
class CoreExitReasonTest {

    @AfterEach
    void backToEnglish() {
        I18n.setLocale(Locale.ENGLISH);
    }

    @Test
    void aPortAnotherProgramHoldsIsNamed() {
        assertThat(describe("FATAL[0000] start service: start inbound/http[http-in]: listen tcp "
                + "127.0.0.1:56929: bind: address already in use"))
                .isEqualTo(I18n.get("engine.exit.port", "56929"));
        assertThat(describe("FATAL[0000] start service: start inbound/mixed[mixed-in]: listen tcp "
                + "127.0.0.1:1080: bind: address already in use"))
                .isEqualTo(I18n.get("engine.exit.port", "1080"));
    }

    /**
     * Windows words the socket error in the system's language where it has
     * no English text, and differently when the other program holds the port
     * exclusively, so only the core's own words before it are matched.
     */
    @Test
    void aPortIsNamedWhateverTheSystemCallsItsError() {
        assertThat(describe("FATAL[0000] start service: start inbound/socks[socks-in]: listen tcp "
                + "127.0.0.1:1080: bind: Only one usage of each socket address "
                + "(protocol/network address/port) is normally permitted."))
                .isEqualTo(I18n.get("engine.exit.port", "1080"));
        assertThat(describe("FATAL[0000] start service: start inbound/socks[socks-in]: listen tcp "
                + "127.0.0.1:1080: bind: An attempt was made to access a socket in a way "
                + "forbidden by its access permissions."))
                .isEqualTo(I18n.get("engine.exit.port", "1080"));
    }

    @Test
    void aRoutingListThatDidNotDownloadIsNamed() {
        assertThat(describe("FATAL[0000] start service: initialize rule-set[0]: initial rule-set: "
                + "geosite-google: Get \"https://raw.githubusercontent.com/SagerNet/sing-geosite/"
                + "rule-set/geosite-google.srs\": dial tcp 127.0.0.1:56931: connect: "
                + "connection refused"))
                .isEqualTo(I18n.get("engine.exit.rule.set", "geosite-google"));
        assertThat(describe("FATAL[0000] start service: initialize rule-set[1]: initial rule-set: "
                + "geoip-ru: unexpected status: 404 Not Found"))
                .isEqualTo(I18n.get("engine.exit.rule.set", "geoip-ru"));
    }

    /**
     * osascript words AppleScript's error -128 in the system's language, and
     * spells it "cancelled" on a British English system, so the number is
     * what is matched. The Windows launcher writes a line of its own when the
     * UAC prompt is declined.
     */
    @Test
    void administratorRightsNotGrantedAreSaidToBeSo() {
        assertThat(describe("0:17: execution error: User cancelled. (-128)"))
                .isEqualTo(I18n.get("engine.exit.rights"));
        assertThat(describe("0:245: execution error: User canceled. (-128)"))
                .isEqualTo(I18n.get("engine.exit.rights"));
        assertThat(describe("FATAL: administrator elevation was declined or failed: This command "
                + "cannot be run due to the error: The operation was canceled by the user."))
                .isEqualTo(I18n.get("engine.exit.rights"));
    }

    /**
     * A dismissed prompt is the user cancelling the connect, on each system:
     * AppleScript's -128, the Windows launcher's line, and pkexec's line for a
     * dismissed PolicyKit dialog, which used to read "exited with code 126".
     * A core that failed on its own is not one.
     */
    @Test
    void aDismissedPromptIsToldApartFromAFailure() {
        assertThat(CoreExitReason.declined("0:245: execution error: User canceled. (-128)"))
                .isTrue();
        assertThat(CoreExitReason.declined("FATAL: administrator elevation was declined or "
                + "failed: The operation was canceled by the user.")).isTrue();
        assertThat(CoreExitReason.declined(
                "Error executing command as another user: Request dismissed")).isTrue();
        assertThat(CoreExitReason.describe(126,
                "Error executing command as another user: Request dismissed"))
                .isEqualTo(I18n.get("engine.exit.rights"));

        assertThat(CoreExitReason.declined(
                "Error executing command as another user: Not authorized")).isFalse();
        assertThat(CoreExitReason.declined("FATAL[0000] start service: start inbound/"
                + "http[http-in]: listen tcp 127.0.0.1:1081: bind: address already in use"))
                .isFalse();
        assertThat(CoreExitReason.declined(null)).isFalse();
    }

    /**
     * Run under the administrator prompt, the core's output comes back in
     * osascript's error: its lines joined with carriage returns, which the log
     * reader splits, and the exit status after the last one.
     */
    @Test
    void aCoreFailureInsideAnOsascriptErrorIsStillNamed() {
        assertThat(describe("FATAL[0000] start service: start inbound/http[http-in]: listen tcp "
                + "127.0.0.1:1081: bind: address already in use (1)"))
                .isEqualTo(I18n.get("engine.exit.port", "1081"));
        assertThat(describe("0:137: execution error: FATAL[0000] start service: initialize "
                + "rule-set[0]: initial rule-set: geoip-ru: unexpected status: 404 Not Found (1)"))
                .isEqualTo(I18n.get("engine.exit.rule.set", "geoip-ru"));
    }

    @Test
    void anythingElseSaysTheCoreExitedAndTheCode() {
        assertThat(CoreExitReason.describe(2, "panic: runtime error: index out of range"))
                .isEqualTo(I18n.get("engine.exited.unexpectedly", "2"));
        assertThat(CoreExitReason.describe(1, null))
                .isEqualTo(I18n.get("engine.exited.unexpectedly", "1"));
    }

    @Test
    void itReadsInRussianInARussianWindow() {
        I18n.setLocale(Locale.of("ru"));

        assertThat(describe("FATAL[0000] start service: start inbound/http[http-in]: listen tcp "
                + "127.0.0.1:1081: bind: address already in use"))
                .contains("1081")
                .doesNotContain("bind", "FATAL", "listen tcp");
        assertThat(CoreExitReason.describe(1, "sing-box crashing"))
                .doesNotContain("sing-box crashing", "Process", "Процесс");
    }

    /** A FATAL line, and the lines leading up to it, end the output. */
    @Test
    void theAppLogGetsTheEndOfTheOutput() {
        List<String> output = IntStream.rangeClosed(1, 30).mapToObj(i -> "line " + i).toList();

        List<String> kept = CoreExitReason.lastLines(output);

        assertThat(kept).hasSize(CoreExitReason.LAST_LINES);
        assertThat(kept.getFirst()).isEqualTo("line 11");
        assertThat(kept.getLast()).isEqualTo("line 30");
        assertThat(CoreExitReason.lastLines(List.of("only line"))).containsExactly("only line");
        assertThat(CoreExitReason.lastLines(List.of())).isEmpty();
    }

    /**
     * A Go crash prints its reason first and the failing goroutine's stack
     * after it, top frame first. Kept from the end, a long stack left only its
     * bottom frames, and the "panic:" line that says what failed was gone.
     */
    @Test
    void aCrashWithALongStackIsKeptFromItsFirstLine() {
        List<String> output = crash("panic: runtime error: invalid memory address or nil "
                + "pointer dereference", 30);

        List<String> kept = CoreExitReason.lastLines(output);

        assertThat(kept).hasSize(CoreExitReason.LAST_LINES);
        assertThat(kept.getFirst()).startsWith("panic: runtime error");
        assertThat(kept).contains("goroutine 4211 [running]:", "github.com/sagernet/sing-box/"
                + "route.step0(...)");
    }

    /** The runtime's own fatal errors print a stack the same way. */
    @Test
    void aFatalErrorOfTheRuntimeIsACrashToo() {
        List<String> kept = CoreExitReason.lastLines(crash("fatal error: concurrent map writes", 30));

        assertThat(kept.getFirst()).isEqualTo("fatal error: concurrent map writes");
    }

    /** A crash that fits keeps the lines before it, which show what the core was doing. */
    @Test
    void aShortCrashKeepsWhatLedUpToIt() {
        List<String> output = crash("panic: send on closed channel", 3);

        List<String> kept = CoreExitReason.lastLines(output);

        assertThat(kept).hasSize(CoreExitReason.LAST_LINES);
        assertThat(kept.getLast()).isEqualTo(output.getLast());
        assertThat(kept.getFirst()).startsWith("INFO[");
        assertThat(kept).contains("panic: send on closed channel");
    }

    /**
     * What sing-box prints when it crashes: ordinary lines, then the Go
     * runtime's report and {@code frames} frames of the failing goroutine.
     */
    private static List<String> crash(String firstLine, int frames) {
        List<String> output = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            output.add("INFO[0040] [" + (3000 + i) + " 0ms] inbound/mixed[mixed-in]: "
                    + "inbound connection from 127.0.0.1:" + (52000 + i));
        }
        output.add(firstLine);
        output.add("[signal SIGSEGV: segmentation violation code=0x1 addr=0x28 pc=0x1045c3a2c]");
        output.add("");
        output.add("goroutine 4211 [running]:");
        for (int frame = 0; frame < frames; frame++) {
            output.add("github.com/sagernet/sing-box/route.step" + frame + "(...)");
            output.add("\tgithub.com/sagernet/sing-box/route/route.go:" + (100 + frame) + " +0x2c");
        }
        return output;
    }

    private static String describe(String lastLine) {
        return CoreExitReason.describe(1, lastLine);
    }
}
