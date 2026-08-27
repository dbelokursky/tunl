package com.vlessclient.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorePlatformTest {

    @Test
    void mac_namingAndResourcePath() {
        CorePlatform mac = new MacCorePlatform();
        assertThat(mac.binaryName()).isEqualTo("sing-box");
        assertThat(mac.assetName("1.13.14", "arm64"))
                .isEqualTo("sing-box-1.13.14-darwin-arm64.tar.gz");
        assertThat(mac.bundledResourcePath("amd64"))
                .isEqualTo("/native/darwin-amd64/sing-box");
    }

    @Test
    void windows_namingAndResourcePath() {
        CorePlatform win = new WindowsCorePlatform();
        assertThat(win.binaryName()).isEqualTo("sing-box.exe");
        assertThat(win.assetName("1.13.14", "amd64"))
                .isEqualTo("sing-box-1.13.14-windows-amd64.zip");
        assertThat(win.bundledResourcePath("amd64"))
                .isEqualTo("/native/windows-amd64/sing-box.exe");
    }

    @Test
    void linux_namingAndResourcePath() {
        CorePlatform linux = new LinuxCorePlatform();
        assertThat(linux.binaryName()).isEqualTo("sing-box");
        assertThat(linux.assetName("1.13.14", "amd64"))
                .isEqualTo("sing-box-1.13.14-linux-amd64.tar.gz");
        assertThat(linux.bundledResourcePath("amd64"))
                .isEqualTo("/native/linux-amd64/sing-box");
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void linux_extractsTarGzViaPathTar(@TempDir Path tmp) throws Exception {
        // GNU/BSD tar from PATH: works on both unix CI hosts, which is the
        // point — the Linux extractor must not depend on a fixed tar path.
        Path stage = tmp.resolve("stage");
        Files.createDirectories(stage);
        Files.writeString(stage.resolve("sing-box"), "#!/bin/sh\necho ok\n");
        Path tar = tmp.resolve("core.tar.gz");
        Process p = new ProcessBuilder("tar", "-czf",
                tar.toString(), "-C", stage.toString(), "sing-box").start();
        p.getInputStream().readAllBytes();
        p.waitFor();

        Path dest = tmp.resolve("out");
        Files.createDirectories(dest);
        new LinuxCorePlatform().extract(tar, dest);

        assertThat(dest.resolve("sing-box")).exists();
    }

    @Test
    void windows_extractsZipPreservingNestedPath(@TempDir Path tmp) throws Exception {
        Path zip = tmp.resolve("core.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("sing-box-1.13.14-windows-amd64/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("sing-box-1.13.14-windows-amd64/sing-box.exe"));
            zos.write("MZbinary".getBytes());
            zos.closeEntry();
        }
        Path dest = tmp.resolve("out");
        Files.createDirectories(dest);

        new WindowsCorePlatform().extract(zip, dest);

        Path exe = dest.resolve("sing-box-1.13.14-windows-amd64").resolve("sing-box.exe");
        assertThat(exe).exists();
        assertThat(Files.readString(exe)).isEqualTo("MZbinary");
    }

    @Test
    void windows_extractRejectsZipSlip(@TempDir Path tmp) throws Exception {
        Path zip = tmp.resolve("evil.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("../escape.txt"));
            zos.write("nope".getBytes());
            zos.closeEntry();
        }
        Path dest = tmp.resolve("out");
        Files.createDirectories(dest);

        assertThatThrownBy(() -> new WindowsCorePlatform().extract(zip, dest))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("escapes");
    }

    @Test
    void windows_extractRejectsAnEmptyFileName(@TempDir Path tmp) throws Exception {
        Path zip = tmp.resolve("empty-name.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry(""));
            zos.write("not a file".getBytes());
            zos.closeEntry();
        }

        assertThatThrownBy(() -> new WindowsCorePlatform().extract(zip, tmp.resolve("out")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("does not name a file");
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void mac_extractsTarGz(@TempDir Path tmp) throws Exception {
        // Build a real .tar.gz with the system tar so the macOS extractor path
        // is exercised end to end.
        Path stage = tmp.resolve("stage");
        Files.createDirectories(stage);
        Files.writeString(stage.resolve("sing-box"), "#!/bin/sh\necho ok\n");
        Path tar = tmp.resolve("core.tar.gz");
        Process p = new ProcessBuilder("/usr/bin/tar", "-czf",
                tar.toString(), "-C", stage.toString(), "sing-box").start();
        try (OutputStream ignored = OutputStream.nullOutputStream()) {
            p.getInputStream().readAllBytes();
        }
        p.waitFor();

        Path dest = tmp.resolve("out");
        Files.createDirectories(dest);
        new MacCorePlatform().extract(tar, dest);

        assertThat(dest.resolve("sing-box")).exists();
    }
}
