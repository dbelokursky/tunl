package com.vlessclient.platform;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows autostart via the per-user registry Run key
 * {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Run}. Enabling writes a
 * {@code VlessClient} value holding the launch command; disabling deletes it.
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

    private final CommandRunner runner;

    public WindowsAutostart() {
        this(CommandRunner.system());
    }

    WindowsAutostart(CommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public boolean isEnabled() {
        try {
            return runner.run(List.of("reg", "query", RUN_KEY, "/v", VALUE_NAME)).exitCode() == 0;
        } catch (IOException e) {
            log.warn("Could not query autostart registry value", e);
            return false;
        }
    }

    @Override
    public void setEnabled(boolean enabled) throws IOException {
        if (enabled) {
            install();
        } else {
            uninstall();
        }
    }

    @Override
    public void refresh() {
        if (!isEnabled()) {
            return;
        }
        try {
            install();
        } catch (IOException e) {
            log.warn("Could not refresh autostart registry value", e);
        }
    }

    private void install() throws IOException {
        CommandRunner.Result result = runner.run(List.of(
                "reg", "add", RUN_KEY, "/v", VALUE_NAME,
                "/t", "REG_SZ", "/d", launchCommandLine(), "/f"));
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
