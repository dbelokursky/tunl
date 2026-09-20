package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import java.util.Locale;
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

    private static String describe(String lastLine) {
        return CoreExitReason.describe(1, lastLine);
    }
}
