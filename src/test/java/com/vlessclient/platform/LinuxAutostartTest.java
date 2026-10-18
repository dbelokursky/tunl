package com.vlessclient.platform;

import com.vlessclient.testing.Await;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LinuxAutostartTest {

    private static final String ENTRY = "vless-client.desktop";

    @TempDir
    Path tempDir;

    @Test
    void isEnabled_falseWhenEntryAbsent() {
        assertThat(new LinuxAutostart(tempDir).isEnabled()).isFalse();
    }

    @Test
    void setEnabledTrue_writesDesktopEntry() throws IOException {
        LinuxAutostart service = new LinuxAutostart(tempDir);

        service.setEnabled(true);

        assertThat(service.isEnabled()).isTrue();
        String entry = Files.readString(tempDir.resolve(ENTRY));
        assertThat(entry).startsWith("[Desktop Entry]");
        assertThat(entry).contains("Type=Application");
        assertThat(entry).contains("Name=Tunl");
        assertThat(entry).contains("Exec=");
        assertThat(entry).contains("X-GNOME-Autostart-enabled=true");
    }

    @Test
    void setEnabledFalse_removesEntry() throws IOException {
        LinuxAutostart service = new LinuxAutostart(tempDir);
        service.setEnabled(true);

        service.setEnabled(false);

        assertThat(service.isEnabled()).isFalse();
        assertThat(tempDir.resolve(ENTRY)).doesNotExist();
    }

    @Test
    void setEnabledTrue_createsAutostartDirWhenMissing() throws IOException {
        Path missing = tempDir.resolve(".config").resolve("autostart");
        LinuxAutostart service = new LinuxAutostart(missing);

        service.setEnabled(true);

        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    void refresh_rewritesEntryWhenEnabled() throws IOException {
        LinuxAutostart service = new LinuxAutostart(tempDir);
        service.setEnabled(true);
        Files.writeString(tempDir.resolve(ENTRY), "stale");

        service.refresh();

        assertThat(Files.readString(tempDir.resolve(ENTRY))).contains("[Desktop Entry]");
    }

    @Test
    void refresh_doesNothingWhenDisabled() {
        LinuxAutostart service = new LinuxAutostart(tempDir);

        service.refresh();

        assertThat(service.isEnabled()).isFalse();
    }

    /**
     * Desktops switch an autostart entry off by editing the user's copy, not
     * by deleting it: Cinnamon and gnome-session-properties write
     * X-GNOME-Autostart-enabled=false, XFCE and LXQt write Hidden=true. The
     * entry still existed, so Tunl read it as on, and the refresh at every
     * start wrote it back with X-GNOME-Autostart-enabled=true, turning
     * autostart on again against the user's choice.
     */
    @Test
    void anEntryTheDesktopSwitchedOffIsOffAndStaysOff() throws IOException {
        for (String off : new String[] {"X-GNOME-Autostart-enabled=false", "Hidden=true"}) {
            LinuxAutostart service = new LinuxAutostart(tempDir);
            service.setEnabled(true);
            Path entry = tempDir.resolve(ENTRY);
            String switchedOff = Files.readString(entry)
                    .replace("X-GNOME-Autostart-enabled=true\n", "") + off + "\n";
            Files.writeString(entry, switchedOff);

            assertThat(service.isEnabled()).as(off).isFalse();

            service.refresh();

            assertThat(Files.readString(entry))
                    .as("the desktop's own switch survives a start of the app (%s)", off)
                    .isEqualTo(switchedOff);
        }
    }

    @Test
    void aSwitchInAnotherGroupOrWithSpacesIsReadLikeTheDesktopReadsIt() throws IOException {
        Path entry = tempDir.resolve(ENTRY);
        LinuxAutostart service = new LinuxAutostart(tempDir);

        // The spec allows spaces around '='.
        Files.writeString(entry, "[Desktop Entry]\nType=Application\nHidden = true\n");
        assertThat(service.isEnabled()).isFalse();

        // Only the [Desktop Entry] group says whether it starts.
        Files.writeString(entry, "[Desktop Entry]\nType=Application\n"
                + "[Desktop Action quit]\nHidden=true\n");
        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    void tickingTheBoxAgainTurnsAnEntryTheDesktopSwitchedOffBackOn() throws IOException {
        Path entry = tempDir.resolve(ENTRY);
        Files.writeString(entry, "[Desktop Entry]\nType=Application\nExec=tunl\n"
                + "X-GNOME-Autostart-enabled=false\nHidden=true\n");
        LinuxAutostart service = new LinuxAutostart(tempDir);

        service.setEnabled(true);

        assertThat(service.isEnabled()).isTrue();
        assertThat(Files.readString(entry))
                .contains("X-GNOME-Autostart-enabled=true")
                .doesNotContain("Hidden=");
    }

    @Test
    void execLine_underJpackage_usesTheInstalledLauncher() {
        String exec = LinuxAutostart.execLine(
                Optional.of("/opt/vless-client/bin/VLESS Client"));

        // The installed launcher itself, Exec-quoted (path has a space).
        assertThat(exec).isEqualTo("\"/opt/vless-client/bin/VLESS Client\"");
    }

    @Test
    void execLine_devJavaRun_fallsBackToJvmInvocation() {
        String exec = LinuxAutostart.execLine(Optional.of("/usr/lib/jvm/bin/java"));

        assertThat(exec).contains("com.vlessclient.app.Launcher");
        assertThat(exec).contains("-cp");
    }

    @Test
    void buildDesktopEntry_escapesExecSpecials() {
        String entry = LinuxAutostart.buildDesktopEntry("\"/opt/a b/launcher\"");

        assertThat(entry).contains("Exec=\"/opt/a b/launcher\"");
        assertThat(entry).endsWith("\n");
    }

    /** Launcher paths that take each of the spec's passes to write. */
    private static final List<String> LAUNCHERS = List.of("plain", "My Apps", "cost$5",
            "tick`tock", "say \"hi\"", "back\\slash", "100%done", "tab\there");

    /**
     * The Desktop Entry spec reads Exec in three passes: the string escapes
     * every value has (\s \n \t \r \\), then the quoting of arguments (\" \`
     * \$ \\ inside double quotes), then the field codes (%% for a percent).
     * The line was written for the second pass alone. GLib's key file reader
     * refuses \$, \` and \" as escapes, so for a path holding $, ` or " GNOME
     * found "no Exec field" and Tunl did not start at login while the box in
     * Settings stayed ticked; a % was read as a field code, and "100%done"
     * became "100one".
     */
    @Test
    void theExecLineReadsBackAsTheLauncherTheSpecsWay() {
        for (String name : LAUNCHERS) {
            String launcher = "/opt/" + name + "/bin/tunl";
            String exec = LinuxAutostart.execLine(Optional.of(launcher));

            assertThat(readExec(exec)).as("Exec=%s", exec)
                    .isEqualTo(name.contains("%") ? List.of("env", launcher) : List.of(launcher));
        }
    }

    /**
     * A source run starts at login as its JVM: the java path, every JVM
     * option (JAVA_TOOL_OPTIONS among them) and the class path, any of which
     * may hold a reserved character or a % ({@code -XX:ErrorFile=hs_err_%p.log}).
     */
    @Test
    void everyArgumentOfASourceRunReadsBackAsItself() {
        List<String> command = List.of("/usr/lib/jvm/jdk 27/bin/java",
                "-XX:ErrorFile=/tmp/hs_err_%p.log", "-Dfrom=$HOME/`x`", "-Dsay=\"hi\"",
                "-cp", "/home/u/My Projects/tunl/target/classes:/home/u/lib/a\\b.jar",
                "com.vlessclient.app.Launcher");

        String exec = LinuxAutostart.execLine(command);

        assertThat(readExec(exec)).as("Exec=%s", exec).isEqualTo(command);
    }

    /**
     * The same entries through GLib, which GNOME's session and file managers
     * launch them with: each must run the launcher it names.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void gioRunsEachEntryAsTheLauncherItNames(@TempDir Path dir) throws Exception {
        Path gio = Path.of("/usr/bin/gio");
        assumeTrue(Files.isExecutable(gio), "GLib's gio is not installed");
        for (int i = 0; i < LAUNCHERS.size(); i++) {
            Path launcher = Files.createDirectories(dir.resolve(LAUNCHERS.get(i)).resolve("bin"))
                    .resolve("tunl");
            Path ran = dir.resolve("ran-" + i);
            Files.writeString(launcher, "#!/bin/sh\nprintf '%s\\n' \"$0\" \"$@\" > '" + ran + "'\n");
            assertThat(launcher.toFile().setExecutable(true, true)).isTrue();
            Path entry = Files.writeString(dir.resolve("entry-" + i + ".desktop"),
                    LinuxAutostart.buildDesktopEntry(
                            LinuxAutostart.execLine(Optional.of(launcher.toString()))));

            Process launch = new ProcessBuilder(gio.toString(), "launch", entry.toString())
                    .redirectErrorStream(true).start();
            String said = new String(launch.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(launch.waitFor(20, TimeUnit.SECONDS)).isTrue();
            if (i == 0) {
                // A plain path is the control: where gio cannot launch even
                // that, this machine says nothing about the Exec line.
                assumeTrue(launch.exitValue() == 0, "gio cannot launch here: " + said);
            }
            assertThat(launch.exitValue()).as("gio launch %s: %s", LAUNCHERS.get(i), said).isZero();
            Await.until("the launcher in " + LAUNCHERS.get(i) + " to run",
                    () -> Files.exists(ran), Duration.ofSeconds(10));
            Await.until("it to record its arguments", () -> readLines(ran).size() == 1,
                    Duration.ofSeconds(5));

            assertThat(readLines(ran)).as(LAUNCHERS.get(i)).containsExactly(launcher.toString());
        }
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            return List.of();
        }
    }

    /** An Exec value read as the Desktop Entry spec says; null where GLib refuses the key. */
    private static List<String> readExec(String value) {
        StringBuilder unescaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\') {
                unescaped.append(c);
                continue;
            }
            char next = ++i < value.length() ? value.charAt(i) : '\0';
            switch (next) {
                case 's' -> unescaped.append(' ');
                case 'n' -> unescaped.append('\n');
                case 't' -> unescaped.append('\t');
                case 'r' -> unescaped.append('\r');
                case '\\' -> unescaped.append('\\');
                default -> {
                    return null;   // "Key file contains invalid escape sequence"
                }
            }
        }
        List<String> args = new ArrayList<>();
        StringBuilder arg = null;
        boolean quoted = false;
        String line = unescaped.toString();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!quoted && c == ' ') {
                if (arg != null) {
                    args.add(fieldCodes(arg.toString()));
                    arg = null;
                }
                continue;
            }
            if (arg == null) {
                arg = new StringBuilder();
            }
            if (c == '"') {
                quoted = !quoted;
            } else if (quoted && c == '\\' && i + 1 < line.length()
                    && "\"`$\\".indexOf(line.charAt(i + 1)) >= 0) {
                arg.append(line.charAt(++i));
            } else {
                arg.append(c);
            }
        }
        if (arg != null) {
            args.add(fieldCodes(arg.toString()));
        }
        return args;
    }

    /** %% is a percent; any other % starts a field code, expanded to nothing at login. */
    private static String fieldCodes(String arg) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '%' && i + 1 < arg.length()) {
                char code = arg.charAt(++i);
                if (code == '%') {
                    out.append('%');
                }
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}
