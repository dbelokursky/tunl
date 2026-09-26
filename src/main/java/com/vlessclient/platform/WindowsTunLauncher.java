package com.vlessclient.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows TUN launcher: sing-box must run elevated (administrator) to create
 * its TUN adapter, but the app itself is not — and a non-elevated process can
 * neither read an elevated child's stdout nor kill it.
 *
 * <p>The launch is therefore split into two generated PowerShell commands:</p>
 *
 * <ol>
 *   <li><b>Outer</b> (non-elevated; the {@link Process} Java observes):
 *       requests elevation of the wrapper via {@code Start-Process -Verb
 *       RunAs} — one UAC prompt per connect — then tails the core's log
 *       files to its own stdout until the wrapper exits, so the app streams
 *       live logs exactly like in system-proxy mode. If the user declines
 *       the UAC prompt, it prints a FATAL line and exits, which the engine
 *       reports as a connection error.</li>
 *   <li><b>Wrapper</b> (elevated): starts sing-box with output redirected to
 *       the log files and loops until sing-box dies, the stop-signal file
 *       appears (elevated-to-elevated kill is permitted), or the app process
 *       disappears — so a crashed app never leaks a privileged core.</li>
 * </ol>
 *
 * <p><b>Nothing on disk is ever elevated.</b> Both commands travel as
 * {@code -EncodedCommand} blobs — base64 of UTF-16LE, the encoding PowerShell
 * requires — rather than as {@code .ps1} files passed to {@code -File}. Writing
 * them to {@code java.io.tmpdir} and elevating them by path was a local
 * privilege escalation: that directory is writable by the very user we are
 * elevating away from, {@code RunAs} reads the file at elevation time, and the
 * UAC prompt holds that window open for as long as the user takes to click
 * Yes. Any code already running as the user could swap the wrapper — or the
 * outer script that builds the elevation request — and ride a prompt the user
 * fully expects into administrator. The script bodies are compile-time
 * constants in the jar and the per-connect values are bound by
 * {@link #wrapperCommand} and {@link #outerCommand}, so there is no file to
 * swap and no window to win.</p>
 */
public final class WindowsTunLauncher implements TunLauncher {

    private static final Logger log = LoggerFactory.getLogger(WindowsTunLauncher.class);

    /**
     * How the launcher's line for a UAC prompt that was declined or failed
     * begins. The app tells the user that administrator rights were not
     * granted by this line, so the script writes it as it stands here.
     */
    public static final String ELEVATION_DECLINED =
            "FATAL: administrator elevation was declined or failed: ";

    /**
     * {@inheritDoc}
     *
     * <p>UAC shows a text of its own, so {@code prompt} goes unused.</p>
     */
    @Override
    public Launched launch(Path binary, Path configFile, Prompt prompt) throws IOException {
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        String token = Long.toString(System.nanoTime());
        Path stopSignalFile = StopSignals.newStopSignalFile();

        Path logOut = tempDir.resolve("vless-tun-" + token + ".out.log");
        Path logErr = tempDir.resolve("vless-tun-" + token + ".err.log");

        // The elevated command is encoded here, in the trusted process, and
        // travels to RunAs as an argument: no intermediate file exists for
        // anyone to rewrite while the UAC prompt is up.
        String encodedWrapper = encode(wrapperCommand(binary, configFile,
                logOut, logErr, stopSignalFile, ProcessHandle.current().pid()));

        ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile",
                "-EncodedCommand", encode(outerCommand(encodedWrapper, logOut, logErr)));
        pb.directory(SecureFiles.parentDirectory(binary).toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        log.info("Started sing-box TUN launch via UAC elevation (prompt expected)");
        // UAC asks on every launch: there is no one-time grant to fall back on.
        return new Launched(process, stopSignalFile, true);
    }

    /**
     * The non-elevated outer script: elevates the wrapper, then tails the
     * core's log files to stdout until the wrapper exits.
     *
     * <p>Bare body. {@link #outerCommand} prepends the {@code $WrapperCommand},
     * {@code $LogOut} and {@code $LogErr} bindings it reads — a {@code param()}
     * block cannot be used, because parameters must be the first statement and
     * {@code -EncodedCommand} has no way to pass arguments alongside the
     * command. The bindings are pinned by {@code WindowsTunLauncherTest}.</p>
     */
    static String outerScript() {
        return """
                $ErrorActionPreference = 'Stop'
                New-Item -ItemType File -Force -Path $LogOut, $LogErr | Out-Null

                function Emit-New([string]$path, [ref]$pos) {
                    # Read anything appended past $pos, sharing the file with the
                    # elevated writer, and forward it line by line to stdout.
                    try {
                        $fs = [System.IO.File]::Open($path, 'Open', 'Read', 'ReadWrite')
                    } catch {
                        return
                    }
                    try {
                        if ($fs.Length -le $pos.Value) { return }
                        $fs.Position = $pos.Value
                        $sr = New-Object System.IO.StreamReader($fs)
                        while ($null -ne ($line = $sr.ReadLine())) { Write-Output $line }
                        $pos.Value = $fs.Length
                    } finally {
                        $fs.Dispose()
                    }
                }

                try {
                    # The elevated side carries its whole script inline. A path
                    # here instead would name a file this unprivileged user can
                    # rewrite while the UAC prompt waits for a click.
                    $wrapperArgs = @('-NoProfile', '-WindowStyle', 'Hidden',
                        '-EncodedCommand', $WrapperCommand)
                    $w = Start-Process -FilePath 'powershell' -Verb RunAs `
                        -WindowStyle Hidden -ArgumentList $wrapperArgs -PassThru
                } catch {
                    Write-Output ('FATAL: administrator elevation was declined or failed: ' `
                        + $_.Exception.Message)
                    exit 3
                }

                $posOut = 0
                $posErr = 0
                while (-not $w.HasExited) {
                    Emit-New $LogErr ([ref]$posErr)
                    Emit-New $LogOut ([ref]$posOut)
                    Start-Sleep -Milliseconds 250
                }
                Emit-New $LogErr ([ref]$posErr)
                Emit-New $LogOut ([ref]$posOut)
                # Everything in them has been forwarded to the app's own log
                # buffer. Left behind, a debug-level core log of every DNS
                # query and dial target piled up in %TEMP% per connection.
                Remove-Item -LiteralPath $LogOut, $LogErr -Force -ErrorAction SilentlyContinue
                try { exit $w.ExitCode } catch { exit 0 }
                """;
    }

    /**
     * The elevated wrapper script: runs sing-box redirected to the log files
     * and stops it when the stop file appears or the app process dies.
     *
     * <p>Bare body, for the reason given on {@link #outerScript}.
     * {@link #wrapperCommand} prepends the six bindings it reads.</p>
     */
    static String wrapperScript() {
        return """
                $ErrorActionPreference = 'Stop'
                $proc = Start-Process -FilePath $Binary `
                    -ArgumentList @('run', '-c', $Config) `
                    -RedirectStandardOutput $LogOut -RedirectStandardError $LogErr `
                    -NoNewWindow -PassThru
                try {
                    while (-not $proc.HasExited) {
                        if (Test-Path -LiteralPath $StopFile) { break }
                        if (-not (Get-Process -Id ([int]$OwnerPid) `
                            -ErrorAction SilentlyContinue)) { break }
                        Start-Sleep -Milliseconds 300
                    }
                } finally {
                    if (-not $proc.HasExited) {
                        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
                    }
                    Remove-Item -LiteralPath $StopFile -Force -ErrorAction SilentlyContinue
                }
                """;
    }

    /**
     * The complete elevated command: this connection's six values bound as
     * PowerShell literals, followed by the constant {@link #wrapperScript()}
     * body that reads them.
     *
     * <p>The pid is emitted unquoted — it is a {@code long}, not text.</p>
     */
    static String wrapperCommand(Path binary, Path configFile, Path logOut, Path logErr,
                                 Path stopSignalFile, long ownerPid) {
        return bind("Binary", binary.toAbsolutePath().toString())
                + bind("Config", configFile.toAbsolutePath().toString())
                + bind("LogOut", logOut.toAbsolutePath().toString())
                + bind("LogErr", logErr.toAbsolutePath().toString())
                + bind("StopFile", stopSignalFile.toAbsolutePath().toString())
                + "$OwnerPid = " + ownerPid + "\n"
                + wrapperScript();
    }

    /**
     * The complete non-elevated command: the already-encoded elevated command
     * it hands to {@code RunAs}, plus the two log paths it tails, followed by
     * the constant {@link #outerScript()} body.
     */
    static String outerCommand(String encodedWrapper, Path logOut, Path logErr) {
        return bind("WrapperCommand", encodedWrapper)
                + bind("LogOut", logOut.toAbsolutePath().toString())
                + bind("LogErr", logErr.toAbsolutePath().toString())
                + outerScript();
    }

    /** One {@code $Name = 'value'} binding line. */
    private static String bind(String name, String value) {
        return "$" + name + " = " + psLiteral(value) + "\n";
    }

    /**
     * Encodes a command for {@code powershell -EncodedCommand}, which decodes
     * base64 as UTF-16LE and nothing else. Incidentally fixes non-ASCII paths:
     * process arguments went through the machine's ANSI codepage, so a core
     * under {@code C:\\Users\\Дмитрий\\...} could arrive mangled; UTF-16LE
     * carries every path exactly.
     */
    static String encode(String powerShell) {
        return Base64.getEncoder()
                .encodeToString(powerShell.getBytes(StandardCharsets.UTF_16LE));
    }

    /**
     * Renders {@code value} as a PowerShell single-quoted string literal.
     *
     * <p><b>This is the seam the whole fix rests on.</b> Removing the temp
     * scripts means the per-connect paths are no longer process arguments —
     * they are now text pasted into a script that runs as administrator. The
     * paths are not fully ours: {@code java.io.tmpdir} contains the Windows
     * username, and a Windows username may legally contain a single quote.
     * Whatever this returns must be impossible to escape from, or we will have
     * traded a race condition for a straight injection.</p>
     *
     * <p>Single quotes, not double: inside a double-quoted string PowerShell
     * expands {@code $(...)}, {@code $env:X} and backtick escapes, so a path is
     * code. A single-quoted string has exactly one metacharacter — the quote,
     * escaped by doubling — and treats backslashes, dollars and even newlines
     * as literal text. To PowerShell's tokenizer that quote is five
     * characters, though: the ASCII apostrophe and the typographic quotes
     * U+2018 to U+201B each end the string. Each is doubled with itself, as
     * PowerShell's own CodeGeneration.EscapeSingleQuotedStringContent does,
     * and reads back as that one character. Doubling is therefore total rather
     * than a blocklist, which is why nothing here rejects input: no value can
     * escape, so refusing one could only break a connect that would have
     * worked.</p>
     *
     * @param value the raw text to embed
     * @return a literal that PowerShell evaluates back to exactly {@code value}
     */
    static String psLiteral(String value) {
        StringBuilder literal = new StringBuilder(value.length() + 2).append('\'');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            literal.append(c);
            if (isSingleQuote(c)) {
                literal.append(c);
            }
        }
        return literal.append('\'').toString();
    }

    /** Whether PowerShell reads {@code c} as a single quote: ' and U+2018 to U+201B. */
    private static boolean isSingleQuote(char c) {
        return c == '\'' || (c >= 0x2018 && c <= 0x201B);
    }
}
