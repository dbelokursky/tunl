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
 * Enabling writes the file; disabling deletes it.
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
        return Files.isRegularFile(entryPath());
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
            return quoteExecArg(processCommand.get());
        }
        StringBuilder exec = new StringBuilder();
        for (String arg : JvmLaunchCommand.current()) {
            if (!exec.isEmpty()) {
                exec.append(' ');
            }
            exec.append(quoteExecArg(arg));
        }
        return exec.toString();
    }

    private static boolean isJavaExecutable(String command) {
        String name = command.substring(command.lastIndexOf('/') + 1)
                .toLowerCase(Locale.ROOT);
        return name.equals("java") || name.equals("javaw");
    }

    /**
     * Desktop-entry Exec quoting: arguments with reserved characters go in
     * double quotes with the inner specials backslash-escaped.
     */
    private static String quoteExecArg(String arg) {
        if (!arg.isEmpty() && arg.chars().noneMatch(c ->
                Character.isWhitespace(c) || "\"'\\><~|&;$*?#()`".indexOf(c) >= 0)) {
            return arg;
        }
        return '"' + arg.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("`", "\\`")
                .replace("$", "\\$") + '"';
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
