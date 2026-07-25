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
