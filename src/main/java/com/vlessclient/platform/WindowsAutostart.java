package com.vlessclient.platform;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows autostart via the per-user registry Run key
 * {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Run}. Enabling writes a
 * {@code VlessClient} value holding the launch command; disabling deletes it.
 * Task Manager's Startup tab switches the entry off without touching it, in
 * a value of the same name under {@code Explorer\StartupApproved\Run}, which
 * {@link #isEnabled()} reads and enabling clears.
 * launchd's macOS equivalent is {@link MacAutostart}.
 *
 * <p>The registry is driven through {@code reg.exe} so no native bindings are
 * needed. The stored command is the reconstructed JVM invocation from
 * {@link JvmLaunchCommand}; once Windows packaging ships a launcher executable
 * (a later phase), that value becomes the launcher path instead.</p>
 */
public final class WindowsAutostart implements Autostart {

    private static final Logger log = LoggerFactory.getLogger(WindowsAutostart.class);

    private static final String RUN_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE_NAME = "VlessClient";
    /**
     * Where Task Manager and Settings, Apps, Startup keep their switch for a
     * Run entry: a REG_BINARY of the same name whose first byte is 03 when
     * switched off and 02 when switched back on. No value means never
     * switched, which Windows treats as on.
     */
    private static final String APPROVED_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run";

    private final CommandRunner runner;
    private final Supplier<String> launchCommand;

    public WindowsAutostart() {
        this(CommandRunner.system());
    }

    WindowsAutostart(CommandRunner runner) {
        this(runner, WindowsAutostart::launchCommandLine);
    }

    /** Test seam: the command line this launcher would register. */
    WindowsAutostart(CommandRunner runner, Supplier<String> launchCommand) {
        this.runner = runner;
        this.launchCommand = launchCommand;
    }

    @Override
    public boolean isEnabled() {
        try {
            // The Run value alone was read, so after Task Manager switched the
            // entry off Settings kept the box ticked for an app Windows no
            // longer starts.
            return queryRun().exitCode() == 0 && !switchedOffInTaskManager();
        } catch (IOException e) {
            log.warn("Could not query autostart registry value", e);
            return false;
        }
    }

    // Synchronized with refresh(), which runs off the launch thread at start:
    // a refresh that had read "on" must not write the value back after the
    // user's untick has removed it.
    @Override
    public synchronized void setEnabled(boolean enabled) throws IOException {
        if (enabled) {
            install(launchCommand.get());
            // Re-adding the Run value alone left Task Manager's switch saying
            // off, which wins, so ticking the box again did nothing.
            clearTaskManagerSwitch();
        } else {
            uninstall();
        }
    }

    @Override
    public synchronized void refresh() {
        try {
            CommandRunner.Result run = queryRun();
            if (run.exitCode() != 0 || switchedOffInTaskManager()) {
                return;
            }
            // Every start rewrote the value with `reg add` even when it held
            // this launcher already: one more process at login, for nothing.
            // A path reg prints in another code page reads as different and
            // is rewritten, as it always was.
            String command = launchCommand.get();
            String stored = storedValue(run.output(), "REG_SZ");
            if (stored != null && unquoted(command).equals(unquoted(stored))) {
                return;
            }
            install(command);
        } catch (IOException e) {
            log.warn("Could not refresh autostart registry value", e);
        }
    }

    private CommandRunner.Result queryRun() throws IOException {
        return runner.run(List.of("reg", "query", RUN_KEY, "/v", VALUE_NAME));
    }

    /**
     * Whether Task Manager's switch for the entry says off. Its low bit is
     * the switch; an answer that cannot be read leaves autostart on, as it
     * was before this was read at all.
     */
    private boolean switchedOffInTaskManager() throws IOException {
        CommandRunner.Result approved = runner.run(
                List.of("reg", "query", APPROVED_KEY, "/v", VALUE_NAME));
        if (approved.exitCode() != 0) {
            return false;
        }
        String data = storedValue(approved.output(), "REG_BINARY");
        if (data == null || data.length() < 2) {
            return false;
        }
        try {
            return (Integer.parseInt(data.substring(0, 2), 16) & 1) != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void clearTaskManagerSwitch() {
        try {
            CommandRunner.Result result = runner.run(List.of(
                    "reg", "delete", APPROVED_KEY, "/v", VALUE_NAME, "/f"));
            if (result.exitCode() != 0
                    && !result.output().toLowerCase(Locale.ROOT).contains("unable to find")) {
                log.warn("reg delete of the startup switch returned {}: {}",
                        result.exitCode(), result.output());
            }
        } catch (IOException e) {
            log.warn("Could not clear the startup switch in the registry", e);
        }
    }

    /**
     * The data of our value in {@code reg query} output, which prints it as
     * {@code    VlessClient    REG_SZ    <data>}.
     *
     * @return the data, or null when the output holds no such line
     */
    private static String storedValue(String output, String type) {
        Pattern line = Pattern.compile("^\\s*" + VALUE_NAME + "\\s+" + type + "\\s+(.*?)\\s*$");
        for (String candidate : output.split("\\R")) {
            Matcher m = line.matcher(candidate);
            if (m.matches()) {
                return m.group(1);
            }
        }
        return null;
    }

    /**
     * {@code value} without one pair of double quotes around the whole of it.
     *
     * <p>A launcher path with a space, {@code C:\Users\John Smith\...\Tunl.exe}
     * under such an account, is registered quoted. Java hands an argument
     * that is already quoted to {@code reg add} as it is, and reg's own
     * command-line parsing takes the quotes off again, so the Run value holds
     * the path bare and {@code reg query} prints it so. Compared as they
     * were, the two never matched, and every start rewrote the value.</p>
     */
    private static String unquoted(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private void install(String command) throws IOException {
        CommandRunner.Result result = runner.run(List.of(
                "reg", "add", RUN_KEY, "/v", VALUE_NAME,
                "/t", "REG_SZ", "/d", command, "/f"));
        if (result.exitCode() != 0) {
            throw new IOException("reg add failed (exit " + result.exitCode() + "): "
                    + result.output());
        }
        log.info("Autostart registry value installed: {}\\{}", RUN_KEY, VALUE_NAME);
    }

    private void uninstall() throws IOException {
        CommandRunner.Result result = runner.run(List.of(
                "reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f"));
        // A missing value ("unable to find") is a successful no-op; anything
        // else that fails is worth a warning but not fatal — the goal (no
        // autostart entry) is already met.
        if (result.exitCode() != 0
                && !result.output().toLowerCase(Locale.ROOT).contains("unable to find")) {
            log.warn("reg delete returned {}: {}", result.exitCode(), result.output());
        }
    }

    /**
     * Builds the command line stored in the Run value.
     *
     * <p>Under jpackage the process command is the installed native launcher
     * ({@code Tunl.exe}) — registering it directly survives app
     * updates and starts without a console. Only a dev run (started by
     * {@code java.exe}) falls back to the reconstructed JVM invocation, with
     * {@code javaw} substituted so no console window flashes at login.</p>
     *
     * @return the launch command as a single command-line string
     */
    static String launchCommandLine() {
        return launchCommandLine(ProcessHandle.current().info().command());
    }

    /** Test seam: same as {@link #launchCommandLine()} with an explicit process command. */
    static String launchCommandLine(java.util.Optional<String> processCommand) {
        if (processCommand.isPresent() && !isJavaExecutable(processCommand.get())) {
            return quote(List.of(processCommand.get()));
        }
        List<String> command = new ArrayList<>(JvmLaunchCommand.current());
        command.set(0, toJavaw(command.get(0)));
        return quote(command);
    }

    private static boolean isJavaExecutable(String command) {
        String name = command.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.equals("java") || name.equals("java.exe")
                || name.equals("javaw") || name.equals("javaw.exe");
    }

    /** {@code .../bin/java[.exe]} to {@code .../bin/javaw[.exe]}: no login console. */
    private static String toJavaw(String javaPath) {
        if (javaPath.endsWith("java.exe")) {
            return javaPath.substring(0, javaPath.length() - "java.exe".length()) + "javaw.exe";
        }
        if (javaPath.endsWith("java")) {
            return javaPath + "w";
        }
        return javaPath;
    }

    private static String quote(List<String> argv) {
        List<String> quoted = new ArrayList<>(argv.size());
        for (String arg : argv) {
            if (arg.isEmpty() || arg.chars().anyMatch(Character::isWhitespace)
                    || arg.contains("\"")) {
                quoted.add('"' + arg.replace("\"", "\\\"") + '"');
            } else {
                quoted.add(arg);
            }
        }
        return String.join(" ", quoted);
    }
}
