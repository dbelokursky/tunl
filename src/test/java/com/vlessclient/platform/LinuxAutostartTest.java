package com.vlessclient.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

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
}
