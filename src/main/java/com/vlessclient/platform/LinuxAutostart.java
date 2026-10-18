package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Linux autostart via the XDG autostart directory: a
 * {@code vless-client.desktop} entry in {@code ~/.config/autostart} that
 * every major desktop (GNOME, KDE, XFCE, …) launches at session start.
 * Enabling writes the file; disabling deletes it. A desktop's own startup
 * settings switch it off inside the file, which {@link #isEnabled()} reads.
 */
public final class LinuxAutostart implements Autostart {

    private static final Logger log = LoggerFactory.getLogger(LinuxAutostart.class);

    private static final String DESKTOP_FILE = "vless-client.desktop";

    private final Path autostartDir;

    public LinuxAutostart() {
        this(Path.of(System.getProperty("user.home"), ".config", "autostart"));
    }

    /** Test seam: inject the autostart directory. */
    LinuxAutostart(Path autostartDir) {
        this.autostartDir = autostartDir;
    }

    @Override
    public boolean isEnabled() {
        Path entry = entryPath();
        if (!Files.isRegularFile(entry)) {
            return false;
        }
        try {
            return !switchedOff(Files.readAllLines(entry));
        } catch (IOException e) {
            // Unreadable says nothing about the switch; it exists, as before.
            log.warn("Could not read autostart desktop entry {}", entry, e);
            return true;
        }
    }

    /**
     * Whether the desktop has switched the entry off. Desktops do that by
     * editing the user's copy rather than deleting it: Cinnamon and
     * gnome-session-properties write {@code X-GNOME-Autostart-enabled=false},
     * XFCE and LXQt write {@code Hidden=true}. Only the file's existence was
     * read, so the entry counted as on, and the {@link #refresh()} at every
     * start wrote it back switched on, against the user's choice.
     */
    private static boolean switchedOff(List<String> lines) {
        boolean inMainGroup = false;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.startsWith("[")) {
                inMainGroup = line.equals("[Desktop Entry]");
                continue;
            }
            int eq = line.indexOf('=');
            if (!inMainGroup || eq < 0) {
                continue;
            }
            // The spec allows spaces around the '='.
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if ((key.equals("Hidden") && value.equalsIgnoreCase("true"))
                    || (key.equals("X-GNOME-Autostart-enabled")
                    && value.equalsIgnoreCase("false"))) {
                return true;
            }
        }
        return false;
    }

    // Synchronized with refresh(), which runs off the launch thread at start:
    // a refresh that had read "on" must not write the entry back after the
    // user's untick has removed it.
    @Override
    public synchronized void setEnabled(boolean enabled) throws IOException {
        if (enabled) {
            install();
        } else {
            uninstall();
        }
    }

    @Override
    public synchronized void refresh() {
        if (!isEnabled()) {
            return;
        }
        try {
            install();
        } catch (IOException e) {
            log.warn("Could not refresh autostart desktop entry", e);
        }
    }

    private void install() throws IOException {
        Files.createDirectories(autostartDir);
        Files.writeString(entryPath(), buildDesktopEntry(execLine()));
        log.info("Autostart entry installed: {}", entryPath());
    }

    private void uninstall() throws IOException {
        if (Files.deleteIfExists(entryPath())) {
            log.info("Autostart entry removed: {}", entryPath());
        }
    }

    private Path entryPath() {
        return autostartDir.resolve(DESKTOP_FILE);
    }

    /**
     * The Exec command: the installed jpackage launcher when running
     * installed (survives app updates), otherwise the reconstructed JVM
     * invocation of a dev run.
     */
    static String execLine() {
        return execLine(ProcessHandle.current().info().command());
    }

    /** Test seam: same as {@link #execLine()} with an explicit process command. */
    static String execLine(Optional<String> processCommand) {
        if (processCommand.isPresent() && !isJavaExecutable(processCommand.get())) {
            return execLine(List.of(processCommand.get()));
        }
        return execLine(JvmLaunchCommand.current());
    }

    /**
     * Test seam: the Exec value that runs {@code command}.
     *
     * <p>A program whose path holds a % runs through {@code env}. GLib, and
     * systemd's autostart generator, look the program up before they expand
     * {@code %%}, so a path written the spec's way was not found and the entry
     * did nothing; as {@code env}'s argument it is expanded like any other.</p>
     */
    static String execLine(List<String> command) {
        StringBuilder exec = new StringBuilder();
        if (command.getFirst().indexOf('%') >= 0) {
            exec.append("env");
        }
        for (String arg : command) {
            if (!exec.isEmpty()) {
                exec.append(' ');
            }
            exec.append(execArg(arg));
        }
        return exec.toString();
    }

    private static boolean isJavaExecutable(String command) {
        String name = command.substring(command.lastIndexOf('/') + 1)
                .toLowerCase(Locale.ROOT);
        return name.equals("java") || name.equals("javaw");
    }

    /**
     * One argument of an Exec value, written for the three passes the Desktop
     * Entry spec reads it in, the last pass first: a % is doubled, as any
     * other % starts a field code; an argument with a reserved character goes
     * in double quotes, with {@code " ` $ \} backslash-escaped; and the value
     * as a whole escapes a backslash, newline, tab or carriage return the way
     * every string in a key file does.
     *
     * <p>The line was written for the quoting pass alone. GLib reads the
     * string escapes first and refuses {@code \$}, {@code \`} and {@code \"},
     * so for a path holding $, ` or " GNOME found no Exec at all, and Tunl did
     * not start at login while the box in Settings stayed ticked. A % was
     * read as a field code: "100%done" became "100one".</p>
     */
    private static String execArg(String arg) {
        String text = arg.replace("%", "%%");
        if (text.isEmpty() || text.chars().anyMatch(c ->
                Character.isWhitespace(c) || "\"'\\><~|&;$*?#()`".indexOf(c) >= 0)) {
            text = '"' + text.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("`", "\\`")
                    .replace("$", "\\$") + '"';
        }
        return text.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                .replace("\r", "\\r");
    }

    /**
     * Renders the XDG desktop entry that runs {@code exec} at login.
     */
    static String buildDesktopEntry(String exec) {
        return String.join("\n", List.of(
                "[Desktop Entry]",
                "Type=Application",
                "Name=Tunl",
                "Exec=" + exec,
                "Terminal=false",
                "X-GNOME-Autostart-enabled=true",
                "Comment=Start Tunl at login",
                ""));
    }
}
