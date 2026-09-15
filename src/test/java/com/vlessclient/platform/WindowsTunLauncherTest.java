package com.vlessclient.platform;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generated PowerShell is what actually runs on Windows, so its
 * invariants are pinned here: the elevation verb, the stop-file and
 * owner-pid watches, the log tailing, and — since the elevated side is
 * assembled here rather than read back off disk — that every value the
 * scripts read is bound, encoded the one way PowerShell accepts, and
 * impossible to break out of.
 */
class WindowsTunLauncherTest {

    @Test
    void outerScript_elevatesWrapperAndReportsDeclinedUac() {
        String outer = WindowsTunLauncher.outerScript();

        assertThat(outer).contains("-Verb RunAs");
        assertThat(outer).contains("-WindowStyle', 'Hidden'");
        assertThat(outer).contains("FATAL: administrator elevation was declined or failed");
        assertThat(outer).contains("exit 3");
    }

    @Test
    void outerScript_tailsBothLogFilesUntilWrapperExits() {
        String outer = WindowsTunLauncher.outerScript();

        assertThat(outer).contains("function Emit-New");
        assertThat(outer).contains("while (-not $w.HasExited)");
        assertThat(outer).contains("Emit-New $LogErr");
        assertThat(outer).contains("Emit-New $LogOut");
        // Shares the file with the elevated writer instead of locking it.
        assertThat(outer).contains("'Open', 'Read', 'ReadWrite'");
        // The per-connection core logs are forwarded, then removed: at debug
        // level they hold every DNS query and dial target.
        assertThat(outer).contains("Remove-Item -LiteralPath $LogOut, $LogErr");
    }

    @Test
    void wrapperScript_runsCoreAndWatchesStopFileAndOwner() {
        String wrapper = WindowsTunLauncher.wrapperScript();

        assertThat(wrapper).contains("@('run', '-c', $Config)");
        assertThat(wrapper).contains("-RedirectStandardOutput $LogOut");
        assertThat(wrapper).contains("-RedirectStandardError $LogErr");
        assertThat(wrapper).contains("Test-Path -LiteralPath $StopFile");
        // A dead app must never leak an elevated core.
        assertThat(wrapper).contains("Get-Process -Id ([int]$OwnerPid)");
        assertThat(wrapper).contains("Stop-Process -Id $proc.Id -Force");
        assertThat(wrapper).contains("Remove-Item -LiteralPath $StopFile");
    }

    @Test
    void scriptsAreAsciiOnly() {
        // The bodies are constants, so keeping them ASCII costs nothing and
        // keeps them readable in any editor. Non-ASCII in the *values* is
        // fine and covered by encode_carriesNonAsciiPathsIntact.
        for (String script : new String[]{
                WindowsTunLauncher.outerScript(), WindowsTunLauncher.wrapperScript()}) {
            assertThat(script.chars().allMatch(c -> c < 128))
                    .as("script must be pure ASCII")
                    .isTrue();
        }
    }

    // --- nothing on disk gets elevated -----------------------------------

    @Test
    void neitherScriptElevatesAFileFromDisk() {
        // The escalation this replaced: both scripts were written to
        // java.io.tmpdir and the wrapper was elevated by path, so anyone
        // running as this user could swap either one while the UAC prompt
        // was up. -File must not come back.
        assertThat(WindowsTunLauncher.outerScript()).doesNotContain("-File'");
        assertThat(WindowsTunLauncher.outerScript()).contains("'-EncodedCommand', $WrapperCommand");
        assertThat(WindowsTunLauncher.wrapperScript()).doesNotContain("-File'");
    }

    @Test
    void encode_isBase64OfUtf16LittleEndian() {
        // powershell -EncodedCommand decodes UTF-16LE and nothing else; UTF-8
        // here yields a ParserError on the user's machine, not a fallback.
        String command = "$Binary = 'C:\\sing-box.exe'";

        byte[] decoded = Base64.getDecoder().decode(WindowsTunLauncher.encode(command));

        assertThat(new String(decoded, StandardCharsets.UTF_16LE)).isEqualTo(command);
    }

    @Test
    void encode_carriesNonAsciiPathsIntact() {
        // Process arguments went through the machine's ANSI codepage, so this
        // path could arrive mangled; UTF-16LE has no such lossy step.
        String command = "$Binary = 'C:\\Users\\Дмитрий\\sing-box.exe'";

        byte[] decoded = Base64.getDecoder().decode(WindowsTunLauncher.encode(command));

        assertThat(new String(decoded, StandardCharsets.UTF_16LE)).isEqualTo(command);
    }

    // --- every value the bodies read is bound ----------------------------

    @Test
    void wrapperCommand_bindsEveryValueTheElevatedBodyReads(@TempDir Path dir) {
        Path binary = dir.resolve("sing-box.exe");
        Path config = dir.resolve("config.json");
        Path logOut = dir.resolve("out.log");
        Path logErr = dir.resolve("err.log");
        Path stop = dir.resolve("stop.signal");

        String command = WindowsTunLauncher.wrapperCommand(
                binary, config, logOut, logErr, stop, 4242L);

        assertThat(command).contains("$Binary = '" + binary.toAbsolutePath() + "'");
        assertThat(command).contains("$Config = '" + config.toAbsolutePath() + "'");
        assertThat(command).contains("$LogOut = '" + logOut.toAbsolutePath() + "'");
        assertThat(command).contains("$LogErr = '" + logErr.toAbsolutePath() + "'");
        assertThat(command).contains("$StopFile = '" + stop.toAbsolutePath() + "'");
        // A pid is a number, not text: quoting it would only invite [int] to
        // parse whatever a quoted value happened to contain.
        assertThat(command).contains("$OwnerPid = 4242\n");
        assertThat(command).endsWith(WindowsTunLauncher.wrapperScript());
    }

    @Test
    void outerCommand_bindsTheEncodedWrapperAndTheLogsItTails(@TempDir Path dir) {
        Path logOut = dir.resolve("out.log");
        Path logErr = dir.resolve("err.log");

        String command = WindowsTunLauncher.outerCommand("QQBiAGMA", logOut, logErr);

        assertThat(command).contains("$WrapperCommand = 'QQBiAGMA'");
        assertThat(command).contains("$LogOut = '" + logOut.toAbsolutePath() + "'");
        assertThat(command).contains("$LogErr = '" + logErr.toAbsolutePath() + "'");
        assertThat(command).endsWith(WindowsTunLauncher.outerScript());
    }

    @Test
    void bothCommandsStayWellUnderTheirArgumentLimits(@TempDir Path dir) {
        // Two different ceilings, so guard both. Growing a script body until a
        // connect fails is the kind of regression that only ever shows up on
        // Windows, which is exactly where it is most expensive to find.
        String encodedWrapper = WindowsTunLauncher.encode(WindowsTunLauncher.wrapperCommand(
                dir.resolve("sing-box.exe"), dir.resolve("config.json"),
                dir.resolve("out.log"), dir.resolve("err.log"),
                dir.resolve("stop.signal"), 4242L));
        String encodedOuter = WindowsTunLauncher.encode(WindowsTunLauncher.outerCommand(
                encodedWrapper, dir.resolve("out.log"), dir.resolve("err.log")));

        // Reaches ShellExecuteEx via Start-Process -ArgumentList, whose
        // conservative documented floor for elevation is 8191. ~2.9k today.
        assertThat(encodedWrapper.length()).isLessThan(6000);
        // Reaches CreateProcess straight from ProcessBuilder, cap 32767. ~12k
        // today: the outer carries the wrapper's base64 inside its own.
        assertThat(encodedOuter.length()).isLessThan(24000);
    }

    // --- the quoting seam -------------------------------------------------

    @Test
    void psLiteral_quotesSoNothingIsInterpolated() {
        // Double quotes would make PowerShell run this. Single quotes are
        // literal all the way through, including $(...), $env: and backticks.
        assertThat(WindowsTunLauncher.psLiteral("$(whoami)")).isEqualTo("'$(whoami)'");
        assertThat(WindowsTunLauncher.psLiteral("C:\\sing-box.exe"))
                .isEqualTo("'C:\\sing-box.exe'");
    }

    /** PowerShell reads each of these as the same single quote as {@code '}. */
    private static final char LEFT_QUOTE = (char) 0x2018;
    private static final char RIGHT_QUOTE = (char) 0x2019;
    private static final char LOW_QUOTE = (char) 0x201A;
    private static final char REVERSED_QUOTE = (char) 0x201B;

    /**
     * Values a literal must survive. java.io.tmpdir carries the Windows
     * username, and a username may legally contain a quote, a typographic
     * apostrophe included, so these are reachable, not theoretical.
     */
    private static final String[] HOSTILE = {
            "C:\\Users\\o'brien\\AppData\\Local\\Temp\\sing-box.exe",
            "'; Write-Output escaped; '",
            "C:\\x'; iex $env:EVIL; '",
            "''",
            "'",
            "C:\\Users\\Дмитрий\\sing-box.exe",
            "C:\\Users\\O" + RIGHT_QUOTE + "Brien\\AppData\\Local\\Temp\\sing-box.exe",
            LEFT_QUOTE + "; Write-Output escaped; " + LEFT_QUOTE,
            "C:\\x" + LOW_QUOTE + "; Write-Output escaped; " + REVERSED_QUOTE,
            "'" + RIGHT_QUOTE,
            String.valueOf(RIGHT_QUOTE)
    };

    @Test
    void psLiteral_cannotBeEscapedFrom() {
        for (String hostile : HOSTILE) {
            assertThat(evaluate(WindowsTunLauncher.psLiteral(hostile)))
                    .as("literal for %s must evaluate back to itself", hostile)
                    .isEqualTo(hostile);
        }
    }

    /**
     * The same values through the real PowerShell, handed over the way the
     * launcher hands over its scripts. {@link #evaluate} models the tokenizer;
     * this is the tokenizer.
     */
    @EnabledOnOs(OS.WINDOWS)
    @Test
    @Timeout(120)
    void psLiteral_evaluatesBackToItselfInPowerShell() throws Exception {
        StringBuilder script = new StringBuilder("$ProgressPreference = 'SilentlyContinue'\n");
        for (String hostile : HOSTILE) {
            // Code units, not text: no console code page can garble a number.
            script.append("[string]::Join(',', [int[]][char[]]")
                    .append(WindowsTunLauncher.psLiteral(hostile))
                    .append(")\n");
        }
        Process powershell = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                "-EncodedCommand", WindowsTunLauncher.encode(script.toString()))
                .redirectErrorStream(true)
                .start();
        powershell.getOutputStream().close();
        String output = new String(powershell.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(powershell.waitFor(60, TimeUnit.SECONDS)).isTrue();

        assertThat(output.strip().lines().toList())
                .as("PowerShell printed:%n%s", output)
                .containsExactlyElementsOf(Arrays.stream(HOSTILE)
                        .map(WindowsTunLauncherTest::codeUnits).toList());
    }

    private static String codeUnits(String value) {
        return value.chars().mapToObj(Integer::toString).collect(Collectors.joining(","));
    }

    /**
     * Reads a PowerShell single-quoted literal the way PowerShell's tokenizer
     * does, and fails if it is not one. The tokenizer counts the typographic
     * quotes U+2018 to U+201B as single quotes too, and reads two quote
     * characters in a row as one. A quote with no quote after it inside the
     * body means the string terminated early and everything after it was
     * parsed as code — running as administrator.
     */
    private static String evaluate(String literal) {
        assertThat(literal).startsWith("'").endsWith("'");
        String body = literal.substring(1, literal.length() - 1);
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (isQuote(c)) {
                assertThat(i + 1 < body.length() && isQuote(body.charAt(i + 1)))
                        .as("literal terminates early at index %d: %s", i + 1, literal)
                        .isTrue();
                c = body.charAt(++i);
            }
            value.append(c);
        }
        return value.toString();
    }

    private static boolean isQuote(char c) {
        return c == '\'' || (c >= 0x2018 && c <= 0x201B);
    }
}
